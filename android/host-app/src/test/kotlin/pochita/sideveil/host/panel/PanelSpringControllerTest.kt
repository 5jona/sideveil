package pochita.sideveil.host.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelSpringControllerTest {
    @Test
    fun `drag position tracks linearly within 0 to 1`() {
        val hiddenX = -960f
        val openX = 32f
        assertEquals(hiddenX, PanelSpringController.computeDragPosition(0f, hiddenX, openX), 0.01f)
        assertEquals((hiddenX + openX) * 0.5f, PanelSpringController.computeDragPosition(0.5f, hiddenX, openX), 0.01f)
        assertEquals(openX, PanelSpringController.computeDragPosition(1.0f, hiddenX, openX), 0.01f)
    }

    @Test
    fun `negative progress clamps to hidden offset`() {
        val hiddenX = -900f
        val openX = 30f
        assertEquals(hiddenX, PanelSpringController.computeDragPosition(-0.5f, hiddenX, openX), 0.01f)
    }

    @Test
    fun `overscroll past 1 applies monotonic logarithmic resistance`() {
        val hiddenX = -900f
        val openX = 30f
        val atOne = PanelSpringController.computeDragPosition(1.0f, hiddenX, openX)
        val atOnePointFive = PanelSpringController.computeDragPosition(1.5f, hiddenX, openX)
        val atTwo = PanelSpringController.computeDragPosition(2.0f, hiddenX, openX)
        val linearStep = (openX - hiddenX) * 0.5f

        assertTrue(atOnePointFive > atOne)
        assertTrue(atTwo > atOnePointFive)
        assertTrue((atOnePointFive - atOne) < linearStep * 0.35f)
        assertTrue((atTwo - atOnePointFive) < (atOnePointFive - atOne))
    }

    @Test
    fun `choreographer anchors vertical travel from startYFraction and settles to center`() {
        val geo =
            PanelMotionGeometry(
                hiddenX = -1000,
                openX = 36,
                panelWidthPx = 960,
                panelHeightPx = 1400,
                screenHeightPx = 2400,
                targetBlurRadiusPx = 160,
            )

        val startTop = PanelMotionChoreographer.computeFrame(0f, 0f, 0.15f, geo)
        val midTop = PanelMotionChoreographer.computeFrame(0.5f, 1.8f, 0.15f, geo)
        val settled = PanelMotionChoreographer.computeFrame(1.0f, 0f, 0.15f, geo)

        assertTrue("origin near top should start above center", startTop.windowY < 0)
        assertTrue("midpoint should glide closer to center", midTop.windowY > startTop.windowY && midTop.windowY < 0)
        assertEquals(0, settled.windowY)
        assertEquals(0f, startTop.contentAlpha, 1e-4f)
        assertEquals(1f, settled.contentAlpha, 1e-4f)
        assertEquals(0, startTop.blurRadiusPx)
        assertTrue(midTop.blurRadiusPx in 40..120)
        assertEquals(160, settled.blurRadiusPx)
        assertTrue(midTop.shaderVelocityShift > 0f)
        assertEquals(0f, settled.shaderVelocityShift, 1e-4f)
    }
}
