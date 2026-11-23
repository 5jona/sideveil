package pochita.sideveil.host

import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.HostWebViewErrorPayload
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PanelVisibilityPayload
import pochita.sideveil.protocol.PluginCapability
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.SideVeilEvents
import pochita.sideveil.protocol.SideVeilWireJson

/**
 * Caches replayable global envelopes (`config.updated`, `theme.paletteUpdated`,
 * `network.contextChanged`) and handles outbound host diagnostics & loopback fetch errors
 * for [PluginWebViewManager].
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
}
