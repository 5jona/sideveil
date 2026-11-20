package pochita.sideveil.sensor

import pochita.sideveil.protocol.DisplayGeometry
import pochita.sideveil.protocol.EdgeConfig
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.SideveilConfig
import kotlin.math.abs

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
    )
}

/**
 * Pure JVM edge-gesture state machine decoupled from Android MotionEvent / InputMonitor APIs.
 *
 * Tracks inward edge swipes inside the configured hotzone, rejects vertical scrolls and
 * pre-pilfer multi-touch ambiguity, throttles move updates (~125Hz), and zeros stale velocity
 * after finger pause before release.
 */
class EdgeGestureStateMachine(
    private val callbacks: EdgeGestureCallbacks,
) {
    private var tracking = false
    private var pilfered = false
    private var activePointerId = INVALID_POINTER_ID
    private var activeEdge: EdgeSide = EdgeSide.RIGHT
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastEventTimeMs = 0L
    private var lastSignificantMoveTimeMs = 0L
    private var lastVelocityPxPerSec = 0f
    private var lastMoveEmitMs = 0L

    val isTracking: Boolean
        get() = tracking

    val isPilfered: Boolean
        get() = pilfered

    val currentPointerId: Int
        get() = activePointerId

    fun onDown(
        pointerId: Int,
        rawX: Float,
        rawY: Float,
        eventTimeMs: Long,
        config: SideveilConfig,
        geometry: DisplayGeometry,
        scopeActive: Boolean,
        panelState: PanelState,
    ) {
        reset(notifyAbortIfPilfered = true)
        if (!scopeActive || panelState == PanelState.OPEN) {
            return
        }
        if (!isInsideHotzone(rawX, rawY, config.edge, geometry)) {
            return
        }

        tracking = true
        pilfered = false
        activePointerId = pointerId
        activeEdge = config.edge.side
        downX = rawX
        downY = rawY
        lastX = rawX
        lastEventTimeMs = eventTimeMs
        lastSignificantMoveTimeMs = eventTimeMs
        lastVelocityPxPerSec = 0f
        lastMoveEmitMs = 0L
    }

    fun onPointerDown() {
        // Multi-touch cancels edge gesture ambiguity unless already committed via pilferPointers().
        if (tracking && !pilfered) {
            reset(notifyAbortIfPilfered = false)
        }
    }

    fun onMove(
        pointerId: Int,
        rawX: Float,
        rawY: Float,
        eventTimeMs: Long,
        uptimeMs: Long,
        config: SideveilConfig,
        geometry: DisplayGeometry,
    ) {
        if (!tracking) return
        if (pointerId != activePointerId) {
            reset(notifyAbortIfPilfered = true)
            return
        }

        val dx = rawX - downX
        val dy = rawY - downY
        val inwardDx = inwardDelta(activeEdge, dx)
        val touchSlopPx = TOUCH_SLOP_DP * geometry.density

        updateVelocity(rawX, eventTimeMs)
        if (!pilfered && !tryCommitPilfer(dx, dy, inwardDx, touchSlopPx, geometry)) {
            return
        }

        if (uptimeMs - lastMoveEmitMs < MOVE_THROTTLE_MS) {
            return
        }
        lastMoveEmitMs = uptimeMs

        val panelWidthPx = (config.panel.widthDp * geometry.density).coerceAtLeast(MIN_PANEL_WIDTH_PX)
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
        config: SideveilConfig,
        geometry: DisplayGeometry,
    ) {
        if (tracking && liftedPointerId == activePointerId) {
            onUpOrCancel(rawX, eventTimeMs, canceled = false, config = config, geometry = geometry)
        }
    }

    fun onUpOrCancel(
        rawX: Float?,
        eventTimeMs: Long,
        canceled: Boolean,
        config: SideveilConfig,
        geometry: DisplayGeometry,
    ) {
        if (!tracking) return
        if (pilfered) {
            pilfered = false
            val finalX = rawX ?: lastX
            val inwardDx = inwardDelta(activeEdge, finalX - downX)
            val triggerDistPx = config.edge.triggerDistanceDp * geometry.density
            val releaseVelocity =
                resolveReleaseVelocity(
                    trackedVelocityPxPerSec = lastVelocityPxPerSec,
                    lastSignificantMoveTimeMs = lastSignificantMoveTimeMs,
                    upEventTimeMs = eventTimeMs,
                )
            val shouldOpen =
                !canceled &&
                    (inwardDx >= triggerDistPx || releaseVelocity >= FLING_OPEN_VELOCITY_PX_PER_SEC)
            callbacks.onDragEnd(shouldOpen = shouldOpen, velocityPxPerSec = releaseVelocity)
        }
        reset(notifyAbortIfPilfered = false)
    }

    fun onScopeInactive() {
        if (tracking && !pilfered) {
            reset(notifyAbortIfPilfered = false)
        }
    }

    fun reset(notifyAbortIfPilfered: Boolean = true) {
        val wasPilfered = pilfered
        tracking = false
        pilfered = false
        activePointerId = INVALID_POINTER_ID
        lastSignificantMoveTimeMs = 0L
        lastVelocityPxPerSec = 0f
        if (notifyAbortIfPilfered && wasPilfered) {
            callbacks.onDragEnd(shouldOpen = false, velocityPxPerSec = 0f)
        }
    }

    private fun updateVelocity(
        rawX: Float,
        eventTimeMs: Long,
    ) {
        val rawDtMs = eventTimeMs - lastEventTimeMs
        val stepInward = inwardDelta(activeEdge, rawX - lastX)
        if (rawDtMs > 0L) {
            val dtMs = rawDtMs.coerceAtLeast(MIN_VELOCITY_DT_MS)
            val instantVelocity =
                ((stepInward * 1000f) / dtMs.toFloat()).coerceIn(-MAX_VELOCITY_PX_PER_SEC, MAX_VELOCITY_PX_PER_SEC)
            val baseVelocity =
                if (eventTimeMs - lastSignificantMoveTimeMs > STALE_VELOCITY_WINDOW_MS) {
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
        geometry: DisplayGeometry,
    ): Boolean {
        if (abs(dy) > touchSlopPx && abs(dy) > abs(dx) * VERTICAL_SLOPE_REJECT_RATIO) {
            reset(notifyAbortIfPilfered = false)
            return false
        }
        if (inwardDx > touchSlopPx && inwardDx > abs(dy)) {
            pilfered = true
            callbacks.onPilferPointers()
            val startYFraction = (downY / geometry.heightPx.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            callbacks.onDragStart(activeEdge, startYFraction)
            return true
        }
        return false
    }

    companion object {
        const val INVALID_POINTER_ID = -1
        const val STALE_VELOCITY_WINDOW_MS = 64L
        const val FLING_OPEN_VELOCITY_PX_PER_SEC = 450f
        private const val TOUCH_SLOP_DP = 10f
        private const val MOVE_THROTTLE_MS = 8L
        private const val MIN_PANEL_WIDTH_PX = 120f
        private const val MAX_PROGRESS = 1.15f
        private const val MIN_VELOCITY_DT_MS = 4L
        private const val MAX_VELOCITY_PX_PER_SEC = 12_000f
        private const val SIGNIFICANT_MOVE_PX = 0.5f
        private const val VERTICAL_SLOPE_REJECT_RATIO = 1.15f

        fun inwardDelta(
            side: EdgeSide,
            dx: Float,
        ): Float = if (side == EdgeSide.RIGHT) -dx else dx

        fun isInsideHotzone(
            rawX: Float,
            rawY: Float,
            edge: EdgeConfig,
            geometry: DisplayGeometry,
        ): Boolean {
            val hotzoneWidthPx = edge.hotzoneWidthDp * geometry.density
            val xMatch =
                when (edge.side) {
                    EdgeSide.LEFT -> rawX in 0f..hotzoneWidthPx
                    EdgeSide.RIGHT -> rawX in (geometry.widthPx - hotzoneWidthPx)..geometry.widthPx.toFloat()
                }
            if (!xMatch) return false

            val heightPx = edge.heightDp * geometry.density
            val maxTop = (geometry.heightPx - heightPx).coerceAtLeast(0f)
            val topPx = ((edge.topPercent / 100f) * geometry.heightPx).coerceIn(0f, maxTop)
            return rawY in topPx..(topPx + heightPx)
        }

        fun resolveReleaseVelocity(
            trackedVelocityPxPerSec: Float,
            lastSignificantMoveTimeMs: Long,
            upEventTimeMs: Long,
            staleWindowMs: Long = STALE_VELOCITY_WINDOW_MS,
        ): Float =
            if (upEventTimeMs - lastSignificantMoveTimeMs > staleWindowMs) {
                0f
            } else {
                trackedVelocityPxPerSec
            }
    }
}
