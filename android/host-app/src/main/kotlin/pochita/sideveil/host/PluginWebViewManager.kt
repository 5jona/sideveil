package pochita.sideveil.host

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.protocol.BUILTIN_DIAG_PLUGIN_ID
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.NetworkContextSnapshot
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PluginBackendStatePayload
import pochita.sideveil.protocol.PluginCapability
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginPresentationMode
import pochita.sideveil.protocol.PluginScopedEventPayload
import pochita.sideveil.protocol.PluginsSnapshotPayload
import pochita.sideveil.protocol.SideVeilEvents
import pochita.sideveil.protocol.SideVeilWireJson
import java.util.Locale

/**
 * Coordinates isolated plugin WebView sandboxes, active plugin switching, scoped event routing,
 * back-press interception, diagnostics reporting, and crash recovery.
 *
 * Delegates warm instance storage, LRU eviction (exempting active, pinned, and `keepAlive`
 * plugins), and renderer priority updates to [PluginWebViewPool].
 */
internal class PluginWebViewManager(
    private val context: Context,
    scope: CoroutineScope,
    daemonClient: DaemonClient,
    haptics: EdgeHapticsController,
    private val surfaceDelegate: PluginHostSurfaceDelegate,
    private val isKeepAlivePlugin: (PluginDescriptor) -> Boolean = { it.surface.keepAlive },
) {
    private val crashRegistry = WebViewCrashRegistry()
    private val eventBridge = PluginWebViewEventBridge(daemonClient, crashRegistry)
    private val assetFetcher =
        PluginAssetFetcher(
            context = context,
            endpointProvider = { daemonClient.currentEndpoint() },
            onFetchError = eventBridge::reportLoopbackFetchError,
        )
    private val webViewDeps =
        PluginWebViewDeps(
            scope = scope,
            daemonClient = daemonClient,
            localRpcHandler = PluginLocalRpcHandler(context, haptics),
            assetFetcher = assetFetcher,
            crashRegistry = crashRegistry,
        )
    private val descriptors = LinkedHashMap<String, PluginDescriptor>()
    private val pool =
        PluginWebViewPool<PluginWebViewInstance>(
            maxWarmInstances = PluginWebViewPool.DEFAULT_MAX_WARM_INSTANCES,
            maxKeepAliveInstances = PluginWebViewPool.DEFAULT_MAX_KEEP_ALIVE_INSTANCES,
            isCrossOriginIsolated = { it.descriptor.surface.crossOriginIsolated },
            setForegroundPriority = { inst, foreground -> inst.setForegroundPriority(foreground) },
            onDestroy = { it.destroy() },
            onEvicted = { id -> Log.i(TAG, "Evicting LRU warm plugin WebView: $id") },
        )
    private val containerCallbacks =
        PluginSurfaceCallbackRouter(
            surfaceDelegate = surfaceDelegate,
            activePluginIdProvider = { activePluginId },
            pinnedPluginIdProvider = { pinnedPluginId },
            instanceLookup = { id -> pool.peek(id) },
            onCrash = ::recoverCrashedPlugin,
        )

    private var matchedPluginIds: List<String> = emptyList()
    private var activePluginId: String = BUILTIN_DIAG_PLUGIN_ID
    private var pinnedPluginId: String? = null
    private var activeHudPluginId: String? = null
    private var panelState: PanelState = PanelState.CLOSED
    private val webViewDebugging = WebViewDebuggingController()
    private var latestNetworkRoute: NetworkRouteContext = NetworkRouteContext()
    private val lastUrlByPlugin = LinkedHashMap<String, String>()

    val webViewDebuggingEnabled: Boolean
        get() = webViewDebugging.effectiveEnabled

    init {
        descriptors[BUILTIN_DIAG_PLUGIN_ID] = PluginDescriptor.builtinDiagnostic()
    }

    fun setConfigWebViewDebugging(enabled: Boolean): Boolean = webViewDebugging.setConfigEnabled(enabled)

    fun debugState(): PluginManagerDebugState =
        PluginManagerDebugState(
            activePluginId = activePluginId,
            pinnedPluginId = pinnedPluginId,
            activeHudPluginId = activeHudPluginId,
            matchedPluginIds = matchedPluginIds.toList(),
            webviewDebuggingEnabled = webViewDebugging.effectiveEnabled,
            webViews =
                pool.snapshotInstances().map { (id, inst) ->
                    inst.debugState(
                        isActive = id == activePluginId,
                        isPinned = id == pinnedPluginId,
                        keepAlive = isKeepAlivePlugin(inst.descriptor),
                    )
                },
        )

    fun warmStartDefault() = activatePlugin(BUILTIN_DIAG_PLUGIN_ID, forceMount = true)

    fun setPinnedPlugin(pluginId: String?) {
        if (pinnedPluginId == pluginId) return
        val previousPinned = pinnedPluginId
        pinnedPluginId = pluginId
        pool.updateRendererPriorities(panelState, activePluginId, pinnedPluginId)
        if (previousPinned != null && (panelState == PanelState.CLOSED || previousPinned != activePluginId)) {
            eventBridge.pushVisibility(pool.peek(previousPinned), visible = false, state = PanelState.CLOSED)
        }
        if (pluginId != null) {
            eventBridge.pushVisibility(pool.peek(pluginId), visible = true, state = PanelState.OPEN)
        }
    }

    fun setActiveHudPlugin(pluginId: String?) {
        if (activeHudPluginId == pluginId) return
        activeHudPluginId = pluginId
        reportDiagnostics()
    }

    fun notifyActiveClickThroughChanged(clickThrough: Boolean) {
        pool.peek(pinnedPluginId ?: activePluginId)?.notifyClickThroughChanged(clickThrough)
    }

    fun resetActiveToDrawer() {
        val targetId = pinnedPluginId ?: activePluginId
        pinnedPluginId = null
        pool.peek(targetId)?.takeIf { it.presentationMode == PluginPresentationMode.PINNED }?.resetPresentationToDrawer()
        pool.get(activePluginId)?.let(::mountInstance)
        pool.updateRendererPriorities(panelState, activePluginId, pinnedPluginId)
    }

    fun onPluginsSnapshot(snapshot: PluginsSnapshotPayload) {
        descriptors.clear()
        var activeNeedsRemount = false
        for (item in snapshot.plugins) {
            descriptors[item.id] = item
            val existing = pool.peek(item.id) ?: continue
            when (existing.updateDescriptor(item)) {
                DescriptorUpdateOutcome.RECREATE_REQUIRED -> {
                    if (item.id == pinnedPluginId) pinnedPluginId = null
                    pool.removeAndDestroy(item.id)
                    if (item.id == activePluginId) activeNeedsRemount = true
                }

                DescriptorUpdateOutcome.SURFACE_UPDATED -> {
                    if (item.id == activePluginId && item.id != pinnedPluginId) {
                        mountInstance(existing)
                    } else if (item.id == pinnedPluginId) {
                        surfaceDelegate.onPluginBackdropChanged(existing.surfaceBackdrop)
                    }
                }

                DescriptorUpdateOutcome.UNCHANGED -> {
                    Unit
                }
            }
        }
        descriptors.putIfAbsent(BUILTIN_DIAG_PLUGIN_ID, PluginDescriptor.builtinDiagnostic())
        webViewDebugging.setHasDevServer(descriptors.values.any { !it.devServerUrl.isNullOrBlank() })

        val evicted = pool.evictStale { id -> descriptors[id]?.enabled == true }
        if (pinnedPluginId != null && evicted.contains(pinnedPluginId)) pinnedPluginId = null

        matchedPluginIds = snapshot.matchedPluginIds.filter(descriptors::containsKey)
        val targetId =
            snapshot.activePluginId?.takeIf(descriptors::containsKey)
                ?: matchedPluginIds.firstOrNull()
                ?: BUILTIN_DIAG_PLUGIN_ID
        activatePlugin(targetId, forceMount = activeNeedsRemount)
        notifyRail()
    }

    fun selectPlugin(pluginId: String): Boolean {
        if (!descriptors.containsKey(pluginId)) return false
        activatePlugin(pluginId, forceMount = false)
        notifyRail()
        return true
    }

    fun onPanelStateChanged(nextState: PanelState) {
        panelState = nextState
        pool.updateRendererPriorities(panelState, activePluginId, pinnedPluginId)
        val active = pool.peek(activePluginId)
        if (nextState != PanelState.CLOSED || activePluginId != pinnedPluginId) {
            eventBridge.pushVisibility(active, visible = nextState != PanelState.CLOSED, state = nextState)
        }
        if (nextState == PanelState.CLOSED) active?.flushCookies()
        reportDiagnostics()
    }

    fun onTrimMemory(level: Int) {
        val severe = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        val moderate = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        if (!moderate && !severe) return
        val protectedIds = descriptors.values.filter(isKeepAlivePlugin).mapTo(LinkedHashSet()) { it.id }
        val evicted =
            pool.trimNonActive(
                activePluginId = activePluginId,
                pinnedPluginId = pinnedPluginId,
                protectedIds = protectedIds,
                includeKeepAlive = severe,
            )
        if (evicted.isNotEmpty()) {
            Log.i(TAG, "Trimmed ${evicted.size} warm plugin WebViews (level=$level, severe=$severe): $evicted")
            reportDiagnostics()
        }
    }

    fun reportDiagnostics() = eventBridge.reportDiagnostics(pool.warmPluginIds(), activeHudPluginId)

    fun isBackIntercepted(): Boolean = pool.peek(activePluginId)?.canConsumeBack() == true

    fun maxPluginPriority(): Int = descriptors.values.maxOfOrNull { it.priority } ?: 0

    fun dispatchBackPress(): Boolean = pool.peek(activePluginId)?.consumeBackPress() == true

    fun broadcastGlobalEvent(envelope: HostEventEnvelope) {
        eventBridge.recordGlobalEnvelope(envelope)
        pool.forEachInstance { it.pushEnvelope(envelope) }
    }

    fun routeNetworkContextChanged(envelope: HostEventEnvelope) {
        eventBridge.recordNetworkContextEnvelope(envelope)
        val snapshot =
            envelope.payload?.let { payload ->
                runCatching { SideVeilWireJson.decodeFromJsonElement<NetworkContextSnapshot>(payload) }.getOrNull()
            }
        if (snapshot != null) {
            latestNetworkRoute =
                NetworkRouteContext(
                    transport = snapshot.transport.name.lowercase(Locale.ROOT),
                    wifiSsid = snapshot.ssid,
                )
        }
        pool.forEachInstance { inst ->
            if (inst.isUntrustedEmbed) {
                if (snapshot != null) {
                    inst.updateNetworkRoute(latestNetworkRoute)
                }
            } else if (inst.descriptor.capabilities.contains(PluginCapability.NETWORK_CONTEXT)) {
                inst.pushEnvelope(envelope)
            }
        }
    }

    fun routeBackendState(payload: PluginBackendStatePayload) {
        descriptors[payload.pluginId]?.let { current ->
            val updated = current.copy(backendState = payload.state)
            descriptors[payload.pluginId] = updated
            pool.peek(payload.pluginId)?.updateDescriptor(updated)
            notifyRail()
        }
        pool.peek(payload.pluginId)?.pushEvent(
            SideVeilEvents.PLUGIN_BACKEND_STATE,
            SideVeilWireJson.encodeToJsonElement(payload),
        )
    }

    fun routePluginScopedEvent(payload: PluginScopedEventPayload) {
        pool.peek(payload.pluginId)?.pushEvent(
            SideVeilEvents.PLUGIN_EVENT,
            SideVeilWireJson.encodeToJsonElement(payload),
        )
    }

    fun destroyAll() {
        pinnedPluginId = null
        activeHudPluginId = null
        pool.destroyAll()
    }

    private fun activatePlugin(
        pluginId: String,
        forceMount: Boolean,
    ) {
        val descriptor = descriptors[pluginId] ?: descriptors[BUILTIN_DIAG_PLUGIN_ID] ?: return
        val previousId = activePluginId
        val switching = previousId != descriptor.id
        if (switching) {
            val previous = pool.peek(previousId)
            if (previousId == pinnedPluginId) {
                previous?.resetPresentationToDrawer()
                pinnedPluginId = null
            }
            if (panelState != PanelState.CLOSED) {
                eventBridge.pushVisibility(previous, visible = false, state = PanelState.CLOSED)
            }
            previous?.flushCookies()
        }

        activePluginId = descriptor.id
        val instance = getOrCreateInstance(descriptor)
        if (instance.presentationMode == PluginPresentationMode.PINNED) {
            instance.resetPresentationToDrawer()
            if (pinnedPluginId == descriptor.id) pinnedPluginId = null
        }
        pool.updateRendererPriorities(panelState, activePluginId, pinnedPluginId)
        if (switching || forceMount || instance.webView.parent == null) mountInstance(instance)
        reportDiagnostics()
        if (switching && panelState != PanelState.CLOSED) {
            eventBridge.pushVisibility(instance, visible = true, state = panelState)
        }
    }

    private fun mountInstance(instance: PluginWebViewInstance) {
        val embed = instance.descriptor.embed
        val toolbar =
            if (instance.isUntrustedEmbed && embed != null && embed.showToolbar) {
                EmbedToolbarBinding(
                    title = instance.descriptor.name,
                    onReload = { instance.reloadEmbed() },
                    onHome = { instance.navigateEmbedHome() },
                    onOpenExternal = {
                        EmbedSessionController.openExternalBrowserUrl(
                            context,
                            instance.descriptor.id,
                            instance.currentExternalUrl(),
                        ) { surfaceDelegate.onDismissPanel() }
                    },
                    bindStatusListener = { listener -> instance.bindEmbedStatusListener(listener) },
                )
            } else {
                instance.bindEmbedStatusListener(null)
                null
            }
        surfaceDelegate.mountPluginWebView(
            webView = instance.webView,
            backdrop = instance.surfaceBackdrop,
            presentationMode = instance.presentationMode,
            sizeOverride = instance.sizeOverride,
            embedToolbar = toolbar,
        )
    }

    private fun getOrCreateInstance(descriptor: PluginDescriptor): PluginWebViewInstance {
        pool.peek(descriptor.id)?.let { existing ->
            if (existing.isUntrustedEmbed != PluginNetworkPolicy.isUntrustedEmbed(descriptor)) {
                if (descriptor.id == pinnedPluginId) pinnedPluginId = null
                pool.removeAndDestroy(descriptor.id)
            }
        }
        return pool.getOrCreate(
            pluginId = descriptor.id,
            activePluginId = activePluginId,
            pinnedPluginId = pinnedPluginId,
            protectedIds = descriptors.values.filter(isKeepAlivePlugin).mapTo(LinkedHashSet()) { it.id },
            onHit = { existing -> existing.updateDescriptor(descriptor) },
        ) {
            PluginWebViewInstance(
                context = context,
                deps = webViewDeps,
                initialDescriptor = descriptor,
                callbacks = containerCallbacks,
                initialNetworkRoute = latestNetworkRoute,
            ).also { created ->
                val initiallyVisible =
                    (panelState != PanelState.CLOSED && descriptor.id == activePluginId) ||
                        descriptor.id == pinnedPluginId
                val restoreUrl = lastUrlByPlugin.remove(descriptor.id)
                eventBridge.hydrateNewInstance(created, descriptor, panelState, initiallyVisible, restoreUrl)
            }
        }
    }

    private fun recoverCrashedPlugin(pluginId: String) {
        Log.w(TAG, "Recovering crashed WebView instance for plugin=$pluginId")
        val lastUrl = pool.peek(pluginId)?.webView?.url
        if (!lastUrl.isNullOrBlank() && (lastUrl.startsWith("http://") || lastUrl.startsWith("https://"))) {
            lastUrlByPlugin[pluginId] = lastUrl
        }
        if (pluginId == pinnedPluginId) pinnedPluginId = null
        pool.removeAndDestroy(pluginId)
        reportDiagnostics()
        val descriptor = descriptors[pluginId] ?: return
        if (pluginId == activePluginId) activatePlugin(descriptor.id, forceMount = true)
    }

    private fun notifyRail() = surfaceDelegate.onMatchedPluginsUpdated(matchedPluginIds.mapNotNull(descriptors::get), activePluginId)

    companion object {
        private const val TAG = "SideVeilPluginMgr"
    }
}
