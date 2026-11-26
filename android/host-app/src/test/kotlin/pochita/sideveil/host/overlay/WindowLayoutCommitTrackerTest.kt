package pochita.sideveil.host.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutCommitTrackerTest {
    @Test
    fun `skips redundant commits for identical snapshots and commits on any field delta`() {
        val tracker = WindowLayoutCommitTracker()
        var commitCount = 0

        val base =
            OverlayWindowLayoutSnapshot(
                gravity = 53,
                width = 150,
                height = 600,
                x = 18,
                y = 0,
                flags = 0x1000128,
                alpha = 1.0f,
                dimAmount = 0.12f,
                preferredDisplayModeId = 2,
                preferredRefreshRate = 144f,
            )

        assertTrue(tracker.commitIfChanged(base) { commitCount++ })
        assertEquals(1, commitCount)

        // Identical animation-tail / redundant collapse snapshots must not re-dispatch updateViewLayout.
        assertFalse(tracker.commitIfChanged(base.copy()) { commitCount++ })
        assertEquals(1, commitCount)

        assertTrue(tracker.commitIfChanged(base.copy(x = 20)) { commitCount++ })
        assertEquals(2, commitCount)

        assertTrue(tracker.commitIfChanged(base.copy(x = 20, dimAmount = 0f)) { commitCount++ })
        assertEquals(3, commitCount)

        assertTrue(tracker.commitIfChanged(base.copy(x = 20, dimAmount = 0f, preferredRefreshRate = 0f)) { commitCount++ })
        assertEquals(4, commitCount)

        // Resetting on detach forces the next snapshot to commit cleanly on re-attach.
        tracker.reset()
        assertTrue(tracker.commitIfChanged(base.copy(x = 20, dimAmount = 0f, preferredRefreshRate = 0f)) { commitCount++ })
        assertEquals(5, commitCount)
    }
}
