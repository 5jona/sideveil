package pochita.sideveil.sensor

import pochita.sideveil.protocol.DisplayGeometry
import pochita.sideveil.protocol.EdgeConfig
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.SideVeilConfig
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
 * Pure JVM geometry and release-kinematics helpers for [EdgeGestureStateMachine].
 */
object EdgeGestureMath {
    const val STALE_VELOCITY_WINDOW_MS = 64L
    const val FLING_OPEN_VELOCITY_PX_PER_SEC = 450f
    const val FLING_CANCEL_VELOCITY_PX_PER_SEC = 380f
    const val REVERSE_CANCEL_EDGE_ZONE_PX = 240f

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
        if (releaseVelocityPxPerSec <= -FLING_CANCEL_VELOCITY_PX_PER_SEC) {
            return true
        }
        val cancelZonePx = min(REVERSE_CANCEL_EDGE_ZONE_PX, panelWidthPx * 0.65f)
        val pulledPastCancelZone = maxInwardDx > cancelZonePx + touchSlopPx
        return pulledPastCancelZone && inwardDx <= cancelZonePx && releaseVelocityPxPerSec <= 0f
    }
}
