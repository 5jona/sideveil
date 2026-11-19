package pochita.sideveil.host

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import pochita.sideveil.shared.EdgeSide
import pochita.sideveil.shared.PanelConfig
import pochita.sideveil.shared.PanelState
import java.util.function.Consumer

/**
 * Warm-resident TYPE_APPLICATION_OVERLAY Dialog container hosting the preloaded panel
 * WebView over a single persistent [GlassBackdropDrawable] and public Android 12+
 * [android.view.Window.setBackgroundBlurRadius] hardware cross-window blur.
 */
class SidebarPanelWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val webBridge: PanelWebViewBridge,
    private val onPanelStateChanged: (PanelState) -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_Sideveil_OverlayDialog)
    private val backdropDrawable = GlassBackdropDrawable(density)
    private val springController = PanelSpringController()

    private var currentState: PanelState = PanelState.CLOSED
    private var attached = false
    private var edgeSide = EdgeSide.RIGHT
    private var panelConfig = PanelConfig()
    private var syncedThemeId = "coloros-glass"
    private var syncedColorScheme = resolveSystemScheme()
    private var systemBlurSupported = queryCrossWindowBlurEnabled()
    private var lastAppliedBlurRadius = -1
    private var lastAppliedCornerPx = -1f

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
                    close()
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

    private val blurEnabledListener =
        Consumer<Boolean> { enabled ->
            if (systemBlurSupported != enabled) {
                systemBlurSupported = enabled
                applyPanelBackdrop()
            }
        }

    init {
        preloadHardwareRenderer()

        contentContainer.setBackgroundColor(Color.TRANSPARENT)
        contentContainer.clipToOutline = true
        contentContainer.outlineProvider = GlassBackdropDrawable.createContentOutlineProvider { cornerPx() }
        contentContainer.addView(
            webBridge.webView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        dialog.setCanceledOnTouchOutside(true)
        dialog.setCancelable(true)
        dialog.setContentView(contentContainer)

        val window = dialog.window ?: error("Overlay dialog window unavailable")
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.setBackgroundDrawable(backdropDrawable)

        val lp = window.attributes
        lp.title = "SideveilPanelHost"
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 0
        lp.width = 1
        lp.height = 1
        lp.flags = BASE_INVISIBLE_FLAGS
        lp.dimAmount = 0f
        applyNoMoveAnimationFlag(lp)
        window.attributes = lp
    }

    fun isSystemBlurSupported(): Boolean = systemBlurSupported

    fun getCurrentState(): PanelState = currentState

    fun attachWarm() {
        if (attached) return
        runCatching {
            dialog.show()
            attached = true
            collapseToWarmInvisible()
            registerBlurListener()
            applyPanelBackdrop()
        }.onFailure { err ->
            Log.e(TAG, "Failed to attach warm panel dialog overlay", err)
        }
    }

    fun updateConfig(
        side: EdgeSide,
        config: PanelConfig,
    ) {
        edgeSide = side
        panelConfig = config
        syncedThemeId = config.themeId
        syncedColorScheme =
            if (config.colorMode == "light" || config.colorMode == "dark") {
                config.colorMode
            } else {
                resolveSystemScheme()
            }
        recomputePanelDimensions()
        contentContainer.invalidateOutline()
        applyPanelBackdrop()
        if (currentState == PanelState.OPEN && attached) {
            applyOpenLayout(settled = true)
        }
    }

    fun syncRuntimeAppearance(
        themeId: String,
        colorScheme: String,
        @Suppress("UNUSED_PARAMETER") wantsNativeBlur: Boolean,
        @Suppress("UNUSED_PARAMETER") cornerRadiusPx: Int,
    ) {
        // Authoritative blur and corner radius come from SideveilConfig via config.updated.
        syncedThemeId = themeId
        syncedColorScheme = colorScheme
        contentContainer.invalidateOutline()
        applyPanelBackdrop()
    }

    fun onGestureStart() {
        if (!attached) attachWarm()
        springController.cancel()
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
        updateWindowPosition(nextX.toInt(), focusable = false, allowOffscreen = nextX < 0f)
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
                updateWindowPosition(value.toInt(), focusable = true, allowOffscreen = value < 0f)
            },
            onSettled = {
                if (currentState == PanelState.OPEN) {
                    applyOpenLayout(settled = true)
                }
            },
        )
        webBridge.webView.requestFocus()
    }

    fun close(initialVelocityPx: Float = 0f) {
        if (!attached || currentState == PanelState.CLOSED) return
        springController.cancel()
        setImeFocused(false)
        recomputePanelDimensions()
        setState(PanelState.DRAGGING)

        springController.animateTo(
            startX = springController.currentX,
            endX = hiddenOffsetX().toFloat(),
            startVelocityPx = -initialVelocityPx,
            onUpdate = { value ->
                updateWindowPosition(value.toInt(), focusable = false, allowOffscreen = true)
            },
            onSettled = {
                collapseToWarmInvisible()
            },
        )
    }

    fun toggle() {
        when (currentState) {
            PanelState.OPEN -> close()
            PanelState.CLOSED, PanelState.DRAGGING -> open()
        }
    }

    fun setImeFocused(focused: Boolean) {
        if (!attached || currentState == PanelState.CLOSED) return
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (focused) {
            updateWindowFlags(focusable = true, allowOffscreen = false)
            webBridge.webView.requestFocus()
            imm?.showSoftInput(webBridge.webView, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm?.hideSoftInputFromWindow(webBridge.webView.windowToken, 0)
        }
    }

    fun detach() {
        springController.cancel()
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { windowManager.removeCrossWindowBlurEnabledListener(blurEnabledListener) }
        }
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
        val horiz = if (edgeSide == EdgeSide.LEFT) Gravity.START else Gravity.END
        lp.gravity = Gravity.CENTER_VERTICAL or horiz
        lp.width = cachedWidthPx
        lp.height = cachedHeightPx
        lp.x = xPx
        lp.y = 0
        lp.flags = computeInteractiveFlags(focusable = focusable, allowOffscreen = allowOffscreen)
        lp.dimAmount = panelConfig.scrimAlpha.coerceIn(0f, 0.75f)
        applyNoMoveAnimationFlag(lp)
        window.attributes = lp
        window.decorView.visibility = View.VISIBLE
        applyPanelBackdrop()
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
        val window = dialog.window ?: return
        window.decorView.visibility = View.INVISIBLE
        applyBlurRadius(window, 0)
        val lp = window.attributes
        lp.gravity = Gravity.TOP or Gravity.START
        lp.width = 1
        lp.height = 1
        lp.x = 0
        lp.y = 0
        lp.dimAmount = 0f
        lp.flags = BASE_INVISIBLE_FLAGS
        applyNoMoveAnimationFlag(lp)
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
        val maxW = (screenW - dp(24f)).coerceAtLeast(dp(260f))
        cachedWidthPx = dp(panelConfig.widthDp.toFloat()).coerceAtMost(maxW)
        cachedHeightPx =
            (screenH * (panelConfig.heightPercent / 100f))
                .toInt()
                .coerceIn(dp(280f), (screenH - dp(32f)).coerceAtLeast(dp(280f)))
    }

    private fun openMarginPx(): Int = dp(12f)

    private fun hiddenOffsetX(): Int = -(cachedWidthPx + dp(24f))

    private fun cornerPx(): Float = dp(panelConfig.cornerRadiusDp.toFloat()).toFloat()

    private fun applyPanelBackdrop() {
        val window = dialog.window ?: return
        val isVisible = window.decorView.visibility == View.VISIBLE
        val effectiveBlurRadius =
            if (isVisible && panelConfig.nativeBlurEnabled && systemBlurSupported && panelConfig.blurRadiusPx > 0) {
                panelConfig.blurRadiusPx
            } else {
                0
            }

        val cornerRadiusPx = cornerPx()
        val changed =
            backdropDrawable.updateStyle(
                GlassBackdropStyle(
                    themeId = syncedThemeId,
                    isDark = syncedColorScheme != "light",
                    blurActive = effectiveBlurRadius > 0,
                    cornerRadiusPx = cornerRadiusPx,
                ),
            )
        if (lastAppliedCornerPx != cornerRadiusPx) {
            lastAppliedCornerPx = cornerRadiusPx
            // Re-bind the same persistent Drawable so DecorView refreshes BackgroundBlurDrawable corner radius.
            window.setBackgroundDrawable(backdropDrawable)
            window.decorView.invalidateOutline()
        } else if (changed) {
            window.decorView.invalidateOutline()
        }
        applyBlurRadius(window, effectiveBlurRadius)
    }

    private fun applyBlurRadius(
        window: android.view.Window,
        radiusPx: Int,
    ) {
        if (Build.VERSION.SDK_INT >= 31 && lastAppliedBlurRadius != radiusPx) {
            lastAppliedBlurRadius = radiusPx
            window.setBackgroundBlurRadius(radiusPx)
        }
    }

    private fun registerBlurListener() {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, blurEnabledListener)
            }
        }
    }

    private fun queryCrossWindowBlurEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
        } else {
            false
        }

    private fun preloadHardwareRenderer() {
        runCatching {
            Class.forName("android.graphics.HardwareRenderer").getMethod("preload").invoke(null)
        }
    }

    private fun resolveSystemScheme(): String {
        val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (nightMask == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
    }

    private fun setState(next: PanelState) {
        if (currentState != next) {
            currentState = next
            onPanelStateChanged(next)
        }
    }

    private fun dp(value: Float): Int = (value * density + 0.5f).toInt().coerceAtLeast(1)

    private fun applyNoMoveAnimationFlag(lp: WindowManager.LayoutParams) {
        runCatching {
            val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
            field.setInt(lp, field.getInt(lp) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        }
    }

    companion object {
        private const val TAG = "SideveilPanelWindow"
        private const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x00000040

        private const val BASE_INVISIBLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
