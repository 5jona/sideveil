package pochita.sideveil.sensor.gesture

import pochita.sideveil.protocol.DisplayGeometry
import pochita.sideveil.protocol.EdgeConfig
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.SideVeilConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Snapshot of active environment inputs passed to [EdgeGestureStateMachine].
 */
data class EdgeGestureEnv(
    val config: SideVeilConfig,
    val geometry: DisplayGeometry,
    val scopeActive: Boolean = true,
    val panelState: PanelState = PanelState.CLOSED,
    val fullscreenActive: Boolean = false,
)

/**
 * Updated velocity state after processing one horizontal move sample.
 */
internal data class VelocityStepResult(
    val velocityPxPerSec: Float,
    val eventTimeMs: Long,
    val significantMoveTimeMs: Long,
)

/**
 * Computed release outcome when a pilfered edge drag ends or is canceled.
 */
internal data class PilferReleaseOutcome(
    val shouldOpen: Boolean,
    val releaseVelocityPxPerSec: Float,
    val canceled: Boolean,
    val rejectReason: String?,
)

/**
 * Pure JVM geometry and release-kinematics helpers for [EdgeGestureStateMachine].
 */
object EdgeGestureMath {
    const val STALE_VELOCITY_WINDOW_MS = 64L
    const val FLING_OPEN_VELOCITY_PX_PER_SEC = 450f
    const val FLING_CANCEL_VELOCITY_PX_PER_SEC = 380f
    const val REVERSE_CANCEL_EDGE_ZONE_PX = 240f
    const val STATUS_BAR_TRIGGER_ZONE_DP = 44f
    const val STATUS_BAR_TRIGGER_ZONE_MIN_PX = 120f
    const val STATUS_BAR_TRIGGER_ZONE_MAX_HEIGHT_FRACTION = 0.10f
    const val STATUS_BAR_PULL_SLOP_DP = 12f
    const val STATUS_BAR_PULL_SLOPE_RATIO = 1.2f
    internal const val TOUCH_SLOP_DP = 10f
    internal const val MIN_PANEL_WIDTH_PX = 120f
    internal const val MAX_PROGRESS = 1.15f
    private const val MIN_VELOCITY_DT_MS = 4L
    private const val MAX_VELOCITY_PX_PER_SEC = 12_000f
    private const val SIGNIFICANT_MOVE_PX = 0.5f
    private const val VERTICAL_SLOPE_REJECT_RATIO = 1.15f

    /** Computes the top status-bar pull-down trigger zone height in pixels. */
    fun statusBarTriggerZonePx(geometry: DisplayGeometry): Float {
        val preferredPx = max(STATUS_BAR_TRIGGER_ZONE_DP * geometry.density, STATUS_BAR_TRIGGER_ZONE_MIN_PX)
        val maxFractionPx = geometry.heightPx.coerceAtLeast(0) * STATUS_BAR_TRIGGER_ZONE_MAX_HEIGHT_FRACTION
        return min(preferredPx, maxFractionPx)
    }

    /** Returns `true` when [rawY] falls within the top status-bar pull-down trigger zone. */
    fun isInsideStatusBarTriggerZone(
        rawY: Float,
        geometry: DisplayGeometry,
    ): Boolean = rawY in 0f..statusBarTriggerZonePx(geometry)

    /** Returns `true` when a downward drag from the status bar exceeds the pull-down slop and slope. */
    fun isStatusBarPullTriggered(
        dx: Float,
        dy: Float,
        geometry: DisplayGeometry,
    ): Boolean {
        val pullSlopPx = STATUS_BAR_PULL_SLOP_DP * geometry.density
        return dy >= pullSlopPx && dy > abs(dx) * STATUS_BAR_PULL_SLOPE_RATIO
    }

    /** Converts a raw horizontal delta [dx] into an inward delta relative to [side]. */
    fun inwardDelta(
        side: EdgeSide,
        dx: Float,
    ): Float = if (side == EdgeSide.RIGHT) -dx else dx

    /** Returns `true` when `([rawX], [rawY])` lies inside the configured edge trigger hotzone. */
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

    internal fun isVerticalScrollRejected(
        dx: Float,
        dy: Float,
        touchSlopPx: Float,
    ): Boolean = abs(dy) > touchSlopPx && abs(dy) > abs(dx) * VERTICAL_SLOPE_REJECT_RATIO

