package pochita.sideveil.sensor

import pochita.sideveil.protocol.DebugGestureStats

/**
 * Pure-Kotlin accumulator tracking edge-gesture trigger, rejection, hotzone-miss, and
 * `pilferPointers` diagnostics for `sideveild`.
 *
 * Emits immediately on discrete gesture transitions (pilfer, trigger, reject) while throttling
 * high-frequency hotzone-miss updates to avoid UDS chatter during normal touchscreen usage.
 */
class GestureDiagnosticsTracker(
    private val hotzoneReportThrottleMs: Long = DEFAULT_HOTZONE_REPORT_THROTTLE_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private var edgeSwipesTriggered: Long = 0L
    private var edgeSwipesRejected: Long = 0L
    private var hotzoneMisses: Long = 0L
    private var pilferInvocations: Long = 0L
    private var lastRejectReason: String? = null
    private var lastEventTimestampMs: Long = 0L
    private var lastHotzoneReportMs: Long = Long.MIN_VALUE

    @Synchronized
    fun recordHotzoneMiss(): DebugGestureStats? {
        val now = clockMs()
        hotzoneMisses++
        lastEventTimestampMs = now
        val elapsed = if (lastHotzoneReportMs == Long.MIN_VALUE) Long.MAX_VALUE else now - lastHotzoneReportMs
        if (elapsed < hotzoneReportThrottleMs) {
            return null
        }
        lastHotzoneReportMs = now
        return snapshotLocked()
    }

    @Synchronized
    fun recordPilfer(): DebugGestureStats {
        pilferInvocations++
        lastEventTimestampMs = clockMs()
        return snapshotLocked()
    }

    @Synchronized
    fun recordTriggered(): DebugGestureStats {
        edgeSwipesTriggered++
        lastEventTimestampMs = clockMs()
        return snapshotLocked()
    }

    @Synchronized
    fun recordRejected(reason: String): DebugGestureStats {
        edgeSwipesRejected++
        lastRejectReason = reason
        lastEventTimestampMs = clockMs()
        return snapshotLocked()
    }

    @Synchronized
    fun snapshot(): DebugGestureStats = snapshotLocked()

    private fun snapshotLocked(): DebugGestureStats =
        DebugGestureStats(
            edgeSwipesTriggered = edgeSwipesTriggered,
            edgeSwipesRejected = edgeSwipesRejected,
            hotzoneMisses = hotzoneMisses,
            pilferInvocations = pilferInvocations,
            lastRejectReason = lastRejectReason,
            lastEventTimestampMs = lastEventTimestampMs,
        )

    companion object {
        const val DEFAULT_HOTZONE_REPORT_THROTTLE_MS = 1_000L
    }
}
