package pochita.sideveil.host.plugin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import pochita.sideveil.host.debug.PluginManagerDebugState
import pochita.sideveil.host.debug.WebViewDebuggingController
import pochita.sideveil.host.overlay.EdgeHapticsController
import pochita.sideveil.host.service.DaemonClient
import pochita.sideveil.protocol.ConfigUpdatedPayload
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PluginBackendStatePayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginPresentationMode
import pochita.sideveil.protocol.PluginScopedEventPayload
import pochita.sideveil.protocol.PluginsSnapshotPayload

/**
 * Coordinates isolated plugin WebView sandboxes, active plugin switching, scoped event routing,
 * back-press interception, diagnostics reporting, lifecycle eviction, and crash recovery.
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
    private val localRpcHandler =
        PluginLocalRpcHandler(context = context, haptics = haptics, onAudit = eventBridge::emitLocalAudit)
    private val webViewDeps =
        PluginWebViewDeps(scope, daemonClient, localRpcHandler, assetFetcher, crashRegistry)
    private val descriptors = LinkedHashMap<String, PluginDescriptor>()
    private val pool =
        PluginWebViewPool<PluginWebViewInstance>(
            PluginWebViewPool.DEFAULT_MAX_WARM_INSTANCES,
            PluginWebViewPool.DEFAULT_MAX_KEEP_ALIVE_INSTANCES,
            { it.descriptor.surface.crossOriginIsolated },
            { inst, foreground -> inst.setForegroundPriority(foreground) },
            { it.destroy() },
            { id -> Log.i(TAG, "Evicting LRU warm plugin WebView: $id") },
        )
    private val containerCallbacks =
        PluginSurfaceCallbackRouter(
            context,
            surfaceDelegate,
            { activePluginId },
            { pinnedPluginId },
            { id -> descriptors[id] },
            { id -> pool.peek(id) },
            ::reloadPluginWebView,
            ::recoverCrashedPlugin,
        )

    private var matchedPluginIds: List<String> = emptyList()
    private var activePluginId: String? = null
    private var pinnedPluginId: String? = null
    private var activeHudPluginId: String? = null
    private var panelState: PanelState = PanelState.CLOSED
    private val webViewDebugging = WebViewDebuggingController()
    private var latestNetworkRoute: NetworkRouteContext = NetworkRouteContext()
    private val lastUrlByPlugin = LinkedHashMap<String, String>()
    private val crashRecovery = WebViewCrashRecoveryCoordinator()

    val webViewDebuggingEnabled: Boolean
        get() = webViewDebugging.effectiveEnabled

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
        eventBridge.reportDiagnostics(pool.warmPluginIds(), activeHudPluginId)
    }

    fun notifyActiveClickThroughChanged(clickThrough: Boolean) {
        val targetId = pinnedPluginId ?: activePluginId ?: return
        pool.peek(targetId)?.notifyClickThroughChanged(clickThrough)
    }

    fun resetActiveToDrawer() {
        val targetId = pinnedPluginId ?: activePluginId
        pinnedPluginId = null
        if (targetId != null) {
            pool.peek(targetId)?.takeIf { it.presentationMode == PluginPresentationMode.PINNED }?.resetPresentationToDrawer()
        }
        activePluginId?.let(pool::get)?.let(containerCallbacks::mountInstance)
        pool.updateRendererPriorities(panelState, activePluginId, pinnedPluginId)
    }

    /** Applies a full plugin catalog and active/matched selection snapshot from `sideveild`. */
    fun onPluginsSnapshot(snapshot: PluginsSnapshotPayload) {
        val previousPluginIds = descriptors.keys.toSet()
        val activeNeedsRemount =
            containerCallbacks.applyDescriptorUpdates(snapshot.plugins, descriptors, pool, crashRecovery) { id ->
                evictOverlay(EvictedOverlayKind.PINNED, id)
            }
        webViewDebugging.setHasDevServer(descriptors.values.any { it.enabled && !it.devServerUrl.isNullOrBlank() })

        val plan =
            PluginLifecycleEvictionPolicy.planSnapshotTransition(
                previousPluginIds,
                descriptors,
                snapshot.matchedPluginIds,
                snapshot.activePluginId,
                pinnedPluginId,
                activeHudPluginId,
            )
        containerCallbacks.evictStaleAndUninstalled(plan, descriptors, pool, crashRecovery, lastUrlByPlugin, assetFetcher, ::evictOverlay)

        matchedPluginIds = snapshot.matchedPluginIds.filter { id -> descriptors[id]?.enabled == true }
        val targetId = plan.nextActivePluginId
        if (targetId != null) {
            activatePlugin(targetId, forceMount = activeNeedsRemount)
        } else {
            val prev = activePluginId?.let(pool::peek)
            if (panelState != PanelState.CLOSED) eventBridge.pushVisibility(prev, visible = false, state = PanelState.CLOSED)
            prev?.flushCookies()
            activePluginId = null
            pool.updateRendererPriorities(panelState, activePluginId = null, pinnedPluginId = pinnedPluginId)
            surfaceDelegate.setPanelOverlayState(PluginPanelOverlayState.Empty)
            eventBridge.reportDiagnostics(pool.warmPluginIds(), activeHudPluginId)
        }
        surfaceDelegate.onMatchedPluginsUpdated(matchedPluginIds.mapNotNull(descriptors::get), activePluginId.orEmpty())
    }

    fun selectPlugin(pluginId: String): Boolean {
        if (descriptors[pluginId]?.enabled != true) return false
        crashRecovery.cancel(pluginId, resetBackoff = true)
        activatePlugin(pluginId, forceMount = false)
        surfaceDelegate.onMatchedPluginsUpdated(matchedPluginIds.mapNotNull(descriptors::get), activePluginId.orEmpty())
        return true
    }

    /** Clears cache, resets crash backoff, and reloads or recreates [pluginId]'s WebView sandbox. */
    fun reloadPluginWebView(pluginId: String) {
        val desc = descriptors[pluginId]?.takeIf { it.enabled } ?: return
        crashRecovery.cancel(pluginId, resetBackoff = true)
        if (pluginId == activePluginId) surfaceDelegate.setPanelOverlayState(PluginPanelOverlayState.None)
        val existing = pool.peek(pluginId)
        when {
            existing != null -> {
                existing.reloadWithCacheClear()
                if (pluginId == activePluginId && pluginId != pinnedPluginId && existing.webView.parent == null) {
                    containerCallbacks.mountInstance(existing)
                }
                val visible = (panelState != PanelState.CLOSED && pluginId == activePluginId) || pluginId == pinnedPluginId
                val effectiveState =
                    when {
                        pluginId == pinnedPluginId && panelState == PanelState.CLOSED -> PanelState.OPEN
                        visible -> panelState
                        else -> PanelState.CLOSED
                    }
                eventBridge.pushVisibility(existing, visible = visible, state = effectiveState, force = true)
            }

            pluginId == activePluginId -> {
                activatePlugin(pluginId, forceMount = true)
            }

            else -> {
                getOrCreateInstance(desc).reloadWithCacheClear()
            }
        }
    }

    fun onPanelStateChanged(nextState: PanelState) {
        panelState = nextState
        pool.updateRendererPriorities(panelState, activePluginId, pinnedPluginId)
        val activeId = activePluginId
        val active = activeId?.let(pool::peek)
        if (nextState != PanelState.CLOSED || activeId != pinnedPluginId) {
            eventBridge.pushVisibility(active, visible = nextState != PanelState.CLOSED, state = nextState)
        }
        if (nextState == PanelState.CLOSED) active?.flushCookies()
        eventBridge.reportDiagnostics(pool.warmPluginIds(), activeHudPluginId)
    }

    fun onTrimMemory(level: Int) =
        eventBridge.trimPoolOnMemoryPressure(level, activePluginId, pinnedPluginId, activeHudPluginId, keepAliveIds, pool)

    fun isBackIntercepted(): Boolean = activePluginId?.let(pool::peek)?.canConsumeBack() == true

    fun maxPluginPriority(): Int = descriptors.values.maxOfOrNull { it.priority } ?: 0

    fun dispatchBackPress(): Boolean = activePluginId?.let(pool::peek)?.consumeBackPress() == true

    fun broadcastConfigUpdated(
        decoded: ConfigUpdatedPayload,
        rawEnvelope: HostEventEnvelope,
    ) = eventBridge.broadcastConfigUpdated(decoded, rawEnvelope, pool)

    fun broadcastGlobalEvent(envelope: HostEventEnvelope) = eventBridge.broadcastGlobalEvent(envelope, pool)

    fun routePluginBusMessage(envelope: HostEventEnvelope) = eventBridge.routePluginBusMessage(envelope, pool)

    fun routeNetworkContextChanged(envelope: HostEventEnvelope) {
        latestNetworkRoute = eventBridge.routeNetworkContextChanged(envelope, latestNetworkRoute, pool)
    }

    fun routeBackendState(payload: PluginBackendStatePayload) =
        eventBridge.routeBackendState(payload, descriptors, pool) {
            surfaceDelegate.onMatchedPluginsUpdated(matchedPluginIds.mapNotNull(descriptors::get), activePluginId.orEmpty())
        }

    fun routePluginScopedEvent(payload: PluginScopedEventPayload) = eventBridge.routePluginScopedEvent(payload, pool)

    fun destroyAll() {
        crashRecovery.clearAll()
        pinnedPluginId = null
        activeHudPluginId = null
        activePluginId = null
        pool.destroyAll()
    }

    private fun activatePlugin(
        pluginId: String,
        forceMount: Boolean,
    ) {
        val descriptor = descriptors[pluginId]?.takeIf { it.enabled } ?: return
        val previousId = activePluginId
        val switching = previousId != descriptor.id
        if (switching && previousId != null) {
            val previous = pool.peek(previousId)
            if (previousId == pinnedPluginId) {
                previous?.resetPresentationToDrawer()
                evictOverlay(EvictedOverlayKind.PINNED, previousId)
            }
            if (panelState != PanelState.CLOSED) eventBridge.pushVisibility(previous, visible = false, state = PanelState.CLOSED)
            previous?.flushCookies()
        }

        activePluginId = descriptor.id
        surfaceDelegate.setPanelOverlayState(PluginPanelOverlayState.None)
        var createdNewInstance = false
        val instance = getOrCreateInstance(descriptor) { createdNewInstance = true }
        if (instance.presentationMode == PluginPresentationMode.PINNED) {
            instance.resetPresentationToDrawer()
            if (pinnedPluginId == descriptor.id) evictOverlay(EvictedOverlayKind.PINNED, descriptor.id)
        }
        pool.updateRendererPriorities(panelState, activePluginId, pinnedPluginId)
        if (switching || forceMount || instance.webView.parent == null) containerCallbacks.mountInstance(instance)
        eventBridge.reportDiagnostics(pool.warmPluginIds(), activeHudPluginId)
        eventBridge.syncActivatedVisibility(instance, panelState, createdNewInstance)
    }

    private fun getOrCreateInstance(
        descriptor: PluginDescriptor,
        onCreated: () -> Unit = {},
    ): PluginWebViewInstance {
        pool.peek(descriptor.id)?.let { existing ->
            if (existing.isUntrustedEmbed != PluginNetworkPolicy.isUntrustedEmbed(descriptor)) {
                if (descriptor.id == pinnedPluginId) evictOverlay(EvictedOverlayKind.PINNED, descriptor.id)
                pool.removeAndDestroy(descriptor.id)
            }
        }
        return pool.getOrCreate(descriptor.id, activePluginId, pinnedPluginId, keepAliveIds, { it.updateDescriptor(descriptor) }) {
            onCreated()
            PluginWebViewInstance(context, webViewDeps, descriptor, containerCallbacks, latestNetworkRoute).also { created ->
                val visible = (panelState != PanelState.CLOSED && descriptor.id == activePluginId) || descriptor.id == pinnedPluginId
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
        if (PluginLifecycleEvictionPolicy.shouldHidePinnedOnCrash(pluginId, pinnedPluginId)) {
            evictOverlay(EvictedOverlayKind.PINNED, pluginId)
        }
        pool.removeAndDestroy(pluginId)
        eventBridge.reportDiagnostics(pool.warmPluginIds(), activeHudPluginId)
        val desc = descriptors[pluginId]?.takeIf { it.enabled } ?: return
        if (pluginId != activePluginId) return
        crashRecovery.schedule(
            pluginId,
            onRecover = {
                if (pluginId == activePluginId && descriptors[pluginId]?.enabled == true) activatePlugin(pluginId, forceMount = true)
            },
            onCircuitOpen = { decision ->
                if (pluginId == activePluginId) {
                    val err =
                        PluginLifecycleEvictionPolicy.buildCrashCircuitError(pluginId, desc.name, decision.attempt) {
                            reloadPluginWebView(pluginId)
                        }
                    surfaceDelegate.setPanelOverlayState(err)
                }
            },
        )
    }

    private fun evictOverlay(
        kind: EvictedOverlayKind,
        pluginId: String,
    ) {
        if (kind == EvictedOverlayKind.PINNED && pinnedPluginId == pluginId) pinnedPluginId = null
        if (kind == EvictedOverlayKind.HUD && activeHudPluginId == pluginId) activeHudPluginId = null
        surfaceDelegate.onOverlayPluginEvicted(kind, pluginId)
    }

    private val keepAliveIds: Set<String>
        get() = descriptors.values.filter(isKeepAlivePlugin).mapTo(LinkedHashSet()) { it.id }

    companion object {
        private const val TAG = "SideVeilPluginMgr"
    }
}
