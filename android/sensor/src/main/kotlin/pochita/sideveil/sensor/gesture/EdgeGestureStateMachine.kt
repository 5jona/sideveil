package pochita.sideveil.sensor.gesture

import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelState
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure JVM edge-gesture state machine decoupled from Android `MotionEvent` / `InputMonitor` APIs.
 *
 * Supports inward swipe pilfering, vertical-scroll & multi-touch rejection, `240px` reverse-pull
 * cancellation, optional fullscreen double-swipe anti-accidental trigger, and long-press edge
 * handle repositioning.
 */
class EdgeGestureStateMachine(
    private val callbacks: EdgeGestureCallbacks,
    private val onStatusBarPullDown: () -> Unit = {},
) {
    private val repositionTracker = EdgeRepositionTracker()
    private val statusBarTracker = StatusBarPullDownTracker()
    private val kinematics = EdgeDragKinematics()

    private var tracking = false
    private var pilfered = false
    private var activePointerId = INVALID_POINTER_ID
    private var activeEdge: EdgeSide = EdgeSide.RIGHT
    private var lastFullscreenHintMs = Long.MIN_VALUE

    /** Whether an edge gesture or status-bar pull-down is currently being tracked. */
    val isTracking: Boolean
        get() = tracking || statusBarTracker.active

    /** Whether the current gesture has already pilfered touch pointers. */
    val isPilfered: Boolean
        get() = pilfered

    /** Whether long-press edge handle repositioning is active. */
    val isRepositioning: Boolean
        get() = repositionTracker.active

    /** Active touch pointer ID, or [INVALID_POINTER_ID] when idle. */
    val currentPointerId: Int
        get() = if (statusBarTracker.active) statusBarTracker.pointerId else activePointerId

    /** Handles `ACTION_DOWN` and begins hotzone or status-bar pull-down tracking if eligible. */
    fun onDown(
        pointerId: Int,
        rawX: Float,
        rawY: Float,
        eventTimeMs: Long,
        env: EdgeGestureEnv,
    ) {
        reset(notifyAbortIfPilfered = true)
        if (statusBarTracker.tryBegin(pointerId, rawX, rawY, env)) return
        if (!env.scopeActive) return
        if (!EdgeGestureMath.isInsideHotzone(rawX, rawY, env.config.edge, env.geometry)) {
            callbacks.onSwipeRejected(REJECT_HOTZONE_MISS, hotzoneMiss = true)
            return
        }

        tracking = true
        pilfered = false
        activePointerId = pointerId
        activeEdge = env.config.edge.side
        kinematics.begin(rawX, rawY, eventTimeMs)

        if (env.config.edge.handleVisible) {
            callbacks.onScheduleLongPress(pointerId)
        }
    }

    /** Handles expiration of the long-press timer to enter edge-handle repositioning mode. */
    fun onLongPressTimeout(
        pointerId: Int,
        env: EdgeGestureEnv,
    ) {
        val eligible = tracking && !pilfered && !repositionTracker.active
        val envAllows = env.scopeActive && env.panelState != PanelState.OPEN && env.config.edge.handleVisible
        if (!eligible || pointerId != activePointerId || !envAllows) return
        if (kinematics.exceedsTouchSlop(EdgeGestureMath.TOUCH_SLOP_DP * env.geometry.density)) return

        pilfered = true
        callbacks.onPilferPointers()
        val (side, topPercent) = repositionTracker.begin(env.config.edge, kinematics.lastY, env.geometry)
        callbacks.onEdgeRepositionStart(side, topPercent)
    }

    /** Handles `ACTION_POINTER_DOWN` and aborts unpilfered gestures on multi-touch. */
    fun onPointerDown() {
        callbacks.onCancelLongPress()
        if (tracking && !pilfered) {
            callbacks.onSwipeRejected(REJECT_MULTI_TOUCH)
            reset(notifyAbortIfPilfered = false)
        }
    }

    /** Processes `ACTION_MOVE` for status-bar pull-down, handle repositioning, or inward drag. */
    fun onMove(
        pointerId: Int,
        rawX: Float,
        rawY: Float,
        eventTimeMs: Long,
        uptimeMs: Long,
        env: EdgeGestureEnv,
    ) {
        if (statusBarTracker.active) {
            if (statusBarTracker.onMove(pointerId, rawX, rawY, env)) onStatusBarPullDown()
            return
        }
        if (!tracking) return
        if (pointerId != activePointerId) {
            reset(notifyAbortIfPilfered = true)
            return
        }

        kinematics.lastY = rawY
        if (repositionTracker.active) {
            repositionTracker.onMove(rawX, rawY, uptimeMs, env.config.edge, env.geometry)?.let { (side, top) ->
                callbacks.onEdgeRepositionMove(side, top)
            }
            kinematics.lastX = rawX
            return
        }

        val dx = rawX - kinematics.downX
        val dy = rawY - kinematics.downY
        val inwardDx = EdgeGestureMath.inwardDelta(activeEdge, dx)
        val touchSlopPx = EdgeGestureMath.TOUCH_SLOP_DP * env.geometry.density
        if (hypot(dx.toDouble(), dy.toDouble()) > touchSlopPx) {
            callbacks.onCancelLongPress()
        }

        kinematics.stepVelocity(activeEdge, rawX, eventTimeMs)
        if (!pilfered && !tryCommitPilfer(dx, dy, inwardDx, touchSlopPx, eventTimeMs, env)) return
        if (!kinematics.recordInwardMove(inwardDx, uptimeMs, MOVE_THROTTLE_MS)) return

        val progress = EdgeGestureMath.computeDragProgress(inwardDx, touchSlopPx, env)
        callbacks.onDragMove(activeEdge, progress, rawX, rawY, inwardDx)
    }

    /** Finishes tracking if the active pointer lifts during a multi-pointer sequence. */
    fun onPointerUp(
        liftedPointerId: Int,
        rawX: Float,
        eventTimeMs: Long,
        env: EdgeGestureEnv,
    ) {
        if (isTracking && liftedPointerId == currentPointerId) {
            onUpOrCancel(rawX, eventTimeMs, canceled = false, env = env)
        }
    }

    /** Handles `ACTION_UP` or `ACTION_CANCEL` and emits the final drag or reposition decision. */
    fun onUpOrCancel(
        rawX: Float?,
        eventTimeMs: Long,
        canceled: Boolean,
        env: EdgeGestureEnv,
    ) {
        callbacks.onCancelLongPress()
        if (statusBarTracker.active) {
            reset(notifyAbortIfPilfered = false)
            return
        }
        if (!tracking) return
        if (repositionTracker.active) {
            repositionTracker.finish()?.let { (side, top) ->
                callbacks.onEdgeRepositionEnd(side, top, committed = !canceled)
            }
            pilfered = false
            reset(notifyAbortIfPilfered = false)
            return
        }
        if (pilfered) {
            pilfered = false
            val outcome = kinematics.evaluateRelease(activeEdge, rawX, eventTimeMs, canceled, env)
            outcome.rejectReason?.let(callbacks::onSwipeRejected)
            callbacks.onDragEnd(outcome.shouldOpen, outcome.releaseVelocityPxPerSec, outcome.canceled)
        } else {
            callbacks.onSwipeRejected(if (canceled) REJECT_CANCELED else REJECT_TOUCH_SLOP)
        }
        reset(notifyAbortIfPilfered = false)
    }

    /** Cancels unpilfered tracking when the foreground app leaves the active scope. */
    fun onScopeInactive() {
        callbacks.onCancelLongPress()
        if (tracking && !pilfered) {
            callbacks.onSwipeRejected(REJECT_SCOPE_INACTIVE)
            reset(notifyAbortIfPilfered = false)
        }
    }

    /** Resets state machine tracking, optionally notifying downstream of an aborted pilfered gesture. */
    fun reset(notifyAbortIfPilfered: Boolean = true) {
        callbacks.onCancelLongPress()
        val wasRepositioning = repositionTracker.active
        val finishedReposition = repositionTracker.finish()
        val wasPilfered = pilfered
        statusBarTracker.reset()
        kinematics.reset()
        tracking = false
        pilfered = false
        activePointerId = INVALID_POINTER_ID
        if (notifyAbortIfPilfered && wasRepositioning && finishedReposition != null) {
            callbacks.onEdgeRepositionEnd(finishedReposition.first, finishedReposition.second, committed = false)
        } else if (notifyAbortIfPilfered && wasPilfered) {
            callbacks.onDragEnd(shouldOpen = false, velocityPxPerSec = 0f, canceled = true)
        }
    }

    private fun tryCommitPilfer(
        dx: Float,
        dy: Float,
        inwardDx: Float,
        touchSlopPx: Float,
        eventTimeMs: Long,
        env: EdgeGestureEnv,
    ): Boolean {
        if (EdgeGestureMath.isVerticalScrollRejected(dx, dy, touchSlopPx)) {
            callbacks.onSwipeRejected(REJECT_VERTICAL_SCROLL)
            reset(notifyAbortIfPilfered = false)
            return false
        }
        if (inwardDx <= touchSlopPx || inwardDx <= abs(dy)) return false
        if (env.fullscreenActive && env.config.edge.doubleSwipeInFullscreen) {
            val withinWindow =
                lastFullscreenHintMs != Long.MIN_VALUE &&
                    eventTimeMs - lastFullscreenHintMs in 0..DOUBLE_SWIPE_WINDOW_MS
            if (!withinWindow) {
                lastFullscreenHintMs = eventTimeMs
                callbacks.onFullscreenHint(activeEdge)
                callbacks.onSwipeRejected(REJECT_FULLSCREEN_FIRST_SWIPE)
                reset(notifyAbortIfPilfered = false)
                return false
            }
            lastFullscreenHintMs = Long.MIN_VALUE
        }
        pilfered = true
        callbacks.onPilferPointers()
        callbacks.onDragStart(activeEdge, EdgeGestureMath.computeStartYFraction(kinematics.downY, env.geometry))
        return true
    }

    companion object {
        const val INVALID_POINTER_ID = -1
        const val DOUBLE_SWIPE_WINDOW_MS = 1500L
        const val LONG_PRESS_TIMEOUT_MS = 450L
        const val REJECT_MULTI_TOUCH = "multi_touch"
        const val REJECT_VERTICAL_SCROLL = "vertical_scroll"
        const val REJECT_FULLSCREEN_FIRST_SWIPE = "fullscreen_first_swipe"
        const val REJECT_REVERSE_PULL = "reverse_pull"
        const val REJECT_SHORT_SWIPE = "short_swipe"
        const val REJECT_CANCELED = "canceled"
        const val REJECT_TOUCH_SLOP = "touch_slop_not_reached"
        const val REJECT_SCOPE_INACTIVE = "scope_inactive"
        const val REJECT_HOTZONE_MISS = "hotzone_miss"
        private const val MOVE_THROTTLE_MS = 8L
    }
}
