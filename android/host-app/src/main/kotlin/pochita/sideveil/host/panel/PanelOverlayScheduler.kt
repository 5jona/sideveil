package pochita.sideveil.host.panel

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import pochita.sideveil.protocol.PanelState

/**
 * Schedules debounced outside-touch dismissal (backed by [PanelOutsideTouchPolicy]),
 * post-dismiss warm-retain collapse timers, live `WindowInsets` binding, and the `DRAGGING`
 * stall watchdog for [SidebarPanelWindow].
 */
internal class PanelOverlayScheduler(
    private val stateProvider: () -> PanelState,
    private val isClosing: () -> Boolean,
    private val isRailTouch: (Float, Float) -> Boolean,
    private val isInnerEdgeBufferTouch: (Float, Float) -> Boolean = { _, _ -> false },
    private val onRequestClose: () -> Unit,
    private val onWarmCollapse: () -> Unit,
    private val onDragWatchdogTimeout: () -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val outsideTouchPolicy = PanelOutsideTouchPolicy(nowMs = SystemClock::uptimeMillis)
    private var pendingCollapse: Runnable? = null
    private var pendingOutsideClose: Runnable? = null
    private var pendingDragWatchdog: Runnable? = null

    fun bindWindowInsetsListener(
        contentContainer: FrameLayout,
        themedContext: Context,
        windowManager: WindowManager,
        windowProvider: () -> Window?,
        surfaceState: PanelSurfaceState,
        decorLocation: IntArray,
        onImeChangedWhileOpen: () -> Unit,
    ) {
        contentContainer.setOnApplyWindowInsetsListener { view, insets ->
            val prevIme = surfaceState.effectiveImeBottomPx
            val nextIme =
                PanelInsetsCoordinator.resolveEffectiveImeFromWindow(
                    context = themedContext,
                    windowManager = windowManager,
                    decorView = windowProvider()?.decorView,
                    decorLocation = decorLocation,
                    insets = insets,
                    previousEffectiveImeBottomPx = prevIme,
                )
            surfaceState.lastLiveInsets = insets
            surfaceState.effectiveImeBottomPx = nextIme
            onImeInsetsChanged(nextIme)
            if (prevIme != nextIme && stateProvider() == PanelState.OPEN && !isClosing()) {
                view.post {
                    if (stateProvider() == PanelState.OPEN && !isClosing()) onImeChangedWhileOpen()
                }
            }
            insets
        }
    }

    fun onImeInsetsChanged(nextImeBottomPx: Int) {
        outsideTouchPolicy.onImeInsetsChanged(nextImeBottomPx)
        if (nextImeBottomPx > 0) {
            cancelPendingTimers(includeCollapse = false)
        }
    }

    fun onOutsideTouch(
        rawX: Float,
        rawY: Float,
    ) {
        if (shouldIgnore(rawX, rawY)) return
        cancelPendingTimers(includeCollapse = false)
        val task =
            Runnable {
                pendingOutsideClose = null
                if (!shouldIgnore(rawX, rawY)) onRequestClose()
            }
        pendingOutsideClose = task
        mainHandler.postDelayed(task, OUTSIDE_CLOSE_DEBOUNCE_MS)
    }

    fun scheduleWarmCollapse() {
        cancelPendingTimers()
        val task =
            Runnable {
                pendingCollapse = null
                if (stateProvider() == PanelState.CLOSED) onWarmCollapse()
            }
        pendingCollapse = task
        mainHandler.postDelayed(task, POST_DISMISS_WARM_RETAIN_MS)
    }

    fun scheduleDragWatchdog(timeoutMs: Long = DRAG_WATCHDOG_TIMEOUT_MS) {
        cancelDragWatchdog()
        val task =
            Runnable {
                pendingDragWatchdog = null
                onDragWatchdogTimeout()
            }
        pendingDragWatchdog = task
        mainHandler.postDelayed(task, timeoutMs)
    }

    fun cancelDragWatchdog() {
        pendingDragWatchdog?.let(mainHandler::removeCallbacks)
        pendingDragWatchdog = null
    }

    fun cancelPendingTimers(
        includeCollapse: Boolean = true,
        includeDragWatchdog: Boolean = includeCollapse,
    ) {
        pendingOutsideClose?.let(mainHandler::removeCallbacks)
        pendingOutsideClose = null
        if (includeCollapse) {
            pendingCollapse?.let(mainHandler::removeCallbacks)
            pendingCollapse = null
        }
        if (includeDragWatchdog) {
            cancelDragWatchdog()
        }
    }

    fun resetPolicyOnCollapse() {
        outsideTouchPolicy.reset()
    }

    private fun shouldIgnore(
        rawX: Float,
        rawY: Float,
    ): Boolean =
        outsideTouchPolicy.shouldIgnoreOutsideTouch(
            panelOpen = stateProvider() == PanelState.OPEN,
            closingInProgress = isClosing(),
            isRailTouch = isRailTouch(rawX, rawY) || isInnerEdgeBufferTouch(rawX, rawY),
        )

    companion object {
        private const val POST_DISMISS_WARM_RETAIN_MS = 150L
        private const val OUTSIDE_CLOSE_DEBOUNCE_MS = 72L
        const val DRAG_WATCHDOG_TIMEOUT_MS = PanelDragStateMachine.DEFAULT_DRAG_WATCHDOG_TIMEOUT_MS
    }
}
