package pochita.sideveil.sensor

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
import pochita.sideveil.protocol.DisplayGeometry
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.SideveilConfig

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
 * Thin Android Framework adapter connecting `InputManagerGlobal.monitorGestureInput` and
 * `DisplayManager` to the pure JVM [EdgeGestureStateMachine].
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
    var geometry: DisplayGeometry = DisplayGeometry()
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
    private var inputHandler: Handler? = null
    private var inputMonitor: InputMonitor? = null
    private var inputReceiver: InputEventReceiver? = null

    private val stateMachine =
        EdgeGestureStateMachine(
            object : EdgeGestureCallbacks {
                override fun onPilferPointers() {
                    runCatching { inputMonitor?.pilferPointers() }
                        .onFailure { SensorLog.warn("pilferPointers failed", it) }
                }

                override fun onDragStart(
                    side: EdgeSide,
                    startYFraction: Float,
                ) = listener.onDragStart(side, startYFraction)

                override fun onDragMove(
                    side: EdgeSide,
                    progress: Float,
                    rawX: Float,
                    rawY: Float,
                    deltaX: Float,
                ) = listener.onDragMove(side, progress, rawX, rawY, deltaX)

                override fun onDragEnd(
                    shouldOpen: Boolean,
                    velocityPxPerSec: Float,
                ) = listener.onDragEnd(shouldOpen, velocityPxPerSec)
            },
        )

    fun start(initialConfig: SideveilConfig) {
        config = initialConfig
        if (inputThread == null) {
            val thread =
                HandlerThread("SideveilInputDispatcher", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
                    start()
                }
            inputThread = thread
            inputHandler = Handler(thread.looper)
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
        if (!nextScopeActive) {
            val dispatcher = inputHandler
            if (dispatcher != null && dispatcher.looper != Looper.myLooper()) {
                dispatcher.post {
                    if (!scopeActive) {
                        stateMachine.onScopeInactive()
                    }
                }
            } else {
                stateMachine.onScopeInactive()
            }
        }
    }

    fun stop() {
        displayListener?.let { dl -> displayManager?.unregisterDisplayListener(dl) }
        displayListener = null
        displayManager = null

        runCatching { inputReceiver?.dispose() }
        inputReceiver = null

        runCatching { inputMonitor?.dispose() }
        inputMonitor = null

        inputHandler?.removeCallbacksAndMessages(null)
        inputHandler = null

        runCatching { inputThread?.quitSafely() }
        inputThread = null

        gestureMonitorActive = false
        stateMachine.reset(notifyAbortIfPilfered = true)
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
            SensorLog.info("Display geometry: ${updated.widthPx}x${updated.heightPx} density=${updated.density}")
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
                val info =
                    dmgClass
                        .getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
                        .invoke(dmg, Display.DEFAULT_DISPLAY)
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
                            SensorLog.warn("Unhandled error in edge input receiver", t)
                            stateMachine.reset(notifyAbortIfPilfered = true)
                        } finally {
                            finishInputEvent(event, false)
                        }
                    }
                }
            inputMonitor = monitor
            inputReceiver = receiver
            gestureMonitorActive = true
            SensorLog.info("Opened [Gesture Monitor] input channel: $channelName")
        }.onFailure { err ->
            gestureMonitorActive = false
            SensorLog.warn("Failed to open monitorGestureInput", err)
        }
    }

    private fun handleMotionEvent(event: MotionEvent) {
        if ((event.source and InputDevice.SOURCE_TOUCHSCREEN) == 0) {
            return
        }
        val cfg = config
        val geo = geometry
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stateMachine.onDown(
                    pointerId = event.getPointerId(0),
                    rawX = event.rawX,
                    rawY = event.rawY,
                    eventTimeMs = event.eventTime,
                    config = cfg,
                    geometry = geo,
                    scopeActive = scopeActive,
                    panelState = panelState,
                )
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                stateMachine.onPointerDown()
            }

            MotionEvent.ACTION_MOVE -> {
                dispatchMove(event, cfg, geo)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                stateMachine.onPointerUp(
                    liftedPointerId = event.getPointerId(idx),
                    rawX = event.getRawX(idx),
                    eventTimeMs = event.eventTime,
                    config = cfg,
                    geometry = geo,
                )
            }

            MotionEvent.ACTION_UP -> {
                stateMachine.onUpOrCancel(
                    rawX = event.rawX,
                    eventTimeMs = event.eventTime,
                    canceled = false,
                    config = cfg,
                    geometry = geo,
                )
            }

            MotionEvent.ACTION_CANCEL -> {
                stateMachine.onUpOrCancel(
                    rawX = null,
                    eventTimeMs = event.eventTime,
                    canceled = true,
                    config = cfg,
                    geometry = geo,
                )
            }
        }
    }

    private fun dispatchMove(
        event: MotionEvent,
        cfg: SideveilConfig,
        geo: DisplayGeometry,
    ) {
        if (!stateMachine.isTracking) return
        val activeId = stateMachine.currentPointerId
        val pointerIndex = event.findPointerIndex(activeId)
        if (pointerIndex < 0) {
            stateMachine.reset(notifyAbortIfPilfered = true)
            return
        }
        stateMachine.onMove(
            pointerId = activeId,
            rawX = event.getRawX(pointerIndex),
            rawY = event.getRawY(pointerIndex),
            eventTimeMs = event.eventTime,
            uptimeMs = SystemClock.uptimeMillis(),
            config = cfg,
            geometry = geo,
        )
    }
}
