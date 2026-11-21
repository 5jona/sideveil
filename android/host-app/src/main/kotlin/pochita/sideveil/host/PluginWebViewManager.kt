package pochita.sideveil.host

import android.content.Context
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.protocol.BUILTIN_DIAG_PLUGIN_ID
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PanelVisibilityPayload
import pochita.sideveil.protocol.PluginBackendStatePayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginScopedEventPayload
import pochita.sideveil.protocol.PluginSurfaceBackdrop
import pochita.sideveil.protocol.PluginsSnapshotPayload
import pochita.sideveil.protocol.SideveilEvents
import pochita.sideveil.protocol.SideveilWireJson

data class PluginSizeOverride(
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val heightPercent: Int? = null,
)

interface PluginContainerCallbacks {
    fun onDismissRequested(pluginId: String)

    fun onImeFocusRequested(
        pluginId: String,
        focused: Boolean,
    )

    fun onAppearanceSynced(
        pluginId: String,
        themeId: String,
        colorScheme: String,
    )

    fun onPreferredSizeChanged(
        pluginId: String,
        size: PluginSizeOverride,
    )

    fun onBackdropChanged(
        pluginId: String,
        backdrop: PluginSurfaceBackdrop,
    )

    fun onRenderProcessCrashed(pluginId: String)
}

interface PluginHostSurfaceDelegate {
    fun mountPluginWebView(
        webView: WebView,
        backdrop: PluginSurfaceBackdrop,
        sizeOverride: PluginSizeOverride,
    )

    fun onDismissPanel()

    fun onSetImeFocused(focused: Boolean)

    fun onSyncAppearance(
        themeId: String,
        colorScheme: String,
    )

    fun onPluginSizeChanged(sizeOverride: PluginSizeOverride)

    fun onPluginBackdropChanged(backdrop: PluginSurfaceBackdrop)

    fun onMatchedPluginsUpdated(
        matchedPlugins: List<PluginDescriptor>,
        activePluginId: String,
    )
}

/**
 * Manages the LRU warm pool (max [MAX_WARM_INSTANCES] WebViews) of isolated plugin sandboxes,
 * active plugin switching, back-press interception, scoped event routing, and render-crash recovery.
 */
class PluginWebViewManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val daemonClient: DaemonClient,
    private val surfaceDelegate: PluginHostSurfaceDelegate,
) {
    private val assetFetcher = PluginAssetFetcher(context) { daemonClient.currentEndpoint() }
    private val descriptors = LinkedHashMap<String, PluginDescriptor>()
    private val warmInstances = LinkedHashMap<String, PluginWebViewInstance>(4, 0.75f, true)

    private var matchedPluginIds: List<String> = emptyList()
    private var activePluginId: String = BUILTIN_DIAG_PLUGIN_ID
    private var panelState: PanelState = PanelState.CLOSED
    private var lastConfigEnvelope: HostEventEnvelope? = null

    init {
        descriptors[BUILTIN_DIAG_PLUGIN_ID] = PluginDescriptor.builtinDiagnostic()
    }

    fun warmStartDefault() {
        activatePlugin(BUILTIN_DIAG_PLUGIN_ID, forceMount = true)
    }

    fun activePluginId(): String = activePluginId

    fun activeWebView(): WebView? = warmInstances[activePluginId]?.webView

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
        val payload =
            SideveilWireJson.encodeToJsonElement(
                PanelVisibilityPayload(visible = nextState != PanelState.CLOSED, state = nextState),
            )
        warmInstances[activePluginId]?.pushEvent(SideveilEvents.PANEL_VISIBILITY, payload)
    }

    fun isBackIntercepted(): Boolean = warmInstances[activePluginId]?.backInterceptEnabled == true

    fun maxPluginPriority(): Int = descriptors.values.maxOfOrNull { it.priority } ?: 0

    fun dispatchBackPress(): Boolean {
        val active = warmInstances[activePluginId] ?: return false
        if (!active.backInterceptEnabled) return false
        active.pushEvent(
            SideveilEvents.PANEL_BACK_PRESSED,
            buildJsonObject { put("timestampMs", JsonPrimitive(System.currentTimeMillis())) },
        )
        return true
    }

    fun broadcastGlobalEvent(envelope: HostEventEnvelope) {
        if (envelope.event == SideveilEvents.CONFIG_UPDATED) {
            lastConfigEnvelope = envelope
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
            SideveilEvents.PLUGIN_BACKEND_STATE,
            SideveilWireJson.encodeToJsonElement(payload),
        )
    }

    fun routePluginScopedEvent(payload: PluginScopedEventPayload) {
        warmInstances[payload.pluginId]?.pushEvent(
            SideveilEvents.PLUGIN_EVENT,
            SideveilWireJson.encodeToJsonElement(payload),
        )
    }

    fun pushEventToActive(
        event: String,
        payload: JsonElement?,
    ) {
        warmInstances[activePluginId]?.pushEvent(event, payload)
    }

    fun destroyAll() {
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
        if (switching && panelState != PanelState.CLOSED) {
            warmInstances[previousId]?.pushEvent(
                SideveilEvents.PANEL_VISIBILITY,
                SideveilWireJson.encodeToJsonElement(
                    PanelVisibilityPayload(visible = false, state = PanelState.CLOSED),
                ),
            )
        }

        activePluginId = descriptor.id
        val instance = getOrCreateInstance(descriptor)
        if (switching || forceMount) {
            surfaceDelegate.mountPluginWebView(
                webView = instance.webView,
                backdrop = instance.surfaceBackdrop,
                sizeOverride = instance.sizeOverride,
            )
        }
        if (switching && panelState != PanelState.CLOSED) {
            instance.pushEvent(
                SideveilEvents.PANEL_VISIBILITY,
                SideveilWireJson.encodeToJsonElement(
                    PanelVisibilityPayload(visible = true, state = panelState),
                ),
            )
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

                        override fun onRenderProcessCrashed(pluginId: String) {
                            recoverCrashedPlugin(pluginId)
                        }
                    },
            )
        warmInstances[descriptor.id] = instance
        instance.loadEntry()
        lastConfigEnvelope?.let { instance.pushEnvelope(it) }
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
            val candidate =
                warmInstances.keys.firstOrNull { it != activePluginId && it != keepId }
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
        private const val TAG = "SideveilPluginMgr"
        private const val MAX_WARM_INSTANCES = 3
    }
}
