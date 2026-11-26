package pochita.sideveil.host.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EdgeIndicatorLayoutSnapshotTest {
    @Test
    fun `identical layout fields compare equal to skip redundant updateViewLayout`() {
        val a =
            EdgeWindowLayoutSnapshot(
                gravity = 53,
                width = 24,
                height = 504,
                x = 0,
                y = 400,
                flags = 0x318,
                alpha = 0.8f,
            )
        val b = a.copy()
        assertEquals(a, b)
        assertNotEquals(a, a.copy(y = 420))
        assertNotEquals(a, a.copy(width = 90))
        assertNotEquals(a, a.copy(gravity = 51))
    }
}
