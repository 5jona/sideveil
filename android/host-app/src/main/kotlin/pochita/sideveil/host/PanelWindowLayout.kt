package pochita.sideveil.host

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelConfig

/**
 * Pure geometry and [WindowManager.LayoutParams] coordinator for [SidebarPanelWindow].
 * Encapsulates dimension clamping, edge/rail inset math, interactive flags, and warm `1x1` hiding.
 */
@SuppressLint("RtlHardcoded")
internal class PanelWindowLayout(
    private val context: Context,
    private val windowManager: WindowManager,
) {
    private val currentDensity: Float
        get() = context.resources.displayMetrics.density

    var cachedWidthPx: Int = dp(340f)
        private set
    var cachedHeightPx: Int = dp(520f)
        private set

    fun configureInitialWindow(window: Window) {
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

    fun recomputeDimensions(
        panelConfig: PanelConfig,
        railInsetDp: Int,
        sizeOverride: PluginSizeOverride,
    ) {
        val bounds = runCatching { windowManager.currentWindowMetrics.bounds }.getOrNull()
        val screenW = bounds?.width() ?: context.resources.displayMetrics.widthPixels
        val screenH = bounds?.height() ?: context.resources.displayMetrics.heightPixels

        val reservedHorizontal = dp((24 + railInsetDp).toFloat())
        val maxW = (screenW - reservedHorizontal).coerceAtLeast(dp(240f))
        val targetWidthDp = (sizeOverride.widthDp ?: panelConfig.widthDp).coerceIn(260, 480)
        cachedWidthPx = dp(targetWidthDp.toFloat()).coerceAtMost(maxW)

        val maxH = (screenH - dp(32f)).coerceAtLeast(dp(280f))
        val desiredHeightPx = resolveDesiredHeightPx(screenH, panelConfig, sizeOverride)
        cachedHeightPx = desiredHeightPx.coerceIn(dp(280f), maxH)
    }

    fun showInteractive(
        window: Window,
        edgeSide: EdgeSide,
        panelConfig: PanelConfig,
        xPx: Int,
        focusable: Boolean,
        allowOffscreen: Boolean,
    ) {
        val lp = window.attributes
        val horiz = if (edgeSide == EdgeSide.LEFT) Gravity.LEFT else Gravity.RIGHT
        lp.gravity = Gravity.CENTER_VERTICAL or horiz
        lp.width = cachedWidthPx
        lp.height = cachedHeightPx
        lp.x = xPx
        lp.y = 0
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
        focusable: Boolean,
        allowOffscreen: Boolean,
    ) {
        val lp = window.attributes
        val nextFlags = computeInteractiveFlags(panelConfig, focusable, allowOffscreen)
        val geometryChanged = lp.x != xPx || lp.width != cachedWidthPx || lp.height != cachedHeightPx
        if (geometryChanged || lp.flags != nextFlags) {
            lp.x = xPx
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
        dialog: android.app.Dialog,
        callback: android.window.OnBackInvokedCallback,
    ): Boolean =
        runCatching {
            dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
            true
        }.getOrDefault(false)

    fun unregisterBackCallback(
        dialog: android.app.Dialog,
        callback: android.window.OnBackInvokedCallback,
    ) {
        runCatching {
            dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        }
    }

    fun openMarginPx(railInsetDp: Int): Int = dp((12 + railInsetDp).toFloat())

    fun hiddenOffsetX(): Int = -(cachedWidthPx + dp(24f))

    fun cornerPx(panelConfig: PanelConfig): Float = dp(panelConfig.cornerRadiusDp.toFloat()).toFloat()

    private fun resolveDesiredHeightPx(
        screenH: Int,
        panelConfig: PanelConfig,
        sizeOverride: PluginSizeOverride,
    ): Int {
        val overrideHeightDp = sizeOverride.heightDp
        val overrideHeightPercent = sizeOverride.heightPercent
        return when {
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
    }

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
