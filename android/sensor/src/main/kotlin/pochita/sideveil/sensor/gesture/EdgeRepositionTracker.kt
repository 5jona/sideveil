package pochita.sideveil.sensor.gesture

import pochita.sideveil.protocol.DisplayGeometry
import pochita.sideveil.protocol.EdgeConfig
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelState
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Pure JVM tracker for long-press edge-handle repositioning (`topPercent` dragging and
 * horizontal side flip with hysteresis).
 */
class EdgeRepositionTracker {
    /** Whether an edge-handle reposition session is currently active. */
    var active: Boolean = false
        private set

    /** Current previewed edge side during repositioning. */
    var currentSide: EdgeSide = EdgeSide.RIGHT
        private set

    /** Current previewed vertical top percentage (`5..85`) during repositioning. */
    var currentTopPercent: Int = 32
        private set

    private var lastEmitMs: Long = 0L

    /** Starts a reposition session anchored at [rawY]. */
    fun begin(
        edge: EdgeConfig,
        rawY: Float,
        geometry: DisplayGeometry,
    ): Pair<EdgeSide, Int> {
        active = true
        currentSide = edge.side
        currentTopPercent = computeTopPercent(rawY, edge, geometry)
        lastEmitMs = 0L
        return currentSide to currentTopPercent
    }

    /**
     * Updates side and `topPercent` on move. Returns the updated `(side, topPercent)` if the
     * throttled interval elapsed or the values changed meaningfully; `null` otherwise.
     */
    fun onMove(
        rawX: Float,
        rawY: Float,
        uptimeMs: Long,
        edge: EdgeConfig,
        geometry: DisplayGeometry,
    ): Pair<EdgeSide, Int>? {
        if (!active) return null
        val screenW = geometry.widthPx.coerceAtLeast(1).toFloat()
        val nextSide =
            when {
                rawX < screenW * LEFT_FLIP_THRESHOLD -> EdgeSide.LEFT
                rawX > screenW * RIGHT_FLIP_THRESHOLD -> EdgeSide.RIGHT
                else -> currentSide
            }
        val nextTop = computeTopPercent(rawY, edge, geometry)
        val sideChanged = nextSide != currentSide
        val topChanged = nextTop != currentTopPercent
        currentSide = nextSide
        currentTopPercent = nextTop

        if (!sideChanged && !topChanged) return null
        if (!sideChanged && uptimeMs - lastEmitMs < REPOSITION_THROTTLE_MS) return null
        lastEmitMs = uptimeMs
        return currentSide to currentTopPercent
    }

    /** Completes the reposition session and returns the final `(side, topPercent)`, or `null` if inactive. */
    fun finish(): Pair<EdgeSide, Int>? {
        if (!active) return null
        active = false
        return currentSide to currentTopPercent
    }

    /** Resets tracking state without emitting a completion pair. */
    fun reset() {
        active = false
        lastEmitMs = 0L
    }

    companion object {
        private const val LEFT_FLIP_THRESHOLD = 0.35f
        private const val RIGHT_FLIP_THRESHOLD = 0.65f
        private const val REPOSITION_THROTTLE_MS = 16L
        private const val MIN_TOP_PERCENT = 5
        private const val MAX_TOP_PERCENT = 85

        /** Computes the clamped vertical `topPercent` (`5..85`) centering the handle on [rawY]. */
        fun computeTopPercent(
            rawY: Float,
            edge: EdgeConfig,
            geometry: DisplayGeometry,
        ): Int {
            val screenH = geometry.heightPx.coerceAtLeast(1).toFloat()
            val handleHeightPx = edge.heightDp * geometry.density
            val topPx = rawY - handleHeightPx * 0.5f
            return ((topPx / screenH) * 100f).roundToInt().coerceIn(MIN_TOP_PERCENT, MAX_TOP_PERCENT)
        }
    }
}

/**
 * Passive tracker detecting top status-bar pull-down gestures while the sidebar panel is open.
 */
