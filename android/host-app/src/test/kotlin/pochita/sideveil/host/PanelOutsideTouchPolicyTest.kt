package pochita.sideveil.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.protocol.EdgeSide

class PanelOutsideTouchPolicyTest {
    @Test
    fun `ignores outside touch while ime is visible`() {
        var now = 1_000L
        val policy = PanelOutsideTouchPolicy(postImeHideGraceMs = 250L, nowMs = { now })

        assertFalse(policy.shouldIgnoreOutsideTouch(panelOpen = true, closingInProgress = false, isRailTouch = false))

        policy.onImeInsetsChanged(840)
        assertTrue(policy.isImeVisible)
        assertTrue(policy.shouldIgnoreOutsideTouch(panelOpen = true, closingInProgress = false, isRailTouch = false))

        now += 5_000L
        assertTrue(policy.shouldIgnoreOutsideTouch(panelOpen = true, closingInProgress = false, isRailTouch = false))
    }

    @Test
    fun `ignores outside touch during 250ms post-ime-hide grace window and allows afterward`() {
        var now = 2_000L
        val policy = PanelOutsideTouchPolicy(postImeHideGraceMs = 250L, nowMs = { now })

        policy.onImeInsetsChanged(760)
        assertTrue(policy.shouldIgnoreOutsideTouch(panelOpen = true, closingInProgress = false, isRailTouch = false))

        now = 2_500L
        policy.onImeInsetsChanged(0)
        assertFalse(policy.isImeVisible)
        assertTrue(policy.shouldIgnoreOutsideTouch(panelOpen = true, closingInProgress = false, isRailTouch = false))

        // Repeated 0 inset notifications must not extend the grace window.
        now = 2_650L
        policy.onImeInsetsChanged(0)
        assertTrue(policy.shouldIgnoreOutsideTouch(panelOpen = true, closingInProgress = false, isRailTouch = false))

        now = 2_749L
        assertTrue(policy.shouldIgnoreOutsideTouch(panelOpen = true, closingInProgress = false, isRailTouch = false))

        // At 250ms elapsed, outside touch is allowed unless targeting the rail.
        now = 2_750L
        assertFalse(policy.shouldIgnoreOutsideTouch(panelOpen = true, closingInProgress = false, isRailTouch = false))
        assertTrue(policy.shouldIgnoreOutsideTouch(panelOpen = true, closingInProgress = false, isRailTouch = true))
    }

    @Test
    fun `reset clears stale ime state and active grace window on warm collapse`() {
        var now = 5_000L
        val policy = PanelOutsideTouchPolicy(postImeHideGraceMs = 250L, nowMs = { now })

        policy.onImeInsetsChanged(620)
        assertTrue(policy.shouldIgnoreOutsideTouch(panelOpen = true, closingInProgress = false, isRailTouch = false))

        policy.reset()
        assertFalse(policy.isImeVisible)
        assertFalse(policy.shouldIgnoreOutsideTouch(panelOpen = true, closingInProgress = false, isRailTouch = false))

        policy.onImeInsetsChanged(620)
        policy.onImeInsetsChanged(0)
        now += 50L
        assertTrue(policy.shouldIgnoreOutsideTouch(panelOpen = true, closingInProgress = false, isRailTouch = false))

        policy.reset()
        assertFalse(policy.shouldIgnoreOutsideTouch(panelOpen = true, closingInProgress = false, isRailTouch = false))
    }

    @Test
    fun `always ignores outside touch when panel is not open or closing`() {
        val policy = PanelOutsideTouchPolicy(postImeHideGraceMs = 250L, nowMs = { 0L })
        assertTrue(policy.shouldIgnoreOutsideTouch(panelOpen = false, closingInProgress = false, isRailTouch = false))
        assertTrue(policy.shouldIgnoreOutsideTouch(panelOpen = true, closingInProgress = true, isRailTouch = false))
    }

    @Test
    fun `ignores inner-edge outside touches within 12dp buffer for right and left edges`() {
        // Right edge: panel bounds [300, 200, 1040, 1800], density 3.0 -> 12dp = 36px buffer -> [264, 300]
        assertTrue(
            PanelOutsideTouchPolicy.shouldIgnoreInnerEdgeOutsideTouch(
                edge = EdgeSide.RIGHT,
                rawX = 270f,
                rawY = 600f,
                panelBoundsLeft = 300,
                panelBoundsTop = 200,
                panelBoundsRight = 1040,
                panelBoundsBottom = 1800,
                density = 3f,
            ),
        )
        assertFalse(
            PanelOutsideTouchPolicy.shouldIgnoreInnerEdgeOutsideTouch(
                edge = EdgeSide.RIGHT,
                rawX = 260f,
                rawY = 600f,
                panelBoundsLeft = 300,
                panelBoundsTop = 200,
                panelBoundsRight = 1040,
                panelBoundsBottom = 1800,
                density = 3f,
            ),
        )
        // Vertically outside panel bounds must not be ignored by inner-edge buffer
        assertFalse(
            PanelOutsideTouchPolicy.shouldIgnoreInnerEdgeOutsideTouch(
                edge = EdgeSide.RIGHT,
                rawX = 280f,
                rawY = 150f,
                panelBoundsLeft = 300,
                panelBoundsTop = 200,
                panelBoundsRight = 1040,
                panelBoundsBottom = 1800,
                density = 3f,
            ),
        )

        // Left edge: panel bounds [40, 200, 780, 1800], density 3.0 -> 12dp = 36px buffer -> [780, 816]
        assertTrue(
            PanelOutsideTouchPolicy.shouldIgnoreInnerEdgeOutsideTouch(
                edge = EdgeSide.LEFT,
                rawX = 805f,
                rawY = 900f,
                panelBoundsLeft = 40,
                panelBoundsTop = 200,
                panelBoundsRight = 780,
                panelBoundsBottom = 1800,
                density = 3f,
            ),
        )
        assertFalse(
            PanelOutsideTouchPolicy.shouldIgnoreInnerEdgeOutsideTouch(
                edge = EdgeSide.LEFT,
                rawX = 820f,
                rawY = 900f,
                panelBoundsLeft = 40,
                panelBoundsTop = 200,
                panelBoundsRight = 780,
                panelBoundsBottom = 1800,
                density = 3f,
            ),
        )
    }
}
