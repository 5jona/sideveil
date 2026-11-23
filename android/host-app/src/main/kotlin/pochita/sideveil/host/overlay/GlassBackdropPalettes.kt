package pochita.sideveil.host.overlay

import kotlin.math.roundToInt

/**
 * Static optical color and specular rim presets for [GlassOpticalPalette] across
 * `coloros-glass` and `modern-saas-dark` themes in dark/light and blur/opaque modes.
 */
internal object GlassBackdropPalettes {
    fun resolveBase(
        isColorOs: Boolean,
        isDark: Boolean,
        blurActive: Boolean,
    ): GlassOpticalPalette =
        if (blurActive) {
            if (isColorOs) colorOsBlurPalette(isDark) else saasBlurPalette(isDark)
        } else {
            if (isColorOs) colorOsOpaquePalette(isDark) else saasOpaquePalette(isDark)
        }

    private fun colorOsBlurPalette(isDark: Boolean): GlassOpticalPalette = if (isDark) COLOROS_BLUR_DARK else COLOROS_BLUR_LIGHT

    private fun saasBlurPalette(isDark: Boolean): GlassOpticalPalette = if (isDark) SAAS_BLUR_DARK else SAAS_BLUR_LIGHT

    private fun colorOsOpaquePalette(isDark: Boolean): GlassOpticalPalette = if (isDark) COLOROS_OPAQUE_DARK else COLOROS_OPAQUE_LIGHT

    private fun saasOpaquePalette(isDark: Boolean): GlassOpticalPalette = if (isDark) SAAS_OPAQUE_DARK else SAAS_OPAQUE_LIGHT

    private fun argb(
        alpha: Float,
        r: Int,
        g: Int,
        b: Int,
    ): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
        return (a shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
    }

    private val COLOROS_BLUR_DARK =
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

    private val COLOROS_BLUR_LIGHT =
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

    private val SAAS_BLUR_DARK =
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

    private val SAAS_BLUR_LIGHT =
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

    private val COLOROS_OPAQUE_DARK =
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

    private val COLOROS_OPAQUE_LIGHT =
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

    private val SAAS_OPAQUE_DARK =
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

    private val SAAS_OPAQUE_LIGHT =
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
