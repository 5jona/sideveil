package pochita.sideveil.host

import android.webkit.WebView
import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.protocol.DynamicThemePalette
import pochita.sideveil.protocol.EdgeStatusPayload
import pochita.sideveil.protocol.GlassTintOverride
import pochita.sideveil.protocol.HostDynamicColorsPayload
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.HudDismissPayload
import pochita.sideveil.protocol.NativeHudSceneHeader
import pochita.sideveil.protocol.PluginPresentationMode
import pochita.sideveil.protocol.PluginSurfaceBackdrop
import pochita.sideveil.protocol.SideveilConfig
import pochita.sideveil.protocol.SideveilEvents
import pochita.sideveil.protocol.SideveilWireJson

/**
 * Coordinates [PinnedContainerWindow], [NativeHudWindow], [DynamicThemeExtractor], and
 * `theme.paletteUpdated` synchronization across all native host overlay surfaces.
 */
internal class HostOverlayCoordinator(
    private val daemonClient: DaemonClient,
    private val edgeIndicator: EdgeIndicatorWindow,
    private val pluginRail: PluginRailWindow,
    private val panelWindow: SidebarPanelWindow,
    private val pinnedWindow: PinnedContainerWindow,
    private val nativeHudWindow: NativeHudWindow,
    private val pluginManagerProvider: () -> PluginWebViewManager,
    private val configProvider: () -> SideveilConfig,
    private val themeIdProvider: () -> String,
    private val darkSurfaceProvider: () -> Boolean,
) : HostOverlayEventCallbacks {
    var latestDynamicTint: GlassTintOverride? = null
        private set

    fun onDynamicColorsExtracted(payload: HostDynamicColorsPayload) {
        daemonClient.sendEvent(
            SideveilEvents.HOST_DYNAMIC_COLORS,
            SideveilWireJson.encodeToJsonElement(payload),
        )
    }

    override fun onThemePaletteUpdated(
        decoded: DynamicThemePalette,
        rawEnvelope: HostEventEnvelope,
    ) {
        latestDynamicTint = decoded.glass
        val scheme = if (decoded.isDark) "dark" else "light"
        panelWindow.syncRuntimeAppearance(decoded.themeId, scheme, decoded.glass)
        refreshOverlayStyles()
        pluginManagerProvider().broadcastGlobalEvent(rawEnvelope)
    }

    override fun onHudSceneUpdated(
        rawSceneJson: String,
        header: NativeHudSceneHeader,
    ) {
        nativeHudWindow.updateScene(rawSceneJson, header)
    }

    override fun onHudDismissed(decoded: HudDismissPayload) {
        nativeHudWindow.dismiss(decoded.pluginId)
    }

    fun handlePresentationChanged(
        webView: WebView,
        backdrop: PluginSurfaceBackdrop,
        mode: PluginPresentationMode,
        sizeOverride: PluginSizeOverride,
    ) {
        if (mode == PluginPresentationMode.PINNED) {
            panelWindow.close()
            pinnedWindow.show(webView, backdrop)
        } else {
            if (pinnedWindow.isVisible) {
                pinnedWindow.hide()
                panelWindow.setContentWebView(webView)
            }
            panelWindow.setPluginBackdrop(backdrop)
            panelWindow.setPluginSizeOverride(sizeOverride, mode)
        }
    }

    fun handleUnpinToDrawer() {
        pinnedWindow.hide()
        pluginManagerProvider().activeWebView()?.let { webView ->
            panelWindow.setContentWebView(webView)
        }
        panelWindow.open()
    }

    fun handlePinnedBoundsChanged(bounds: PinnedBoundsSpec) {
        pinnedWindow.updateBounds(bounds)
    }

    fun handleEdgeStatusChanged(status: EdgeStatusPayload?) {
        edgeIndicator.setEdgeStatus(status)
    }

    fun refreshOverlayStyles() {
        val cfg = configProvider()
        val themeId = themeIdProvider()
        val isDark = darkSurfaceProvider()
        val blur = panelWindow.isSystemBlurSupported
        pluginRail.updateStyle(cfg.edge.side, cfg.panel, themeId, isDark, blur, latestDynamicTint)
        pinnedWindow.updateStyle(cfg.panel, themeId, isDark, latestDynamicTint)
        nativeHudWindow.updateStyle(cfg.panel, themeId, isDark, blur, latestDynamicTint)
    }

    fun hideTransientOverlays() {
        pinnedWindow.hide()
        nativeHudWindow.dismiss(null)
        edgeIndicator.setEdgeStatus(null)
    }

    fun createSurfaceDelegate(onSyncAppearance: (String, String) -> Unit): PluginHostSurfaceDelegate =
        object : PluginHostSurfaceDelegate {
            override fun mountPluginWebView(
                webView: WebView,
                backdrop: PluginSurfaceBackdrop,
                presentationMode: PluginPresentationMode,
                sizeOverride: PluginSizeOverride,
            ) {
                pinnedWindow.hide()
                panelWindow.setContentWebView(webView)
                panelWindow.setPluginBackdrop(backdrop)
                panelWindow.setPluginSizeOverride(sizeOverride, presentationMode)
            }

            override fun onDismissPanel() {
                if (pinnedWindow.isVisible) pinnedWindow.hide()
                panelWindow.close()
            }

            override fun onSetImeFocused(focused: Boolean) = panelWindow.setImeFocused(focused)

            override fun onSyncAppearance(
                themeId: String,
                colorScheme: String,
            ) = onSyncAppearance(themeId, colorScheme)

            override fun onPluginSizeChanged(sizeOverride: PluginSizeOverride) = panelWindow.setPluginSizeOverride(sizeOverride)

            override fun onPluginBackdropChanged(backdrop: PluginSurfaceBackdrop) = panelWindow.setPluginBackdrop(backdrop)

            override fun onPluginPresentationChanged(
                webView: WebView,
                backdrop: PluginSurfaceBackdrop,
                mode: PluginPresentationMode,
            ) = handlePresentationChanged(webView, backdrop, mode, PluginSizeOverride())

            override fun onPinnedBoundsChanged(bounds: PinnedBoundsSpec) = handlePinnedBoundsChanged(bounds)

            override fun onEdgeStatusChanged(status: EdgeStatusPayload?) = handleEdgeStatusChanged(status)

            override fun onMatchedPluginsUpdated(
                matchedPlugins: List<pochita.sideveil.protocol.PluginDescriptor>,
                activePluginId: String,
            ) {
                pluginRail.updatePlugins(matchedPlugins, activePluginId)
                panelWindow.setRailInsetDp(pluginRail.railInsetDp())
            }
        }
}
