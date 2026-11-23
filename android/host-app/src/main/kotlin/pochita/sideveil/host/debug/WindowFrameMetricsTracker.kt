package pochita.sideveil.host.debug

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import pochita.sideveil.protocol.DebugFrameMetricsSnapshot
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * Pure-Kotlin fixed-capacity ring buffer storing frame timings (total, draw, GPU) and jank
 * indicators against the display refresh deadline. Kept Android-free for JVM unit tests.
 */
class FrameMetricsRingBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "FrameMetricsRingBuffer capacity must be positive" }
    }

    private val totalSamples = DoubleArray(capacity)
    private val drawSamples = DoubleArray(capacity)
    private val gpuSamples = DoubleArray(capacity)
    private val jankFlags = BooleanArray(capacity)

    private var writeIndex = 0
    private var count = 0
    private var lastDeadlineMs = 0.0

    /** Appends one frame timing sample (`totalMs`, `drawMs`, `gpuMs`, `deadlineMs`) into the ring buffer. */
    fun record(
        totalMs: Double,
        drawMs: Double,
        gpuMs: Double,
        deadlineMs: Double,
    ) {
        if (!totalMs.isFinite() || totalMs <= 0.0) return
        val cleanDraw = if (drawMs.isFinite() && drawMs >= 0.0) drawMs else 0.0
        val cleanGpu = if (gpuMs.isFinite() && gpuMs >= 0.0) gpuMs else 0.0
        val cleanDeadline = if (deadlineMs.isFinite() && deadlineMs > 0.0) deadlineMs else 0.0

        totalSamples[writeIndex] = totalMs
        drawSamples[writeIndex] = cleanDraw
        gpuSamples[writeIndex] = cleanGpu
        jankFlags[writeIndex] = cleanDeadline > 0.0 && totalMs > cleanDeadline

        writeIndex = (writeIndex + 1) % capacity
        if (count < capacity) count++
        if (cleanDeadline > 0.0) lastDeadlineMs = cleanDeadline
    }

    /** Computes percentile (`p50`, `p95`, `p99`, `max`) and jank statistics across buffered frames. */
    fun snapshot(fallbackDeadlineMs: Double = 0.0): DebugFrameMetricsSnapshot {
        val effectiveDeadline =
            when {
                lastDeadlineMs > 0.0 -> lastDeadlineMs
                fallbackDeadlineMs.isFinite() && fallbackDeadlineMs > 0.0 -> fallbackDeadlineMs
                else -> 0.0
            }
        if (count == 0) {
            return DebugFrameMetricsSnapshot(deadlineMs = roundMs(effectiveDeadline))
        }

        val sortedTotal = totalSamples.copyOf(count).apply { sort() }
        val sortedDraw = drawSamples.copyOf(count).apply { sort() }
        val sortedGpu = gpuSamples.copyOf(count).apply { sort() }
        var jankCount = 0
        for (i in 0 until count) {
            if (jankFlags[i]) jankCount++
        }

        return DebugFrameMetricsSnapshot(
            sampleCount = count,
            p50Ms = roundMs(percentile(sortedTotal, 0.50)),
            p95Ms = roundMs(percentile(sortedTotal, 0.95)),
            p99Ms = roundMs(percentile(sortedTotal, 0.99)),
            maxMs = roundMs(sortedTotal.last()),
            drawP95Ms = roundMs(percentile(sortedDraw, 0.95)),
            gpuP95Ms = roundMs(percentile(sortedGpu, 0.95)),
            jankCount = jankCount,
            deadlineMs = roundMs(effectiveDeadline),
        )
    }

    /** Clears all buffered frame samples. */
    fun reset() {
        writeIndex = 0
        count = 0
        lastDeadlineMs = 0.0
    }

    companion object {
        const val DEFAULT_CAPACITY = 120

        /** Returns the value at [quantile] (`0.0..1.0`) in a pre-sorted array. */
        fun percentile(
            sorted: DoubleArray,
            quantile: Double,
        ): Double {
            if (sorted.isEmpty()) return 0.0
            val clamped = quantile.coerceIn(0.0, 1.0)
            val index = (ceil(clamped * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }

        /** Rounds a millisecond duration to two decimal places. */
        fun roundMs(ms: Double): Double = if (ms.isFinite()) (ms * 100.0).roundToLong() / 100.0 else 0.0
    }
}

/**
 * Attaches [Window.OnFrameMetricsAvailableListener] callbacks on a dedicated background
 * [HandlerThread] to SideVeil overlay windows (`panel`, `pinned`, `hud`), keeping a bounded
 * 120-frame ring buffer for live debug inspection without blocking the UI thread.
 */
class WindowFrameMetricsTracker(
    private val capacity: Int = FrameMetricsRingBuffer.DEFAULT_CAPACITY,
) {
    private data class WindowRegistration(
        val window: Window,
        val listener: Window.OnFrameMetricsAvailableListener,
        var attachedToWindow: Boolean = false,
        var pendingAttachListener: View.OnAttachStateChangeListener? = null,
    )

    private val lock = Any()
    private val combinedBuffer = FrameMetricsRingBuffer(capacity)
    private val perWindowBuffers = LinkedHashMap<String, FrameMetricsRingBuffer>()
    private val registrations = LinkedHashMap<String, WindowRegistration>()
    private var metricsThread: HandlerThread? = null
    private var metricsHandler: Handler? = null
    private var destroyed = false

    /** Registers frame-metrics collection for [window] under [label]. */
    fun attach(
        label: String,
        window: Window,
    ) {
        val handler: Handler
        val registration: WindowRegistration
        synchronized(lock) {
            if (destroyed) return
            val existing = registrations[label]
            if (existing != null && existing.window === window) return
            existing?.let(::unregisterLocked)
            handler = ensureHandlerLocked()
            perWindowBuffers.putIfAbsent(label, FrameMetricsRingBuffer(capacity))
            val listener =
                Window.OnFrameMetricsAvailableListener { win, metrics, _ ->
                    onFrameMetrics(label, win, metrics)
                }
            registration = WindowRegistration(window = window, listener = listener)
            registrations[label] = registration
        }

        val decorView = window.decorView
        if (decorView.isAttachedToWindow) {
            registerNow(label, registration, handler)
        } else {
            val attachListener =
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        v.removeOnAttachStateChangeListener(this)
                        registerNow(label, registration, handler)
                    }

                    override fun onViewDetachedFromWindow(v: View) = Unit
                }
            synchronized(lock) {
                if (registrations[label] === registration && !destroyed) {
                    registration.pendingAttachListener = attachListener
                    decorView.addOnAttachStateChangeListener(attachListener)
                }
            }
        }
    }

    /** Returns an aggregate [DebugFrameMetricsSnapshot] across all tracked overlay windows. */
    fun snapshot(fallbackDeadlineMs: Double = 0.0): DebugFrameMetricsSnapshot =
        synchronized(lock) { combinedBuffer.snapshot(fallbackDeadlineMs) }

    /** Returns per-window [DebugFrameMetricsSnapshot] entries keyed by window label. */
    fun windowSnapshots(fallbackDeadlineMs: Double = 0.0): Map<String, DebugFrameMetricsSnapshot> =
        synchronized(lock) {
            perWindowBuffers.mapValues { (_, buffer) -> buffer.snapshot(fallbackDeadlineMs) }
        }

    /** Resets both combined and per-window ring buffers. */
    fun reset() {
        synchronized(lock) {
            combinedBuffer.reset()
            perWindowBuffers.values.forEach(FrameMetricsRingBuffer::reset)
        }
    }

    /** Unregisters all window listeners without stopping the background handler thread. */
    fun detach() {
        synchronized(lock) {
            registrations.values.forEach(::unregisterLocked)
            registrations.clear()
        }
    }

    /** Detaches all window listeners and quits the background frame-metrics thread. */
    fun destroy() {
        val threadToQuit: HandlerThread?
        synchronized(lock) {
            destroyed = true
            registrations.values.forEach(::unregisterLocked)
            registrations.clear()
            combinedBuffer.reset()
            perWindowBuffers.clear()
            metricsHandler = null
            threadToQuit = metricsThread
            metricsThread = null
        }
        runCatching { threadToQuit?.quitSafely() }
    }

    private fun registerNow(
        label: String,
        registration: WindowRegistration,
        handler: Handler,
    ) {
        synchronized(lock) {
            if (destroyed || registrations[label] !== registration || registration.attachedToWindow) return
            registration.pendingAttachListener = null
            runCatching {
                registration.window.addOnFrameMetricsAvailableListener(registration.listener, handler)
                registration.attachedToWindow = true
            }.onFailure { err ->
                Log.w(TAG, "Failed to attach FrameMetrics listener for $label: ${err.message}")
            }
        }
    }

    private fun unregisterLocked(registration: WindowRegistration) {
        registration.pendingAttachListener?.let { listener ->
            runCatching { registration.window.decorView.removeOnAttachStateChangeListener(listener) }
            registration.pendingAttachListener = null
        }
        if (registration.attachedToWindow) {
            runCatching { registration.window.removeOnFrameMetricsAvailableListener(registration.listener) }
            registration.attachedToWindow = false
        }
    }

    private fun ensureHandlerLocked(): Handler {
        metricsHandler?.let { return it }
        val thread = HandlerThread("SideVeilFrameMetrics", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val handler = Handler(thread.looper)
        metricsThread = thread
        metricsHandler = handler
        return handler
    }

    private fun onFrameMetrics(
        label: String,
        window: Window,
        metrics: FrameMetrics,
    ) {
        val totalNs = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        if (totalNs <= 0L) return
        val drawNs = metrics.getMetric(FrameMetrics.DRAW_DURATION).coerceAtLeast(0L)
        val gpuNs = metrics.getMetric(FrameMetrics.GPU_DURATION).coerceAtLeast(0L)
        val deadlineNs = metrics.getMetric(FrameMetrics.DEADLINE)
        val deadlineMs = if (deadlineNs > 0L) deadlineNs / NS_PER_MS else resolveDisplayDeadlineMs(window)
        val totalMs = totalNs / NS_PER_MS
        val drawMs = drawNs / NS_PER_MS
        val gpuMs = gpuNs / NS_PER_MS

        synchronized(lock) {
            if (destroyed) return
            combinedBuffer.record(totalMs, drawMs, gpuMs, deadlineMs)
            perWindowBuffers
                .getOrPut(label) { FrameMetricsRingBuffer(capacity) }
                .record(totalMs, drawMs, gpuMs, deadlineMs)
        }
    }

    private fun resolveDisplayDeadlineMs(window: Window): Double {
        val hz =
            runCatching { window.decorView.display?.refreshRate }
                .getOrNull()
                ?.takeIf { it.isFinite() && it > 0f }
                ?: return 0.0
        return 1_000.0 / hz
    }

    companion object {
        private const val TAG = "SideVeilFrameMetrics"
        private const val NS_PER_MS = 1_000_000.0
    }
}
