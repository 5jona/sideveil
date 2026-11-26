package pochita.sideveil.sensor.gesture

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
import pochita.sideveil.protocol.DebugGestureStats
import pochita.sideveil.protocol.DisplayGeometry
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.SideVeilConfig
import pochita.sideveil.sensor.SensorLog
import pochita.sideveil.sensor.system.DisplayGeometryWatcher

/**
 * Thin Android Framework adapter connecting `InputManagerGlobal.monitorGestureInput` and
 * `DisplayManager` to the pure JVM [EdgeGestureStateMachine].
 */
class EdgeGestureMonitor(
    private val systemContext: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val listener: EdgeGestureListener,
) {
    /** Whether `monitorGestureInput` is currently open and receiving global touchscreen events. */
    @Volatile
    var gestureMonitorActive: Boolean = false
        private set

    private val displayWatcher =
        DisplayGeometryWatcher(systemContext, handler) { updated ->
            listener.onDisplayChanged(updated)
        }

    /** Current default display dimensions and density. */
    val geometry: DisplayGeometry
        get() = displayWatcher.geometry

    private val diagnosticsTracker = GestureDiagnosticsTracker()

    /** Returns an immutable snapshot of accumulated gesture diagnostics. */
    fun gestureStatsSnapshot(): DebugGestureStats = diagnosticsTracker.snapshot()

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

    /** Single mutable snapshot reused exclusively on the input looper thread. */
    private val reusableEnv =
        EdgeGestureEnv(
            config = SideVeilConfig.DEFAULT,
            geometry = DisplayGeometry(),
            scopeActive = false,
            panelState = PanelState.CLOSED,
            fullscreenActive = false,
        )

    private val stateMachine: EdgeGestureStateMachine =
        EdgeGestureStateMachine(
            callbacks =
                EdgeGestureCallbackAdapter(
                    listener = listener,
                    diagnosticsTracker = diagnosticsTracker,
                    onPilfer = {
                        runCatching { inputMonitor?.pilferPointers() }
                            .onFailure { SensorLog.warn("pilferPointers failed", it) }
                    },
                    onScheduleLongPressCallback = ::scheduleLongPress,
                    onCancelLongPressCallback = ::cancelPendingLongPress,
                ),
            onStatusBarPullDown = { listener.onStatusBarPullDown() },
        )

    /** Starts the dedicated input thread, display watcher, and `monitorGestureInput` channel. */
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

    /** Updates the active configuration, scope eligibility, panel state, and fullscreen flag. */
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
            dispatchToInputThread {
                if (!scopeActive) stateMachine.onScopeInactive()
            }
        }
    }

    /** Disposes the input receiver/monitor on the input thread and stops the display watcher. */
    fun stop() {
        displayWatcher.stop()
        gestureMonitorActive = false

        val dispatcher = inputHandler
        val thread = inputThread
        val receiverToDispose = inputReceiver
        val monitorToDispose = inputMonitor
        inputHandler = null
        inputThread = null
        inputReceiver = null
        inputMonitor = null
        pendingLongPress = null

        val cleanupOnInputThread = {
            runCatching { receiverToDispose?.dispose() }
            runCatching { monitorToDispose?.dispose() }
            stateMachine.reset(notifyAbortIfPilfered = true)
        }

        dispatcher?.removeCallbacksAndMessages(null)
        if (dispatcher != null && dispatcher.looper != Looper.myLooper()) {
            if (!dispatcher.post(cleanupOnInputThread)) cleanupOnInputThread()
        } else {
            cleanupOnInputThread()
        }
        runCatching { thread?.quitSafely() }
    }

    private fun dispatchToInputThread(action: () -> Unit) {
        val dispatcher = inputHandler
        if (dispatcher != null && dispatcher.looper != Looper.myLooper()) {
            if (!dispatcher.post(action)) action()
        } else {
            action()
        }
    }

    private fun openInputMonitor() {
        val openedChannelName =
            runCatching {
                val monitor = InputManagerGlobal.getInstance().monitorGestureInput("sideveil-edge", Display.DEFAULT_DISPLAY)
                val channel = monitor.inputChannel
                val channelName = runCatching { channel.name }.getOrDefault("sideveil-edge")
                val dispatchLooper = inputThread?.looper ?: handler.looper
                val receiver =
                    object : InputEventReceiver(channel, dispatchLooper) {
                        override fun onInputEvent(event: InputEvent) {
                            try {
                                if (event is MotionEvent) handleMotionEvent(event)
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
                channelName
            }.onFailure { err ->
                gestureMonitorActive = false
                SensorLog.warn("Failed to open monitorGestureInput", err)
            }.getOrNull()
        if (openedChannelName != null) {
            SensorLog.info("Opened [Gesture Monitor] input channel: $openedChannelName")
        }
    }

    private fun handleMotionEvent(event: MotionEvent) {
        if ((event.source and InputDevice.SOURCE_TOUCHSCREEN) == 0) return
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!stateMachine.isTracking) return
                dispatchMove(event, currentEnv())
            }

            MotionEvent.ACTION_DOWN -> {
                stateMachine.onDown(event.getPointerId(0), event.rawX, event.rawY, event.eventTime, currentEnv())
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!stateMachine.isTracking) return
                stateMachine.onPointerDown()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (!stateMachine.isTracking) return
                val idx = event.actionIndex
                stateMachine.onPointerUp(event.getPointerId(idx), event.getRawX(idx), event.eventTime, currentEnv())
            }

            MotionEvent.ACTION_UP -> {
                if (!stateMachine.isTracking) return
                stateMachine.onUpOrCancel(event.rawX, event.eventTime, canceled = false, env = currentEnv())
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!stateMachine.isTracking) return
                stateMachine.onUpOrCancel(rawX = null, eventTimeMs = event.eventTime, canceled = true, env = currentEnv())
            }
        }
    }

    private fun dispatchMove(
        event: MotionEvent,
        env: EdgeGestureEnv,
    ) {
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

    private fun currentEnv(): EdgeGestureEnv = reusableEnv.update(config, geometry, scopeActive, panelState, fullscreenActive)

    private fun scheduleLongPress(pointerId: Int) {
        cancelPendingLongPress()
        val runnable =
            Runnable {
                pendingLongPress = null
                stateMachine.onLongPressTimeout(pointerId, currentEnv())
            }
        pendingLongPress = runnable
        inputHandler?.postDelayed(runnable, EdgeGestureStateMachine.LONG_PRESS_TIMEOUT_MS)
    }

    private fun cancelPendingLongPress() {
        pendingLongPress?.let { inputHandler?.removeCallbacks(it) }
        pendingLongPress = null
    }
}
