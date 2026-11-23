package pochita.sideveil.sensor

import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelState
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure JVM edge-gesture state machine decoupled from Android MotionEvent / InputMonitor APIs.
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

    private var tracking = false
    private var trackingStatusBarPullDown = false
    private var pilfered = false
    private var activePointerId = INVALID_POINTER_ID
    private var activeEdge: EdgeSide = EdgeSide.RIGHT
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var maxInwardDx = 0f
    private var lastEventTimeMs = 0L
    private var lastSignificantMoveTimeMs = 0L
    private var lastVelocityPxPerSec = 0f
    private var lastMoveEmitMs = 0L
    private var lastFullscreenHintMs = Long.MIN_VALUE

    val isTracking: Boolean
        get() = tracking || trackingStatusBarPullDown

    val isPilfered: Boolean
        get() = pilfered

    val isRepositioning: Boolean
        get() = repositionTracker.active

    val currentPointerId: Int
        get() = activePointerId

    fun onDown(
        pointerId: Int,
        rawX: Float,
        rawY: Float,
        eventTimeMs: Long,
        env: EdgeGestureEnv,
    ) {
        reset(notifyAbortIfPilfered = true)
        if (env.panelState == PanelState.OPEN) {
            if (EdgeGestureMath.isInsideStatusBarTriggerZone(rawY, env.geometry)) {
                trackingStatusBarPullDown = true
                activePointerId = pointerId
                downX = rawX
                downY = rawY
                lastX = rawX
                lastY = rawY
            }
            return
        }
        if (!env.scopeActive) {
            return
        }
        if (!EdgeGestureMath.isInsideHotzone(rawX, rawY, env.config.edge, env.geometry)) {
            callbacks.onSwipeRejected(REJECT_HOTZONE_MISS, hotzoneMiss = true)
            return
        }

        tracking = true
        pilfered = false
        activePointerId = pointerId
        activeEdge = env.config.edge.side
        downX = rawX
        downY = rawY
        lastX = rawX
        lastY = rawY
        maxInwardDx = 0f
        lastEventTimeMs = eventTimeMs
        lastSignificantMoveTimeMs = eventTimeMs
        lastVelocityPxPerSec = 0f
        lastMoveEmitMs = 0L

        if (env.config.edge.handleVisible) {
            callbacks.onScheduleLongPress(pointerId)
        }
    }

    fun onLongPressTimeout(
        pointerId: Int,
        env: EdgeGestureEnv,
    ) {
        val eligible = tracking && !pilfered && !repositionTracker.active
        val envAllows = env.scopeActive && env.panelState != PanelState.OPEN && env.config.edge.handleVisible
        if (!eligible || pointerId != activePointerId || !envAllows) {
            return
        }
        val touchSlopPx = TOUCH_SLOP_DP * env.geometry.density
        if (hypot((lastX - downX).toDouble(), (lastY - downY).toDouble()) > touchSlopPx) {
            return
        }
        pilfered = true
        callbacks.onPilferPointers()
        val (side, topPercent) = repositionTracker.begin(env.config.edge, lastY, env.geometry)
        callbacks.onEdgeRepositionStart(side, topPercent)
    }

    fun onPointerDown() {
        callbacks.onCancelLongPress()
        if (tracking && !pilfered) {
            callbacks.onSwipeRejected(REJECT_MULTI_TOUCH)
            reset(notifyAbortIfPilfered = false)
        }
    }

    fun onMove(
        pointerId: Int,
        rawX: Float,
        rawY: Float,
        eventTimeMs: Long,
        uptimeMs: Long,
        env: EdgeGestureEnv,
    ) {
        if (trackingStatusBarPullDown) {
            handleStatusBarPullMove(pointerId, rawX, rawY, env)
            return
        }
        if (!tracking) return
        if (pointerId != activePointerId) {
            reset(notifyAbortIfPilfered = true)
            return
        }

        lastY = rawY
        if (repositionTracker.active) {
            repositionTracker.onMove(rawX, rawY, uptimeMs, env.config.edge, env.geometry)?.let { (side, top) ->
                callbacks.onEdgeRepositionMove(side, top)
            }
            lastX = rawX
            return
        }

        val dx = rawX - downX
        val dy = rawY - downY
        val inwardDx = EdgeGestureMath.inwardDelta(activeEdge, dx)
        val touchSlopPx = TOUCH_SLOP_DP * env.geometry.density
        if (hypot(dx.toDouble(), dy.toDouble()) > touchSlopPx) {
            callbacks.onCancelLongPress()
        }

        updateVelocity(rawX, eventTimeMs)
        if (!pilfered && !tryCommitPilfer(dx, dy, inwardDx, touchSlopPx, eventTimeMs, env)) {
            return
        }
        if (inwardDx > maxInwardDx) {
            maxInwardDx = inwardDx
        }

        if (uptimeMs - lastMoveEmitMs < MOVE_THROTTLE_MS) {
            return
        }
        lastMoveEmitMs = uptimeMs

        val panelWidthPx = (env.config.panel.widthDp * env.geometry.density).coerceAtLeast(MIN_PANEL_WIDTH_PX)
        val progress = ((inwardDx - touchSlopPx) / panelWidthPx).coerceIn(0f, MAX_PROGRESS)
        callbacks.onDragMove(
            side = activeEdge,
            progress = progress,
            rawX = rawX,
            rawY = rawY,
            deltaX = inwardDx,
        )
    }

    fun onPointerUp(
        liftedPointerId: Int,
        rawX: Float,
        eventTimeMs: Long,
        env: EdgeGestureEnv,
    ) {
        if (isTracking && liftedPointerId == activePointerId) {
            onUpOrCancel(rawX, eventTimeMs, canceled = false, env = env)
        }
    }

    fun onUpOrCancel(
        rawX: Float?,
        eventTimeMs: Long,
        canceled: Boolean,
        env: EdgeGestureEnv,
    ) {
        callbacks.onCancelLongPress()
        if (trackingStatusBarPullDown) {
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
            val finalX = rawX ?: lastX
            val inwardDx = EdgeGestureMath.inwardDelta(activeEdge, finalX - downX)
            val triggerDistPx = env.config.edge.triggerDistanceDp * env.geometry.density
            val panelWidthPx = (env.config.panel.widthDp * env.geometry.density).coerceAtLeast(MIN_PANEL_WIDTH_PX)
            val releaseVelocity =
                EdgeGestureMath.resolveReleaseVelocity(
                    trackedVelocityPxPerSec = lastVelocityPxPerSec,
                    lastSignificantMoveTimeMs = lastSignificantMoveTimeMs,
                    upEventTimeMs = eventTimeMs,
                )
            val reverseCanceled =
                EdgeGestureMath.isReversePullCanceled(
                    inwardDx = inwardDx,
                    maxInwardDx = maxInwardDx,
                    releaseVelocityPxPerSec = releaseVelocity,
                    panelWidthPx = panelWidthPx,
                    touchSlopPx = TOUCH_SLOP_DP * env.geometry.density,
                )
            val shouldOpen =
                !canceled &&
                    !reverseCanceled &&
                    (inwardDx >= triggerDistPx || releaseVelocity >= EdgeGestureMath.FLING_OPEN_VELOCITY_PX_PER_SEC)
            if (!shouldOpen) {
                val rejectReason =
                    when {
                        reverseCanceled -> REJECT_REVERSE_PULL
                        canceled -> REJECT_CANCELED
                        else -> REJECT_SHORT_SWIPE
                    }
                callbacks.onSwipeRejected(rejectReason)
            }
            callbacks.onDragEnd(
                shouldOpen = shouldOpen,
                velocityPxPerSec = releaseVelocity,
                canceled = canceled || reverseCanceled,
            )
        } else {
            callbacks.onSwipeRejected(if (canceled) REJECT_CANCELED else REJECT_TOUCH_SLOP)
        }
        reset(notifyAbortIfPilfered = false)
    }

    fun onScopeInactive() {
        callbacks.onCancelLongPress()
        if (tracking && !pilfered) {
            callbacks.onSwipeRejected(REJECT_SCOPE_INACTIVE)
            reset(notifyAbortIfPilfered = false)
        }
    }

    fun reset(notifyAbortIfPilfered: Boolean = true) {
        callbacks.onCancelLongPress()
        val wasRepositioning = repositionTracker.active
        val finishedReposition = repositionTracker.finish()
        val wasPilfered = pilfered
        tracking = false
        trackingStatusBarPullDown = false
        pilfered = false
        activePointerId = INVALID_POINTER_ID
        maxInwardDx = 0f
        lastSignificantMoveTimeMs = 0L
        lastVelocityPxPerSec = 0f
        if (notifyAbortIfPilfered && wasRepositioning && finishedReposition != null) {
            callbacks.onEdgeRepositionEnd(finishedReposition.first, finishedReposition.second, committed = false)
        } else if (notifyAbortIfPilfered && wasPilfered) {
            callbacks.onDragEnd(shouldOpen = false, velocityPxPerSec = 0f, canceled = true)
        }
    }

    private fun handleStatusBarPullMove(
        pointerId: Int,
        rawX: Float,
        rawY: Float,
        env: EdgeGestureEnv,
    ) {
        if (pointerId != activePointerId || env.panelState != PanelState.OPEN) {
            reset(notifyAbortIfPilfered = false)
            return
        }
        lastX = rawX
        lastY = rawY
        val dx = rawX - downX
        val dy = rawY - downY
        val pullSlopPx = EdgeGestureMath.STATUS_BAR_PULL_SLOP_DP * env.geometry.density
        if (dy >= pullSlopPx && dy > abs(dx) * EdgeGestureMath.STATUS_BAR_PULL_SLOPE_RATIO) {
            reset(notifyAbortIfPilfered = false)
            onStatusBarPullDown()
        }
    }

    private fun updateVelocity(
        rawX: Float,
        eventTimeMs: Long,
    ) {
        val rawDtMs = eventTimeMs - lastEventTimeMs
        val stepInward = EdgeGestureMath.inwardDelta(activeEdge, rawX - lastX)
        if (rawDtMs > 0L) {
            val dtMs = rawDtMs.coerceAtLeast(MIN_VELOCITY_DT_MS)
            val instantVelocity =
                ((stepInward * 1000f) / dtMs.toFloat()).coerceIn(-MAX_VELOCITY_PX_PER_SEC, MAX_VELOCITY_PX_PER_SEC)
            val baseVelocity =
                if (eventTimeMs - lastSignificantMoveTimeMs > EdgeGestureMath.STALE_VELOCITY_WINDOW_MS) {
                    0f
                } else {
                    lastVelocityPxPerSec
                }
            lastVelocityPxPerSec = baseVelocity * 0.4f + instantVelocity * 0.6f
            lastEventTimeMs = eventTimeMs
        }
        if (abs(stepInward) >= SIGNIFICANT_MOVE_PX) {
            lastSignificantMoveTimeMs = eventTimeMs
        }
        lastX = rawX
    }

    private fun tryCommitPilfer(
        dx: Float,
        dy: Float,
        inwardDx: Float,
        touchSlopPx: Float,
        eventTimeMs: Long,
        env: EdgeGestureEnv,
    ): Boolean {
        if (abs(dy) > touchSlopPx && abs(dy) > abs(dx) * VERTICAL_SLOPE_REJECT_RATIO) {
            callbacks.onSwipeRejected(REJECT_VERTICAL_SCROLL)
            reset(notifyAbortIfPilfered = false)
            return false
        }
        if (inwardDx <= touchSlopPx || inwardDx <= abs(dy)) {
            return false
        }
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
        val startYFraction =
            (
                downY /
                    env.geometry.heightPx
                        .coerceAtLeast(1)
                        .toFloat()
            ).coerceIn(0f, 1f)
        callbacks.onDragStart(activeEdge, startYFraction)
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
        private const val TOUCH_SLOP_DP = 10f
        private const val MOVE_THROTTLE_MS = 8L
        private const val MIN_PANEL_WIDTH_PX = 120f
        private const val MAX_PROGRESS = 1.15f
        private const val MIN_VELOCITY_DT_MS = 4L
        private const val MAX_VELOCITY_PX_PER_SEC = 12_000f
        private const val SIGNIFICANT_MOVE_PX = 0.5f
        private const val VERTICAL_SLOPE_REJECT_RATIO = 1.15f
    }
}
