package pochita.sideveil.host.overlay

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
import pochita.sideveil.protocol.DebugAgslSnapshot
import pochita.sideveil.protocol.GlassTintOverride
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
    private var lastBoundsWidthPx: Int = -1
    private var lastBoundsHeightPx: Int = -1
    private var lastBoundsCornerPx: Float = -1f

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

    /** Updates display density for dp-scaled rim and glow shader uniforms. */
    fun updateDensity(nextDensity: Float): Boolean {
        val clamped = nextDensity.coerceAtLeast(1f)
        if (abs(density - clamped) < UNIFORM_EPSILON) return false
        density = clamped
        syncVisuals()
        invalidateSelf()
        return true
    }

    /**
     * Zero-allocation style update checking field equality before constructing a [GlassBackdropStyle].
     */
    fun updateStyle(
        themeId: String,
        isDark: Boolean,
        blurActive: Boolean,
        cornerRadiusPx: Float,
        dynamicOverride: GlassTintOverride?,
    ): Boolean {
        if (style.matches(themeId, isDark, blurActive, cornerRadiusPx, dynamicOverride, UNIFORM_EPSILON)) {
            return false
        }
        return updateStyle(GlassBackdropStyle(themeId, isDark, blurActive, cornerRadiusPx, dynamicOverride))
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
     * Coalesces drawable alpha and AGSL specular motion uniform updates into a single
     * [invalidateSelf] call when either value exceeds its delta threshold.
     */
    fun applyMotion(
        nextAlpha: Int,
        nextMotionProgress: Float,
        nextVelocityShift: Float,
    ): Boolean {
        val clampedAlpha = nextAlpha.coerceIn(0, 255)
        val alphaChanged = drawableAlpha != clampedAlpha
        if (alphaChanged) {
            drawableAlpha = clampedAlpha
        }
        val clampedProgress = nextMotionProgress.coerceIn(0f, 1.15f)
        val clampedShift = nextVelocityShift.coerceIn(-1f, 1f)
        val motionChanged =
            abs(clampedProgress - motionProgress) >= MOTION_EPSILON ||
                abs(clampedShift - velocityShift) >= MOTION_EPSILON
        if (motionChanged) {
            motionProgress = clampedProgress
            velocityShift = clampedShift
            runtimeShader?.let { shader ->
                GlassBackdropShader.applyMotionUniforms(shader, motionProgress, velocityShift)
                rebindShader(shader)
            }
        }
        if (!alphaChanged && !motionChanged) {
            return false
        }
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
    ): Boolean = applyMotion(drawableAlpha, nextMotionProgress, nextVelocityShift)

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        fallbackDrawable.bounds = bounds
        val shader = runtimeShader ?: return
        val w = bounds.width()
        val h = bounds.height()
        val corner = style.cornerRadiusPx
        val sizeChanged = w != lastBoundsWidthPx || h != lastBoundsHeightPx
        val cornerChanged = abs(corner - lastBoundsCornerPx) >= UNIFORM_EPSILON
        if (sizeChanged || cornerChanged) {
            lastBoundsWidthPx = w
            lastBoundsHeightPx = h
            lastBoundsCornerPx = corner
            GlassBackdropShader.applyBoundsUniforms(shader, style, bounds)
            rebindShader(shader)
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
        if (shaderPaint.colorFilter == colorFilter) return
        shaderPaint.colorFilter = colorFilter
        fallbackDrawable.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** Captures a diagnostic snapshot of AGSL shader compilation and uniform state. */
    fun debugState(): DebugAgslSnapshot =
        DebugAgslSnapshot(
            shaderCompiled = runtimeShader != null,
            fallbackActive = runtimeShader == null,
            themeId = style.themeId,
            isDark = style.isDark,
            blurActive = style.blurActive,
            cornerRadiusPx = style.cornerRadiusPx,
            drawableAlpha = drawableAlpha,
            motionProgress = motionProgress,
            velocityShift = velocityShift,
            hasDynamicOverride = style.dynamicOverride != null,
            boundsWidthPx = bounds.width(),
            boundsHeightPx = bounds.height(),
        )

    private fun syncVisuals() {
        val shader = runtimeShader
        if (shader != null) {
            GlassBackdropShader.applyStyleUniforms(shader, style, palette, density)
            GlassBackdropShader.applyBoundsUniforms(shader, style, bounds)
            lastBoundsWidthPx = bounds.width()
            lastBoundsHeightPx = bounds.height()
            lastBoundsCornerPx = style.cornerRadiusPx
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
        private const val UNIFORM_EPSILON = 0.001f

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
