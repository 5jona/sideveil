package pochita.sideveil.host.plugin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import pochita.sideveil.host.debug.PluginManagerDebugState
import pochita.sideveil.host.debug.WebViewDebuggingController
import pochita.sideveil.host.overlay.EdgeHapticsController
import pochita.sideveil.host.service.DaemonClient
import pochita.sideveil.protocol.BUILTIN_DIAG_PLUGIN_ID
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PluginBackendStatePayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginPresentationMode
import pochita.sideveil.protocol.PluginScopedEventPayload
import pochita.sideveil.protocol.PluginsSnapshotPayload

/**
 * Coordinates isolated plugin WebView sandboxes, active plugin switching, scoped event routing,
 * back-press interception, diagnostics reporting, and crash recovery.
 */
class PluginWebViewManager(
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
        PluginAssetFetcher(context, { daemonClient.currentEndpoint() }, eventBridge::reportLoopbackFetchError)
    private val webViewDeps =
        PluginWebViewDeps(scope, daemonClient, PluginLocalRpcHandler(context, haptics), assetFetcher, crashRegistry)
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
            context = context,
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
    private val crashRecovery = WebViewCrashRecoveryCoordinator()

    val webViewDebuggingEnabled: Boolean
        get() = webViewDebugging.effectiveEnabled

    init {
        descriptors[BUILTIN_DIAG_PLUGIN_ID] = PluginDescriptor.builtinDiagnostic()
    }

    fun setConfigWebViewDebugging(enabled: Boolean): Boolean = webViewDebugging.setConfigEnabled(enabled)

    internal fun debugState(): PluginManagerDebugState =
        eventBridge.buildManagerDebugState(
            activePluginId,
            pinnedPluginId,
            activeHudPluginId,
            matchedPluginIds,
            webViewDebugging.effectiveEnabled,
            pool,
            isKeepAlivePlugin,
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
        pool.get(activePluginId)?.let(containerCallbacks::mountInstance)
        pool.updateRendererPriorities(panelState, activePluginId, pinnedPluginId)
    }

    /** Applies a full plugin catalog and active/matched selection snapshot from `sideveild`. */
    fun onPluginsSnapshot(snapshot: PluginsSnapshotPayload) {
        val activeNeedsRemount =
            containerCallbacks.applyDescriptorUpdates(
                plugins = snapshot.plugins,
                descriptors = descriptors,
                pool = pool,
                crashRecovery = crashRecovery,
                onClearPinned = { pinnedPluginId = null },
            )
        webViewDebugging.setHasDevServer(descriptors.values.any { !it.devServerUrl.isNullOrBlank() })
        val evicted = pool.evictStale { id -> descriptors[id]?.enabled == true }
        evicted.forEach { crashRecovery.cancel(it, resetBackoff = true) }
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
        crashRecovery.cancel(pluginId, resetBackoff = true)
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

    fun onTrimMemory(level: Int) =
        eventBridge.trimPoolOnMemoryPressure(
            level,
            activePluginId,
            pinnedPluginId,
            activeHudPluginId,
            keepAliveIds(),
            pool,
        )

    fun reportDiagnostics() = eventBridge.reportDiagnostics(pool.warmPluginIds(), activeHudPluginId)

    fun isBackIntercepted(): Boolean = pool.peek(activePluginId)?.canConsumeBack() == true

    fun maxPluginPriority(): Int = descriptors.values.maxOfOrNull { it.priority } ?: 0

    fun dispatchBackPress(): Boolean = pool.peek(activePluginId)?.consumeBackPress() == true

    fun broadcastGlobalEvent(envelope: HostEventEnvelope) = eventBridge.broadcastGlobalEvent(envelope, pool)

    fun routeNetworkContextChanged(envelope: HostEventEnvelope) {
        latestNetworkRoute = eventBridge.routeNetworkContextChanged(envelope, latestNetworkRoute, pool)
    }

    fun routeBackendState(payload: PluginBackendStatePayload) = eventBridge.routeBackendState(payload, descriptors, pool, ::notifyRail)

    fun routePluginScopedEvent(payload: PluginScopedEventPayload) = eventBridge.routePluginScopedEvent(payload, pool)

    fun destroyAll() {
        crashRecovery.clearAll()
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
            if (panelState != PanelState.CLOSED) eventBridge.pushVisibility(previous, visible = false, state = PanelState.CLOSED)
            previous?.flushCookies()
        }

        activePluginId = descriptor.id
        val instance = getOrCreateInstance(descriptor)
        if (instance.presentationMode == PluginPresentationMode.PINNED) {
            instance.resetPresentationToDrawer()
            if (pinnedPluginId == descriptor.id) pinnedPluginId = null
        }
        pool.updateRendererPriorities(panelState, activePluginId, pinnedPluginId)
        if (switching || forceMount || instance.webView.parent == null) containerCallbacks.mountInstance(instance)
        reportDiagnostics()
        if (switching && panelState != PanelState.CLOSED) {
            eventBridge.pushVisibility(instance, visible = true, state = panelState)
        }
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
            protectedIds = keepAliveIds(),
            onHit = { existing -> existing.updateDescriptor(descriptor) },
        ) {
            PluginWebViewInstance(context, webViewDeps, descriptor, containerCallbacks, latestNetworkRoute).also { created ->
                val visible =
                    (panelState != PanelState.CLOSED && descriptor.id == activePluginId) ||
                        descriptor.id == pinnedPluginId
                eventBridge.hydrateNewInstance(created, descriptor, panelState, visible, lastUrlByPlugin.remove(descriptor.id))
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
        if (pluginId != activePluginId || !descriptors.containsKey(pluginId)) return
        crashRecovery.schedule(pluginId) {
            if (pluginId == activePluginId && descriptors.containsKey(pluginId)) {
                activatePlugin(pluginId, forceMount = true)
            }
        }
    }

    private fun keepAliveIds(): Set<String> = descriptors.values.filter(isKeepAlivePlugin).mapTo(LinkedHashSet()) { it.id }

    private fun notifyRail() = surfaceDelegate.onMatchedPluginsUpdated(matchedPluginIds.mapNotNull(descriptors::get), activePluginId)

    companion object {
        private const val TAG = "SideVeilPluginMgr"
    }
}
