package pochita.sideveil.sensor

import pochita.sideveil.protocol.DebugGestureStats
import pochita.sideveil.protocol.DisplayGeometry
import pochita.sideveil.protocol.EdgeSide

/**
 * Low-level callbacks emitted by the pure JVM [EdgeGestureStateMachine].
 */
interface EdgeGestureCallbacks {
    fun onPilferPointers()

    fun onDragStart(
        side: EdgeSide,
        startYFraction: Float,
    )

    fun onDragMove(
        side: EdgeSide,
        progress: Float,
        rawX: Float,
        rawY: Float,
        deltaX: Float,
    )

    fun onDragEnd(
        shouldOpen: Boolean,
        velocityPxPerSec: Float,
        canceled: Boolean = false,
    )

    fun onFullscreenHint(side: EdgeSide) {}

    fun onEdgeRepositionStart(
        side: EdgeSide,
        topPercent: Int,
    ) {}

    fun onEdgeRepositionMove(
        side: EdgeSide,
        topPercent: Int,
    ) {}

    fun onEdgeRepositionEnd(
        side: EdgeSide,
        topPercent: Int,
        committed: Boolean,
    ) {}

    fun onScheduleLongPress(pointerId: Int) {}

    fun onCancelLongPress() {}

    fun onSwipeRejected(
        reason: String,
        hotzoneMiss: Boolean = false,
    ) {}
}

/**
 * High-level sensor listener consuming edge gestures, display geometry changes,
 * and throttled [DebugGestureStats] snapshots from [EdgeGestureMonitor].
 */
interface EdgeGestureListener {
    fun onDragStart(
        side: EdgeSide,
        startYFraction: Float,
    )

    fun onDragMove(
        side: EdgeSide,
        progress: Float,
        rawX: Float,
        rawY: Float,
        deltaX: Float,
    )

    fun onDragEnd(
        shouldOpen: Boolean,
        velocityPxPerSec: Float,
        canceled: Boolean,
    )

    fun onFullscreenHint(side: EdgeSide)

    fun onEdgeRepositionStart(
        side: EdgeSide,
        topPercent: Int,
    )

    fun onEdgeRepositionMove(
        side: EdgeSide,
        topPercent: Int,
    )

    fun onEdgeRepositionEnd(
        side: EdgeSide,
        topPercent: Int,
        committed: Boolean,
    )

    fun onDisplayChanged(geometry: DisplayGeometry)

    fun onGestureStatsUpdated(stats: DebugGestureStats) {}
}
