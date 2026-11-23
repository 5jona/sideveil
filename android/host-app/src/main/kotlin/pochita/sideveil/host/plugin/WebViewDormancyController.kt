package pochita.sideveil.host.plugin

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import pochita.sideveil.protocol.PluginDescriptor

/**
 * Pure-Kotlin state machine coordinating per-instance `WebView.onPause()` / `onResume()`
 * dormancy so hidden warm plugin WebViews release compositor/GPU tiles and throttle timers
 * without interfering with initial page load hydration or visible pinned windows.
 *
 * Never uses process-global `WebView.pauseTimers()` so active pinned windows remain unaffected.
 */
class WebViewDormancyController(
    private val onPauseWebView: () -> Unit,
    private val onResumeWebView: () -> Unit,
    private val scheduleDelayed: (delayMs: Long, action: () -> Unit) -> () -> Unit,
    hidePauseDelayMs: Long = DEFAULT_HIDE_PAUSE_DELAY_MS,
    private val settlePauseDelayMs: Long = DEFAULT_SETTLE_PAUSE_DELAY_MS,
) {
    private var hidePauseDelayMs: Long = hidePauseDelayMs.coerceAtLeast(0L)
    private var foreground: Boolean = false
    private var pageLoaded: Boolean = false
    private var paused: Boolean = false
    private var destroyed: Boolean = false
    private var cancelPendingPause: (() -> Unit)? = null

    /** Whether the associated WebView is currently in `onPause()` dormancy. */
    val isPaused: Boolean
        get() = paused

    /** Updates the delay before pausing after the WebView leaves the foreground. */
    fun updateHidePauseDelayMs(nextDelayMs: Long) {
        hidePauseDelayMs = nextDelayMs.coerceAtLeast(0L)
    }

    /** Marks the start of a new page load, resuming the WebView immediately if paused. */
    fun onLoadStarted() {
        if (destroyed) return
        pageLoaded = false
        cancelPending()
        if (paused) {
            paused = false
            onResumeWebView()
        }
    }

    /** Marks page load completion and schedules dormancy if currently hidden. */
    fun onPageFinished() {
        if (destroyed) return
        pageLoaded = true
        if (!foreground && !paused) {
            schedulePause(settlePauseDelayMs)
        }
    }

    /** Transitions foreground visibility, resuming immediately or scheduling delayed dormancy. */
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

    /** Cancels pending dormancy timers and disables further state transitions. */
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
        const val DEFAULT_KEEP_ALIVE_GRACE_MS = 180_000L

        /** Resolves the post-hide dormancy delay in milliseconds for [descriptor]. */
        fun resolveHidePauseDelayMs(descriptor: PluginDescriptor): Long =
            if (descriptor.surface.keepAlive) {
                descriptor.embed
                    ?.keepAliveGraceSec
                    ?.coerceIn(0, 1800)
                    ?.times(1000L)
                    ?: DEFAULT_KEEP_ALIVE_GRACE_MS
            } else {
                DEFAULT_HIDE_PAUSE_DELAY_MS
            }

        /** Creates a [WebViewDormancyController] wired to [webView] and [mainHandler]. */
        fun forWebView(
            webView: WebView,
            mainHandler: Handler,
            hidePauseDelayMs: Long = DEFAULT_HIDE_PAUSE_DELAY_MS,
        ): WebViewDormancyController =
            WebViewDormancyController(
                onPauseWebView = { runOnMain(mainHandler) { webView.onPause() } },
                onResumeWebView = { runOnMain(mainHandler) { webView.onResume() } },
                scheduleDelayed = { delayMs, action ->
                    val runnable = Runnable { action() }
                    mainHandler.postDelayed(runnable, delayMs)
                    ({ mainHandler.removeCallbacks(runnable) })
                },
                hidePauseDelayMs = hidePauseDelayMs,
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
