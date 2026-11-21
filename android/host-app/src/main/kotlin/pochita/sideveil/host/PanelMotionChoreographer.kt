package pochita.sideveil.host

import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Immutable window and optical constraints for one panel motion pass.
 * Dimensions stay strictly locked throughout open/close springs to guarantee zero WebView CSS reflow.
 */
data class PanelMotionGeometry(
    val hiddenX: Int,
    val openX: Int,
    val panelWidthPx: Int,
    val panelHeightPx: Int,
    val screenHeightPx: Int,
    val targetBlurRadiusPx: Int,
)

/**
 * Pure per-frame output computed from normalized gesture/spring progress.
 */
data class PanelMotionFrame(
    val progress: Float,
    val windowX: Int,
    val windowY: Int,
    val contentAlpha: Float,
    val backdropAlpha: Float,
    val blurRadiusPx: Int,
    val shaderMotionProgress: Float,
    val shaderVelocityShift: Float,
    val railProgress: Float,
)

/**
 * Pure JVM zero-reflow motion choreographer computing origin-anchored translation,
 * logarithmic rubber-band overscroll, staged content reveal, quantized cross-window blur,
 * AGSL specular flow shifts, and synchronized Plugin Rail progress.
 */
object PanelMotionChoreographer {
    private const val OVERSCROLL_DAMPING = 0.16f
    private const val CONTENT_REVEAL_START = 0.18f
    private const val CONTENT_REVEAL_END = 0.72f
    private const val BACKDROP_FADE_END = 0.35f
    private const val BLUR_RAMP_START = 0.06f
    private const val BLUR_RAMP_END = 0.96f
    private const val BLUR_QUANT_STEP_PX = 8f
    private const val VELOCITY_NORM_SCALE = 4.5f
    private const val RAIL_STAGGER_START = 0.08f

    fun computeFrame(
        rawProgress: Float,
        velocityPerSec: Float,
        startYFraction: Float,
        geometry: PanelMotionGeometry,
    ): PanelMotionFrame {
        val clampedNonNeg = rawProgress.coerceAtLeast(0f)
        val visualProgress = applyOverscrollCurve(clampedNonNeg)
        val windowX =
            (geometry.hiddenX + (geometry.openX - geometry.hiddenX) * visualProgress).roundToInt()

        val clampedUnit = clampedNonNeg.coerceIn(0f, 1f)
        val centeredTravelPx = ((geometry.screenHeightPx - geometry.panelHeightPx) * 0.5f).coerceAtLeast(0f)
        val maxBiasPx = min(centeredTravelPx * 0.72f, geometry.panelHeightPx * 0.28f)
        val originBias = ((startYFraction.coerceIn(0f, 1f) - 0.5f) * 2f) * maxBiasPx
        val settleRemaining = (1f - clampedUnit).toDouble().pow(1.6).toFloat()
        val windowY = (originBias * settleRemaining).roundToInt()

        val contentAlpha =
            ((clampedUnit - CONTENT_REVEAL_START) / (CONTENT_REVEAL_END - CONTENT_REVEAL_START))
                .coerceIn(0f, 1f)
        val backdropAlpha =
            (0.20f + (clampedUnit / BACKDROP_FADE_END).coerceIn(0f, 1f) * 0.80f).coerceIn(0f, 1f)

        val blurRadiusPx = computeProgressiveBlur(clampedUnit, geometry.targetBlurRadiusPx)
        val shaderVelocityShift = (velocityPerSec / VELOCITY_NORM_SCALE).coerceIn(-1f, 1f)
        val railProgress =
            ((clampedUnit - RAIL_STAGGER_START) / (1f - RAIL_STAGGER_START)).coerceIn(0f, 1f)

        return PanelMotionFrame(
            progress = clampedNonNeg,
            windowX = windowX,
            windowY = windowY,
            contentAlpha = contentAlpha,
            backdropAlpha = backdropAlpha,
            blurRadiusPx = blurRadiusPx,
            shaderMotionProgress = clampedNonNeg.coerceIn(0f, 1.15f),
            shaderVelocityShift = shaderVelocityShift,
            railProgress = railProgress,
        )
    }

    /**
     * Maps normalized progress `[0, +inf)` linearly within `[0, 1]` and applies smooth
     * logarithmic rubber-band resistance beyond `1.0`.
     */
    fun applyOverscrollCurve(progress: Float): Float =
        if (progress <= 1.0f) {
            progress.coerceAtLeast(0f)
        } else {
            1.0f + ln(1.0 + (progress - 1.0)).toFloat() * OVERSCROLL_DAMPING
        }

    fun computeProgressiveBlur(
        unitProgress: Float,
        targetBlurRadiusPx: Int,
    ): Int {
        if (targetBlurRadiusPx <= 0 || unitProgress <= BLUR_RAMP_START) {
            return 0
        }
        if (unitProgress >= BLUR_RAMP_END) {
            return targetBlurRadiusPx
        }
        val t = ((unitProgress - BLUR_RAMP_START) / (BLUR_RAMP_END - BLUR_RAMP_START)).coerceIn(0f, 1f)
        val raw = t * targetBlurRadiusPx
        return ((raw / BLUR_QUANT_STEP_PX).roundToInt() * BLUR_QUANT_STEP_PX.toInt())
            .coerceIn(0, targetBlurRadiusPx)
    }
}
