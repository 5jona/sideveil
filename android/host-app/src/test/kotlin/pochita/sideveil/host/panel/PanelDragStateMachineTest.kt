package pochita.sideveil.host.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.protocol.PanelState

class PanelDragStateMachineTest {
    @Test
    fun `animation guard rejects stale callbacks after cancel or preemption`() {
        val guard = PanelAnimationGuard()
        val firstToken = guard.nextToken()
        var firstCallbackCalls = 0
        assertTrue(guard.runIfActive(firstToken) { firstCallbackCalls += 1 })
        assertEquals(1, firstCallbackCalls)

        // Simulates cancelActiveAnimations() invalidating the in-flight animation.
        guard.invalidate()
        assertFalse(guard.isActive(firstToken))
        assertFalse(guard.runIfActive(firstToken) { firstCallbackCalls += 1 })
        assertEquals(1, firstCallbackCalls)

        // Simulates a newer animation preempting an older one.
        val secondToken = guard.nextToken()
        val thirdToken = guard.nextToken()
        var secondCallbackCalls = 0
        var thirdCallbackCalls = 0
        assertFalse(guard.runIfActive(secondToken) { secondCallbackCalls += 1 })
        assertTrue(guard.runIfActive(thirdToken) { thirdCallbackCalls += 1 })
        assertEquals(0, secondCallbackCalls)
        assertEquals(1, thirdCallbackCalls)
    }

    @Test
    fun `drag watchdog decides close below threshold and open at or above threshold`() {
        assertEquals(
            DragWatchdogAction.CLOSE,
            PanelDragStateMachine.decideDragTimeout(
                state = PanelState.DRAGGING,
                closingInProgress = false,
                currentProgress = 0f,
            ),
        )
        assertEquals(
            DragWatchdogAction.CLOSE,
            PanelDragStateMachine.decideDragTimeout(
                state = PanelState.DRAGGING,
                closingInProgress = false,
                currentProgress = 0.449f,
            ),
        )
        assertEquals(
            DragWatchdogAction.CLOSE,
            PanelDragStateMachine.decideDragTimeout(
                state = PanelState.DRAGGING,
                closingInProgress = false,
                currentProgress = Float.NaN,
            ),
        )
        assertEquals(
            DragWatchdogAction.OPEN,
            PanelDragStateMachine.decideDragTimeout(
                state = PanelState.DRAGGING,
                closingInProgress = false,
                currentProgress = 0.45f,
            ),
        )
        assertEquals(
            DragWatchdogAction.OPEN,
            PanelDragStateMachine.decideDragTimeout(
                state = PanelState.DRAGGING,
                closingInProgress = false,
                currentProgress = 0.92f,
            ),
        )
    }

    @Test
    fun `drag watchdog returns none when not actively dragging or already closing`() {
        assertEquals(
            DragWatchdogAction.NONE,
            PanelDragStateMachine.decideDragTimeout(
                state = PanelState.CLOSED,
                closingInProgress = false,
                currentProgress = 0.8f,
            ),
        )
        assertEquals(
            DragWatchdogAction.NONE,
            PanelDragStateMachine.decideDragTimeout(
                state = PanelState.OPEN,
                closingInProgress = false,
                currentProgress = 0.2f,
            ),
        )
        assertEquals(
            DragWatchdogAction.NONE,
            PanelDragStateMachine.decideDragTimeout(
                state = PanelState.DRAGGING,
                closingInProgress = true,
                currentProgress = 0.8f,
            ),
        )
    }

