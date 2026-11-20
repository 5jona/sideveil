package pochita.sideveil.host

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.FrameLayout
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PluginSurfaceBackdrop

/**
 * Warm-resident TYPE_APPLICATION_OVERLAY Dialog container hosting the active plugin
 * WebView over a two-layer rendering architecture coordinated by [PanelBackdropController].
 */
@SuppressLint("RtlHardcoded")
class SidebarPanelWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onPanelStateChanged: (PanelState) -> Unit,
    private val onBackPressedIntercept: () -> Boolean = { false },
) {
    private val currentDensity: Float
        get() = context.resources.displayMetrics.density
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_Sideveil_OverlayDialog)
    private val springController = PanelSpringController()

    private var activeWebView: WebView? = null
    private var currentState: PanelState = PanelState.CLOSED
    private var closingInProgress = false
    private var attached = false
    private var edgeSide = EdgeSide.RIGHT
    private var railInsetDp = 0
    private var panelConfig = PanelConfig()
    private var pluginSizeOverride = PluginSizeOverride()

    private val contentContainer = FrameLayout(themedContext)
    private var cachedWidthPx: Int = dp(340f)
    private var cachedHeightPx: Int = dp(520f)

    private val dialog =
        object : Dialog(themedContext, R.style.Theme_Sideveil_OverlayDialog) {
            override fun cancel() {
                close()
            }

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK &&
                    event.action == KeyEvent.ACTION_UP &&
                    currentState == PanelState.OPEN
                ) {
                    if (!onBackPressedIntercept()) {
                        close()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_OUTSIDE && currentState == PanelState.OPEN) {
                    close()
                    return true
                }
                return super.onTouchEvent(event)
            }
        }

    private val backdropController =
        PanelBackdropController(
            context = context,
            windowManager = windowManager,
            contentContainer = contentContainer,
            cornerRadiusProvider = ::cornerPx,
            windowProvider = { dialog.window },
        )

    init {
        OverlayWindowFlags.preloadHardwareRenderer()

        dialog.setCanceledOnTouchOutside(true)
        dialog.setCancelable(true)
        dialog.setContentView(
            contentContainer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val window = dialog.window ?: error("Overlay dialog window unavailable")
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.decorView.setPadding(0, 0, 0, 0)
        backdropController.attachToWindow(window)

        val lp = window.attributes
        lp.title = "SideveilPanelHost"
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

    fun isSystemBlurSupported(): Boolean = backdropController.systemBlurSupported

    fun isDarkSurfaceActive(): Boolean = backdropController.colorScheme != "light"

    fun getCurrentState(): PanelState = currentState

    fun setContentWebView(webView: WebView) {
        if (activeWebView === webView && webView.parent === contentContainer) {
            return
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        contentContainer.removeAllViews()
        contentContainer.addView(
            webView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        activeWebView = webView
        if (currentState == PanelState.OPEN && attached) {
            webView.requestFocus()
        }
    }

    fun setRailInsetDp(insetDp: Int) {
        val clamped = insetDp.coerceIn(0, 80)
        if (railInsetDp == clamped) return
        railInsetDp = clamped
        recomputePanelDimensions()
        if (currentState == PanelState.OPEN && attached) {
            applyOpenLayout(settled = true)
        }
    }

    fun setPluginSizeOverride(override: PluginSizeOverride) {
        if (pluginSizeOverride == override) return
        pluginSizeOverride = override
        recomputePanelDimensions()
        if (currentState == PanelState.OPEN && attached) {
            applyOpenLayout(settled = true)
        }
    }

    fun setPluginBackdrop(backdrop: PluginSurfaceBackdrop) {
        backdropController.setPluginBackdrop(backdrop)
    }

    fun attachWarm() {
        if (attached) return
        runCatching {
            dialog.show()
            attached = true
            collapseToWarmInvisible()
            backdropController.registerBlurListener()
            backdropController.apply()
        }.onFailure { err ->
            Log.e(TAG, "Failed to attach warm panel dialog overlay", err)
        }
    }

    fun onSystemConfigurationChanged() {
        recomputePanelDimensions()
        backdropController.onSystemConfigurationChanged()
        if (currentState == PanelState.OPEN && attached) {
            applyOpenLayout(settled = true)
        }
    }

    fun updateConfig(
        side: EdgeSide,
        config: PanelConfig,
    ) {
        edgeSide = side
        panelConfig = config
        recomputePanelDimensions()
        backdropController.updateConfig(config)
        if (currentState == PanelState.OPEN && attached) {
            applyOpenLayout(settled = true)
        }
    }

    fun syncRuntimeAppearance(
        themeId: String,
        colorScheme: String,
    ) {
        backdropController.syncRuntimeAppearance(themeId, colorScheme)
    }

    fun onGestureStart() {
        if (!attached) attachWarm()
        springController.cancel()
        closingInProgress = false
        recomputePanelDimensions()
        val startX = hiddenOffsetX().toFloat()
        springController.snapTo(startX)
        showInteractiveWindow(xPx = startX.toInt(), focusable = false, allowOffscreen = true)
        setState(PanelState.DRAGGING)
    }

    fun onGestureMove(progress: Float) {
        if (currentState != PanelState.DRAGGING) {
            onGestureStart()
        }
        val nextX =
            springController.updateDrag(
                progress = progress,
                hiddenX = hiddenOffsetX().toFloat(),
                openX = openMarginPx().toFloat(),
            )
        updateWindowPosition(nextX.toInt(), focusable = false, allowOffscreen = true)
    }

    fun onGestureEnd(
        shouldOpen: Boolean,
        velocityPxPerSec: Float,
    ) {
        if (shouldOpen) {
            open(initialVelocityPx = velocityPxPerSec)
        } else {
            close(initialVelocityPx = velocityPxPerSec)
        }
    }

    fun open(initialVelocityPx: Float = 0f) {
        if (!attached) attachWarm()
        springController.cancel()
        closingInProgress = false
        recomputePanelDimensions()

        val wasClosed = currentState == PanelState.CLOSED
        val startX = if (wasClosed) hiddenOffsetX().toFloat() else springController.currentX
        val endX = openMarginPx().toFloat()
        springController.snapTo(startX)
        showInteractiveWindow(xPx = startX.toInt(), focusable = true, allowOffscreen = true)
        setState(PanelState.OPEN)

        springController.animateTo(
            startX = startX,
            endX = endX,
            startVelocityPx = initialVelocityPx,
            onUpdate = { value ->
                updateWindowPosition(value.toInt(), focusable = true, allowOffscreen = true)
            },
            onSettled = {
                if (currentState == PanelState.OPEN && !closingInProgress) {
                    applyOpenLayout(settled = true)
                }
            },
        )
        activeWebView?.requestFocus()
    }

    fun close(initialVelocityPx: Float = 0f) {
        if (!attached || currentState == PanelState.CLOSED || closingInProgress) return
        springController.cancel()
        closingInProgress = true
        setImeFocused(false)
        recomputePanelDimensions()

        val closeVelocityPx = if (initialVelocityPx <= 0f) initialVelocityPx else -initialVelocityPx
        springController.animateTo(
            startX = springController.currentX,
            endX = hiddenOffsetX().toFloat(),
            startVelocityPx = closeVelocityPx,
            onUpdate = { value ->
                updateWindowPosition(value.toInt(), focusable = false, allowOffscreen = true)
            },
            onSettled = {
                collapseToWarmInvisible()
            },
        )
    }

    fun toggle() {
        if (currentState == PanelState.OPEN && !closingInProgress) {
            close()
        } else {
            open()
        }
    }

    fun setImeFocused(focused: Boolean) {
        if (!attached || currentState == PanelState.CLOSED) return
        val webView = activeWebView ?: return
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (focused) {
            updateWindowFlags(focusable = true, allowOffscreen = false)
            webView.requestFocus()
            imm?.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm?.hideSoftInputFromWindow(webView.windowToken, 0)
        }
    }

    fun detach() {
        springController.cancel()
        backdropController.unregisterBlurListener()
        if (attached) {
            runCatching { dialog.dismiss() }
            attached = false
        }
    }

    private fun showInteractiveWindow(
        xPx: Int,
        focusable: Boolean,
        allowOffscreen: Boolean,
    ) {
        val window = dialog.window ?: return
        val lp = window.attributes
        val horiz = if (edgeSide == EdgeSide.LEFT) Gravity.LEFT else Gravity.RIGHT
        lp.gravity = Gravity.CENTER_VERTICAL or horiz
        lp.width = cachedWidthPx
        lp.height = cachedHeightPx
        lp.x = xPx
        lp.y = 0
        lp.flags = computeInteractiveFlags(focusable = focusable, allowOffscreen = allowOffscreen)
        lp.dimAmount = panelConfig.scrimAlpha.coerceIn(0f, 0.75f)
        OverlayWindowFlags.disableMoveAnimation(lp)
        window.attributes = lp
        window.decorView.visibility = View.VISIBLE
        backdropController.apply()
    }

    private fun updateWindowPosition(
        xPx: Int,
        focusable: Boolean,
        allowOffscreen: Boolean,
    ) {
        val window = dialog.window ?: return
        val lp = window.attributes
        val nextFlags = computeInteractiveFlags(focusable = focusable, allowOffscreen = allowOffscreen)
        if (lp.x != xPx || lp.flags != nextFlags || lp.width != cachedWidthPx || lp.height != cachedHeightPx) {
            lp.x = xPx
            lp.width = cachedWidthPx
            lp.height = cachedHeightPx
            lp.flags = nextFlags
            lp.dimAmount = panelConfig.scrimAlpha.coerceIn(0f, 0.75f)
            window.attributes = lp
        }
    }

    private fun applyOpenLayout(settled: Boolean) {
        updateWindowPosition(
            xPx = openMarginPx(),
            focusable = true,
            allowOffscreen = !settled,
        )
    }

    private fun updateWindowFlags(
        focusable: Boolean,
        allowOffscreen: Boolean,
    ) {
        val window = dialog.window ?: return
        val lp = window.attributes
        val nextFlags = computeInteractiveFlags(focusable = focusable, allowOffscreen = allowOffscreen)
        if (lp.flags != nextFlags) {
            lp.flags = nextFlags
            window.attributes = lp
        }
    }

    private fun collapseToWarmInvisible() {
        closingInProgress = false
        val window = dialog.window ?: return
        window.decorView.visibility = View.INVISIBLE
        backdropController.clearWindowBlur(window)
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
        setState(PanelState.CLOSED)
    }

    private fun computeInteractiveFlags(
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

    private fun recomputePanelDimensions() {
        val bounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()
        val screenW = bounds?.width() ?: context.resources.displayMetrics.widthPixels
        val screenH = bounds?.height() ?: context.resources.displayMetrics.heightPixels
        val reservedHorizontal = dp((24 + railInsetDp).toFloat())
        val maxW = (screenW - reservedHorizontal).coerceAtLeast(dp(240f))
        val targetWidthDp = (pluginSizeOverride.widthDp ?: panelConfig.widthDp).coerceIn(260, 480)
        cachedWidthPx = dp(targetWidthDp.toFloat()).coerceAtMost(maxW)

        val maxH = (screenH - dp(32f)).coerceAtLeast(dp(280f))
        val overrideHeightDp = pluginSizeOverride.heightDp
        val overrideHeightPercent = pluginSizeOverride.heightPercent
        val desiredHeightPx =
            when {
                overrideHeightDp != null -> {
                    dp(overrideHeightDp.coerceIn(240, 960).toFloat())
                }

                overrideHeightPercent != null -> {
                    val pct = overrideHeightPercent.coerceIn(40, 92)
                    (screenH * (pct / 100f)).toInt()
                }

                else -> {
                    (screenH * (panelConfig.heightPercent / 100f)).toInt()
                }
            }
        cachedHeightPx = desiredHeightPx.coerceIn(dp(280f), maxH)
    }

    private fun openMarginPx(): Int = dp((12 + railInsetDp).toFloat())

    private fun hiddenOffsetX(): Int = -(cachedWidthPx + dp(24f))

    private fun cornerPx(): Float = dp(panelConfig.cornerRadiusDp.toFloat()).toFloat()

    private fun setState(next: PanelState) {
        if (currentState != next) {
            currentState = next
            onPanelStateChanged(next)
        }
    }

    private fun dp(value: Float): Int = (value * currentDensity + 0.5f).toInt().coerceAtLeast(1)

    companion object {
        private const val TAG = "SideveilPanelWindow"

        private const val BASE_INVISIBLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
