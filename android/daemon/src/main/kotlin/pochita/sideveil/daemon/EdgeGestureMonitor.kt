package pochita.sideveil.daemon

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.InputManagerGlobal
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputMonitor
import android.view.MotionEvent
import pochita.sideveil.shared.EdgeConfig
import pochita.sideveil.shared.EdgeSide
import pochita.sideveil.shared.PanelState
import pochita.sideveil.shared.SideveilConfig
import kotlin.math.abs

data class DisplayGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
)

interface EdgeGestureListener {
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

    fun onDisplayChanged(geometry: DisplayGeometry)
}

/**
 * Listens globally to touch events via InputManagerGlobal.monitorGestureInput without stealing
 * normal clicks or vertical scrolls, pilfering pointers only on qualifying inward edge drags.
 */
class EdgeGestureMonitor(
    private val systemContext: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val listener: EdgeGestureListener,
) {
    @Volatile
    var gestureMonitorActive: Boolean = false
        private set

    @Volatile
    var geometry: DisplayGeometry = DisplayGeometry(1080, 2400, 2.625f)
        private set

    @Volatile
    private var config: SideveilConfig = SideveilConfig.DEFAULT

    @Volatile
    private var scopeActive: Boolean = false

    @Volatile
    private var panelState: PanelState = PanelState.CLOSED

    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var inputThread: HandlerThread? = null
    private var inputMonitor: InputMonitor? = null
    private var inputReceiver: InputEventReceiver? = null

    private var tracking = false
    private var pilfered = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastEventTimeMs = 0L
    private var lastVelocityPxPerSec = 0f
    private var lastMoveEmitMs = 0L
    private var activeEdge: EdgeSide = EdgeSide.RIGHT

    fun start(initialConfig: SideveilConfig) {
        config = initialConfig
        if (inputThread == null) {
            inputThread =
                HandlerThread("SideveilInputDispatcher", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
                    start()
                }
        }
        refreshDisplayGeometry(notify = false)
        registerDisplayWatcher()
        openInputMonitor()
    }

    fun updateState(
        nextConfig: SideveilConfig,
        nextScopeActive: Boolean,
        nextPanelState: PanelState,
    ) {
        config = nextConfig
        scopeActive = nextScopeActive
        panelState = nextPanelState
        if (!scopeActive && tracking && !pilfered) {
            resetTracking()
        }
    }

    fun stop() {
        displayListener?.let { dl ->
            displayManager?.unregisterDisplayListener(dl)
        }
        displayListener = null
        displayManager = null

        runCatching { inputReceiver?.dispose() }
        inputReceiver = null

        runCatching { inputMonitor?.dispose() }
        inputMonitor = null

        runCatching { inputThread?.quitSafely() }
        inputThread = null

        gestureMonitorActive = false
        resetTracking()
    }

    private fun registerDisplayWatcher() {
        val dm = systemContext.getSystemService(DisplayManager::class.java) ?: return
        displayManager = dm
        val dl =
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) = Unit

                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        refreshDisplayGeometry(notify = true)
                    }
                }
            }
        dm.registerDisplayListener(dl, handler)
        displayListener = dl
    }

    private fun refreshDisplayGeometry(notify: Boolean) {
        val updated = queryDisplayGeometry()
        if (updated != geometry) {
            geometry = updated
            DaemonLog.info("Display geometry: ${updated.widthPx}x${updated.heightPx} density=${updated.density}")
            if (notify) {
                listener.onDisplayChanged(updated)
            }
        }
    }

    private fun queryDisplayGeometry(): DisplayGeometry {
        val fromGlobal =
            runCatching {
                val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
                val dmg = dmgClass.getMethod("getInstance").invoke(null)
                val info = dmgClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(dmg, Display.DEFAULT_DISPLAY)
                if (info != null) {
                    val w = info.javaClass.getField("logicalWidth").getInt(info)
                    val h = info.javaClass.getField("logicalHeight").getInt(info)
                    val dpi = info.javaClass.getField("logicalDensityDpi").getInt(info)
                    if (w > 0 && h > 0 && dpi > 0) {
                        return@runCatching DisplayGeometry(w, h, dpi / 160f)
                    }
                }
                null
            }.getOrNull()
        if (fromGlobal != null) {
            return fromGlobal
        }

        val metrics = DisplayMetrics()
        val display =
            systemContext
                .getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return DisplayGeometry(metrics.widthPixels, metrics.heightPixels, metrics.density)
            }
        }
        return geometry
    }

    private fun openInputMonitor() {
        runCatching {
            val monitor = InputManagerGlobal.getInstance().monitorGestureInput("sideveil-edge", Display.DEFAULT_DISPLAY)
            val channel = monitor.inputChannel
            val channelName = channel.name
            val dispatchLooper = inputThread?.looper ?: handler.looper
            val receiver =
                object : InputEventReceiver(channel, dispatchLooper) {
                    override fun onInputEvent(event: InputEvent) {
                        try {
                            if (event is MotionEvent) {
                                handleMotionEvent(event)
                            }
                        } catch (t: Throwable) {
                            DaemonLog.warn("Unhandled error in edge input receiver", t)
                            resetTracking()
                        } finally {
                            finishInputEvent(event, false)
                        }
                    }
                }
            inputMonitor = monitor
            inputReceiver = receiver
            gestureMonitorActive = true
            DaemonLog.info("Opened [Gesture Monitor] input channel: $channelName")
        }.onFailure { err ->
            gestureMonitorActive = false
            DaemonLog.warn("Failed to open monitorGestureInput", err)
        }
    }

    private fun handleMotionEvent(event: MotionEvent) {
        if ((event.source and InputDevice.SOURCE_TOUCHSCREEN) == 0) {
            return
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handleDown(event)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch cancels edge gesture ambiguity unless already committed via pilferPointers().
                if (tracking && !pilfered) {
                    resetTracking()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                handleMove(event)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedIndex = event.actionIndex
                if (tracking && event.getPointerId(liftedIndex) == activePointerId) {
                    handleUpOrCancel(event, canceled = false)
                }
            }

            MotionEvent.ACTION_UP -> {
                handleUpOrCancel(event, canceled = false)
            }

            MotionEvent.ACTION_CANCEL -> {
                handleUpOrCancel(event, canceled = true)
            }
        }
    }

    private fun handleDown(event: MotionEvent) {
        resetTracking()
        if (!scopeActive || panelState == PanelState.OPEN) {
            return
        }

        val edge = config.edge
        val geo = geometry
        val x = event.rawX
        val y = event.rawY
        if (!isInsideHotzone(x, y, edge, geo)) {
            return
        }

        tracking = true
        pilfered = false
        activePointerId = event.getPointerId(0)
        activeEdge = edge.side
        downX = x
        downY = y
        lastX = x
        lastEventTimeMs = event.eventTime
        lastVelocityPxPerSec = 0f
        lastMoveEmitMs = 0L
    }

    private fun handleMove(event: MotionEvent) {
        if (!tracking) return
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) {
            if (pilfered) {
                listener.onDragEnd(shouldOpen = false, velocityPxPerSec = 0f)
            }
            resetTracking()
            return
        }

        val rawX = event.getRawX(index)
        val rawY = event.getRawY(index)
        val dx = rawX - downX
        val dy = rawY - downY
        val inwardDx = if (activeEdge == EdgeSide.RIGHT) -dx else dx
        val geo = geometry
        val touchSlopPx = 10f * geo.density

        val dtMs = (event.eventTime - lastEventTimeMs).coerceAtLeast(4L)
        val stepInward = if (activeEdge == EdgeSide.RIGHT) -(rawX - lastX) else (rawX - lastX)
        val instantVelocity = ((stepInward * 1000f) / dtMs.toFloat()).coerceIn(-12_000f, 12_000f)
        lastVelocityPxPerSec = lastVelocityPxPerSec * 0.4f + instantVelocity * 0.6f
        lastX = rawX
        lastEventTimeMs = event.eventTime

        if (!pilfered) {
            if (abs(dy) > touchSlopPx && abs(dy) > abs(dx) * 1.15f) {
                resetTracking()
                return
            }
            if (inwardDx > touchSlopPx && inwardDx > abs(dy)) {
                pilfered = true
                runCatching { inputMonitor?.pilferPointers() }
                    .onFailure { DaemonLog.warn("pilferPointers failed", it) }
                val startYFraction = (downY / geo.heightPx.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                listener.onDragStart(activeEdge, startYFraction)
            } else {
                return
            }
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastMoveEmitMs < 8L) {
            return
        }
        lastMoveEmitMs = now

        val panelWidthPx = (config.panel.widthDp * geo.density).coerceAtLeast(120f)
        val progress = ((inwardDx - touchSlopPx) / panelWidthPx).coerceIn(0f, 1.15f)
        listener.onDragMove(
            side = activeEdge,
            progress = progress,
            rawX = rawX,
            rawY = rawY,
            deltaX = inwardDx,
        )
    }

    private fun handleUpOrCancel(
        event: MotionEvent,
        canceled: Boolean,
    ) {
        if (!tracking) return
        if (pilfered) {
            val index = event.findPointerIndex(activePointerId)
            val rawX = if (index >= 0) event.getRawX(index) else lastX
            val dx = rawX - downX
            val inwardDx = if (activeEdge == EdgeSide.RIGHT) -dx else dx
            val triggerDistPx = config.edge.triggerDistanceDp * geometry.density
            val shouldOpen = !canceled && (inwardDx >= triggerDistPx || lastVelocityPxPerSec >= 450f)
            listener.onDragEnd(shouldOpen = shouldOpen, velocityPxPerSec = lastVelocityPxPerSec)
        }
        resetTracking()
    }

    private fun isInsideHotzone(
        rawX: Float,
        rawY: Float,
        edge: EdgeConfig,
        geo: DisplayGeometry,
    ): Boolean {
        val hotzoneWidthPx = edge.hotzoneWidthDp * geo.density
        val xMatch =
            when (edge.side) {
                EdgeSide.LEFT -> rawX >= 0f && rawX <= hotzoneWidthPx
                EdgeSide.RIGHT -> rawX <= geo.widthPx.toFloat() && rawX >= (geo.widthPx - hotzoneWidthPx)
            }
        if (!xMatch) return false

        val heightPx = edge.heightDp * geo.density
        val maxTop = (geo.heightPx - heightPx).coerceAtLeast(0f)
        val topPx = ((edge.topPercent / 100f) * geo.heightPx).coerceIn(0f, maxTop)
        return rawY >= topPx && rawY <= (topPx + heightPx)
    }

    private fun resetTracking() {
        tracking = false
        pilfered = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        lastVelocityPxPerSec = 0f
    }
}