internal class StatusBarPullDownTracker {
    var active: Boolean = false
        private set
    var pointerId: Int = EdgeGestureStateMachine.INVALID_POINTER_ID
        private set
    private var downX = 0f
    private var downY = 0f

    fun tryBegin(
        pointerId: Int,
        rawX: Float,
        rawY: Float,
        env: EdgeGestureEnv,
    ): Boolean {
        if (env.panelState != PanelState.OPEN) return false
        if (EdgeGestureMath.isInsideStatusBarTriggerZone(rawY, env.geometry)) {
            active = true
            this.pointerId = pointerId
            downX = rawX
            downY = rawY
        }
        return true
    }

    fun onMove(
        pointerId: Int,
        rawX: Float,
        rawY: Float,
        env: EdgeGestureEnv,
    ): Boolean {
        if (!active) return false
        if (pointerId != this.pointerId || env.panelState != PanelState.OPEN) {
            reset()
            return false
        }
        if (EdgeGestureMath.isStatusBarPullTriggered(rawX - downX, rawY - downY, env.geometry)) {
            reset()
            return true
        }
        return false
    }

    fun reset() {
        active = false
        pointerId = EdgeGestureStateMachine.INVALID_POINTER_ID
    }
}

/**
 * Mutable kinematics accumulator for an active edge swipe gesture.
 */
internal class EdgeDragKinematics {
    var downX = 0f
        private set
    var downY = 0f
        private set
    var lastX = 0f
    var lastY = 0f
    var maxInwardDx = 0f
        private set
    var lastEventTimeMs = 0L
        private set
    var lastSignificantMoveTimeMs = 0L
        private set
    var lastVelocityPxPerSec = 0f
        private set
    private var lastMoveEmitMs = 0L

    fun begin(
        rawX: Float,
        rawY: Float,
        eventTimeMs: Long,
    ) {
        downX = rawX
        downY = rawY
        lastX = rawX
        lastY = rawY
        maxInwardDx = 0f
        lastEventTimeMs = eventTimeMs
        lastSignificantMoveTimeMs = eventTimeMs
        lastVelocityPxPerSec = 0f
        lastMoveEmitMs = 0L
    }

    fun exceedsTouchSlop(touchSlopPx: Float): Boolean = hypot((lastX - downX).toDouble(), (lastY - downY).toDouble()) > touchSlopPx

    fun stepVelocity(
        side: EdgeSide,
        rawX: Float,
        eventTimeMs: Long,
    ) {
        val step =
            EdgeGestureMath.stepVelocity(
                side,
                rawX,
                lastX,
                eventTimeMs,
                lastEventTimeMs,
                lastSignificantMoveTimeMs,
                lastVelocityPxPerSec,
            )
        lastVelocityPxPerSec = step.velocityPxPerSec
        lastEventTimeMs = step.eventTimeMs
        lastSignificantMoveTimeMs = step.significantMoveTimeMs
        lastX = rawX
    }

    fun recordInwardMove(
        inwardDx: Float,
        uptimeMs: Long,
        throttleMs: Long,
    ): Boolean {
        if (inwardDx > maxInwardDx) maxInwardDx = inwardDx
        if (uptimeMs - lastMoveEmitMs < throttleMs) return false
        lastMoveEmitMs = uptimeMs
        return true
    }

    fun evaluateRelease(
        side: EdgeSide,
        rawX: Float?,
        eventTimeMs: Long,
        canceled: Boolean,
        env: EdgeGestureEnv,
    ): PilferReleaseOutcome =
        EdgeGestureMath.evaluatePilferRelease(
            side = side,
            downX = downX,
            finalX = rawX ?: lastX,
            maxInwardDx = maxInwardDx,
            trackedVelocityPxPerSec = lastVelocityPxPerSec,
            lastSignificantMoveTimeMs = lastSignificantMoveTimeMs,
            upEventTimeMs = eventTimeMs,
            canceled = canceled,
            env = env,
        )

    fun reset() {
        maxInwardDx = 0f
        lastSignificantMoveTimeMs = 0L
        lastVelocityPxPerSec = 0f
    }
}
