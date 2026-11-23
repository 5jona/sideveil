package pochita.sideveil.host

import pochita.sideveil.protocol.PanelState

/**
 * Monotonic generation token guard ensuring canceled or preempted animations cannot execute
 * stale `onUpdate` or `onSettled` callbacks after a newer gesture, open, or force-close begins.
 */
internal class PanelAnimationGuard {
    var currentGeneration: Long = 0L
        private set

    fun nextToken(): Long {
        currentGeneration += 1L
        return currentGeneration
    }

    fun invalidate(): Long = nextToken()

    fun isActive(token: Long): Boolean = token == currentGeneration

    inline fun runIfActive(
        token: Long,
        action: () -> Unit,
    ): Boolean {
        if (!isActive(token)) return false
        action()
        return true
    }
}

internal enum class DragWatchdogAction {
    NONE,
    OPEN,
    CLOSE,
}

/**
 * Pure state machine governing [SidebarPanelWindow]'s [PanelState], `closingInProgress` lifecycle,
 * animation generation invalidation, and `DRAGGING` watchdog timeout recovery without Android
 * framework dependencies.
 */
internal class PanelDragStateMachine(
    private val onStateChanged: (PanelState) -> Unit = {},
    private val openThreshold: Float = DEFAULT_DRAG_OPEN_THRESHOLD,
) {
    private val animationGuard = PanelAnimationGuard()

    var currentState: PanelState = PanelState.CLOSED
        private set

    var closingInProgress: Boolean = false
        private set

    val animationGeneration: Long
        get() = animationGuard.currentGeneration

    fun isCurrentGeneration(token: Long): Boolean = animationGuard.isActive(token)

    fun interruptClosing(): Long {
        closingInProgress = false
        return animationGuard.invalidate()
    }

    fun shouldRestartDragOnMove(): Boolean = currentState != PanelState.DRAGGING || closingInProgress

    fun beginGestureDrag(): Long {
        val token = animationGuard.nextToken()
        closingInProgress = false
        transitionState(PanelState.DRAGGING)
        return token
    }

    fun beginOpen(): Long {
        val token = animationGuard.nextToken()
        closingInProgress = false
        transitionState(PanelState.OPEN)
        return token
    }

    fun onOpenSettled(token: Long): Boolean = animationGuard.isActive(token) && currentState == PanelState.OPEN && !closingInProgress

    fun beginClose(attached: Boolean): Long? {
        if (!attached || currentState == PanelState.CLOSED || closingInProgress) return null
        val token = animationGuard.nextToken()
        closingInProgress = true
        return token
    }

    fun onCloseSettled(token: Long): Boolean {
        if (!animationGuard.isActive(token) || !closingInProgress) return false
        closingInProgress = false
        transitionState(PanelState.CLOSED)
        return true
    }

    fun forceCloseImmediate(): Long {
        val token = animationGuard.invalidate()
        closingInProgress = false
        transitionState(PanelState.CLOSED)
        return token
    }

    fun onWarmCollapsed(markClosed: Boolean = true) {
        closingInProgress = false
        if (markClosed) {
            transitionState(PanelState.CLOSED)
        }
    }

    fun onDetach(): Long {
        val token = animationGuard.invalidate()
        closingInProgress = false
        transitionState(PanelState.CLOSED)
        return token
    }

    fun shouldToggleClose(): Boolean = currentState == PanelState.OPEN && !closingInProgress

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
