package pochita.sideveil.sensor

import android.content.Context
import android.hardware.input.InputManagerGlobal
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.InputEventReceiver
import android.view.InputMonitor
import android.view.MotionEvent
import pochita.sideveil.protocol.DisplayGeometry
import pochita.sideveil.protocol.EdgeSide
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.SideVeilConfig

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
        canceled: Boolean,
    )

    fun onFullscreenHint(side: EdgeSide)

    fun onEdgeRepositionStart(
        side: EdgeSide,
        topPercent: Int,
    )

    fun onEdgeRepositionMove(
        side: EdgeSide,
        topPercent: Int,
    )

    fun onEdgeRepositionEnd(
        side: EdgeSide,
        topPercent: Int,
        committed: Boolean,
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

    private val displayWatcher =
        DisplayGeometryWatcher(systemContext, handler) { updated ->
            listener.onDisplayChanged(updated)
        }

    val geometry: DisplayGeometry
        get() = displayWatcher.geometry

    @Volatile
    private var config: SideVeilConfig = SideVeilConfig.DEFAULT

    @Volatile
    private var scopeActive: Boolean = false

    @Volatile
    private var panelState: PanelState = PanelState.CLOSED

    @Volatile
    private var fullscreenActive: Boolean = false

    private var inputThread: HandlerThread? = null
    private var inputHandler: Handler? = null
    private var inputMonitor: InputMonitor? = null
    private var inputReceiver: InputEventReceiver? = null
    private var pendingLongPress: Runnable? = null

    private val stateMachine: EdgeGestureStateMachine =
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
                    canceled: Boolean,
                ) = listener.onDragEnd(shouldOpen, velocityPxPerSec, canceled)

                override fun onFullscreenHint(side: EdgeSide) = listener.onFullscreenHint(side)

                override fun onEdgeRepositionStart(
                    side: EdgeSide,
                    topPercent: Int,
                ) = listener.onEdgeRepositionStart(side, topPercent)

                override fun onEdgeRepositionMove(
                    side: EdgeSide,
                    topPercent: Int,
                ) = listener.onEdgeRepositionMove(side, topPercent)

                override fun onEdgeRepositionEnd(
                    side: EdgeSide,
                    topPercent: Int,
                    committed: Boolean,
                ) = listener.onEdgeRepositionEnd(side, topPercent, committed)

                override fun onScheduleLongPress(pointerId: Int) {
                    cancelPendingLongPress()
                    val runnable =
                        Runnable {
                            pendingLongPress = null
                            stateMachine.onLongPressTimeout(pointerId, currentEnv())
                        }
                    pendingLongPress = runnable
                    inputHandler?.postDelayed(runnable, EdgeGestureStateMachine.LONG_PRESS_TIMEOUT_MS)
                }

                override fun onCancelLongPress() {
                    cancelPendingLongPress()
                }
            },
        )

    fun start(initialConfig: SideVeilConfig) {
        config = initialConfig
        if (inputThread == null) {
            val thread =
                HandlerThread("SideVeilInputDispatcher", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
                    start()
                }
            inputThread = thread
            inputHandler = Handler(thread.looper)
        }
        displayWatcher.start()
        openInputMonitor()
    }

    fun updateState(
        nextConfig: SideVeilConfig,
        nextScopeActive: Boolean,
        nextPanelState: PanelState,
        nextFullscreenActive: Boolean = fullscreenActive,
    ) {
        config = nextConfig
        scopeActive = nextScopeActive
        panelState = nextPanelState
        fullscreenActive = nextFullscreenActive
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
        displayWatcher.stop()

        runCatching { inputReceiver?.dispose() }
        inputReceiver = null

        runCatching { inputMonitor?.dispose() }
        inputMonitor = null

        pendingLongPress = null
        inputHandler?.removeCallbacksAndMessages(null)
        inputHandler = null

        runCatching { inputThread?.quitSafely() }
        inputThread = null

        gestureMonitorActive = false
        stateMachine.reset(notifyAbortIfPilfered = true)
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
        val env = currentEnv()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stateMachine.onDown(
                    pointerId = event.getPointerId(0),
                    rawX = event.rawX,
                    rawY = event.rawY,
                    eventTimeMs = event.eventTime,
                    env = env,
                )
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                stateMachine.onPointerDown()
            }

            MotionEvent.ACTION_MOVE -> {
                dispatchMove(event, env)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                stateMachine.onPointerUp(
                    liftedPointerId = event.getPointerId(idx),
                    rawX = event.getRawX(idx),
                    eventTimeMs = event.eventTime,
                    env = env,
                )
            }

            MotionEvent.ACTION_UP -> {
                stateMachine.onUpOrCancel(
                    rawX = event.rawX,
                    eventTimeMs = event.eventTime,
                    canceled = false,
                    env = env,
                )
            }

            MotionEvent.ACTION_CANCEL -> {
                stateMachine.onUpOrCancel(
                    rawX = null,
                    eventTimeMs = event.eventTime,
                    canceled = true,
                    env = env,
                )
            }
        }
    }

    private fun dispatchMove(
        event: MotionEvent,
        env: EdgeGestureEnv,
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
            env = env,
        )
    }

    private fun currentEnv(): EdgeGestureEnv =
        EdgeGestureEnv(
            config = config,
            geometry = geometry,
            scopeActive = scopeActive,
            panelState = panelState,
            fullscreenActive = fullscreenActive,
        )

    private fun cancelPendingLongPress() {
        pendingLongPress?.let { inputHandler?.removeCallbacks(it) }
        pendingLongPress = null
    }
}
