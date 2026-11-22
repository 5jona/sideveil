package pochita.sideveil.host

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowFrameMetricsTrackerTest {
    @Test
    fun `empty ring buffer returns zeroed metrics with rounded fallback deadline`() {
        val ring = FrameMetricsRingBuffer(capacity = 120)
        val snap = ring.snapshot(fallbackDeadlineMs = 1_000.0 / 120.0)
        assertEquals(0, snap.sampleCount)
        assertEquals(0.0, snap.p50Ms, 0.001)
        assertEquals(0.0, snap.p95Ms, 0.001)
        assertEquals(0.0, snap.p99Ms, 0.001)
        assertEquals(0, snap.jankCount)
        assertEquals(8.33, snap.deadlineMs, 0.001)
    }

    @Test
    fun `computes p50, p95, p99, drawP95, gpuP95, and jankCount accurately`() {
        val ring = FrameMetricsRingBuffer(capacity = 120)
        val deadlineMs = 8.333333
        // Record 100 samples: 1..90 are smooth (<= 8.33ms), 91..100 exceed deadline (10 janks).
        for (i in 1..100) {
            val totalMs = if (i <= 90) i * 0.09 else 8.5 + (i - 90) * 0.5
            val drawMs = i * 0.04
            val gpuMs = i * 0.05
            ring.record(totalMs = totalMs, drawMs = drawMs, gpuMs = gpuMs, deadlineMs = deadlineMs)
        }

        val snap = ring.snapshot()
        assertEquals(100, snap.sampleCount)
        assertEquals(10, snap.jankCount)
        assertEquals(4.5, snap.p50Ms, 0.01)
        assertEquals(11.0, snap.p95Ms, 0.01)
        assertEquals(13.0, snap.p99Ms, 0.01)
        assertEquals(13.5, snap.maxMs, 0.01)
        assertEquals(3.8, snap.drawP95Ms, 0.01)
        assertEquals(4.75, snap.gpuP95Ms, 0.01)
        assertEquals(8.33, snap.deadlineMs, 0.01)
    }

    @Test
    fun `ring buffer overwrites oldest samples beyond 120 frames and resets cleanly`() {
        val ring = FrameMetricsRingBuffer(capacity = 120)
        val deadlineMs = 8.33
        // Seed 10 janky frames first.
        repeat(10) {
            ring.record(totalMs = 20.0, drawMs = 6.0, gpuMs = 8.0, deadlineMs = deadlineMs)
        }
        assertEquals(10, ring.snapshot().jankCount)

        // Push 120 smooth frames to completely age out the initial jank burst.
        repeat(120) {
            ring.record(totalMs = 4.0, drawMs = 1.2, gpuMs = 1.5, deadlineMs = deadlineMs)
        }
        val steady = ring.snapshot()
        assertEquals(120, steady.sampleCount)
        assertEquals(0, steady.jankCount)
        assertEquals(4.0, steady.p50Ms, 0.001)
        assertEquals(4.0, steady.p99Ms, 0.001)

        // Invalid non-positive / NaN samples must be ignored.
        ring.record(totalMs = 0.0, drawMs = 0.0, gpuMs = 0.0, deadlineMs = deadlineMs)
        ring.record(totalMs = Double.NaN, drawMs = -1.0, gpuMs = Double.POSITIVE_INFINITY, deadlineMs = deadlineMs)
        assertEquals(120, ring.snapshot().sampleCount)

        ring.reset()
        val afterReset = ring.snapshot(fallbackDeadlineMs = 16.666)
        assertEquals(0, afterReset.sampleCount)
        assertEquals(0, afterReset.jankCount)
        assertEquals(16.67, afterReset.deadlineMs, 0.001)
    }
}
