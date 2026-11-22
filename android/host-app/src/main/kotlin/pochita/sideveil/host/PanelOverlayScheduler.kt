package pochita.sideveil.host

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import pochita.sideveil.protocol.PanelState

/**
 * Schedules debounced outside-touch dismissal (backed by [PanelOutsideTouchPolicy]) and
 * post-dismiss warm-retain collapse timers for [SidebarPanelWindow].
 */
internal class PanelOverlayScheduler(
    private val stateProvider: () -> PanelState,
    private val isClosing: () -> Boolean,
    private val isRailTouch: (Float, Float) -> Boolean,
    private val onRequestClose: () -> Unit,
    private val onWarmCollapse: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val outsideTouchPolicy = PanelOutsideTouchPolicy(nowMs = SystemClock::uptimeMillis)
    private var pendingCollapse: Runnable? = null
    private var pendingOutsideClose: Runnable? = null

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

    fun cancelPendingTimers(includeCollapse: Boolean = true) {
        pendingOutsideClose?.let(mainHandler::removeCallbacks)
        pendingOutsideClose = null
        if (includeCollapse) {
            pendingCollapse?.let(mainHandler::removeCallbacks)
            pendingCollapse = null
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
            isRailTouch = isRailTouch(rawX, rawY),
        )

    companion object {
        private const val POST_DISMISS_WARM_RETAIN_MS = 150L
        private const val OUTSIDE_CLOSE_DEBOUNCE_MS = 72L
    }
}
