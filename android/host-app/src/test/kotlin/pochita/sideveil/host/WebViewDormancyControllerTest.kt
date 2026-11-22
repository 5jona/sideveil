package pochita.sideveil.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewDormancyControllerTest {
    private class FakeScheduler {
        var lastDelayMs: Long? = null
        private var pending: (() -> Unit)? = null
        var cancelCount: Int = 0

        val schedule: (Long, () -> Unit) -> () -> Unit = { delayMs, action ->
            lastDelayMs = delayMs
            pending = action
            val captured = action
            (
                {
                    if (pending === captured) {
                        pending = null
                        cancelCount += 1
                    }
                }
            )
        }

        fun hasPending(): Boolean = pending != null

        fun fire() {
            val action = pending ?: return
            pending = null
            action()
        }
    }

    @Test
    fun `warm hidden load waits for page finish and settle delay before pausing`() {
        val scheduler = FakeScheduler()
        var pauses = 0
        var resumes = 0
        val controller =
            WebViewDormancyController(
                onPauseWebView = { pauses += 1 },
                onResumeWebView = { resumes += 1 },
                scheduleDelayed = scheduler.schedule,
            )

        controller.onLoadStarted()
        controller.setForeground(false)
        assertFalse("must not schedule pause before page finishes loading", scheduler.hasPending())
        assertEquals(0, pauses)

        controller.onPageFinished()
        assertTrue(scheduler.hasPending())
        assertEquals(WebViewDormancyController.DEFAULT_SETTLE_PAUSE_DELAY_MS, scheduler.lastDelayMs)
        assertFalse(controller.isPaused)

        scheduler.fire()
        assertTrue(controller.isPaused)
        assertEquals(1, pauses)
        assertEquals(0, resumes)
    }

    @Test
    fun `becoming foreground cancels pending pause and resumes immediately when paused`() {
        val scheduler = FakeScheduler()
        var pauses = 0
        var resumes = 0
        val controller =
            WebViewDormancyController(
                onPauseWebView = { pauses += 1 },
                onResumeWebView = { resumes += 1 },
                scheduleDelayed = scheduler.schedule,
            )

        controller.onLoadStarted()
        controller.onPageFinished()
        assertTrue(scheduler.hasPending())

        controller.setForeground(true)
        assertFalse(scheduler.hasPending())
        assertEquals(0, pauses)
        assertEquals(0, resumes)

        controller.setForeground(false)
        assertTrue(scheduler.hasPending())
        assertEquals(WebViewDormancyController.DEFAULT_HIDE_PAUSE_DELAY_MS, scheduler.lastDelayMs)
        scheduler.fire()
        assertTrue(controller.isPaused)
        assertEquals(1, pauses)

        controller.setForeground(true)
        assertFalse(controller.isPaused)
        assertEquals(1, resumes)
    }

    @Test
    fun `reload while paused resumes until new page finishes and settles`() {
        val scheduler = FakeScheduler()
        var pauses = 0
        var resumes = 0
        val controller =
            WebViewDormancyController(
                onPauseWebView = { pauses += 1 },
                onResumeWebView = { resumes += 1 },
                scheduleDelayed = scheduler.schedule,
            )

        controller.onLoadStarted()
        controller.onPageFinished()
        scheduler.fire()
        assertTrue(controller.isPaused)
        assertEquals(1, pauses)

        controller.onLoadStarted()
        assertFalse(controller.isPaused)
        assertEquals(1, resumes)

        controller.onPageFinished()
        scheduler.fire()
        assertTrue(controller.isPaused)
        assertEquals(2, pauses)
    }

    @Test
    fun `destroy cancels pending pause and prevents further transitions`() {
        val scheduler = FakeScheduler()
        var pauses = 0
        var resumes = 0
        val controller =
            WebViewDormancyController(
                onPauseWebView = { pauses += 1 },
                onResumeWebView = { resumes += 1 },
                scheduleDelayed = scheduler.schedule,
            )

        controller.onLoadStarted()
        controller.onPageFinished()
        assertTrue(scheduler.hasPending())

        controller.destroy()
        assertFalse(scheduler.hasPending())
        scheduler.fire()
        controller.setForeground(false)
        controller.onPageFinished()
        assertEquals(0, pauses)
        assertEquals(0, resumes)
    }
}