    internal fun computeStartYFraction(
        downY: Float,
        geometry: DisplayGeometry,
    ): Float = (downY / geometry.heightPx.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

    internal fun computeDragProgress(
        inwardDx: Float,
        touchSlopPx: Float,
        env: EdgeGestureEnv,
    ): Float {
        val panelWidthPx = (env.config.panel.widthDp * env.geometry.density).coerceAtLeast(MIN_PANEL_WIDTH_PX)
        return ((inwardDx - touchSlopPx) / panelWidthPx).coerceIn(0f, MAX_PROGRESS)
    }

    internal fun stepVelocity(
        side: EdgeSide,
        rawX: Float,
        lastX: Float,
        eventTimeMs: Long,
        lastEventTimeMs: Long,
        lastSignificantMoveTimeMs: Long,
        lastVelocityPxPerSec: Float,
    ): VelocityStepResult {
        val rawDtMs = eventTimeMs - lastEventTimeMs
        val stepInward = inwardDelta(side, rawX - lastX)
        var nextVelocity = lastVelocityPxPerSec
        var nextEventTime = lastEventTimeMs
        if (rawDtMs > 0L) {
            val dtMs = rawDtMs.coerceAtLeast(MIN_VELOCITY_DT_MS)
            val instantVelocity =
                ((stepInward * 1000f) / dtMs.toFloat()).coerceIn(-MAX_VELOCITY_PX_PER_SEC, MAX_VELOCITY_PX_PER_SEC)
            val baseVelocity =
                if (eventTimeMs - lastSignificantMoveTimeMs > STALE_VELOCITY_WINDOW_MS) 0f else lastVelocityPxPerSec
            nextVelocity = baseVelocity * 0.4f + instantVelocity * 0.6f
            nextEventTime = eventTimeMs
        }
        val nextSigTime =
            if (abs(stepInward) >= SIGNIFICANT_MOVE_PX) eventTimeMs else lastSignificantMoveTimeMs
        return VelocityStepResult(nextVelocity, nextEventTime, nextSigTime)
    }

    /** Decay-checks [trackedVelocityPxPerSec] against [staleWindowMs] at release time. */
    fun resolveReleaseVelocity(
        trackedVelocityPxPerSec: Float,
        lastSignificantMoveTimeMs: Long,
        upEventTimeMs: Long,
        staleWindowMs: Long = STALE_VELOCITY_WINDOW_MS,
    ): Float = if (upEventTimeMs - lastSignificantMoveTimeMs > staleWindowMs) 0f else trackedVelocityPxPerSec

    /**
     * ColorOS-inspired reverse-pull cancellation: if the finger pulled inward and then
     * retreated back inside the `240px` edge cancel zone (or flicked outward), cancel opening.
     */
    fun isReversePullCanceled(
        inwardDx: Float,
        maxInwardDx: Float,
        releaseVelocityPxPerSec: Float,
        panelWidthPx: Float,
        touchSlopPx: Float,
    ): Boolean {
        if (releaseVelocityPxPerSec <= -FLING_CANCEL_VELOCITY_PX_PER_SEC) return true
        val cancelZonePx = min(REVERSE_CANCEL_EDGE_ZONE_PX, panelWidthPx * 0.65f)
        val pulledPastCancelZone = maxInwardDx > cancelZonePx + touchSlopPx
        return pulledPastCancelZone && inwardDx <= cancelZonePx && releaseVelocityPxPerSec <= 0f
    }

    internal fun evaluatePilferRelease(
        side: EdgeSide,
        downX: Float,
        finalX: Float,
        maxInwardDx: Float,
        trackedVelocityPxPerSec: Float,
        lastSignificantMoveTimeMs: Long,
        upEventTimeMs: Long,
        canceled: Boolean,
        env: EdgeGestureEnv,
    ): PilferReleaseOutcome {
        val inwardDx = inwardDelta(side, finalX - downX)
        val triggerDistPx = env.config.edge.triggerDistanceDp * env.geometry.density
        val panelWidthPx = (env.config.panel.widthDp * env.geometry.density).coerceAtLeast(MIN_PANEL_WIDTH_PX)
        val releaseVelocity =
            resolveReleaseVelocity(trackedVelocityPxPerSec, lastSignificantMoveTimeMs, upEventTimeMs)
        val reverseCanceled =
            isReversePullCanceled(
                inwardDx = inwardDx,
                maxInwardDx = maxInwardDx,
                releaseVelocityPxPerSec = releaseVelocity,
                panelWidthPx = panelWidthPx,
                touchSlopPx = TOUCH_SLOP_DP * env.geometry.density,
            )
        val shouldOpen =
            !canceled &&
                !reverseCanceled &&
                (inwardDx >= triggerDistPx || releaseVelocity >= FLING_OPEN_VELOCITY_PX_PER_SEC)
        val rejectReason =
            when {
                shouldOpen -> null
                reverseCanceled -> EdgeGestureStateMachine.REJECT_REVERSE_PULL
                canceled -> EdgeGestureStateMachine.REJECT_CANCELED
                else -> EdgeGestureStateMachine.REJECT_SHORT_SWIPE
            }
        return PilferReleaseOutcome(
            shouldOpen = shouldOpen,
            releaseVelocityPxPerSec = releaseVelocity,
            canceled = canceled || reverseCanceled,
            rejectReason = rejectReason,
        )
    }
}