    @Test
    fun `gesture start and move interrupt active close without closingInProgress deadlock`() {
        val transitions = mutableListOf<PanelState>()
        val fsm = PanelDragStateMachine(onStateChanged = transitions::add)

        fsm.beginGestureDrag()
        assertEquals(PanelState.DRAGGING, fsm.currentState)
        assertFalse(fsm.shouldRestartDragOnMove())

        // Start a normal close while still in DRAGGING (e.g. from onGestureEnd(false) or disconnect).
        val staleCloseToken = fsm.beginClose(attached = true)
        assertNotNull(staleCloseToken)
        assertTrue(fsm.closingInProgress)
        // New onGestureMove arriving mid-close must detect closingInProgress and restart drag cleanly.
        assertTrue(fsm.shouldRestartDragOnMove())

        // Interruption resets closingInProgress and invalidates stale close token.
        fsm.beginGestureDrag()
        assertFalse(fsm.closingInProgress)
        assertEquals(PanelState.DRAGGING, fsm.currentState)
        assertFalse(fsm.onCloseSettled(staleCloseToken!!))
        assertEquals(PanelState.DRAGGING, fsm.currentState)

        // Subsequent close must NOT deadlock on closingInProgress.
        val nextCloseToken = fsm.beginClose(attached = true)
        assertNotNull(nextCloseToken)
        assertTrue(fsm.closingInProgress)
        assertTrue(fsm.onCloseSettled(nextCloseToken!!))
        assertFalse(fsm.closingInProgress)
        assertEquals(PanelState.CLOSED, fsm.currentState)
        assertEquals(listOf(PanelState.DRAGGING, PanelState.CLOSED), transitions)
    }

    @Test
    fun `open interrupting close rejects stale close callback and honors open settle`() {
        val fsm = PanelDragStateMachine()
        fsm.beginOpen()
        val closeToken = fsm.beginClose(attached = true)
        assertNotNull(closeToken)
        assertTrue(fsm.closingInProgress)

        val openToken = fsm.beginOpen()
        assertFalse(fsm.closingInProgress)
        assertEquals(PanelState.OPEN, fsm.currentState)
        assertFalse(fsm.onCloseSettled(closeToken!!))
        assertEquals(PanelState.OPEN, fsm.currentState)
        assertTrue(fsm.onOpenSettled(openToken))
        assertTrue(fsm.shouldToggleClose())
    }

    @Test
    fun `forceCloseImmediate bypasses closingInProgress and invalidates stale animations`() {
        val transitions = mutableListOf<PanelState>()
        val fsm = PanelDragStateMachine(onStateChanged = transitions::add)

        fsm.beginOpen()
        val closeToken = fsm.beginClose(attached = true)
        assertNotNull(closeToken)
        assertTrue(fsm.closingInProgress)
        // Regular close is ignored while closingInProgress == true.
        assertNull(fsm.beginClose(attached = true))

        // Force close ignores closingInProgress and immediately resets to CLOSED.
        fsm.forceCloseImmediate()
        assertFalse(fsm.closingInProgress)
        assertEquals(PanelState.CLOSED, fsm.currentState)
        assertFalse(fsm.onCloseSettled(closeToken!!))
        assertEquals(listOf(PanelState.OPEN, PanelState.CLOSED), transitions)

        // Calling forceCloseImmediate again when already CLOSED is idempotent.
        fsm.forceCloseImmediate()
        assertEquals(listOf(PanelState.OPEN, PanelState.CLOSED), transitions)
    }

    @Test
    fun `forceCloseImmediate and detach recover stalled dragging state`() {
        val fsm = PanelDragStateMachine()
        val dragToken = fsm.beginGestureDrag()
        assertEquals(DragWatchdogAction.CLOSE, fsm.decideDragWatchdog(0.2f))
        assertEquals(DragWatchdogAction.OPEN, fsm.decideDragWatchdog(0.6f))

        fsm.forceCloseImmediate()
        assertEquals(PanelState.CLOSED, fsm.currentState)
        assertFalse(fsm.closingInProgress)
        assertFalse(fsm.isCurrentGeneration(dragToken))
        assertEquals(DragWatchdogAction.NONE, fsm.decideDragWatchdog(0.6f))

        val openToken = fsm.beginOpen()
        fsm.onDetach()
        assertEquals(PanelState.CLOSED, fsm.currentState)
        assertFalse(fsm.closingInProgress)
        assertFalse(fsm.onOpenSettled(openToken))
    }
}
