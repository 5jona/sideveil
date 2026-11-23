package pochita.sideveil.sensor.gesture

import pochita.sideveil.protocol.DebugGestureStats
import pochita.sideveil.protocol.DisplayGeometry
import pochita.sideveil.protocol.EdgeSide

/**
 * Low-level callbacks emitted by the pure JVM [EdgeGestureStateMachine].
 */
interface EdgeGestureCallbacks {
    /** Requests the active `InputMonitor` to pilfer touch pointers from the foreground window. */
    fun onPilferPointers()

    /** Emitted once when an inward edge drag commits and pilfers pointers. */
    fun onDragStart(
        side: EdgeSide,
        startYFraction: Float,
    )

    /** Emitted on throttled inward drag progress updates. */
    fun onDragMove(
        side: EdgeSide,
        progress: Float,
        rawX: Float,
        rawY: Float,
        deltaX: Float,
    )

    /** Emitted when a pilfered edge drag finishes or aborts. */
    fun onDragEnd(
        shouldOpen: Boolean,
        velocityPxPerSec: Float,
        canceled: Boolean = false,
    )

    /** Emitted on the first swipe in fullscreen double-swipe mode. */
    fun onFullscreenHint(side: EdgeSide) {}

    /** Emitted when a long-press on the visible edge handle enters reposition mode. */
    fun onEdgeRepositionStart(
        side: EdgeSide,
        topPercent: Int,
    ) {}

    /** Emitted when the edge handle moves vertically or flips sides during repositioning. */
    fun onEdgeRepositionMove(
        side: EdgeSide,
        topPercent: Int,
    ) {}

    /** Emitted when edge handle repositioning completes or is canceled. */
    fun onEdgeRepositionEnd(
        side: EdgeSide,
        topPercent: Int,
        committed: Boolean,
    ) {}

    /** Schedules a delayed long-press timeout check for [pointerId]. */
    fun onScheduleLongPress(pointerId: Int) {}

    /** Cancels any pending long-press timeout check. */
    fun onCancelLongPress() {}

    /** Records a rejected swipe attempt or hotzone miss for diagnostics. */
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
    /** Forwards drag start to the Hub. */
    fun onDragStart(
        side: EdgeSide,
        startYFraction: Float,
    )

    /** Forwards drag move progress to the Hub. */
    fun onDragMove(
        side: EdgeSide,
        progress: Float,
        rawX: Float,
        rawY: Float,
        deltaX: Float,
    )

    /** Forwards drag release/cancel outcome to the Hub. */
    fun onDragEnd(
        shouldOpen: Boolean,
        velocityPxPerSec: Float,
        canceled: Boolean,
    )

    /** Forwards fullscreen double-swipe hint to the Hub. */
    fun onFullscreenHint(side: EdgeSide)

    /** Forwards edge handle reposition start to the Hub. */
    fun onEdgeRepositionStart(
        side: EdgeSide,
        topPercent: Int,
    )

    /** Forwards edge handle reposition move to the Hub. */
    fun onEdgeRepositionMove(
        side: EdgeSide,
        topPercent: Int,
    )

    /** Forwards edge handle reposition completion to the Hub. */
    fun onEdgeRepositionEnd(
        side: EdgeSide,
        topPercent: Int,
        committed: Boolean,
    )

    /** Forwards updated display geometry to the Hub. */
    fun onDisplayChanged(geometry: DisplayGeometry)

    /** Forwards updated gesture diagnostics counters to the Hub. */
    fun onGestureStatsUpdated(stats: DebugGestureStats) {}

    /** Forwards a top status-bar pull-down detected while the panel is open. */
    fun onStatusBarPullDown() {}
}

/**
 * Bridges [EdgeGestureCallbacks] from [EdgeGestureStateMachine] to [EdgeGestureListener]
 * and [GestureDiagnosticsTracker].
 */
internal class EdgeGestureCallbackAdapter(
    private val listener: EdgeGestureListener,
    private val diagnosticsTracker: GestureDiagnosticsTracker,
    private val onPilfer: () -> Unit,
    private val onScheduleLongPressCallback: (Int) -> Unit,
    private val onCancelLongPressCallback: () -> Unit,
) : EdgeGestureCallbacks {
    override fun onPilferPointers() {
        onPilfer()
        listener.onGestureStatsUpdated(diagnosticsTracker.recordPilfer())
    }

    override fun onDragStart(
        side: EdgeSide,
        startYFraction: Float,
    ) = listener.onDragStart(side, startYFraction)

    override fun onDragMove(
        side: EdgeSide,
        progress: Float,
        rawX: Float,
        rawY: Float,
        deltaX: Float,
    ) = listener.onDragMove(side, progress, rawX, rawY, deltaX)

    override fun onDragEnd(
        shouldOpen: Boolean,
        velocityPxPerSec: Float,
        canceled: Boolean,
    ) {
        if (shouldOpen) {
            listener.onGestureStatsUpdated(diagnosticsTracker.recordTriggered())
        }
        listener.onDragEnd(shouldOpen, velocityPxPerSec, canceled)
    }

    override fun onFullscreenHint(side: EdgeSide) = listener.onFullscreenHint(side)

    override fun onEdgeRepositionStart(
        side: EdgeSide,
        topPercent: Int,
    ) = listener.onEdgeRepositionStart(side, topPercent)

    override fun onEdgeRepositionMove(
        side: EdgeSide,
        topPercent: Int,
    ) = listener.onEdgeRepositionMove(side, topPercent)

    override fun onEdgeRepositionEnd(
        side: EdgeSide,
        topPercent: Int,
        committed: Boolean,
    ) = listener.onEdgeRepositionEnd(side, topPercent, committed)

    override fun onScheduleLongPress(pointerId: Int) = onScheduleLongPressCallback(pointerId)

    override fun onCancelLongPress() = onCancelLongPressCallback()

    override fun onSwipeRejected(
        reason: String,
        hotzoneMiss: Boolean,
    ) {
        if (hotzoneMiss) {
            diagnosticsTracker.recordHotzoneMiss()?.let(listener::onGestureStatsUpdated)
        } else {
            listener.onGestureStatsUpdated(diagnosticsTracker.recordRejected(reason))
        }
    }
}
