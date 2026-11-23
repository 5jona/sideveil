package pochita.sideveil.sensor.gesture

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
    ): EdgeGestureEnv = EdgeGestureEnv(config, geometry, scopeActive, panelState, fullscreenActive)

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
        var statusBarPullDownCount = 0

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

        fun onStatusBarPullDown() {
            statusBarPullDownCount++
        }
    }

    @Test
    fun `rejects vertical scroll and multi-touch ambiguity without pilfering`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb)

        sm.onDown(0, 990f, 700f, 100L, env())
        assertTrue(sm.isTracking)
        sm.onMove(0, 985f, 740f, 116L, 116L, env())
        assertFalse(sm.isTracking)
        assertFalse(sm.isPilfered)

        sm.onDown(0, 990f, 700f, 200L, env())
        assertTrue(sm.isTracking)
        sm.onPointerDown()
        assertFalse(sm.isTracking)
        assertEquals(0, cb.pilferCount)
        assertTrue(cb.starts.isEmpty())
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

        sm.onMove(0, 940f, 702f, 120L, 120L, env())
        assertEquals(1, cb.moves.size)

        sm.onMove(0, 900f, 702f, 132L, 132L, env())
        assertEquals(2, cb.moves.size)

        sm.onUpOrCancel(900f, 140L, canceled = false, env = env())
        assertEquals(1, cb.ends.size)
        assertTrue(cb.ends.single().shouldOpen)
        assertFalse(cb.ends.single().canceled)
        assertTrue(cb.ends.single().velocity > 0f)
    }

    @Test
    fun `zeros release velocity past stale window and cancels on 240px reverse-pull`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb)

        sm.onDown(0, 990f, 700f, 100L, env())
        sm.onMove(0, 955f, 700f, 110L, 110L, env())
        sm.onUpOrCancel(955f, 230L, canceled = false, env = env())
        assertFalse(cb.ends.single().shouldOpen)
        assertEquals(0f, cb.ends.single().velocity, 1e-4f)

        cb.ends.clear()
        sm.onDown(0, 990f, 700f, 300L, env())
        sm.onMove(0, 680f, 700f, 320L, 320L, env())
        sm.onMove(0, 840f, 700f, 340L, 340L, env())
        sm.onUpOrCancel(840f, 345L, canceled = false, env = env())
        assertFalse(cb.ends.single().shouldOpen)
        assertTrue(cb.ends.single().canceled)
    }

    @Test
    fun `requires double swipe in fullscreen when anti-accidental mode enabled`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb)
        val fsConfig = rightConfig.copy(edge = rightConfig.edge.copy(doubleSwipeInFullscreen = true))
        val fsEnv = env(config = fsConfig, fullscreenActive = true)

        sm.onDown(0, 990f, 700f, 100L, fsEnv)
        sm.onMove(0, 950f, 700f, 120L, 120L, fsEnv)
        assertFalse(sm.isPilfered)
        assertEquals(0, cb.pilferCount)
        assertEquals(listOf(EdgeSide.RIGHT), cb.fullscreenHints)

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

        sm.onMove(0, 200f, 1200f, 600L, 600L, env())
        assertEquals(EdgeSide.LEFT, cb.repositionMoves.single().first)

        sm.onUpOrCancel(200f, 650L, canceled = false, env = env())
        assertFalse(sm.isRepositioning)
        assertEquals(EdgeSide.LEFT, cb.repositionEnds.single().first)
        assertTrue(cb.repositionEnds.single().third)
    }

    @Test
    fun `ignores touches outside hotzone and detects status bar pull-down only when panel is open`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb, cb::onStatusBarPullDown)
        val openEnv = env(panelState = PanelState.OPEN)

        sm.onDown(0, 500f, 700f, 100L, env())
        assertFalse(sm.isTracking)
        sm.onDown(0, 990f, 700f, 100L, openEnv)
        assertFalse(sm.isTracking)

        val leftConfig = rightConfig.copy(edge = rightConfig.edge.copy(side = EdgeSide.LEFT))
        sm.onDown(0, 10f, 700f, 100L, env(config = leftConfig))
        assertTrue(sm.isTracking)

        sm.onDown(0, 500f, 40f, 200L, openEnv)
        assertTrue(sm.isTracking)
        sm.onMove(0, 502f, 55f, 210L, 210L, openEnv)
        assertEquals(0, cb.statusBarPullDownCount)
        sm.onMove(0, 505f, 80f, 220L, 220L, openEnv)
        assertEquals(1, cb.statusBarPullDownCount)
        assertFalse(sm.isTracking)

        sm.onDown(0, 500f, 700f, 300L, openEnv)
        sm.onMove(0, 500f, 820f, 320L, 320L, openEnv)
        sm.onDown(0, 500f, 40f, 400L, env())
        sm.onMove(0, 500f, 120f, 420L, 420L, env())
        sm.onDown(0, 300f, 40f, 500L, openEnv)
        sm.onMove(0, 460f, 70f, 520L, 520L, openEnv)
        assertEquals(1, cb.statusBarPullDownCount)
        assertEquals(0, cb.pilferCount)
    }
}
