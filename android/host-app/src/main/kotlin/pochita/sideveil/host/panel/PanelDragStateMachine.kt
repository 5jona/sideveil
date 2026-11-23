package pochita.sideveil.host.panel

import pochita.sideveil.protocol.PanelState

/**
 * Monotonic generation token guard ensuring canceled or preempted animations cannot execute
 * stale `onUpdate` or `onSettled` callbacks after a newer gesture, open, or force-close begins.
 */
class PanelAnimationGuard {
    /** Current active generation token. */
    var currentGeneration: Long = 0L
        private set

    /** Advances and returns the next generation token. */
    fun nextToken(): Long {
        currentGeneration += 1L
        return currentGeneration
    }

    /** Invalidates any in-flight generation token. */
    fun invalidate(): Long = nextToken()

    /** Returns `true` if [token] matches [currentGeneration]. */
    fun isActive(token: Long): Boolean = token == currentGeneration

    /** Runs [action] only when [token] matches [currentGeneration]. */
    inline fun runIfActive(
        token: Long,
        action: () -> Unit,
    ): Boolean {
        if (!isActive(token)) return false
        action()
        return true
    }
}

/**
 * Action decided when the drag stall watchdog fires while the panel is in [PanelState.DRAGGING].
 */
enum class DragWatchdogAction {
    NONE,
    OPEN,
    CLOSE,
}

/**
 * Pure state machine governing [SidebarPanelWindow]'s [PanelState], `closingInProgress` lifecycle,
 * animation generation invalidation, and `DRAGGING` watchdog timeout recovery without Android
 * framework dependencies.
 */
class PanelDragStateMachine(
    private val onStateChanged: (PanelState) -> Unit = {},
    private val openThreshold: Float = DEFAULT_DRAG_OPEN_THRESHOLD,
) {
    private val animationGuard = PanelAnimationGuard()

    /** Current discrete panel state (`CLOSED`, `DRAGGING`, `OPEN`). */
    var currentState: PanelState = PanelState.CLOSED
        private set

    /** Whether a close spring animation is currently in flight. */
    var closingInProgress: Boolean = false
        private set

    val animationGeneration: Long
        get() = animationGuard.currentGeneration

    /** Returns `true` if [token] is the active animation generation. */
    fun isCurrentGeneration(token: Long): Boolean = animationGuard.isActive(token)

    /** Cancels `closingInProgress` and invalidates any in-flight close animation token. */
    fun interruptClosing(): Long {
        closingInProgress = false
        return animationGuard.invalidate()
    }

    /** Returns `true` when an incoming `onGestureMove` must re-initialize drag tracking. */
    fun shouldRestartDragOnMove(): Boolean = currentState != PanelState.DRAGGING || closingInProgress

    /** Enters [PanelState.DRAGGING] and returns a fresh generation token. */
    fun beginGestureDrag(): Long {
        val token = animationGuard.nextToken()
        closingInProgress = false
        transitionState(PanelState.DRAGGING)
        return token
    }

    /** Enters [PanelState.OPEN] and returns a fresh generation token. */
    fun beginOpen(): Long {
        val token = animationGuard.nextToken()
        closingInProgress = false
        transitionState(PanelState.OPEN)
        return token
    }

    /** Verifies whether an open spring settle callback with [token] is still valid. */
    fun onOpenSettled(token: Long): Boolean = animationGuard.isActive(token) && currentState == PanelState.OPEN && !closingInProgress

    /** Begins a close transition if eligible, returning the close generation token or `null`. */
    fun beginClose(attached: Boolean): Long? {
        if (!attached || currentState == PanelState.CLOSED || closingInProgress) return null
        val token = animationGuard.nextToken()
        closingInProgress = true
        return token
    }

    /** Settles a close animation with [token] and transitions to [PanelState.CLOSED]. */
    fun onCloseSettled(token: Long): Boolean {
        if (!animationGuard.isActive(token) || !closingInProgress) return false
        closingInProgress = false
        transitionState(PanelState.CLOSED)
        return true
    }

    /** Immediately transitions to [PanelState.CLOSED], bypassing `closingInProgress`. */
    fun forceCloseImmediate(): Long {
        val token = animationGuard.invalidate()
        closingInProgress = false
        transitionState(PanelState.CLOSED)
        return token
    }

    /** Finalizes state when the overlay window collapses to warm-invisible `1x1`. */
    fun onWarmCollapsed(markClosed: Boolean = true) {
        closingInProgress = false
        if (markClosed) {
            transitionState(PanelState.CLOSED)
        }
    }

    /** Resets state to [PanelState.CLOSED] when the window detaches. */
    fun onDetach(): Long {
        val token = animationGuard.invalidate()
        closingInProgress = false
        transitionState(PanelState.CLOSED)
        return token
    }

    /** Returns `true` when `toggle()` should close the panel rather than open it. */
    fun shouldToggleClose(): Boolean = currentState == PanelState.OPEN && !closingInProgress

    /** Decides the recovery action when a drag gesture stalls beyond the watchdog timeout. */
    fun decideDragWatchdog(currentProgress: Float): DragWatchdogAction =
        decideDragTimeout(
            state = currentState,
            closingInProgress = closingInProgress,
            currentProgress = currentProgress,
            openThreshold = openThreshold,
        )

    private fun transitionState(next: PanelState) {
        if (currentState != next) {
            currentState = next
            onStateChanged(next)
        }
    }

    companion object {
        const val DEFAULT_DRAG_WATCHDOG_TIMEOUT_MS = 1_500L
        const val DEFAULT_DRAG_OPEN_THRESHOLD = 0.45f

        /** Pure watchdog decision helper for stalled drag states. */
        fun decideDragTimeout(
            state: PanelState,
            closingInProgress: Boolean,
            currentProgress: Float,
            openThreshold: Float = DEFAULT_DRAG_OPEN_THRESHOLD,
        ): DragWatchdogAction {
            if (state != PanelState.DRAGGING || closingInProgress) {
                return DragWatchdogAction.NONE
            }
            return if (currentProgress >= openThreshold) {
                DragWatchdogAction.OPEN
            } else {
                DragWatchdogAction.CLOSE
            }
        }
    }
}
