package pochita.sideveil.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassBackdropPaletteTest {
    @Test
    fun `all theme and mode palettes maintain valid color and alpha ranges`() {
        val themes = listOf("coloros-glass", "modern-saas-dark")
        for (theme in themes) {
            for (isDark in listOf(true, false)) {
                for (blurActive in listOf(true, false)) {
                    val p =
                        GlassOpticalPalette.from(
                            GlassBackdropStyle(
                                themeId = theme,
                                isDark = isDark,
                                blurActive = blurActive,
                                cornerRadiusPx = 58f,
                            ),
                        )
                    assertTrue(p.tintTopA in 0f..1f)
                    assertTrue(p.tintBottomA in 0f..1f)
                    assertTrue(p.innerGlowAlpha in 0f..1f)
                    assertTrue(p.grainStrength in 0f..0.05f)
                    assertTrue(p.rimNearAlpha in 0f..1f)
                    assertTrue(p.rimFarAlpha in 0f..1f)
                    assertTrue(p.rimUniformAlpha in 0f..1f)
                    if (!blurActive) {
                        assertEquals(1.0f, p.tintTopA, 0.001f)
                        assertEquals(1.0f, p.tintBottomA, 0.001f)
                    } else {
                        assertTrue(p.tintTopA < 0.75f)
                        assertTrue(p.tintBottomA < 0.75f)
                    }
                }
            }
        }
    }

    @Test
    fun `coloros glass uses low-turbidity translucent veil and stronger directional rim than saas`() {
        val colorOsDark =
            GlassOpticalPalette.from(
                GlassBackdropStyle(themeId = "coloros-glass", isDark = true, blurActive = true),
            )
        val saasDark =
            GlassOpticalPalette.from(
                GlassBackdropStyle(themeId = "modern-saas-dark", isDark = true, blurActive = true),
            )
        assertTrue(colorOsDark.tintTopA < saasDark.tintTopA)
        assertTrue(colorOsDark.rimNearAlpha > colorOsDark.rimFarAlpha)
        assertTrue(colorOsDark.rimNearAlpha > saasDark.rimNearAlpha)
        assertEquals(0.008f, colorOsDark.grainStrength, 0.0001f)
    }

    @Test
    fun `perimeter fade ratio clamps cleanly across aspect ratios`() {
        val portrait = GlassOpticalPalette.computePerimeterFadeRatio(892f, 1728f, 58f)
        val square = GlassOpticalPalette.computePerimeterFadeRatio(500f, 500f, 40f)
        val degenerate = GlassOpticalPalette.computePerimeterFadeRatio(0f, 0f, 0f)
        assertTrue(portrait in 0.15f..0.25f)
        assertEquals(0.29f, square, 0.01f)
        assertEquals(0.08f, degenerate, 0.001f)
    }
}
