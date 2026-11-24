package pochita.sideveil.host.panel

import android.content.Context
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import android.widget.FrameLayout
import pochita.sideveil.host.plugin.EmbedQuickBarView
import pochita.sideveil.host.plugin.EmbedToolbarBinding
import pochita.sideveil.host.plugin.PluginPanelOverlayState
import pochita.sideveil.host.plugin.PluginSizeOverride
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PluginPresentationMode

/**
 * Coordinates mounting of the active plugin [WebView], optional [EmbedQuickBarView], and native
 * [PluginPanelStateView] inside the panel's content container, along with rail-inset and size state.
 */
internal class PanelContentMountController(
    themedContext: Context,
    private val contentContainer: FrameLayout,
    private val windowLayout: PanelWindowLayout,
    private val surfaceState: PanelSurfaceState,
) {
    var activeWebView: WebView? = null
        private set
    private var activeEmbedToolbar: EmbedToolbarBinding? = null
    private val embedQuickBar: EmbedQuickBarView by lazy { EmbedQuickBarView(themedContext) }
    private val stateOverlayView: PluginPanelStateView by lazy { PluginPanelStateView(themedContext) }
    private var showingStateOverlay: Boolean = false

    val hasEmbedToolbar: Boolean
        get() = activeEmbedToolbar != null

    fun setContentAlpha(alpha: Float) {
        if (activeEmbedToolbar != null) embedQuickBar.alpha = alpha
        if (showingStateOverlay) stateOverlayView.alpha = alpha
    }

    fun setOverlayState(
        state: PluginPanelOverlayState,
        initialAlpha: Float,
        onBeforeMount: () -> Unit,
        onAfterMount: () -> Unit,
    ) {
        when (state) {
            PluginPanelOverlayState.None -> {
                if (showingStateOverlay && activeWebView != null) {
                    (stateOverlayView.parent as? ViewGroup)?.removeView(stateOverlayView)
                    showingStateOverlay = false
                }
            }

            PluginPanelOverlayState.Empty -> {
                activeEmbedToolbar?.bindStatusListener?.invoke(null)
                activeEmbedToolbar = null
                activeWebView = null
                onBeforeMount()
                contentContainer.removeAllViews()
                stateOverlayView.bindEmptyState()
                attachStateOverlay(initialAlpha)
                onAfterMount()
            }

            is PluginPanelOverlayState.Error -> {
                stateOverlayView.bindErrorState(state.error)
                attachStateOverlay(initialAlpha)
            }
        }
    }

    private fun attachStateOverlay(initialAlpha: Float) {
        (stateOverlayView.parent as? ViewGroup)?.removeView(stateOverlayView)
        stateOverlayView.alpha = initialAlpha
        contentContainer.addView(
            stateOverlayView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        showingStateOverlay = true
    }

    fun requestFocusIfMounted() {
        activeWebView?.requestFocus()
    }

    fun applyImeFocus(
        window: Window?,
        focused: Boolean,
    ) {
        activeWebView?.let { windowLayout.applyImeFocus(window, it, surfaceState.panelConfig, focused) }
    }

    fun updateConfig(
        side: EdgeSide,
        config: PanelConfig,
    ) {
        surfaceState.edgeSide = side
        surfaceState.panelConfig = config
    }

    fun updateRailInsetDp(insetDp: Int): Boolean {
        val clamped = insetDp.coerceIn(0, 80)
        if (surfaceState.railInsetDp == clamped) return false
        surfaceState.railInsetDp = clamped
        return true
    }

    fun updatePluginSizeOverride(
        override: PluginSizeOverride,
        mode: PluginPresentationMode,
    ): Boolean {
        if (surfaceState.pluginSizeOverride == override && surfaceState.presentationMode == mode) return false
        surfaceState.pluginSizeOverride = override
        surfaceState.presentationMode = mode
        return true
    }

    fun mount(
        webView: WebView?,
        embedToolbar: EmbedToolbarBinding?,
        initialAlpha: Float,
        shouldRequestFocus: Boolean,
        onBeforeMount: () -> Unit,
        onAfterMount: () -> Unit,
    ) {
        activeEmbedToolbar?.bindStatusListener?.invoke(null)
        if (webView == null) {
            activeWebView = null
            activeEmbedToolbar = null
            showingStateOverlay = false
            contentContainer.removeAllViews()
            onAfterMount()
            return
        }
        onBeforeMount()
        if (showingStateOverlay) {
            (stateOverlayView.parent as? ViewGroup)?.removeView(stateOverlayView)
            showingStateOverlay = false
        }
        val quickBar =
            if (embedToolbar != null) {
                embedQuickBar.bind(
                    title = embedToolbar.title,
                    onReload = embedToolbar.onReload,
                    onHome = embedToolbar.onHome,
                    onOpenExternal = embedToolbar.onOpenExternal,
                )
                embedQuickBar.setStatusMessage(null, isError = false)
                embedToolbar.bindStatusListener { msg, isError ->
                    embedQuickBar.setStatusMessage(msg, isError)
                }
                embedQuickBar
            } else {
                null
            }
        val toolbarChanged = (activeEmbedToolbar != null) != (embedToolbar != null)
        activeEmbedToolbar = embedToolbar
        if (activeWebView === webView && webView.parent === contentContainer && !toolbarChanged) {
            onAfterMount()
            return
        }
        windowLayout.mountWebView(contentContainer, webView, initialAlpha, quickBar)
        activeWebView = webView
        onAfterMount()
        if (shouldRequestFocus) webView.requestFocus()
    }
}
