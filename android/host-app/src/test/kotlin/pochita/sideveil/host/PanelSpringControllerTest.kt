package pochita.sideveil.host

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
}
