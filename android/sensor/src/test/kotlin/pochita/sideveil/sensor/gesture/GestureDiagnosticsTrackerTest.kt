package pochita.sideveil.sensor.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import pochita.sideveil.protocol.DisplayGeometry
import pochita.sideveil.protocol.EdgeConfig
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.SideVeilConfig

class GestureDiagnosticsTrackerTest {
    @Test
    fun `throttles hotzone miss emissions while reporting discrete gesture transitions immediately`() {
        var now = 10_000L
        val tracker = GestureDiagnosticsTracker(hotzoneReportThrottleMs = 1_000L, clockMs = { now })

        val firstMiss = tracker.recordHotzoneMiss()
        assertNotNull(firstMiss)
        assertEquals(1L, firstMiss!!.hotzoneMisses)
        assertEquals(10_000L, firstMiss.lastEventTimestampMs)

        // Rapid taps within the throttle window increment the counter without emitting.
        now = 10_400L
        assertNull(tracker.recordHotzoneMiss())
        now = 10_999L
        assertNull(tracker.recordHotzoneMiss())
        assertEquals(3L, tracker.snapshot().hotzoneMisses)

        // Once the throttle interval elapses, the next miss emits the accumulated count.
        now = 11_000L
        val throttledEmit = tracker.recordHotzoneMiss()
        assertNotNull(throttledEmit)
        assertEquals(4L, throttledEmit!!.hotzoneMisses)

        // Pilfer, trigger, and reject transitions always emit immediately.
        now = 11_100L
        val pilfer = tracker.recordPilfer()
        assertEquals(1L, pilfer.pilferInvocations)

        now = 11_200L
        val triggered = tracker.recordTriggered()
        assertEquals(1L, triggered.edgeSwipesTriggered)

        now = 11_300L
        val rejected = tracker.recordRejected(EdgeGestureStateMachine.REJECT_VERTICAL_SCROLL)
        assertEquals(1L, rejected.edgeSwipesRejected)
        assertEquals(EdgeGestureStateMachine.REJECT_VERTICAL_SCROLL, rejected.lastRejectReason)
        assertEquals(11_300L, rejected.lastEventTimestampMs)
    }

    @Test
    fun `state machine reports hotzone misses, rejections, pilfers, and triggers to tracker`() {
        var now = 20_000L
        val tracker = GestureDiagnosticsTracker(hotzoneReportThrottleMs = 0L, clockMs = { now })
        val sm =
            EdgeGestureStateMachine(
                object : EdgeGestureCallbacks {
                    override fun onPilferPointers() {
                        now += 10L
                        tracker.recordPilfer()
                    }

                    override fun onDragStart(
                        side: EdgeSide,
                        startYFraction: Float,
                    ) = Unit

                    override fun onDragMove(
                        side: EdgeSide,
                        progress: Float,
                        rawX: Float,
                        rawY: Float,
                        deltaX: Float,
                    ) = Unit

                    override fun onDragEnd(
                        shouldOpen: Boolean,
                        velocityPxPerSec: Float,
                        canceled: Boolean,
                    ) {
                        if (shouldOpen) {
                            now += 10L
                            tracker.recordTriggered()
                        }
                    }

                    override fun onSwipeRejected(
                        reason: String,
                        hotzoneMiss: Boolean,
                    ) {
                        now += 10L
                        if (hotzoneMiss) {
                            tracker.recordHotzoneMiss()
                        } else {
                            tracker.recordRejected(reason)
                        }
                    }
                },
            )

        val geometry = DisplayGeometry(widthPx = 1000, heightPx = 2000, density = 2.0f)
        val env =
            EdgeGestureEnv(
                config =
                    SideVeilConfig(
                        edge =
                            EdgeConfig(
                                side = EdgeSide.RIGHT,
                                topPercent = 30,
                                heightDp = 200,
                                hotzoneWidthDp = 25,
                                triggerDistanceDp = 40,
                            ),
                    ),
                geometry = geometry,
                scopeActive = true,
                panelState = PanelState.CLOSED,
                fullscreenActive = false,
            )

        // 1. Touch outside hotzone -> hotzone miss.
        sm.onDown(pointerId = 1, rawX = 500f, rawY = 700f, eventTimeMs = 100L, env = env)
        assertEquals(1L, tracker.snapshot().hotzoneMisses)

        // 2. Vertical scroll inside hotzone -> rejected with vertical_scroll.
        sm.onDown(pointerId = 2, rawX = 990f, rawY = 700f, eventTimeMs = 200L, env = env)
        sm.onMove(pointerId = 2, rawX = 985f, rawY = 760f, eventTimeMs = 220L, uptimeMs = 220L, env = env)
        assertEquals(1L, tracker.snapshot().edgeSwipesRejected)
        assertEquals(EdgeGestureStateMachine.REJECT_VERTICAL_SCROLL, tracker.snapshot().lastRejectReason)

        // 3. Multi-touch before pilfer -> rejected with multi_touch.
        sm.onDown(pointerId = 3, rawX = 990f, rawY = 700f, eventTimeMs = 300L, env = env)
        sm.onPointerDown()
        assertEquals(2L, tracker.snapshot().edgeSwipesRejected)
        assertEquals(EdgeGestureStateMachine.REJECT_MULTI_TOUCH, tracker.snapshot().lastRejectReason)

        // 4. Clean inward swipe past trigger distance -> pilfer + triggered.
        sm.onDown(pointerId = 4, rawX = 990f, rawY = 700f, eventTimeMs = 400L, env = env)
        sm.onMove(pointerId = 4, rawX = 950f, rawY = 702f, eventTimeMs = 420L, uptimeMs = 420L, env = env)
        sm.onMove(pointerId = 4, rawX = 880f, rawY = 704f, eventTimeMs = 440L, uptimeMs = 440L, env = env)
        sm.onUpOrCancel(rawX = 880f, eventTimeMs = 450L, canceled = false, env = env)

        val finalStats = tracker.snapshot()
        assertEquals(1L, finalStats.pilferInvocations)
        assertEquals(1L, finalStats.edgeSwipesTriggered)
        assertEquals(2L, finalStats.edgeSwipesRejected)
        assertEquals(1L, finalStats.hotzoneMisses)
    }
}
