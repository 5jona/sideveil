package pochita.sideveil.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.protocol.DisplayGeometry
import pochita.sideveil.protocol.EdgeConfig
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.SideveilConfig

class EdgeGestureStateMachineTest {
    private val geometry = DisplayGeometry(widthPx = 1000, heightPx = 2000, density = 2.0f)
    private val rightConfig =
        SideveilConfig(
            edge =
                EdgeConfig(
                    side = EdgeSide.RIGHT,
                    topPercent = 30,
                    heightDp = 200,
                    hotzoneWidthDp = 25,
                    triggerDistanceDp = 40,
                ),
        )

    private class RecordingCallbacks : EdgeGestureCallbacks {
        var pilferCount = 0
        val starts = mutableListOf<Pair<EdgeSide, Float>>()
        val moves = mutableListOf<Float>()
        val ends = mutableListOf<Pair<Boolean, Float>>()

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
        ) {
            ends += shouldOpen to velocityPxPerSec
        }
    }

    @Test
    fun `rejects vertical scroll without pilfering pointers`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb)

        sm.onDown(0, 990f, 700f, 100L, rightConfig, geometry, scopeActive = true, panelState = PanelState.CLOSED)
        assertTrue(sm.isTracking)

        sm.onMove(0, 985f, 740f, 116L, 116L, rightConfig, geometry)
        assertFalse(sm.isTracking)
        assertFalse(sm.isPilfered)
        assertEquals(0, cb.pilferCount)
        assertTrue(cb.starts.isEmpty())
    }

    @Test
    fun `cancels ambiguity on multi-touch before pilfer`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb)

        sm.onDown(0, 990f, 700f, 100L, rightConfig, geometry, scopeActive = true, panelState = PanelState.CLOSED)
        assertTrue(sm.isTracking)
        sm.onPointerDown()
        assertFalse(sm.isTracking)
        assertEquals(0, cb.pilferCount)
    }

    @Test
    fun `pilfers inward swipe, throttles moves, and opens when trigger distance reached`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb)

        sm.onDown(0, 990f, 700f, 100L, rightConfig, geometry, scopeActive = true, panelState = PanelState.CLOSED)
        // Touch slop is 10dp * 2 = 20px; move inward 30px -> commits pilfer.
        sm.onMove(0, 960f, 702f, 116L, 116L, rightConfig, geometry)
        assertTrue(sm.isPilfered)
        assertEquals(1, cb.pilferCount)
        assertEquals(1, cb.starts.size)
        assertEquals(1, cb.moves.size)

        // Move within 8ms throttle window is dropped.
        sm.onMove(0, 940f, 702f, 120L, 120L, rightConfig, geometry)
        assertEquals(1, cb.moves.size)

        // Move past trigger distance (40dp * 2 = 80px) after throttle window emits move.
        sm.onMove(0, 900f, 702f, 132L, 132L, rightConfig, geometry)
        assertEquals(2, cb.moves.size)

        sm.onUpOrCancel(900f, 140L, canceled = false, config = rightConfig, geometry = geometry)
        assertEquals(1, cb.ends.size)
        assertTrue(cb.ends.single().first)
        assertTrue(cb.ends.single().second > 0f)
    }

    @Test
    fun `zeros release velocity when finger pauses past stale window`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb)

        sm.onDown(0, 990f, 700f, 100L, rightConfig, geometry, scopeActive = true, panelState = PanelState.CLOSED)
        // Quick inward flick of 35px (below 80px trigger dist, but high velocity).
        sm.onMove(0, 955f, 700f, 110L, 110L, rightConfig, geometry)
        assertTrue(sm.isPilfered)

        // Finger holds still for 120ms (> 64ms stale window) before lifting.
        sm.onUpOrCancel(955f, 230L, canceled = false, config = rightConfig, geometry = geometry)
        assertEquals(1, cb.ends.size)
        assertFalse(cb.ends.single().first)
        assertEquals(0f, cb.ends.single().second, 1e-4f)
    }

    @Test
    fun `ignores touches outside hotzone or when panel is already open`() {
        val cb = RecordingCallbacks()
        val sm = EdgeGestureStateMachine(cb)

        // Outside X hotzone (x=500)
        sm.onDown(0, 500f, 700f, 100L, rightConfig, geometry, scopeActive = true, panelState = PanelState.CLOSED)
        assertFalse(sm.isTracking)

        // Inside hotzone, but panel is already OPEN
        sm.onDown(0, 990f, 700f, 100L, rightConfig, geometry, scopeActive = true, panelState = PanelState.OPEN)
        assertFalse(sm.isTracking)

        // Left-edge hotzone check
        val leftConfig = rightConfig.copy(edge = rightConfig.edge.copy(side = EdgeSide.LEFT))
        sm.onDown(0, 10f, 700f, 100L, leftConfig, geometry, scopeActive = true, panelState = PanelState.CLOSED)
        assertTrue(sm.isTracking)
    }
}
