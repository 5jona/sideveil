package pochita.sideveil.host

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelInsetsCoordinatorTest {
    @Test
    fun resolveScreenImeBottomPxConvertsWindowLocalInsetToScreenHeight() {
        val imeHeight =
            PanelInsetsCoordinator.resolveScreenImeBottomPx(
                imeVisible = true,
                windowLocalImeBottomPx = 564,
                windowBottomOnScreenPx = 2064,
                screenHeightPx = 2400,
                metricsImeBottomPx = 0,
                previousEffectiveImeBottomPx = 0,
            )
        assertEquals(900, imeHeight)
    }

    @Test
    fun resolveScreenImeBottomPxRetainsHeightAfterPanelMovesAboveIme() {
        val stabilized =
            PanelInsetsCoordinator.resolveScreenImeBottomPx(
                imeVisible = true,
                windowLocalImeBottomPx = 0,
                windowBottomOnScreenPx = 1468,
                screenHeightPx = 2400,
                metricsImeBottomPx = 0,
                previousEffectiveImeBottomPx = 900,
            )
        assertEquals(900, stabilized)
    }

    @Test
    fun resolveScreenImeBottomPxUpdatesWhenImeGrowsAndResetsOnDismiss() {
        val grown =
            PanelInsetsCoordinator.resolveScreenImeBottomPx(
                imeVisible = true,
                windowLocalImeBottomPx = 168,
                windowBottomOnScreenPx = 1468,
                screenHeightPx = 2400,
                metricsImeBottomPx = 0,
                previousEffectiveImeBottomPx = 900,
            )
        assertEquals(1100, grown)

        val dismissed =
            PanelInsetsCoordinator.resolveScreenImeBottomPx(
                imeVisible = false,
                windowLocalImeBottomPx = 0,
                windowBottomOnScreenPx = 1268,
                screenHeightPx = 2400,
                metricsImeBottomPx = 0,
                previousEffectiveImeBottomPx = 1100,
            )
        assertEquals(0, dismissed)
    }
}
