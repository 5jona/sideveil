package pochita.sideveil.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.protocol.DisplayGeometry
import pochita.sideveil.protocol.EdgeConfig
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.SideVeilConfig

class EdgeGestureStateMachineTest {
    private val geometry = DisplayGeometry(widthPx = 1000, heightPx = 2000, density = 2.0f)
    private val rightConfig =
        SideVeilConfig(
            edge =
                EdgeConfig(
                    side = EdgeSide.RIGHT,
                    topPercent = 30,
                    heightDp = 200,
                    hotzoneWidthDp = 25,
                    triggerDistanceDp = 40,
                ),
        )

    private fun env(
        config: SideVeilConfig = rightConfig,
        scopeActive: Boolean = true,
        panelState: PanelState = PanelState.CLOSED,
        fullscreenActive: Boolean = false,
    ): EdgeGestureEnv =
        EdgeGestureEnv(
            config = config,
            geometry = geometry,
            scopeActive = scopeActive,
            panelState = panelState,
            fullscreenActive = fullscreenActive,
        )

    private data class DragEndRecord(
        val shouldOpen: Boolean,
        val velocity: Float,
        val canceled: Boolean,
    )

    private class RecordingCallbacks : EdgeGestureCallbacks {
        var pilferCount = 0
        val starts = mutableListOf<Pair<EdgeSide, Float>>()
        val moves = mutableListOf<Float>()
        val ends = mutableListOf<DragEndRecord>()
        val fullscreenHints = mutableListOf<EdgeSide>()
        val repositionStarts = mutableListOf<Pair<EdgeSide, Int>>()
        val repositionMoves = mutableListOf<Pair<EdgeSide, Int>>()
        val repositionEnds = mutableListOf<Triple<EdgeSide, Int, Boolean>>()

        override fun onPilferPointers() {
            pilferCount++
        }

        override fun onDragStart(
            side: EdgeSide,
            startYFraction: Float,
        ) {
            starts += side to startYFraction
        }

        override fun onDragMove(
            side: EdgeSide,
            progress: Float,
            rawX: Float,
            rawY: Float,
            deltaX: Float,
        ) {
            moves += progress
        }

        override fun onDragEnd(
            shouldOpen: Boolean,
            velocityPxPerSec: Float,
            canceled: Boolean,
        ) {
            ends += DragEndRecord(shouldOpen, velocityPxPerSec, canceled)
        }

        override fun onFullscreenHint(side: EdgeSide) {
            fullscreenHints += side
        }

        override fun onEdgeRepositionStart(
            side: EdgeSide,
            topPercent: Int,
        ) {
            repositionStarts += side to topPercent
        }

        override fun onEdgeRepositionMove(
            side: EdgeSide,
            topPercent: Int,
        ) {
            repositionMoves += side to topPercent
        }

        override fun onEdgeRepositionEnd(
            side: EdgeSide,
            topPercent: Int,
            committed: Boolean,
        ) {
            repositionEnds += Triple(side, topPercent, committed)
        }
    }

