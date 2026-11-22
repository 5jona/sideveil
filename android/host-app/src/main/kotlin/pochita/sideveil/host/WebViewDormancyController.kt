package pochita.sideveil.host

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Pure-Kotlin state machine coordinating per-instance `WebView.onPause()` / `onResume()`
 * dormancy so hidden warm plugin WebViews release compositor/GPU tiles and throttle timers
 * without interfering with initial page load hydration or visible pinned windows.
 *
 * Never uses process-global `WebView.pauseTimers()` so active pinned windows remain unaffected.
 */
internal class WebViewDormancyController(
    private val onPauseWebView: () -> Unit,
    private val onResumeWebView: () -> Unit,
    private val scheduleDelayed: (delayMs: Long, action: () -> Unit) -> () -> Unit,
    private val hidePauseDelayMs: Long = DEFAULT_HIDE_PAUSE_DELAY_MS,
    private val settlePauseDelayMs: Long = DEFAULT_SETTLE_PAUSE_DELAY_MS,
) {
    private var foreground: Boolean = false
    private var pageLoaded: Boolean = false
    private var paused: Boolean = false
    private var destroyed: Boolean = false
    private var cancelPendingPause: (() -> Unit)? = null

    val isPaused: Boolean
        get() = paused

    fun onLoadStarted() {
        if (destroyed) return
        pageLoaded = false
        cancelPending()
        if (paused) {
            paused = false
            onResumeWebView()
        }
    }

    fun onPageFinished() {
        if (destroyed) return
        pageLoaded = true
        if (!foreground && !paused) {
            schedulePause(settlePauseDelayMs)
        }
    }

    fun setForeground(nextForeground: Boolean) {
        if (destroyed) return
        foreground = nextForeground
        if (nextForeground) {
            cancelPending()
            if (paused) {
                paused = false
                onResumeWebView()
            }
        } else if (pageLoaded && !paused) {
            schedulePause(hidePauseDelayMs)
        }
    }

    fun destroy() {
        destroyed = true
        cancelPending()
    }

    private fun schedulePause(delayMs: Long) {
        cancelPending()
        cancelPendingPause =
            scheduleDelayed(delayMs) {
                cancelPendingPause = null
                if (canPauseNow()) {
                    paused = true
                    onPauseWebView()
                }
            }
    }

    private fun canPauseNow(): Boolean {
        if (destroyed || foreground) return false
        return pageLoaded && !paused
    }

    private fun cancelPending() {
        cancelPendingPause?.invoke()
        cancelPendingPause = null
    }

    companion object {
        const val DEFAULT_HIDE_PAUSE_DELAY_MS = 400L
        const val DEFAULT_SETTLE_PAUSE_DELAY_MS = 1200L

        fun forWebView(
            webView: WebView,
            mainHandler: Handler,
        ): WebViewDormancyController =
            WebViewDormancyController(
                onPauseWebView = { runOnMain(mainHandler) { webView.onPause() } },
                onResumeWebView = { runOnMain(mainHandler) { webView.onResume() } },
                scheduleDelayed = { delayMs, action ->
                    val runnable = Runnable { action() }
                    mainHandler.postDelayed(runnable, delayMs)
                    ({ mainHandler.removeCallbacks(runnable) })
                },
            )

        private inline fun runOnMain(
            mainHandler: Handler,
            crossinline block: () -> Unit,
        ) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                block()
            } else {
                mainHandler.post { block() }
            }
        }
    }
}
