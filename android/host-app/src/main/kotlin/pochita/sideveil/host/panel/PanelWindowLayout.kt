package pochita.sideveil.host.panel

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.FrameLayout
import pochita.sideveil.host.overlay.OverlayWindowFlags
import pochita.sideveil.host.overlay.WindowLayoutCommitTracker
import pochita.sideveil.host.plugin.EmbedQuickBarView
import pochita.sideveil.host.plugin.PluginSizeOverride
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PluginPresentationMode

/**
 * Geometry and [WindowManager.LayoutParams] coordinator for [SidebarPanelWindow].
 * Coordinates safe insets (IME, display cutout, tablet landscape), locked zero-reflow sizing,
 * interactive flags, WebView slot mounting, and warm `1x1` hiding.
 */
@SuppressLint("RtlHardcoded")
internal class PanelWindowLayout(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private val currentDensity: Float
        get() =
            context.resources.displayMetrics.density
                .coerceAtLeast(1f)

    var cachedWidthPx: Int = dp(340f)
        private set
    var cachedHeightPx: Int = dp(520f)
        private set
    var safeBounds: PanelSafeBounds = PanelInsetsCoordinator.resolveBounds(context, windowManager)
        private set
    var safeCenterOffsetY: Int = 0
        private set
    private val layoutCommitTracker = WindowLayoutCommitTracker()

    fun resetAppliedLayoutSnapshot() = layoutCommitTracker.reset()

    fun createContentContainer(themedContext: Context): FrameLayout =
        PanelDialogFactory.createContentContainer(themedContext, { cachedWidthPx }, { cachedHeightPx })

    fun createOverlayDialog(
        themedContext: Context,
        contentView: View,
        stateProvider: () -> PanelState,
        onBackPressedIntercept: () -> Boolean,
        onCloseRequested: () -> Unit,
        onOutsideTouchRequested: (Float, Float) -> Unit = { _, _ -> onCloseRequested() },
    ): Dialog =
        PanelDialogFactory.createOverlayDialog(
            themedContext = themedContext,
            contentView = contentView,
            stateProvider = stateProvider,
            onBackPressedIntercept = onBackPressedIntercept,
            onCloseRequested = onCloseRequested,
            onOutsideTouchRequested = onOutsideTouchRequested,
            onConfigureWindow = ::configureInitialWindow,
        )

    fun applyImeFocus(
        window: Window?,
        webView: WebView,
        panelConfig: PanelConfig,
        focused: Boolean,
    ) {
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (focused) {
            window?.let { updateFlags(it, panelConfig, focusable = true, allowOffscreen = false) }
            webView.requestFocus()
            @Suppress("DEPRECATION")
            imm?.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm?.hideSoftInputFromWindow(webView.windowToken, 0)
        }
    }

    fun configureInitialWindow(window: Window) {
        val lp = window.attributes
        lp.title = "SideVeilPanelHost"
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.x = 0
        lp.y = 0
        lp.width = 1
        lp.height = 1
        lp.flags = BASE_INVISIBLE_FLAGS
        lp.dimAmount = 0f
        OverlayWindowFlags.applyTouchSafeAlpha(lp)
        OverlayWindowFlags.clearPreferredRefreshRate(lp)
        OverlayWindowFlags.disableMoveAnimation(lp)
        applyAttributesIfChanged(window, lp)
    }

    fun mountWebView(
        container: FrameLayout,
        webView: WebView,
        initialAlpha: Float,
        quickBar: EmbedQuickBarView? = null,
    ) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        (quickBar?.parent as? ViewGroup)?.removeView(quickBar)
        container.removeAllViews()
        val topMarginPx = quickBar?.barHeightPx() ?: 0
        if (quickBar != null) {
            container.addView(
                quickBar,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, topMarginPx, Gravity.TOP),
            )
            quickBar.alpha = initialAlpha
        }
        val webViewLp =
            FrameLayout
                .LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                .apply { topMargin = topMarginPx }
        container.addView(webView, webViewLp)
        container.requestLayout()
        val targetWebHeight = (cachedHeightPx - topMarginPx).coerceAtLeast(1)
        if (webView.width <= 2 || webView.height <= 2) {
            val wSpec = View.MeasureSpec.makeMeasureSpec(cachedWidthPx, View.MeasureSpec.EXACTLY)
            val hSpec = View.MeasureSpec.makeMeasureSpec(targetWebHeight, View.MeasureSpec.EXACTLY)
            webView.measure(wSpec, hSpec)
            webView.layout(0, topMarginPx, cachedWidthPx, cachedHeightPx)
        }
        webView.alpha = initialAlpha
        OverlayWindowFlags.applyHighFrameRate(webView, context, container.display)
    }

    fun recomputeDimensions(
        edgeSide: EdgeSide,
        panelConfig: PanelConfig,
        railInsetDp: Int,
        sizeOverride: PluginSizeOverride,
        liveInsets: WindowInsets? = null,
        presentationMode: PluginPresentationMode = PluginPresentationMode.DRAWER,
        effectiveImeBottomPx: Int? = null,
    ) {
        val resolved = PanelInsetsCoordinator.resolveBounds(context, windowManager, liveInsets, effectiveImeBottomPx)
        safeBounds = resolved
        val (widthPx, heightPx) =
            PanelInsetsCoordinator.computePanelDimensions(
                safe = resolved,
                density = currentDensity,
                edgeSide = edgeSide,
                panelConfig = panelConfig,
                railInsetDp = railInsetDp,
                sizeOverride = sizeOverride,
                presentationMode = presentationMode,
            )
        cachedWidthPx = widthPx
        cachedHeightPx = heightPx
        safeCenterOffsetY = PanelInsetsCoordinator.computeSafeCenterOffsetY(resolved, currentDensity, heightPx)
    }

    fun buildMotionGeometry(
        edgeSide: EdgeSide,
        railInsetDp: Int,
        targetBlurRadiusPx: Int,
    ): PanelMotionGeometry =
        PanelMotionGeometry(
            hiddenX = hiddenOffsetX(),
            openX = openMarginPx(edgeSide, railInsetDp),
            panelWidthPx = cachedWidthPx,
            panelHeightPx = cachedHeightPx,
            screenHeightPx = safeBounds.screenHeightPx,
            targetBlurRadiusPx = targetBlurRadiusPx,
        )

    fun showInteractive(
        window: Window,
        edgeSide: EdgeSide,
        panelConfig: PanelConfig,
        frame: PanelMotionFrame,
        focusable: Boolean,
        allowOffscreen: Boolean,
    ) {
        val lp = window.attributes
        val horiz = if (edgeSide == EdgeSide.LEFT) Gravity.LEFT else Gravity.RIGHT
        lp.gravity = Gravity.CENTER_VERTICAL or horiz
        lp.width = cachedWidthPx
        lp.height = cachedHeightPx
        lp.x = frame.windowX
        lp.y = frame.windowY + safeCenterOffsetY
        lp.flags = computeInteractiveFlags(panelConfig, focusable, allowOffscreen)
        lp.dimAmount = panelConfig.scrimAlpha.coerceIn(0f, 0.75f)
        OverlayWindowFlags.applyTouchSafeAlpha(lp)
        OverlayWindowFlags.applyPreferredMaxRefreshRate(context, lp, window.decorView.display)
        OverlayWindowFlags.disableMoveAnimation(lp)
        applyAttributesIfChanged(window, lp)
        if (window.decorView.visibility != View.VISIBLE) {
            window.decorView.visibility = View.VISIBLE
        }
        OverlayWindowFlags.applyHighFrameRate(window.decorView, context, window.decorView.display)
    }

    fun updatePosition(
        window: Window,
        panelConfig: PanelConfig,
        frame: PanelMotionFrame,
        focusable: Boolean,
        allowOffscreen: Boolean,
    ) {
        val lp = window.attributes
        val nextFlags = computeInteractiveFlags(panelConfig, focusable, allowOffscreen)
        lp.x = frame.windowX
        lp.y = frame.windowY + safeCenterOffsetY
        lp.width = cachedWidthPx
        lp.height = cachedHeightPx
        lp.flags = nextFlags
        lp.alpha = OverlayWindowFlags.resolveWindowAlpha(nextFlags)
        lp.dimAmount = panelConfig.scrimAlpha.coerceIn(0f, 0.75f)
        applyAttributesIfChanged(window, lp)
    }

    fun updateFlags(
        window: Window,
        panelConfig: PanelConfig,
        focusable: Boolean,
        allowOffscreen: Boolean,
    ) {
        val lp = window.attributes
        val nextFlags = computeInteractiveFlags(panelConfig, focusable, allowOffscreen)
        lp.flags = nextFlags
        lp.alpha = OverlayWindowFlags.resolveWindowAlpha(nextFlags)
        applyAttributesIfChanged(window, lp)
    }

    fun collapseToWarmInvisible(window: Window) {
        OverlayWindowFlags.clearHighFrameRate(window.decorView)
        if (window.decorView.visibility != View.INVISIBLE) {
            window.decorView.visibility = View.INVISIBLE
        }
        val lp = window.attributes
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = 1
        lp.height = 1
        lp.x = 0
        lp.y = 0
        lp.dimAmount = 0f
        lp.flags = BASE_INVISIBLE_FLAGS
        OverlayWindowFlags.applyTouchSafeAlpha(lp)
        OverlayWindowFlags.clearPreferredRefreshRate(lp)
        OverlayWindowFlags.disableMoveAnimation(lp)
        applyAttributesIfChanged(window, lp)
    }

    private fun applyAttributesIfChanged(
        window: Window,
        lp: WindowManager.LayoutParams,
    ) {
        layoutCommitTracker.commitIfChanged(lp) {
            window.attributes = lp
        }
    }

    fun openMarginPx(
        edgeSide: EdgeSide,
        railInsetDp: Int,
    ): Int {
        val horizontal = PanelInsetsCoordinator.resolveHorizontalMetrics(safeBounds.screenWidthPx, currentDensity)
        val effectiveRailInsetDp = if (railInsetDp > 0) horizontal.railInsetDp else 0
        return dp((horizontal.edgeMarginDp + effectiveRailInsetDp).toFloat()) +
            PanelInsetsCoordinator.edgeCutoutInsetPx(safeBounds, edgeSide)
    }

    fun hiddenOffsetX(): Int = -(cachedWidthPx + dp(24f))

    fun cornerPx(panelConfig: PanelConfig): Float = dp(panelConfig.cornerRadiusDp.toFloat()).toFloat()

    private fun computeInteractiveFlags(
        panelConfig: PanelConfig,
        focusable: Boolean,
        allowOffscreen: Boolean,
    ): Int {
        var flags =
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (allowOffscreen) flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (panelConfig.scrimAlpha > 0.005f) flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        return flags
    }

    private fun dp(value: Float): Int = OverlayWindowFlags.dp(currentDensity, value)

    companion object {
        private const val BASE_INVISIBLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
