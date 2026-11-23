package pochita.sideveil.host.plugin

import android.content.ComponentCallbacks2
import android.util.Log
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.host.debug.PluginManagerDebugState
import pochita.sideveil.host.debug.buildHostDiagnosticsPayload
import pochita.sideveil.host.service.DaemonClient
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.HostWebViewErrorPayload
import pochita.sideveil.protocol.NetworkContextSnapshot
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PanelVisibilityPayload
import pochita.sideveil.protocol.PluginBackendStatePayload
import pochita.sideveil.protocol.PluginCapability
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginScopedEventPayload
import pochita.sideveil.protocol.SideVeilEvents
import pochita.sideveil.protocol.SideVeilWireJson
import java.util.Locale

/**
 * Caches replayable global envelopes (`config.updated`, `theme.paletteUpdated`,
 * `network.contextChanged`) and handles outbound host diagnostics, memory-pressure pool
 * trimming, scoped event routing, and loopback fetch errors for [PluginWebViewManager].
 */
internal class PluginWebViewEventBridge(
    private val daemonClient: DaemonClient,
    private val crashRegistry: WebViewCrashRegistry,
) {
    private var lastConfigEnvelope: HostEventEnvelope? = null
    private var lastPaletteEnvelope: HostEventEnvelope? = null
    private var lastNetworkContextEnvelope: HostEventEnvelope? = null

    fun recordGlobalEnvelope(envelope: HostEventEnvelope) {
        when (envelope.event) {
            SideVeilEvents.CONFIG_UPDATED -> lastConfigEnvelope = envelope
            SideVeilEvents.THEME_PALETTE_UPDATED -> lastPaletteEnvelope = envelope
        }
    }

    fun recordNetworkContextEnvelope(envelope: HostEventEnvelope) {
        lastNetworkContextEnvelope = envelope
    }

    fun broadcastGlobalEvent(
        envelope: HostEventEnvelope,
        pool: PluginWebViewPool<PluginWebViewInstance>,
    ) {
        recordGlobalEnvelope(envelope)
        pool.forEachInstance { it.pushEnvelope(envelope) }
    }

    fun routeNetworkContextChanged(
        envelope: HostEventEnvelope,
        currentRoute: NetworkRouteContext,
        pool: PluginWebViewPool<PluginWebViewInstance>,
    ): NetworkRouteContext {
        recordNetworkContextEnvelope(envelope)
        val snapshot =
            envelope.payload?.let { payload ->
                runCatching { SideVeilWireJson.decodeFromJsonElement<NetworkContextSnapshot>(payload) }.getOrNull()
            }
        val nextRoute =
            if (snapshot != null) {
                NetworkRouteContext(
                    transport = snapshot.transport.name.lowercase(Locale.ROOT),
                    wifiSsid = snapshot.ssid,
                )
            } else {
                currentRoute
            }
        pool.forEachInstance { inst ->
            if (inst.isUntrustedEmbed) {
                if (snapshot != null) {
                    inst.updateNetworkRoute(nextRoute)
                }
            } else if (inst.descriptor.capabilities.contains(PluginCapability.NETWORK_CONTEXT)) {
                inst.pushEnvelope(envelope)
            }
        }
        return nextRoute
    }

    fun routeBackendState(
        payload: PluginBackendStatePayload,
        descriptors: MutableMap<String, PluginDescriptor>,
        pool: PluginWebViewPool<PluginWebViewInstance>,
        onUpdated: () -> Unit,
    ) {
        descriptors[payload.pluginId]?.let { current ->
            val updated = current.copy(backendState = payload.state)
            descriptors[payload.pluginId] = updated
            pool.peek(payload.pluginId)?.updateDescriptor(updated)
            onUpdated()
        }
        pool.peek(payload.pluginId)?.pushEvent(
            SideVeilEvents.PLUGIN_BACKEND_STATE,
            SideVeilWireJson.encodeToJsonElement(payload),
        )
    }

    fun routePluginScopedEvent(
        payload: PluginScopedEventPayload,
        pool: PluginWebViewPool<PluginWebViewInstance>,
    ) {
        pool.peek(payload.pluginId)?.pushEvent(
            SideVeilEvents.PLUGIN_EVENT,
            SideVeilWireJson.encodeToJsonElement(payload),
        )
    }

    fun buildManagerDebugState(
        activePluginId: String,
        pinnedPluginId: String?,
        activeHudPluginId: String?,
        matchedPluginIds: List<String>,
        webviewDebuggingEnabled: Boolean,
        pool: PluginWebViewPool<PluginWebViewInstance>,
        isKeepAlivePlugin: (PluginDescriptor) -> Boolean,
    ): PluginManagerDebugState =
        PluginManagerDebugState(
            activePluginId = activePluginId,
            pinnedPluginId = pinnedPluginId,
            activeHudPluginId = activeHudPluginId,
            matchedPluginIds = matchedPluginIds.toList(),
            webviewDebuggingEnabled = webviewDebuggingEnabled,
            webViews =
                pool.snapshotInstances().map { (id, inst) ->
                    inst.debugState(
                        isActive = id == activePluginId,
                        isPinned = id == pinnedPluginId,
                        keepAlive = isKeepAlivePlugin(inst.descriptor),
                    )
                },
        )

    fun trimPoolOnMemoryPressure(
        level: Int,
        activePluginId: String,
        pinnedPluginId: String?,
        activeHudPluginId: String?,
        protectedIds: Set<String>,
        pool: PluginWebViewPool<PluginWebViewInstance>,
    ) {
        val severe = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        val moderate = level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        if (!moderate && !severe) return
        val evicted =
            pool.trimNonActive(
                activePluginId = activePluginId,
                pinnedPluginId = pinnedPluginId,
                protectedIds = protectedIds,
                includeKeepAlive = severe,
            )
        if (evicted.isNotEmpty()) {
            Log.i(TAG, "Trimmed ${evicted.size} warm plugin WebViews (level=$level, severe=$severe): $evicted")
            reportDiagnostics(pool.warmPluginIds(), activeHudPluginId)
        }
    }

    fun hydrateNewInstance(
        instance: PluginWebViewInstance,
        descriptor: PluginDescriptor,
        panelState: PanelState,
        initiallyVisible: Boolean,
        restoreUrl: String? = null,
    ) {
        instance.loadEntry(restoreUrl)
        lastConfigEnvelope?.let(instance::pushEnvelope)
        lastPaletteEnvelope?.let(instance::pushEnvelope)
        if (!instance.isUntrustedEmbed && descriptor.capabilities.contains(PluginCapability.NETWORK_CONTEXT)) {
            lastNetworkContextEnvelope?.let(instance::pushEnvelope)
        }
        instance.pushBackendState()
        pushVisibility(
            instance = instance,
            visible = initiallyVisible,
            state = if (initiallyVisible) panelState else PanelState.CLOSED,
        )
    }

    fun pushVisibility(
        instance: PluginWebViewInstance?,
        visible: Boolean,
        state: PanelState,
    ) {
        instance?.pushEvent(
            SideVeilEvents.PANEL_VISIBILITY,
            SideVeilWireJson.encodeToJsonElement(PanelVisibilityPayload(visible = visible, state = state)),
        )
    }

    fun reportLoopbackFetchError(
        pluginId: String,
        url: String,
        description: String,
        isMainFrame: Boolean,
    ) {
        daemonClient.sendEvent(
            SideVeilEvents.HOST_WEBVIEW_ERROR,
            SideVeilWireJson.encodeToJsonElement(
                HostWebViewErrorPayload(
                    pluginId = pluginId,
                    kind = "loopback",
                    code = 502,
                    description = description,
                    url = url,
                    isMainFrame = isMainFrame,
                ),
            ),
        )
    }

    fun reportDiagnostics(
        warmPluginIds: List<String>,
        activeHudPluginId: String?,
    ) {
        val diag =
            buildHostDiagnosticsPayload(
                warmPluginIds = warmPluginIds,
                activeHudPluginId = activeHudPluginId,
                webviewCrashCount = crashRegistry.totalCrashes(),
            )
        daemonClient.sendEvent(SideVeilEvents.HOST_DIAGNOSTICS, SideVeilWireJson.encodeToJsonElement(diag))
    }

    companion object {
        private const val TAG = "SideVeilPluginMgr"
    }
}
