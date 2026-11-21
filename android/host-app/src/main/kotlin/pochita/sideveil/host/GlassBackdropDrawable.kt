package pochita.sideveil.host

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.abs

/**
 * Optical frosted-glass backdrop [Drawable] backed by a reusable AGSL
 * [RuntimeShader] composited over SurfaceFlinger cross-window blur.
 *
 * Gracefully falls back to [GradientDrawable] if shader compilation fails.
 */
class GlassBackdropDrawable(
    initialDensity: Float,
) : Drawable() {
    private var density: Float = initialDensity.coerceAtLeast(1f)
    private var style: GlassBackdropStyle = GlassBackdropStyle()
    private var palette: GlassOpticalPalette = GlassOpticalPalette.from(style)
    private var drawableAlpha: Int = 255
    private var motionProgress: Float = 1f
    private var velocityShift: Float = 0f

    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val runtimeShader: RuntimeShader? = GlassBackdropShader.create()
    private val fallbackDrawable = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE }

    init {
        if (runtimeShader != null) {
            shaderPaint.shader = runtimeShader
            GlassBackdropShader.applyStyleUniforms(runtimeShader, style, palette, density)
            GlassBackdropShader.applyMotionUniforms(runtimeShader, motionProgress, velocityShift)
        } else {
            syncFallbackDrawable()
        }
    }

    fun updateDensity(nextDensity: Float): Boolean {
        val clamped = nextDensity.coerceAtLeast(1f)
        if (density == clamped) return false
        density = clamped
        syncVisuals()
        invalidateSelf()
        return true
    }

    /**
     * Updates optical style parameters and invalidates only when values actually change,
     * keeping static idle frames free of unnecessary GPU redraws.
     */
    fun updateStyle(next: GlassBackdropStyle): Boolean {
        if (next == style) {
            return false
        }
        style = next
        palette = GlassOpticalPalette.from(next)
        syncVisuals()
        invalidateSelf()
        return true
    }

    /**
     * Updates AGSL specular motion flow uniforms during drag/spring transitions without
     * reallocating paints or redrawing idle frames.
     */
    fun updateMotion(
        nextMotionProgress: Float,
        nextVelocityShift: Float,
    ): Boolean {
        val clampedProgress = nextMotionProgress.coerceIn(0f, 1.15f)
        val clampedShift = nextVelocityShift.coerceIn(-1f, 1f)
        if (abs(clampedProgress - motionProgress) < MOTION_EPSILON &&
            abs(clampedShift - velocityShift) < MOTION_EPSILON
        ) {
            return false
        }
        motionProgress = clampedProgress
        velocityShift = clampedShift
        val shader = runtimeShader ?: return false
        GlassBackdropShader.applyMotionUniforms(shader, motionProgress, velocityShift)
        rebindShader(shader)
        invalidateSelf()
        return true
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        fallbackDrawable.bounds = bounds
        if (runtimeShader != null) {
            GlassBackdropShader.applyBoundsUniforms(runtimeShader, style, bounds)
            rebindShader(runtimeShader)
        }
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val shader = runtimeShader
        if (shader != null) {
            shaderPaint.alpha = drawableAlpha
            canvas.drawRect(bounds, shaderPaint)
        } else {
            fallbackDrawable.alpha = drawableAlpha
            fallbackDrawable.draw(canvas)
        }
    }

    override fun getOutline(outline: Outline) {
        // SurfaceFlinger BlurRegion reads the scalar radius strictly from Outline.setRoundRect().
        outline.setRoundRect(bounds, style.cornerRadiusPx.coerceAtLeast(0f))
        outline.alpha = if (style.blurActive) 0f else 1f
    }

    override fun setAlpha(alpha: Int) {
        val clamped = alpha.coerceIn(0, 255)
        if (drawableAlpha != clamped) {
            drawableAlpha = clamped
            invalidateSelf()
        }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        shaderPaint.colorFilter = colorFilter
        fallbackDrawable.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun syncVisuals() {
        val shader = runtimeShader
        if (shader != null) {
            GlassBackdropShader.applyStyleUniforms(shader, style, palette, density)
            GlassBackdropShader.applyBoundsUniforms(shader, style, bounds)
            GlassBackdropShader.applyMotionUniforms(shader, motionProgress, velocityShift)
            rebindShader(shader)
        } else {
            syncFallbackDrawable()
        }
    }

    private fun rebindShader(shader: RuntimeShader) {
        shaderPaint.shader = null
        shaderPaint.shader = shader
    }

    private fun syncFallbackDrawable() {
        fallbackDrawable.cornerRadius = style.cornerRadiusPx.coerceAtLeast(0f)
        fallbackDrawable.setColor(palette.fallbackFillArgb)
        fallbackDrawable.setStroke((density + 0.5f).toInt().coerceAtLeast(1), palette.fallbackStrokeArgb)
    }

    companion object {
        private const val MOTION_EPSILON = 0.005f

        /**
         * Creates a [ViewOutlineProvider] synchronized with the exact round-rect corner radius
         * rendered by [GlassBackdropDrawable].
         */
        fun createContentOutlineProvider(radiusProvider: () -> Float): ViewOutlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(
                    view: View,
                    outline: Outline,
                ) {
                    outline.setRoundRect(0, 0, view.width, view.height, radiusProvider().coerceAtLeast(0f))
                }
            }
    }
}
