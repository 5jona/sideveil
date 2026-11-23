package pochita.sideveil.host.overlay

import pochita.sideveil.protocol.GlassTintOverride
import pochita.sideveil.protocol.SideVeilConfig

/**
 * Immutable rendering configuration for [GlassBackdropDrawable].
 */
data class GlassBackdropStyle(
    val themeId: String = THEME_COLOROS_GLASS,
    val isDark: Boolean = true,
    val blurActive: Boolean = true,
    val cornerRadiusPx: Float = 56f,
    val dynamicOverride: GlassTintOverride? = null,
) {
    companion object {
        /** Canonical theme identifier for the translucent ColorOS-style glass preset. */
        val THEME_COLOROS_GLASS: String = SideVeilConfig.DEFAULT.panel.themeId

        /** Canonical theme identifier for the low-grain Modern SaaS dark/light preset. */
        const val THEME_MODERN_SAAS_DARK: String = "modern-saas-dark"
    }
}

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

        /** Resolves the optical palette for [style], applying optional [GlassTintOverride]. */
        fun from(style: GlassBackdropStyle): GlassOpticalPalette {
            val isColorOs = style.themeId == GlassBackdropStyle.THEME_COLOROS_GLASS
            val base = GlassBackdropPalettes.resolveBase(isColorOs, style.isDark, style.blurActive)
            val dyn = style.dynamicOverride ?: return base
            return applyDynamicTint(base, dyn, style.blurActive)
        }

        private fun applyDynamicTint(
            base: GlassOpticalPalette,
            dyn: GlassTintOverride,
            blurActive: Boolean,
        ): GlassOpticalPalette {
            val top = dyn.tintTopArgb.toInt()
            val bot = dyn.tintBottomArgb.toInt()
            val rim = dyn.rimArgb.toInt()
            val topA = if (blurActive) ((top ushr 24) and 0xFF) / 255f else 1.0f
            val botA = if (blurActive) ((bot ushr 24) and 0xFF) / 255f else 1.0f
            return base.copy(
                tintTopR = ((top ushr 16) and 0xFF) / 255f,
                tintTopG = ((top ushr 8) and 0xFF) / 255f,
                tintTopB = (top and 0xFF) / 255f,
                tintTopA = topA,
                tintBottomR = ((bot ushr 16) and 0xFF) / 255f,
                tintBottomG = ((bot ushr 8) and 0xFF) / 255f,
                tintBottomB = (bot and 0xFF) / 255f,
                tintBottomA = botA,
                rimR = ((rim ushr 16) and 0xFF) / 255f,
                rimG = ((rim ushr 8) and 0xFF) / 255f,
                rimB = (rim and 0xFF) / 255f,
                fallbackFillArgb =
                    if (blurActive) {
                        dyn.fallbackFillArgb.toInt()
                    } else {
                        dyn.fallbackFillArgb.toInt() or 0xFF000000.toInt()
                    },
                fallbackStrokeArgb = dyn.fallbackStrokeArgb.toInt(),
            )
        }
    }
}
