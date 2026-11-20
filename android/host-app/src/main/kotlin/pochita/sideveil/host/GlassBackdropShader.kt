package pochita.sideveil.host

import android.graphics.Rect
import android.graphics.RuntimeShader
import android.util.Log

/**
 * Compiles and updates uniforms for the AGSL optical frosted-glass shader used by
 * [GlassBackdropDrawable].
 */
internal object GlassBackdropShader {
    private const val TAG = "GlassBackdropShader"

    fun create(): RuntimeShader? =
        runCatching {
            RuntimeShader(GLASS_OPTICAL_AGSL)
        }.onFailure { err ->
            Log.w(TAG, "AGSL RuntimeShader compilation failed; falling back to GradientDrawable", err)
        }.getOrNull()

    fun applyStyleUniforms(
        shader: RuntimeShader,
        style: GlassBackdropStyle,
        palette: GlassOpticalPalette,
        density: Float,
    ) {
        shader.setFloatUniform("uCornerRadius", style.cornerRadiusPx.coerceAtLeast(0f))
        shader.setFloatUniform("uTintTop", palette.tintTopR, palette.tintTopG, palette.tintTopB, palette.tintTopA)
        shader.setFloatUniform(
            "uTintBottom",
            palette.tintBottomR,
            palette.tintBottomG,
            palette.tintBottomB,
            palette.tintBottomA,
        )
        shader.setFloatUniform(
            "uInnerGlow",
            palette.innerGlowR,
            palette.innerGlowG,
            palette.innerGlowB,
            palette.innerGlowAlpha,
        )
        shader.setFloatUniform("uInnerGlowRange", palette.innerGlowRangeDp * density)
        shader.setFloatUniform("uGrainStrength", palette.grainStrength)
        shader.setFloatUniform("uRimColor", palette.rimR, palette.rimG, palette.rimB)
        shader.setFloatUniform(
            "uRimWidths",
            palette.rimNearSolidDp * density,
            palette.rimNearFadeDp * density,
            palette.rimFarSolidDp * density,
            palette.rimFarFadeDp * density,
        )
        shader.setFloatUniform("uRimAlphas", palette.rimNearAlpha, palette.rimFarAlpha, palette.rimUniformAlpha)
    }

    fun applyBoundsUniforms(
        shader: RuntimeShader,
        style: GlassBackdropStyle,
        rect: Rect,
    ) {
        val w = rect.width().coerceAtLeast(1).toFloat()
        val h = rect.height().coerceAtLeast(1).toFloat()
        val fadeRatio = GlassOpticalPalette.computePerimeterFadeRatio(w, h, style.cornerRadiusPx)
        shader.setFloatUniform("uSize", w, h)
        shader.setFloatUniform("uRimFadeRatio", fadeRatio)
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
