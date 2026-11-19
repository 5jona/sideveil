package pochita.sideveil.host

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.RequiresApi
import kotlin.math.roundToInt

/**
 * Immutable rendering configuration for [GlassBackdropDrawable].
 */
data class GlassBackdropStyle(
    val themeId: String = "coloros-glass",
    val isDark: Boolean = true,
    val blurActive: Boolean = true,
    val cornerRadiusPx: Float = 56f,
)

/**
 * Straight-alpha optical color palette and specular rim parameters derived for a
 * given [GlassBackdropStyle]. Kept framework-free so palette invariants are JVM-testable.
 */
data class GlassOpticalPalette(
    val tintTopR: Float,
    val tintTopG: Float,
    val tintTopB: Float,
    val tintTopA: Float,
    val tintBottomR: Float,
    val tintBottomG: Float,
    val tintBottomB: Float,
    val tintBottomA: Float,
    val innerGlowR: Float,
    val innerGlowG: Float,
    val innerGlowB: Float,
    val innerGlowAlpha: Float,
    val innerGlowRangeDp: Float,
    val grainStrength: Float,
    val rimR: Float,
    val rimG: Float,
    val rimB: Float,
    val rimNearAlpha: Float,
    val rimFarAlpha: Float,
    val rimUniformAlpha: Float,
    val rimNearSolidDp: Float,
    val rimNearFadeDp: Float,
    val rimFarSolidDp: Float,
    val rimFarFadeDp: Float,
    val fallbackFillArgb: Int,
    val fallbackStrokeArgb: Int,
) {
    companion object {
        /**
         * ColorOS-derived perimeter fade ratio clamping specular catchlight span
         * relative to the container's half-perimeter `(width + height)`.
         */
        fun computePerimeterFadeRatio(
            widthPx: Float,
            heightPx: Float,
            cornerRadiusPx: Float,
        ): Float {
            val denom = maxOf(widthPx + heightPx, 1f)
            return ((widthPx * 0.5f + cornerRadiusPx) / denom).coerceIn(0.08f, 0.32f)
        }

        fun from(style: GlassBackdropStyle): GlassOpticalPalette {
            val isColorOs = style.themeId == "coloros-glass"
            return when {
                style.blurActive && isColorOs && style.isDark -> {
                    GlassOpticalPalette(
                        tintTopR = 44f / 255f,
                        tintTopG = 44f / 255f,
                        tintTopB = 50f / 255f,
                        tintTopA = 0.22f,
                        tintBottomR = 22f / 255f,
                        tintBottomG = 23f / 255f,
                        tintBottomB = 29f / 255f,
                        tintBottomA = 0.29f,
                        innerGlowR = 1f,
                        innerGlowG = 1f,
                        innerGlowB = 1f,
                        innerGlowAlpha = 0.075f,
                        innerGlowRangeDp = 22f,
                        grainStrength = 0.008f,
                        rimR = 1f,
                        rimG = 1f,
                        rimB = 1f,
                        rimNearAlpha = 0.52f,
                        rimFarAlpha = 0.28f,
                        rimUniformAlpha = 0.07f,
                        rimNearSolidDp = 0.75f,
                        rimNearFadeDp = 2.2f,
                        rimFarSolidDp = 0.75f,
                        rimFarFadeDp = 1.6f,
                        fallbackFillArgb = argb(0.26f, 32, 33, 39),
                        fallbackStrokeArgb = argb(0.32f, 255, 255, 255),
                    )
                }

                style.blurActive && isColorOs && !style.isDark -> {
                    GlassOpticalPalette(
                        tintTopR = 252f / 255f,
                        tintTopG = 253f / 255f,
                        tintTopB = 255f / 255f,
                        tintTopA = 0.30f,
                        tintBottomR = 242f / 255f,
                        tintBottomG = 246f / 255f,
                        tintBottomB = 252f / 255f,
                        tintBottomA = 0.24f,
                        innerGlowR = 1f,
                        innerGlowG = 1f,
                        innerGlowB = 1f,
                        innerGlowAlpha = 0.11f,
                        innerGlowRangeDp = 22f,
                        grainStrength = 0.008f,
                        rimR = 1f,
                        rimG = 1f,
                        rimB = 1f,
                        rimNearAlpha = 0.80f,
                        rimFarAlpha = 0.44f,
                        rimUniformAlpha = 0.16f,
                        rimNearSolidDp = 0.75f,
                        rimNearFadeDp = 2.2f,
                        rimFarSolidDp = 0.75f,
                        rimFarFadeDp = 1.6f,
                        fallbackFillArgb = argb(0.28f, 248, 250, 255),
                        fallbackStrokeArgb = argb(0.65f, 255, 255, 255),
                    )
                }

                style.blurActive && !isColorOs && style.isDark -> {
                    GlassOpticalPalette(
                        tintTopR = 18f / 255f,
                        tintTopG = 19f / 255f,
                        tintTopB = 24f / 255f,
                        tintTopA = 0.54f,
                        tintBottomR = 13f / 255f,
                        tintBottomG = 14f / 255f,
                        tintBottomB = 19f / 255f,
                        tintBottomA = 0.62f,
                        innerGlowR = 1f,
                        innerGlowG = 1f,
                        innerGlowB = 1f,
                        innerGlowAlpha = 0.03f,
                        innerGlowRangeDp = 14f,
                        grainStrength = 0.004f,
                        rimR = 0.88f,
                        rimG = 0.90f,
                        rimB = 0.96f,
                        rimNearAlpha = 0.24f,
                        rimFarAlpha = 0.14f,
                        rimUniformAlpha = 0.14f,
                        rimNearSolidDp = 0.6f,
                        rimNearFadeDp = 1.25f,
                        rimFarSolidDp = 0.6f,
                        rimFarFadeDp = 1.05f,
                        fallbackFillArgb = argb(0.58f, 16, 17, 22),
                        fallbackStrokeArgb = argb(0.22f, 224, 230, 245),
                    )
                }

                style.blurActive && !isColorOs && !style.isDark -> {
                    GlassOpticalPalette(
                        tintTopR = 251f / 255f,
                        tintTopG = 252f / 255f,
                        tintTopB = 254f / 255f,
                        tintTopA = 0.64f,
                        tintBottomR = 244f / 255f,
                        tintBottomG = 246f / 255f,
                        tintBottomB = 250f / 255f,
                        tintBottomA = 0.58f,
                        innerGlowR = 1f,
                        innerGlowG = 1f,
                        innerGlowB = 1f,
                        innerGlowAlpha = 0.04f,
                        innerGlowRangeDp = 14f,
                        grainStrength = 0.004f,
                        rimR = 0.12f,
                        rimG = 0.16f,
                        rimB = 0.24f,
                        rimNearAlpha = 0.16f,
                        rimFarAlpha = 0.10f,
                        rimUniformAlpha = 0.11f,
                        rimNearSolidDp = 0.6f,
                        rimNearFadeDp = 1.25f,
                        rimFarSolidDp = 0.6f,
                        rimFarFadeDp = 1.05f,
                        fallbackFillArgb = argb(0.62f, 248, 250, 252),
                        fallbackStrokeArgb = argb(0.16f, 30, 41, 59),
                    )
                }

                isColorOs && style.isDark -> {
                    GlassOpticalPalette(
                        tintTopR = 24f / 255f,
                        tintTopG = 28f / 255f,
                        tintTopB = 38f / 255f,
                        tintTopA = 1.0f,
                        tintBottomR = 17f / 255f,
                        tintBottomG = 20f / 255f,
                        tintBottomB = 29f / 255f,
                        tintBottomA = 1.0f,
                        innerGlowR = 1f,
                        innerGlowG = 1f,
                        innerGlowB = 1f,
                        innerGlowAlpha = 0.05f,
                        innerGlowRangeDp = 20f,
                        grainStrength = 0.008f,
                        rimR = 1f,
                        rimG = 1f,
                        rimB = 1f,
                        rimNearAlpha = 0.30f,
                        rimFarAlpha = 0.16f,
                        rimUniformAlpha = 0.08f,
                        rimNearSolidDp = 0.75f,
                        rimNearFadeDp = 2.0f,
                        rimFarSolidDp = 0.75f,
                        rimFarFadeDp = 1.5f,
                        fallbackFillArgb = argb(1.0f, 20, 24, 34),
                        fallbackStrokeArgb = argb(0.18f, 255, 255, 255),
                    )
                }

                isColorOs && !style.isDark -> {
                    GlassOpticalPalette(
                        tintTopR = 242f / 255f,
                        tintTopG = 245f / 255f,
                        tintTopB = 250f / 255f,
                        tintTopA = 1.0f,
                        tintBottomR = 232f / 255f,
                        tintBottomG = 238f / 255f,
                        tintBottomB = 246f / 255f,
                        tintBottomA = 1.0f,
                        innerGlowR = 1f,
                        innerGlowG = 1f,
                        innerGlowB = 1f,
                        innerGlowAlpha = 0.08f,
                        innerGlowRangeDp = 20f,
                        grainStrength = 0.008f,
                        rimR = 0.10f,
                        rimG = 0.14f,
                        rimB = 0.22f,
                        rimNearAlpha = 0.14f,
                        rimFarAlpha = 0.08f,
                        rimUniformAlpha = 0.08f,
                        rimNearSolidDp = 0.75f,
                        rimNearFadeDp = 1.8f,
                        rimFarSolidDp = 0.75f,
                        rimFarFadeDp = 1.4f,
                        fallbackFillArgb = argb(1.0f, 238, 242, 248),
                        fallbackStrokeArgb = argb(0.16f, 18, 24, 38),
                    )
                }

                style.isDark -> {
                    GlassOpticalPalette(
                        tintTopR = 19f / 255f,
                        tintTopG = 19f / 255f,
                        tintTopB = 23f / 255f,
                        tintTopA = 1.0f,
                        tintBottomR = 14f / 255f,
                        tintBottomG = 14f / 255f,
                        tintBottomB = 17f / 255f,
                        tintBottomA = 1.0f,
                        innerGlowR = 1f,
                        innerGlowG = 1f,
                        innerGlowB = 1f,
                        innerGlowAlpha = 0.02f,
                        innerGlowRangeDp = 12f,
                        grainStrength = 0.004f,
                        rimR = 0.85f,
                        rimG = 0.88f,
                        rimB = 0.94f,
                        rimNearAlpha = 0.16f,
                        rimFarAlpha = 0.10f,
                        rimUniformAlpha = 0.12f,
                        rimNearSolidDp = 0.6f,
                        rimNearFadeDp = 1.2f,
                        rimFarSolidDp = 0.6f,
                        rimFarFadeDp = 1.0f,
                        fallbackFillArgb = argb(1.0f, 17, 17, 20),
                        fallbackStrokeArgb = argb(0.14f, 255, 255, 255),
                    )
                }

                else -> {
                    GlassOpticalPalette(
                        tintTopR = 255f / 255f,
                        tintTopG = 255f / 255f,
                        tintTopB = 255f / 255f,
                        tintTopA = 1.0f,
                        tintBottomR = 248f / 255f,
                        tintBottomG = 250f / 255f,
                        tintBottomB = 252f / 255f,
                        tintBottomA = 1.0f,
                        innerGlowR = 1f,
                        innerGlowG = 1f,
                        innerGlowB = 1f,
                        innerGlowAlpha = 0.02f,
                        innerGlowRangeDp = 12f,
                        grainStrength = 0.004f,
                        rimR = 0.10f,
                        rimG = 0.13f,
                        rimB = 0.20f,
                        rimNearAlpha = 0.14f,
                        rimFarAlpha = 0.09f,
                        rimUniformAlpha = 0.10f,
                        rimNearSolidDp = 0.6f,
                        rimNearFadeDp = 1.2f,
                        rimFarSolidDp = 0.6f,
                        rimFarFadeDp = 1.0f,
                        fallbackFillArgb = argb(1.0f, 252, 253, 254),
                        fallbackStrokeArgb = argb(0.14f, 18, 24, 38),
                    )
                }
            }
        }

        private fun argb(
            alpha: Float,
            r: Int,
            g: Int,
            b: Int,
        ): Int {
            val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            return (a shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
        }
    }
}

/**
 * Optical frosted-glass backdrop [Drawable] backed by a reusable Android 13+ AGSL
 * [RuntimeShader] composited over SurfaceFlinger cross-window blur.
 *
 * Renders low-turbidity color-preserving translucent tints, directional top-left/bottom-right
 * specular rim catchlights, Fresnel inner edge glow, and 0.8% procedural soft-light micro-grain
 * strictly inside valid premultiplied RGBA space (`0 <= rgb <= a <= 1`).
 *
 * Gracefully falls back to [GradientDrawable] on API 31-32 or if shader compilation fails.
 */
class GlassBackdropDrawable(
    private val density: Float,
) : Drawable() {
    private var style: GlassBackdropStyle = GlassBackdropStyle()
    private var palette: GlassOpticalPalette = GlassOpticalPalette.from(style)
    private var drawableAlpha: Int = 255

    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val runtimeShader: RuntimeShader? = createRuntimeShader()
    private val fallbackDrawable = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE }

    init {
        if (runtimeShader != null && Build.VERSION.SDK_INT >= 33) {
            shaderPaint.shader = runtimeShader
            pushStyleUniforms(runtimeShader)
        } else {
            syncFallbackDrawable()
        }
    }

    /**
     * Updates optical style parameters and invalidates only when values actually change,
     * keeping static idle frames completely free of unnecessary GPU redraws.
     */
    fun updateStyle(next: GlassBackdropStyle): Boolean {
        if (next == style) {
            return false
        }
        style = next
        palette = GlassOpticalPalette.from(next)
        if (runtimeShader != null && Build.VERSION.SDK_INT >= 33) {
            pushStyleUniforms(runtimeShader)
            pushBoundsUniforms(runtimeShader, bounds)
        } else {
            syncFallbackDrawable()
        }
        invalidateSelf()
        return true
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        fallbackDrawable.bounds = bounds
        if (runtimeShader != null && Build.VERSION.SDK_INT >= 33) {
            pushBoundsUniforms(runtimeShader, bounds)
        }
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val shader = runtimeShader
        if (shader != null && Build.VERSION.SDK_INT >= 33) {
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

    @RequiresApi(33)
    private fun pushStyleUniforms(shader: RuntimeShader) {
        val p = palette
        shader.setFloatUniform("uCornerRadius", style.cornerRadiusPx.coerceAtLeast(0f))
        shader.setFloatUniform("uTintTop", p.tintTopR, p.tintTopG, p.tintTopB, p.tintTopA)
        shader.setFloatUniform("uTintBottom", p.tintBottomR, p.tintBottomG, p.tintBottomB, p.tintBottomA)
        shader.setFloatUniform("uInnerGlow", p.innerGlowR, p.innerGlowG, p.innerGlowB, p.innerGlowAlpha)
        shader.setFloatUniform("uInnerGlowRange", p.innerGlowRangeDp * density)
        shader.setFloatUniform("uGrainStrength", p.grainStrength)
        shader.setFloatUniform("uRimColor", p.rimR, p.rimG, p.rimB)
        shader.setFloatUniform(
            "uRimWidths",
            p.rimNearSolidDp * density,
            p.rimNearFadeDp * density,
            p.rimFarSolidDp * density,
            p.rimFarFadeDp * density,
        )
        shader.setFloatUniform("uRimAlphas", p.rimNearAlpha, p.rimFarAlpha, p.rimUniformAlpha)
    }

    @RequiresApi(33)
    private fun pushBoundsUniforms(
        shader: RuntimeShader,
        rect: Rect,
    ) {
        val w = rect.width().coerceAtLeast(1).toFloat()
        val h = rect.height().coerceAtLeast(1).toFloat()
        val fadeRatio = GlassOpticalPalette.computePerimeterFadeRatio(w, h, style.cornerRadiusPx)
        shader.setFloatUniform("uSize", w, h)
        shader.setFloatUniform("uRimFadeRatio", fadeRatio)
    }

    private fun syncFallbackDrawable() {
        fallbackDrawable.cornerRadius = style.cornerRadiusPx.coerceAtLeast(0f)
        fallbackDrawable.setColor(palette.fallbackFillArgb)
        fallbackDrawable.setStroke((density + 0.5f).toInt().coerceAtLeast(1), palette.fallbackStrokeArgb)
    }

    private fun createRuntimeShader(): RuntimeShader? {
        if (Build.VERSION.SDK_INT < 33) return null
        return runCatching {
            RuntimeShader(GLASS_OPTICAL_AGSL)
        }.onFailure { err ->
            Log.w(TAG, "AGSL RuntimeShader compilation failed; falling back to GradientDrawable", err)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "GlassBackdropDrawable"

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

        private val GLASS_OPTICAL_AGSL =
            """
            uniform float2 uSize;
            uniform float uCornerRadius;
            uniform float4 uTintTop;
            uniform float4 uTintBottom;
            uniform float4 uInnerGlow;
            uniform float uInnerGlowRange;
            uniform float uGrainStrength;
            uniform float3 uRimColor;
            uniform float4 uRimWidths;
            uniform float3 uRimAlphas;
            uniform float uRimFadeRatio;

            float rBox(float2 p, float2 halfSize, float r) {
                float2 q = abs(p) - halfSize + r;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
            }

            float randomNoise(float2 st) {
                return fract(sin(dot(st.yx, float2(79.1214, 78.233))) * 43760.5453);
            }

            float softLightChannel(float a, float b) {
                float low = b - (1.0 - 2.0 * a) * b * (1.0 - b);
                float high = b + (2.0 * a - 1.0) * (sqrt(max(b, 0.0)) - b);
                return mix(low, high, step(0.5, a));
            }

            float4 compositeOver(float4 dst, float3 srcRgb, float srcAlpha) {
                float sa = clamp(srcAlpha, 0.0, 1.0);
                float outA = sa + dst.a * (1.0 - sa);
                float3 outRgb = (srcRgb * sa + dst.rgb * dst.a * (1.0 - sa)) / max(outA, 1e-5);
                return float4(outRgb, outA);
            }

            half4 main(float2 coord) {
                float2 safeSize = max(uSize, float2(1.0));
                float2 halfSize = safeSize * 0.5;
                float2 pos = coord - halfSize;
                float r = min(uCornerRadius, min(halfSize.x, halfSize.y));

                float d = rBox(pos, halfSize, r);
                float shape = smoothstep(0.5, -1.0, d);
                if (shape < 1e-4) {
                    return half4(0.0);
                }

                // Vertical low-turbidity translucent glass veil.
                float v = clamp(coord.y / safeSize.y, 0.0, 1.0);
                float4 col = mix(uTintTop, uTintBottom, v);

                // Subtle RGB micro-dither to break 8-bit banding on large blur radii.
                float fineNoise = (randomNoise(coord) - 0.5) * uGrainStrength * 1.4;
                col.rgb = clamp(col.rgb + float3(fineNoise), 0.0, 1.0);

                // Fresnel inner edge glow modulated by ColorOS-style 0.8% soft-light frosted grain.
                if (uInnerGlow.a > 1e-4 && uInnerGlowRange > 0.5) {
                    float edgeBand = smoothstep(-uInnerGlowRange, 0.0, d);
                    float shaped = pow(max(edgeBand, 1e-6), 3.5) * 0.5 + edgeBand * 0.5;
                    float topBias = mix(1.0, 0.55, v);
                    float glowRaw = shaped * topBias;
                    float grain = clamp((randomNoise(coord * 0.013) - 0.5) * uGrainStrength + 0.5, 0.0, 1.0);
                    float glowAlpha = clamp(softLightChannel(grain, glowRaw), 0.0, 1.0) * uInnerGlow.a;
                    col = compositeOver(col, uInnerGlow.rgb, glowAlpha);
                }

                // Directional specular rim stroke: bright top-left light bevel, smooth side falloff,
                // secondary bottom-right catchlight, plus a subtle continuous bevel floor.
                float maxRimWidth = max(uRimWidths.y, uRimWidths.w);
                if (d > -maxRimWidth) {
                    float2 dir = pos / max(length(pos), 1e-4);
                    float2 absDir = abs(dir);
                    float edgeScale = min(
                        halfSize.x / max(absDir.x, 1e-4),
                        halfSize.y / max(absDir.y, 1e-4)
                    );
                    float2 pEdge = dir * edgeScale;

                    float halfPerimeter = (halfSize.x + halfSize.y) * 2.0;
                    float distTopLeft = (pEdge.x + halfSize.x) + (pEdge.y + halfSize.y);
                    float distBottomRight = max(halfPerimeter - distTopLeft, 0.0);
                    float fadeSpan = max(uRimFadeRatio * halfPerimeter, 1.0);

                    float nearWeight = smoothstep(fadeSpan, 0.0, distTopLeft);
                    float farWeight = smoothstep(fadeSpan, 0.0, distBottomRight);

                    float nearSdf = smoothstep(-uRimWidths.y, -uRimWidths.x, d);
                    float farSdf = smoothstep(-uRimWidths.w, -uRimWidths.z, d);
                    nearSdf = pow(max(nearSdf, 1e-6), 6.0) * 0.25 + 0.75 * nearSdf;
                    farSdf = pow(max(farSdf, 1e-6), 6.0) * 0.25 + 0.75 * farSdf;

                    float rimAlpha = clamp(
                        uRimAlphas.x * nearWeight * nearSdf +
                        uRimAlphas.y * farWeight * farSdf +
                        uRimAlphas.z * farSdf,
                        0.0,
                        1.0
                    );
                    col = compositeOver(col, uRimColor, rimAlpha);
                }

                // Strictly valid premultiplied output (0 <= rgb <= a <= 1).
                float finalAlpha = clamp(col.a, 0.0, 1.0) * shape;
                float3 finalRgb = clamp(col.rgb, 0.0, 1.0) * finalAlpha;
                return half4(finalRgb, finalAlpha);
            }
            """.trimIndent()
    }
}
