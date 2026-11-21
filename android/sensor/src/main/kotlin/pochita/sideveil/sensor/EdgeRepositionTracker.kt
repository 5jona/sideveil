package pochita.sideveil.sensor

import pochita.sideveil.protocol.DisplayGeometry
import pochita.sideveil.protocol.EdgeConfig
import pochita.sideveil.protocol.EdgeSide
import kotlin.math.roundToInt

/**
 * Pure JVM tracker for long-press edge-handle repositioning (`topPercent` dragging and
 * horizontal side flip with hysteresis).
 */
internal class EdgeRepositionTracker {
    var active: Boolean = false
        private set
    var currentSide: EdgeSide = EdgeSide.RIGHT
        private set
    var currentTopPercent: Int = 32
        private set

    private var lastEmitMs: Long = 0L

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
     * Updates side & topPercent on move. Returns the updated `(side, topPercent)` if throttled
     * interval elapsed or the values changed meaningfully; null otherwise.
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

        if (!sideChanged && !topChanged) {
            return null
        }
        if (!sideChanged && uptimeMs - lastEmitMs < REPOSITION_THROTTLE_MS) {
            return null
        }
        lastEmitMs = uptimeMs
        return currentSide to currentTopPercent
    }

    fun finish(): Pair<EdgeSide, Int>? {
        if (!active) return null
        active = false
        return currentSide to currentTopPercent
    }

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
