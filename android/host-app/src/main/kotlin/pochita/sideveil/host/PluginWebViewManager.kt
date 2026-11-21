package pochita.sideveil.host

import android.content.Context
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.protocol.BUILTIN_DIAG_PLUGIN_ID
import pochita.sideveil.protocol.EdgeStatusPayload
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.HostPluginDiagnosticsPayload
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PanelVisibilityPayload
import pochita.sideveil.protocol.PluginBackendStatePayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginPresentationMode
import pochita.sideveil.protocol.PluginScopedEventPayload
import pochita.sideveil.protocol.PluginSurfaceBackdrop
import pochita.sideveil.protocol.PluginsSnapshotPayload
import pochita.sideveil.protocol.SideVeilEvents
import pochita.sideveil.protocol.SideVeilWireJson

/**
 * Manages the LRU warm pool (max [MAX_WARM_INSTANCES] WebViews) of isolated plugin sandboxes,
 * active plugin switching, renderer priority / WebGPU VRAM reclamation, per-plugin DevTools,
 * back-press interception, scoped event routing, and render-crash recovery.
 */
internal class PluginWebViewManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val daemonClient: DaemonClient,
    haptics: EdgeHapticsController,
    private val surfaceDelegate: PluginHostSurfaceDelegate,
) {
    private val assetFetcher = PluginAssetFetcher(context) { daemonClient.currentEndpoint() }
    private val localRpcHandler = PluginLocalRpcHandler(context, haptics)
    private val descriptors = LinkedHashMap<String, PluginDescriptor>()
    private val warmInstances = LinkedHashMap<String, PluginWebViewInstance>(4, 0.75f, true)

    private var matchedPluginIds: List<String> = emptyList()
    private var activePluginId: String = BUILTIN_DIAG_PLUGIN_ID
    private var pinnedPluginId: String? = null
    private var activeHudPluginId: String? = null
    private var panelState: PanelState = PanelState.CLOSED
    private var lastConfigEnvelope: HostEventEnvelope? = null
    private var lastPaletteEnvelope: HostEventEnvelope? = null

    init {
        descriptors[BUILTIN_DIAG_PLUGIN_ID] = PluginDescriptor.builtinDiagnostic()
    }

    fun warmStartDefault() {
        activatePlugin(BUILTIN_DIAG_PLUGIN_ID, forceMount = true)
    }

    fun setPinnedPlugin(pluginId: String?) {
        if (pinnedPluginId == pluginId) return
        pinnedPluginId = pluginId
        updateAllRendererPriorities()
    }

    fun setActiveHudPlugin(pluginId: String?) {
        if (activeHudPluginId == pluginId) return
        activeHudPluginId = pluginId
        reportDiagnostics()
    }

    fun notifyActiveClickThroughChanged(clickThrough: Boolean) {
        val targetId = pinnedPluginId ?: activePluginId
        warmInstances[targetId]?.notifyClickThroughChanged(clickThrough)
    }

    fun resetActiveToDrawer() {
        val targetId = pinnedPluginId ?: activePluginId
        pinnedPluginId = null
        val instance = warmInstances[targetId]
        if (instance != null && instance.presentationMode == PluginPresentationMode.PINNED) {
            instance.resetPresentationToDrawer()
        }
        val activeInstance = warmInstances[activePluginId]
        if (activeInstance != null) {
            surfaceDelegate.mountPluginWebView(
                webView = activeInstance.webView,
                backdrop = activeInstance.surfaceBackdrop,
                presentationMode = activeInstance.presentationMode,
                sizeOverride = activeInstance.sizeOverride,
            )
        }
        updateAllRendererPriorities()
    }

    fun onPluginsSnapshot(snapshot: PluginsSnapshotPayload) {
        descriptors.clear()
        for (item in snapshot.plugins) {
            descriptors[item.id] = item
            warmInstances[item.id]?.updateDescriptor(item)
        }
        if (!descriptors.containsKey(BUILTIN_DIAG_PLUGIN_ID)) {
            descriptors[BUILTIN_DIAG_PLUGIN_ID] = PluginDescriptor.builtinDiagnostic()
        }

        // Evict warm instances whose plugins were removed or disabled.
        val staleIds =
            warmInstances.keys.filter { id ->
                val desc = descriptors[id]
                desc == null || !desc.enabled
            }
        for (id in staleIds) {
            warmInstances.remove(id)?.destroy()
        }

        matchedPluginIds = snapshot.matchedPluginIds.filter { descriptors.containsKey(it) }
        val targetId =
            snapshot.activePluginId?.takeIf { descriptors.containsKey(it) }
                ?: matchedPluginIds.firstOrNull()
                ?: BUILTIN_DIAG_PLUGIN_ID

        activatePlugin(targetId, forceMount = false)
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
        updateAllRendererPriorities()
        if (nextState == PanelState.CLOSED && activePluginId == pinnedPluginId) {
            reportDiagnostics()
            return
        }
        val payload =
            SideVeilWireJson.encodeToJsonElement(
                PanelVisibilityPayload(visible = nextState != PanelState.CLOSED, state = nextState),
            )
        warmInstances[activePluginId]?.pushEvent(SideVeilEvents.PANEL_VISIBILITY, payload)
        reportDiagnostics()
    }

    fun reportDiagnostics() {
        val diag =
            HostPluginDiagnosticsPayload(
                warmPluginIds = warmInstances.keys.toList(),
                activeHudPluginId = activeHudPluginId,
                hostNativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024L,
                hostPssKb = runCatching { Debug.getPss() }.getOrNull(),
            )
        daemonClient.sendEvent(SideVeilEvents.HOST_DIAGNOSTICS, SideVeilWireJson.encodeToJsonElement(diag))
    }

    fun isBackIntercepted(): Boolean = warmInstances[activePluginId]?.backInterceptEnabled == true

    fun maxPluginPriority(): Int = descriptors.values.maxOfOrNull { it.priority } ?: 0

    fun dispatchBackPress(): Boolean {
        val active = warmInstances[activePluginId] ?: return false
        if (!active.backInterceptEnabled) return false
        active.pushEvent(
            SideVeilEvents.PANEL_BACK_PRESSED,
            buildJsonObject { put("timestampMs", JsonPrimitive(System.currentTimeMillis())) },
        )
        return true
    }

    fun broadcastGlobalEvent(envelope: HostEventEnvelope) {
        if (envelope.event == SideVeilEvents.CONFIG_UPDATED) {
            lastConfigEnvelope = envelope
        } else if (envelope.event == SideVeilEvents.THEME_PALETTE_UPDATED) {
            lastPaletteEnvelope = envelope
        }
        for (instance in warmInstances.values) {
            instance.pushEnvelope(envelope)
        }
    }

    fun routeBackendState(payload: PluginBackendStatePayload) {
        val current = descriptors[payload.pluginId]
        if (current != null) {
            val updated = current.copy(backendState = payload.state)
            descriptors[payload.pluginId] = updated
            warmInstances[payload.pluginId]?.updateDescriptor(updated)
            notifyRail()
        }
        warmInstances[payload.pluginId]?.pushEvent(
            SideVeilEvents.PLUGIN_BACKEND_STATE,
            SideVeilWireJson.encodeToJsonElement(payload),
        )
    }

    fun routePluginScopedEvent(payload: PluginScopedEventPayload) {
        warmInstances[payload.pluginId]?.pushEvent(
            SideVeilEvents.PLUGIN_EVENT,
            SideVeilWireJson.encodeToJsonElement(payload),
        )
    }

    fun destroyAll() {
        pinnedPluginId = null
        activeHudPluginId = null
        for (instance in warmInstances.values) {
            instance.destroy()
        }
        warmInstances.clear()
    }

    private fun activatePlugin(
        pluginId: String,
        forceMount: Boolean,
    ) {
        val descriptor = descriptors[pluginId] ?: descriptors[BUILTIN_DIAG_PLUGIN_ID] ?: return
        val previousId = activePluginId
        val switching = previousId != descriptor.id
        if (switching) {
            if (previousId == pinnedPluginId) {
                warmInstances[previousId]?.resetPresentationToDrawer()
                pinnedPluginId = null
            }
            if (panelState != PanelState.CLOSED) {
                warmInstances[previousId]?.pushEvent(
                    SideVeilEvents.PANEL_VISIBILITY,
                    SideVeilWireJson.encodeToJsonElement(
                        PanelVisibilityPayload(visible = false, state = PanelState.CLOSED),
                    ),
                )
            }
        }

        activePluginId = descriptor.id
        val instance = getOrCreateInstance(descriptor)
        if (instance.presentationMode == PluginPresentationMode.PINNED) {
            instance.resetPresentationToDrawer()
            if (pinnedPluginId == descriptor.id) pinnedPluginId = null
        }
        updateAllRendererPriorities()
        if (switching || forceMount || instance.webView.parent == null) {
            surfaceDelegate.mountPluginWebView(
                webView = instance.webView,
                backdrop = instance.surfaceBackdrop,
                presentationMode = instance.presentationMode,
                sizeOverride = instance.sizeOverride,
            )
        }
        reportDiagnostics()
        if (switching && panelState != PanelState.CLOSED) {
            instance.pushEvent(
                SideVeilEvents.PANEL_VISIBILITY,
                SideVeilWireJson.encodeToJsonElement(
                    PanelVisibilityPayload(visible = true, state = panelState),
                ),
            )
        }
    }

    private fun updateAllRendererPriorities() {
        warmInstances.forEach { (id, inst) ->
            val foreground = (panelState != PanelState.CLOSED && id == activePluginId) || id == pinnedPluginId
            inst.setForegroundPriority(foreground)
        }
    }

    private fun getOrCreateInstance(descriptor: PluginDescriptor): PluginWebViewInstance {
        warmInstances[descriptor.id]?.let { existing ->
            existing.updateDescriptor(descriptor)
            return existing
        }

        evictLruIfNeeded(keepId = descriptor.id)
        val instance =
            PluginWebViewInstance(
                context = context,
                scope = scope,
                daemonClient = daemonClient,
                localRpcHandler = localRpcHandler,
                assetFetcher = assetFetcher,
                initialDescriptor = descriptor,
                callbacks =
                    object : PluginContainerCallbacks {
                        override fun onDismissRequested(pluginId: String) {
                            if (pluginId == activePluginId) surfaceDelegate.onDismissPanel()
                        }

                        override fun onImeFocusRequested(
                            pluginId: String,
                            focused: Boolean,
                        ) {
                            if (pluginId == activePluginId) surfaceDelegate.onSetImeFocused(focused)
                        }

                        override fun onAppearanceSynced(
                            pluginId: String,
                            themeId: String,
                            colorScheme: String,
                        ) {
                            if (pluginId == activePluginId) {
                                surfaceDelegate.onSyncAppearance(themeId, colorScheme)
                            }
                        }

                        override fun onPreferredSizeChanged(
                            pluginId: String,
                            size: PluginSizeOverride,
                        ) {
                            if (pluginId == activePluginId) surfaceDelegate.onPluginSizeChanged(size)
                        }

                        override fun onBackdropChanged(
                            pluginId: String,
                            backdrop: PluginSurfaceBackdrop,
                        ) {
                            if (pluginId == activePluginId) {
                                surfaceDelegate.onPluginBackdropChanged(backdrop)
                            }
                        }

                        override fun onPresentationModeChanged(
                            pluginId: String,
                            mode: PluginPresentationMode,
                        ) {
                            if (pluginId == activePluginId) {
                                warmInstances[pluginId]?.let { activeInst ->
                                    surfaceDelegate.onPluginPresentationChanged(
                                        pluginId = pluginId,
                                        webView = activeInst.webView,
                                        backdrop = activeInst.surfaceBackdrop,
                                        mode = mode,
                                        sizeOverride = activeInst.sizeOverride,
                                    )
                                }
                            }
                        }

                        override fun onPinnedBoundsChanged(
                            pluginId: String,
                            bounds: PinnedBoundsSpec,
                        ) {
                            if (pluginId == activePluginId) surfaceDelegate.onPinnedBoundsChanged(bounds)
                        }

                        override fun onEdgeStatusChanged(
                            pluginId: String,
                            status: EdgeStatusPayload?,
                        ) {
                            if (pluginId == activePluginId) surfaceDelegate.onEdgeStatusChanged(status)
                        }

                        override fun onRenderProcessCrashed(pluginId: String) {
                            recoverCrashedPlugin(pluginId)
                        }
                    },
            )
        warmInstances[descriptor.id] = instance
        instance.loadEntry()
        lastConfigEnvelope?.let { instance.pushEnvelope(it) }
        lastPaletteEnvelope?.let { instance.pushEnvelope(it) }
        instance.pushBackendState()
        return instance
    }

    private fun recoverCrashedPlugin(pluginId: String) {
        Log.w(TAG, "Recovering crashed WebView instance for plugin=$pluginId")
        warmInstances.remove(pluginId)?.destroy()
        val descriptor = descriptors[pluginId] ?: return
        if (pluginId == activePluginId) {
            activatePlugin(descriptor.id, forceMount = true)
        }
    }

    private fun evictLruIfNeeded(keepId: String) {
        while (warmInstances.size >= MAX_WARM_INSTANCES) {
            // Reclaim inactive COOP/COEP (WebGPU/WASM) renderers first to free GPU VRAM aggressively.
            val candidate =
                warmInstances.entries
                    .firstOrNull { (id, inst) ->
                        id != activePluginId && id != keepId && inst.descriptor.surface.crossOriginIsolated
                    }?.key
                    ?: warmInstances.keys.firstOrNull { it != activePluginId && it != keepId }
                    ?: break
            Log.i(TAG, "Evicting LRU warm plugin WebView: $candidate")
            warmInstances.remove(candidate)?.destroy()
        }
    }

    private fun notifyRail() {
        val matched = matchedPluginIds.mapNotNull { descriptors[it] }
        surfaceDelegate.onMatchedPluginsUpdated(matched, activePluginId)
    }

    companion object {
        private const val TAG = "SideVeilPluginMgr"
        private const val MAX_WARM_INSTANCES = 3
    }
}