    @Test
    fun `rejects vertical scroll without pilfering pointers`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb)

        sm.onDown(0, 990f, 700f, 100L, env())
        assertTrue(sm.isTracking)

        sm.onMove(0, 985f, 740f, 116L, 116L, env())
        assertFalse(sm.isTracking)
        assertFalse(sm.isPilfered)
        assertEquals(0, cb.pilferCount)
        assertTrue(cb.starts.isEmpty())
    }

    @Test
    fun `cancels ambiguity on multi-touch before pilfer`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb)

        sm.onDown(0, 990f, 700f, 100L, env())
        assertTrue(sm.isTracking)
        sm.onPointerDown()
        assertFalse(sm.isTracking)
        assertEquals(0, cb.pilferCount)
    }

    @Test
    fun `pilfers inward swipe, throttles moves, and opens when trigger distance reached`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb)

        sm.onDown(0, 990f, 700f, 100L, env())
        sm.onMove(0, 960f, 702f, 116L, 116L, env())
        assertTrue(sm.isPilfered)
        assertEquals(1, cb.pilferCount)
        assertEquals(1, cb.starts.size)
        assertEquals(1, cb.moves.size)

        // Move within 8ms throttle window is dropped.
        sm.onMove(0, 940f, 702f, 120L, 120L, env())
        assertEquals(1, cb.moves.size)

        // Move past trigger distance (40dp * 2 = 80px) after throttle window emits move.
        sm.onMove(0, 900f, 702f, 132L, 132L, env())
        assertEquals(2, cb.moves.size)

        sm.onUpOrCancel(900f, 140L, canceled = false, env = env())
        assertEquals(1, cb.ends.size)
        assertTrue(cb.ends.single().shouldOpen)
        assertFalse(cb.ends.single().canceled)
        assertTrue(cb.ends.single().velocity > 0f)
    }

    @Test
    fun `zeros release velocity when finger pauses past stale window`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb)

        sm.onDown(0, 990f, 700f, 100L, env())
        sm.onMove(0, 955f, 700f, 110L, 110L, env())
        assertTrue(sm.isPilfered)

        sm.onUpOrCancel(955f, 230L, canceled = false, env = env())
        assertEquals(1, cb.ends.size)
        assertFalse(cb.ends.single().shouldOpen)
        assertEquals(0f, cb.ends.single().velocity, 1e-4f)
    }

    @Test
    fun `cancels opening when user pulls back into 240px reverse-cancel zone`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb)

        sm.onDown(0, 990f, 700f, 100L, env())
        // Pull deep inward by 310px (past 240px cancel zone + 20px touch slop).
        sm.onMove(0, 680f, 700f, 120L, 120L, env())
        assertTrue(sm.isPilfered)

        // Pull back outward to inwardDx = 150px (still > 80px triggerDist, but inside 240px zone).
        sm.onMove(0, 840f, 700f, 140L, 140L, env())
        sm.onUpOrCancel(840f, 145L, canceled = false, env = env())

        assertEquals(1, cb.ends.size)
        assertFalse(cb.ends.single().shouldOpen)
        assertTrue(cb.ends.single().canceled)
    }

    @Test
    fun `requires double swipe in fullscreen when anti-accidental mode enabled`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb)
        val fsConfig = rightConfig.copy(edge = rightConfig.edge.copy(doubleSwipeInFullscreen = true))
        val fsEnv = env(config = fsConfig, fullscreenActive = true)

        // First swipe emits hint without pilfering pointers.
        sm.onDown(0, 990f, 700f, 100L, fsEnv)
        sm.onMove(0, 950f, 700f, 120L, 120L, fsEnv)
        assertFalse(sm.isPilfered)
        assertEquals(0, cb.pilferCount)
        assertEquals(listOf(EdgeSide.RIGHT), cb.fullscreenHints)

        // Second swipe within 1500ms commits pilfer and opens normally.
        sm.onDown(0, 990f, 700f, 600L, fsEnv)
        sm.onMove(0, 900f, 700f, 620L, 620L, fsEnv)
        assertTrue(sm.isPilfered)
        assertEquals(1, cb.pilferCount)
        assertEquals(1, cb.starts.size)
    }

    @Test
    fun `long press enters handle reposition mode and commits side flip and topPercent`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb)

        sm.onDown(0, 990f, 700f, 100L, env())
        sm.onLongPressTimeout(0, env())
        assertTrue(sm.isRepositioning)
        assertEquals(1, cb.pilferCount)
        assertEquals(1, cb.repositionStarts.size)

        // Drag vertically down and across horizontal threshold (< 35% width) to flip to LEFT.
        sm.onMove(0, 200f, 1200f, 600L, 600L, env())
        assertEquals(1, cb.repositionMoves.size)
        assertEquals(EdgeSide.LEFT, cb.repositionMoves.single().first)

        sm.onUpOrCancel(200f, 650L, canceled = false, env = env())
        assertFalse(sm.isRepositioning)
        assertEquals(1, cb.repositionEnds.size)
        assertEquals(EdgeSide.LEFT, cb.repositionEnds.single().first)
        assertTrue(cb.repositionEnds.single().third)
    }

    @Test
    fun `ignores touches outside hotzone or when panel is already open`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb)

        sm.onDown(0, 500f, 700f, 100L, env())
        assertFalse(sm.isTracking)

        sm.onDown(0, 990f, 700f, 100L, env(panelState = PanelState.OPEN))
        assertFalse(sm.isTracking)

        val leftConfig = rightConfig.copy(edge = rightConfig.edge.copy(side = EdgeSide.LEFT))
        sm.onDown(0, 10f, 700f, 100L, env(config = leftConfig))
        assertTrue(sm.isTracking)
    }
}
