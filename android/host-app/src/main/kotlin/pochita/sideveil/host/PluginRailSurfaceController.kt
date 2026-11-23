package pochita.sideveil.host

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import pochita.sideveil.protocol.DebugAgslSnapshot
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.GlassTintOverride
import pochita.sideveil.protocol.PanelConfig
import kotlin.math.roundToInt

enum class RailQuickAction {
    PIN_TOP,
    DISABLE,
    RELOAD,
}

/**
 * Owns Window layout, progressive blur, and AGSL glass backdrop synchronization for
 * [PluginRailWindow], preserving [BlurMaskDrawable] on [Window] vs. [GlassBackdropDrawable]
 * on the rail content container.
 */
@SuppressLint("RtlHardcoded")
internal class PluginRailSurfaceController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val densityProvider: () -> Float,
) {
    val backdropDrawable = GlassBackdropDrawable(densityProvider())

    var edgeSide: EdgeSide = EdgeSide.RIGHT
        private set
    var isDark: Boolean = true
        private set
    var lastDesiredHeightPx: Int = dp(120f)
        private set
    var lastHorizontalMetrics: OverlayHorizontalMetrics =
        PanelInsetsCoordinator.resolveHorizontalMetrics(
            context.resources.displayMetrics.widthPixels,
            densityProvider(),
        )
        private set

    private var panelConfig = PanelConfig()
    private var syncedThemeId = "coloros-glass"
    private var dynamicTint: GlassTintOverride? = null
    private var systemBlurSupported = runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
    private var lastAppliedBlurRadius = -1

    fun configureWindow(window: Window) {
        window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.decorView.setPadding(0, 0, 0, 0)
        window.setBackgroundDrawable(BlurMaskDrawable(cornerPx()))
        window.attributes =
            window.attributes.apply {
                title = "SideVeilPluginRail"
                gravity = Gravity.TOP or Gravity.LEFT
                width = 1
                height = 1
                flags = INVISIBLE_FLAGS
                OverlayWindowFlags.applyTouchSafeAlpha(this)
                OverlayWindowFlags.disableMoveAnimation(this)
            }
    }

    fun updateStyle(
        side: EdgeSide,
        config: PanelConfig,
        themeId: String,
        dark: Boolean,
        blurSupported: Boolean,
        tint: GlassTintOverride?,
    ) {
        edgeSide = side
        panelConfig = config
        syncedThemeId = themeId
        isDark = dark
        systemBlurSupported = blurSupported
        if (tint != null) {
            dynamicTint = tint
        }
        backdropDrawable.updateDensity(densityProvider())
    }

    fun currentHorizontalMetrics(): OverlayHorizontalMetrics {
        val safe = PanelInsetsCoordinator.resolveBounds(context, windowManager)
        return PanelInsetsCoordinator.resolveHorizontalMetrics(safe.screenWidthPx, densityProvider()).also {
            lastHorizontalMetrics = it
        }
    }

    fun showAnimated(
        window: Window,
        railContainer: View,
        motionProgress: Float,
        pluginCount: Int,
    ) {
        val safe = PanelInsetsCoordinator.resolveBounds(context, windowManager)
        val metrics = PanelInsetsCoordinator.resolveHorizontalMetrics(safe.screenWidthPx, densityProvider())
        lastHorizontalMetrics = metrics
        val maxHeight = (safe.screenHeightPx * 0.65f).toInt().coerceAtLeast(dp(120f))
        val desiredHeight = (dp(12f) + pluginCount * dp(metrics.railSlotPitchDp.toFloat())).coerceAtMost(maxHeight)
        lastDesiredHeightPx = desiredHeight

        val hiddenX = -dp((metrics.railWidthDp + 16).toFloat())
        val openX = dp(metrics.edgeMarginDp.toFloat()) + PanelInsetsCoordinator.edgeCutoutInsetPx(safe, edgeSide)
        val currentX = (hiddenX + (openX - hiddenX) * motionProgress).roundToInt()

        val lp = window.attributes
        val horiz = if (edgeSide == EdgeSide.LEFT) Gravity.LEFT else Gravity.RIGHT
        lp.gravity = Gravity.CENTER_VERTICAL or horiz
        lp.width = dp(metrics.railWidthDp.toFloat())
        lp.height = desiredHeight
        lp.x = currentX
        lp.y = 0
        lp.flags = if (motionProgress >= 0.45f) VISIBLE_FLAGS else ANIMATING_FLAGS
        OverlayWindowFlags.applyTouchSafeAlpha(lp)
        OverlayWindowFlags.applyPreferredMaxRefreshRate(context, lp, window.decorView.display)
        OverlayWindowFlags.disableMoveAnimation(lp)
        window.attributes = lp
        window.decorView.visibility = View.VISIBLE
        OverlayWindowFlags.applyHighFrameRate(railContainer, context, window.decorView.display)
        railContainer.alpha = motionProgress
        applyBackdrop(window, progress = motionProgress)
    }

    fun collapseHidden(
        window: Window,
        railContainer: View,
    ) {
        applyBackdrop(window, progress = 0f)
        OverlayWindowFlags.clearHighFrameRate(railContainer)
        window.decorView.visibility = View.INVISIBLE
        railContainer.alpha = 0f
        val lp = window.attributes
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = 1
        lp.height = 1
        lp.x = 0
        lp.y = 0
        lp.flags = INVISIBLE_FLAGS
        OverlayWindowFlags.applyTouchSafeAlpha(lp)
        OverlayWindowFlags.clearPreferredRefreshRate(lp)
        window.attributes = lp
    }

    fun cornerPx(): Float = dp(if (lastHorizontalMetrics.compact) 16f else 20f).toFloat()

    fun appliedBlurRadiusPx(): Int = lastAppliedBlurRadius.coerceAtLeast(0)

    fun agslDebugState(): DebugAgslSnapshot = backdropDrawable.debugState()

    private fun applyBackdrop(
        window: Window,
        progress: Float,
    ) {
        val configBlur = if (panelConfig.nativeBlurEnabled && systemBlurSupported) panelConfig.blurRadiusPx else 0
        val radius = PanelMotionChoreographer.computeProgressiveBlur(progress, configBlur)
        backdropDrawable.updateStyle(
            GlassBackdropStyle(
                themeId = syncedThemeId,
                isDark = isDark,
                blurActive = radius > 0,
                cornerRadiusPx = cornerPx(),
                dynamicOverride = dynamicTint,
            ),
        )
        backdropDrawable.updateMotion(progress, 0f)
        if (lastAppliedBlurRadius != radius) {
            lastAppliedBlurRadius = radius
            window.setBackgroundBlurRadius(radius)
        }
    }

    private fun dp(value: Float): Int = OverlayWindowFlags.dp(densityProvider(), value)

    companion object {
        private const val INVISIBLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        private const val ANIMATING_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        private const val VISIBLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
