package pochita.sideveil.host

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PluginPresentationMode

/**
 * Geometry and [WindowManager.LayoutParams] coordinator for [SidebarPanelWindow].
 * Coordinates safe insets (IME, display cutout, tablet landscape), locked zero-reflow sizing,
 * interactive flags, predictive-back registration, and warm `1x1` hiding.
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

    fun createOverlayDialog(
        themedContext: Context,
        contentView: View,
        stateProvider: () -> PanelState,
        onBackPressedIntercept: () -> Boolean,
        onCloseRequested: () -> Unit,
    ): Dialog {
        val dialog =
            object : Dialog(themedContext, R.style.Theme_SideVeil_OverlayDialog) {
                override fun cancel() = onCloseRequested()

                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    val isBackUp = event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
                    if (isBackUp && stateProvider() == PanelState.OPEN) {
                        if (!onBackPressedIntercept()) onCloseRequested()
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }

                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (event.action == MotionEvent.ACTION_OUTSIDE && stateProvider() == PanelState.OPEN) {
                        onCloseRequested()
                        return true
                    }
                    return super.onTouchEvent(event)
                }
            }
        dialog.setCanceledOnTouchOutside(true)
        dialog.setCancelable(true)
        dialog.setContentView(
            contentView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        val window = dialog.window ?: error("Overlay dialog window unavailable")
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.setFormat(PixelFormat.TRANSLUCENT)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.decorView.setPadding(0, 0, 0, 0)
        configureInitialWindow(window)
        return dialog
    }

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
        OverlayWindowFlags.disableMoveAnimation(lp)
        window.attributes = lp
    }

    fun recomputeDimensions(
        edgeSide: EdgeSide,
        panelConfig: PanelConfig,
        railInsetDp: Int,
        sizeOverride: PluginSizeOverride,
        liveInsets: WindowInsets? = null,
        presentationMode: PluginPresentationMode = PluginPresentationMode.DRAWER,
    ) {
        val resolved = PanelInsetsCoordinator.resolveBounds(context, windowManager, liveInsets)
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
        xPx: Int,
        yPx: Int,
        focusable: Boolean,
        allowOffscreen: Boolean,
    ) {
        val lp = window.attributes
        val horiz = if (edgeSide == EdgeSide.LEFT) Gravity.LEFT else Gravity.RIGHT
        lp.gravity = Gravity.CENTER_VERTICAL or horiz
        lp.width = cachedWidthPx
        lp.height = cachedHeightPx
        lp.x = xPx
        lp.y = yPx
        lp.flags = computeInteractiveFlags(panelConfig, focusable, allowOffscreen)
        lp.dimAmount = panelConfig.scrimAlpha.coerceIn(0f, 0.75f)
        OverlayWindowFlags.disableMoveAnimation(lp)
        window.attributes = lp
        window.decorView.visibility = View.VISIBLE
    }

    fun updatePosition(
        window: Window,
        panelConfig: PanelConfig,
        xPx: Int,
        yPx: Int,
        focusable: Boolean,
        allowOffscreen: Boolean,
    ) {
        val lp = window.attributes
        val nextFlags = computeInteractiveFlags(panelConfig, focusable, allowOffscreen)
        val geometryChanged =
            lp.x != xPx || lp.y != yPx || lp.width != cachedWidthPx || lp.height != cachedHeightPx
        if (geometryChanged || lp.flags != nextFlags) {
            lp.x = xPx
            lp.y = yPx
            lp.width = cachedWidthPx
            lp.height = cachedHeightPx
            lp.flags = nextFlags
            lp.dimAmount = panelConfig.scrimAlpha.coerceIn(0f, 0.75f)
            window.attributes = lp
        }
    }

    fun updateFlags(
        window: Window,
        panelConfig: PanelConfig,
        focusable: Boolean,
        allowOffscreen: Boolean,
    ) {
        val lp = window.attributes
        val nextFlags = computeInteractiveFlags(panelConfig, focusable, allowOffscreen)
        if (lp.flags != nextFlags) {
            lp.flags = nextFlags
            window.attributes = lp
        }
    }

    fun collapseToWarmInvisible(window: Window) {
        window.decorView.visibility = View.INVISIBLE
        val lp = window.attributes
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = 1
        lp.height = 1
        lp.x = 0
        lp.y = 0
        lp.dimAmount = 0f
        lp.flags = BASE_INVISIBLE_FLAGS
        OverlayWindowFlags.disableMoveAnimation(lp)
        window.attributes = lp
    }

    fun registerBackCallback(
        dialog: Dialog,
        callback: OnBackInvokedCallback,
    ): Boolean =
        runCatching {
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
            true
        }.getOrDefault(false)

    fun unregisterBackCallback(
        dialog: Dialog,
        callback: OnBackInvokedCallback,
    ) {
        runCatching {
            dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        }
    }

    fun openMarginPx(
        edgeSide: EdgeSide,
        railInsetDp: Int,
    ): Int = dp((12 + railInsetDp).toFloat()) + PanelInsetsCoordinator.edgeCutoutInsetPx(safeBounds, edgeSide)

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
        if (!focusable) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (allowOffscreen) {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        if (panelConfig.scrimAlpha > 0.005f) {
            flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        }
        return flags
    }

    private fun dp(value: Float): Int = (value * currentDensity + 0.5f).toInt().coerceAtLeast(1)

    companion object {
        private const val BASE_INVISIBLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
