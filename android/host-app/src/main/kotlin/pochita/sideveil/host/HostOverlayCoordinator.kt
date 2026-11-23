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
import pochita.sideveil.protocol.SideVeilConfig
import pochita.sideveil.protocol.SideVeilEvents
import pochita.sideveil.protocol.SideVeilWireJson

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
    private val configProvider: () -> SideVeilConfig,
    private val themeIdProvider: () -> String,
    private val darkSurfaceProvider: () -> Boolean,
) : HostOverlayEventCallbacks {
    var latestDynamicTint: GlassTintOverride? = null
        private set
    private var pendingPinnedBounds: PinnedBoundsSpec? = null

    fun onDynamicColorsExtracted(payload: HostDynamicColorsPayload) {
        daemonClient.sendEvent(
            SideVeilEvents.HOST_DYNAMIC_COLORS,
            SideVeilWireJson.encodeToJsonElement(payload),
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
        pluginId: String,
        webView: WebView,
        backdrop: PluginSurfaceBackdrop,
        mode: PluginPresentationMode,
        sizeOverride: PluginSizeOverride,
    ) {
        if (mode == PluginPresentationMode.PINNED) {
            val initialBounds = pendingPinnedBounds
            pendingPinnedBounds = null
            pluginManagerProvider().setPinnedPlugin(pluginId)
            panelWindow.setContentWebView(null)
            panelWindow.close()
            pinnedWindow.show(webView, backdrop, initialBounds)
        } else {
            pendingPinnedBounds = null
            val wasPinned = pinnedWindow.isVisible
            if (wasPinned) {
                pinnedWindow.hide()
            }
            pluginManagerProvider().setPinnedPlugin(null)
            panelWindow.setPluginBackdrop(backdrop)
            panelWindow.setPluginSizeOverride(sizeOverride, mode)
            panelWindow.setContentWebView(webView)
            if (wasPinned && panelWindow.currentState == pochita.sideveil.protocol.PanelState.CLOSED) {
                panelWindow.open()
            }
        }
    }

    fun handlePinnedClickThroughChanged(clickThrough: Boolean) {
        pluginManagerProvider().notifyActiveClickThroughChanged(clickThrough)
    }

    fun handleDismissPinned() {
        pendingPinnedBounds = null
        pinnedWindow.hide()
        pluginManagerProvider().resetActiveToDrawer()
    }

    fun ensureDrawerMountedBeforeOpen() {
        if (pinnedWindow.isVisible) {
            pendingPinnedBounds = null
            pinnedWindow.hide()
            pluginManagerProvider().resetActiveToDrawer()
        }
    }

    fun handlePinnedBoundsChanged(bounds: PinnedBoundsSpec) {
        if (pinnedWindow.isVisible) {
            pinnedWindow.updateBounds(bounds)
        } else {
            val prev = pendingPinnedBounds
            pendingPinnedBounds =
                PinnedBoundsSpec(
                    widthDp = bounds.widthDp ?: prev?.widthDp,
                    heightDp = bounds.heightDp ?: prev?.heightDp,
                    xPercent = bounds.xPercent ?: prev?.xPercent,
                    yPercent = bounds.yPercent ?: prev?.yPercent,
                    clickThrough = bounds.clickThrough ?: prev?.clickThrough,
                )
        }
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
        pendingPinnedBounds = null
        if (pinnedWindow.isVisible) {
            pinnedWindow.hide()
            pluginManagerProvider().resetActiveToDrawer()
        } else {
            pinnedWindow.hide()
        }
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
                embedToolbar: EmbedToolbarBinding?,
            ) {
                pinnedWindow.hide()
                panelWindow.setPluginBackdrop(backdrop)
                panelWindow.setPluginSizeOverride(sizeOverride, presentationMode)
                panelWindow.setContentWebView(webView, embedToolbar)
            }

            override fun onDismissPanel() {
                if (pinnedWindow.isVisible) {
                    handleDismissPinned()
                }
                panelWindow.close()
            }

            override fun onSetImeFocused(focused: Boolean) = panelWindow.setImeFocused(focused)

            override fun onSetPinnedImeFocused(focused: Boolean) = pinnedWindow.setImeFocused(focused)

            override fun onSyncAppearance(
                themeId: String,
                colorScheme: String,
            ) = onSyncAppearance(themeId, colorScheme)

            override fun onPluginSizeChanged(sizeOverride: PluginSizeOverride) = panelWindow.setPluginSizeOverride(sizeOverride)

            override fun onPluginBackdropChanged(backdrop: PluginSurfaceBackdrop) {
                if (pinnedWindow.isVisible) {
                    pinnedWindow.updateBackdrop(backdrop)
                }
                panelWindow.setPluginBackdrop(backdrop)
            }

            override fun onPluginPresentationChanged(
                pluginId: String,
                webView: WebView,
                backdrop: PluginSurfaceBackdrop,
                mode: PluginPresentationMode,
                sizeOverride: PluginSizeOverride,
            ) = handlePresentationChanged(pluginId, webView, backdrop, mode, sizeOverride)

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
