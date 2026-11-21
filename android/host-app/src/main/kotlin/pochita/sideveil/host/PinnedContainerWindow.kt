package pochita.sideveil.host

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.LinearLayout
import pochita.sideveil.protocol.GlassTintOverride
import pochita.sideveil.protocol.PanelConfig
import pochita.sideveil.protocol.PluginSurfaceBackdrop
import java.util.function.Consumer
import kotlin.math.roundToInt

data class PinnedBoundsSpec(
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val xPercent: Float? = null,
    val yPercent: Float? = null,
    val clickThrough: Boolean? = null,
)

/**
 * Warm-resident draggable `TYPE_APPLICATION_OVERLAY` Dialog hosting plugins in
 * `PluginPresentationMode.PINNED` mode. Uses `BlurMaskDrawable` + `GlassBackdropDrawable` for
 * real hardware cross-window blur, delegates single-layer bottom drag handle and unlock chip
 * chrome to [PinnedChromeViews], and synchronizes click-through state back to the plugin.
 */
@SuppressLint("RtlHardcoded")
internal class PinnedContainerWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onClickThroughChanged: (Boolean) -> Unit,
) {
    private val density: Float
        get() =
            context.resources.displayMetrics.density
                .coerceAtLeast(1f)
    private val themedContext = ContextThemeWrapper(context, R.style.Theme_SideVeil_OverlayDialog)
    private val backdropDrawable = GlassBackdropDrawable(density)
    private val dialog = Dialog(themedContext, R.style.Theme_SideVeil_OverlayDialog)
    private val webSlot = FrameLayout(themedContext)
    private val chrome =
        PinnedChromeViews(
            context = context,
            themedContext = themedContext,
            windowManager = windowManager,
            onDragEvent = ::handleDrag,
            onUnlockClickThrough = { applyClickThroughState(enabled = false, notify = true) },
        )
    private val rootContainer =
        LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            background = backdropDrawable
            clipToOutline = true
            outlineProvider = GlassBackdropDrawable.createContentOutlineProvider { cornerPx() }
        }

    private var attached = false
    private var visible = false
    private var clickThrough = false
    private var mountedWebView: WebView? = null
    private var panelConfig = PanelConfig()
    private var pluginBackdrop = PluginSurfaceBackdrop.NATIVE_GLASS
    private var themeId = "coloros-glass"
    private var isDark = true
    private var dynamicTint: GlassTintOverride? = null
    private var systemBlurSupported = runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
    private var lastAppliedBlur = -1
    private var lastAppliedCornerPx = -1f
    private var currentWidthPx = dp(DEFAULT_WIDTH_DP)
    private var currentHeightPx = dp(DEFAULT_HEIGHT_DP)
    private var currentX = dp(16f)
    private var currentY = dp(96f)
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartWinX = 0
    private var dragStartWinY = 0

    private val blurEnabledListener =
        Consumer<Boolean> { enabled ->
            if (systemBlurSupported != enabled) {
                systemBlurSupported = enabled
                if (visible) applyVisualStyle()
            }
        }

    val isVisible: Boolean
        get() = visible

    init {
        chrome.updateStyle(isDark, pluginBackdrop, dynamicTint)
        rootContainer.addView(webSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootContainer.addView(
            chrome.bottomHandleBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, chrome.handleBarHeightPx()),
        )
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.setContentView(
            rootContainer,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        val window = dialog.window ?: error("Pinned overlay dialog window unavailable")
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.decorView.setPadding(0, 0, 0, 0)
        lastAppliedCornerPx = cornerPx()
        window.setBackgroundDrawable(BlurMaskDrawable(lastAppliedCornerPx))
        collapseWindow(window)
    }

    fun attachWarm() {
        if (attached) return
        runCatching {
            dialog.show()
            attached = true
            runCatching { windowManager.addCrossWindowBlurEnabledListener(context.mainExecutor, blurEnabledListener) }
            dialog.window?.let(::collapseWindow)
        }.onFailure { err -> Log.w(TAG, "Failed to attach PinnedContainerWindow", err) }
    }

    fun show(
        webView: WebView,
        backdrop: PluginSurfaceBackdrop,
        bounds: PinnedBoundsSpec? = null,
    ) {
        if (!attached) attachWarm()
        if (!attached) return
        pluginBackdrop = backdrop
        visible = true
        (webView.parent as? ViewGroup)?.removeView(webView)
        webSlot.removeAllViews()
        webSlot.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        webView.alpha = 1f
        mountedWebView = webView

        bounds?.let(::applyBoundsInternal)
        clampWindowPosition()
        val desiredClickThrough = bounds?.clickThrough ?: false
        applyClickThroughState(enabled = desiredClickThrough, notify = desiredClickThrough)
        applyVisualStyle()
        syncWindowLayout()
        dialog.window?.let { win ->
            win.decorView.visibility = View.VISIBLE
            OverlayWindowFlags.applyHighFrameRate(rootContainer, context, win.decorView.display)
            OverlayWindowFlags.applyHighFrameRate(webView, context, win.decorView.display)
        }
    }

    fun updateBackdrop(backdrop: PluginSurfaceBackdrop) {
        pluginBackdrop = backdrop
        if (visible) applyVisualStyle()
    }

    fun updateBounds(bounds: PinnedBoundsSpec) {
        applyBoundsInternal(bounds)
        bounds.clickThrough?.let { applyClickThroughState(enabled = it, notify = false) }
        if (visible) syncWindowLayout()
    }

    fun updateStyle(
        config: PanelConfig,
        nextThemeId: String,
        dark: Boolean,
        tint: GlassTintOverride?,
    ) {
        panelConfig = config
        themeId = nextThemeId
        isDark = dark
        dynamicTint = tint
        backdropDrawable.updateDensity(density)
        chrome.updateStyle(isDark, pluginBackdrop, dynamicTint)
        if (visible) applyVisualStyle()
    }

    fun hide() {
        visible = false
        applyClickThroughState(enabled = false, notify = false)
        mountedWebView?.let(OverlayWindowFlags::clearHighFrameRate)
        mountedWebView = null
        webSlot.removeAllViews()
        dialog.window?.let(::collapseWindow)
    }

    fun detach() {
        hide()
        runCatching { windowManager.removeCrossWindowBlurEnabledListener(blurEnabledListener) }
        if (attached) {
            runCatching { dialog.dismiss() }
            attached = false
        }
    }

    private fun handleDrag(event: MotionEvent): Boolean {
        if (!visible || clickThrough) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartWinX = currentX
                dragStartWinY = currentY
                return true
            }

            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentX = dragStartWinX + (event.rawX - dragStartRawX).roundToInt()
                currentY = dragStartWinY + (event.rawY - dragStartRawY).roundToInt()
                clampWindowPosition()
                syncWindowLayout()
                return true
            }
        }
        return false
    }

    private fun applyClickThroughState(
        enabled: Boolean,
        notify: Boolean,
    ) {
        val changed = clickThrough != enabled
        clickThrough = enabled
        chrome.setHandleVisible(!enabled)
        if (visible) syncWindowLayout() else chrome.detachUnlockChip()
        if (notify && changed) onClickThroughChanged(enabled)
    }

    private fun applyBoundsInternal(bounds: PinnedBoundsSpec) {
        val safe = PanelInsetsCoordinator.resolveBounds(context, windowManager)
        bounds.widthDp?.let { currentWidthPx = dp(it.coerceIn(180, 420).toFloat()) }
        bounds.heightDp?.let { currentHeightPx = dp(it.coerceIn(140, 560).toFloat()) }
        bounds.xPercent?.let {
            val pct = it.coerceIn(0f, 100f) / 100f
            currentX = ((safe.screenWidthPx - currentWidthPx).coerceAtLeast(0) * pct).roundToInt()
        }
        bounds.yPercent?.let {
            val pct = it.coerceIn(0f, 100f) / 100f
            val minY = safe.safeTopPx
            val maxY = (safe.screenHeightPx - safe.safeBottomPx - currentHeightPx).coerceAtLeast(minY)
            currentY = (minY + (maxY - minY) * pct).roundToInt()
        }
        clampWindowPosition()
    }

    private fun clampWindowPosition() {
        val safe = PanelInsetsCoordinator.resolveBounds(context, windowManager)
        val maxX = (safe.screenWidthPx - currentWidthPx).coerceAtLeast(0)
        val minY = safe.safeTopPx
        val maxY = (safe.screenHeightPx - safe.safeBottomPx - currentHeightPx).coerceAtLeast(minY)
        currentX = currentX.coerceIn(0, maxX)
        currentY = currentY.coerceIn(minY, maxY)
    }

    private fun applyVisualStyle() {
        val window = dialog.window ?: return
        chrome.updateStyle(isDark, pluginBackdrop, dynamicTint)
        if (pluginBackdrop == PluginSurfaceBackdrop.TRANSPARENT) {
            rootContainer.background = null
            rootContainer.clipToOutline = false
            applyBlur(window, 0)
            return
        }
        if (rootContainer.background !== backdropDrawable) rootContainer.background = backdropDrawable
        rootContainer.clipToOutline = true
        val corner = cornerPx()
        if (lastAppliedCornerPx != corner) {
            lastAppliedCornerPx = corner
            window.setBackgroundDrawable(BlurMaskDrawable(corner))
            rootContainer.invalidateOutline()
        }
        val blurAllowed =
            pluginBackdrop == PluginSurfaceBackdrop.NATIVE_GLASS &&
                systemBlurSupported &&
                panelConfig.nativeBlurEnabled
        val radius = if (blurAllowed) panelConfig.blurRadiusPx.coerceIn(0, 180) else 0
        backdropDrawable.updateStyle(
            GlassBackdropStyle(themeId, isDark, blurActive = radius > 0, cornerRadiusPx = corner, dynamicOverride = dynamicTint),
        )
        applyBlur(window, radius)
    }

    private fun syncWindowLayout() {
        val window = dialog.window ?: return
        if (!attached || !visible) return
        val lp = window.attributes
        lp.title = "SideVeilPinnedPlugin"
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = currentWidthPx
        lp.height = currentHeightPx
        lp.x = currentX
        lp.y = currentY
        lp.flags = if (clickThrough) CLICK_THROUGH_FLAGS else INTERACTIVE_FLAGS
        OverlayWindowFlags.applyPreferredMaxRefreshRate(context, lp, window.decorView.display)
        OverlayWindowFlags.disableMoveAnimation(lp)
        window.attributes = lp
        chrome.syncUnlockChip(clickThrough, currentX, currentY, currentWidthPx, currentHeightPx)
    }

    private fun collapseWindow(window: Window) {
        applyBlur(window, 0)
        OverlayWindowFlags.clearHighFrameRate(rootContainer)
        window.decorView.visibility = View.INVISIBLE
        val lp = window.attributes
        lp.title = "SideVeilPinnedPlugin"
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = 1
        lp.height = 1
        lp.x = 0
        lp.y = 0
        lp.flags = INVISIBLE_FLAGS
        OverlayWindowFlags.clearPreferredRefreshRate(lp)
        OverlayWindowFlags.disableMoveAnimation(lp)
        window.attributes = lp
    }

    private fun applyBlur(
        window: Window,
        radius: Int,
    ) {
        if (lastAppliedBlur != radius) {
            lastAppliedBlur = radius
            window.setBackgroundBlurRadius(radius)
        }
    }

    private fun cornerPx(): Float = dp(panelConfig.cornerRadiusDp.coerceIn(12, 24).toFloat()).toFloat()

    private fun dp(value: Float): Int = OverlayWindowFlags.dp(density, value)

    companion object {
        private const val TAG = "SideVeilPinnedWindow"
        private const val DEFAULT_WIDTH_DP = 316f
        private const val DEFAULT_HEIGHT_DP = 348f

        private const val INVISIBLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        private const val INTERACTIVE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        private const val CLICK_THROUGH_FLAGS =
            INTERACTIVE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }
}
