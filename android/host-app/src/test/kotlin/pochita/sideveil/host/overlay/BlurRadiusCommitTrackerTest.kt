package pochita.sideveil.host.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.protocol.GlassTintOverride

class BlurRadiusCommitTrackerTest {
    @Test
    fun `deduplicates identical integer blur radii and clamps negatives`() {
        val tracker = BlurRadiusCommitTracker()
        val committed = mutableListOf<Int>()

        assertTrue(tracker.commitIfChanged(0) { committed.add(it) })
        assertFalse(tracker.commitIfChanged(-8) { committed.add(it) })
        assertFalse(tracker.commitIfChanged(0) { committed.add(it) })

        assertTrue(tracker.commitIfChanged(48) { committed.add(it) })
        assertFalse(tracker.commitIfChanged(48) { committed.add(it) })
        assertTrue(tracker.commitIfChanged(64) { committed.add(it) })

        assertEquals(listOf(0, 48, 64), committed)
        assertEquals(64, tracker.appliedRadiusPx)

        tracker.reset()
        assertEquals(0, tracker.appliedRadiusPx)
        assertTrue(tracker.commitIfChanged(64) { committed.add(it) })
        assertEquals(listOf(0, 48, 64, 64), committed)
    }

    @Test
    fun `deduplicates float blur radii after integer rounding`() {
        val tracker = BlurRadiusCommitTracker()
        val committed = mutableListOf<Int>()

        assertTrue(tracker.commitIfChanged(15.4f) { committed.add(it) })
        assertFalse(tracker.commitIfChanged(15.1f) { committed.add(it) })
        assertFalse(tracker.commitIfChanged(14.6f) { committed.add(it) })
        assertTrue(tracker.commitIfChanged(15.6f) { committed.add(it) })

        assertEquals(listOf(15, 16), committed)
    }

    @Test
    fun `glass backdrop style matches respects epsilon and field deltas`() {
        val base =
            GlassBackdropStyle(
                themeId = GlassBackdropStyle.THEME_COLOROS_GLASS,
                isDark = true,
                blurActive = true,
                cornerRadiusPx = 56f,
                dynamicOverride = null,
            )

        assertTrue(
            base.matches(
                themeId = GlassBackdropStyle.THEME_COLOROS_GLASS,
                isDark = true,
                blurActive = true,
                cornerRadiusPx = 56.0005f,
                dynamicOverride = null,
            ),
        )
        assertFalse(
            base.matches(
                themeId = GlassBackdropStyle.THEME_COLOROS_GLASS,
                isDark = true,
                blurActive = true,
                cornerRadiusPx = 56.5f,
                dynamicOverride = null,
            ),
        )
        assertFalse(
            base.matches(
                themeId = GlassBackdropStyle.THEME_COLOROS_GLASS,
                isDark = false,
                blurActive = true,
                cornerRadiusPx = 56f,
                dynamicOverride = null,
            ),
        )
        assertFalse(
            base.matches(
                themeId = GlassBackdropStyle.THEME_COLOROS_GLASS,
                isDark = true,
                blurActive = true,
                cornerRadiusPx = 56f,
                dynamicOverride = GlassTintOverride(1L, 2L, 3L, 4L, 5L, 6L),
            ),
        )
    }
}
