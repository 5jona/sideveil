package pochita.sideveil.host

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

internal sealed interface CrashRecoveryDecision {
    data class RetryAfter(
        val delayMs: Long,
        val attempt: Int,
    ) : CrashRecoveryDecision

    data class CircuitOpen(
        val attempt: Int,
        val cooldownRemainingMs: Long,
    ) : CrashRecoveryDecision
}

/**
 * Pure-Kotlin bounded exponential backoff and circuit breaker for rapid WebView render-process
 * crash recovery.
 */
internal class WebViewCrashBackoff(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    private val maxRapidCrashes: Int = DEFAULT_MAX_RAPID_CRASHES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private data class CrashState(
        val consecutiveCount: Int,
        val lastCrashMs: Long,
        val circuitOpenUntilMs: Long,
    )

    private val states = LinkedHashMap<String, CrashState>()

    fun recordCrashAndDecide(pluginId: String): CrashRecoveryDecision {
        val now = clockMs()
        val prev = states[pluginId]
        if (prev != null && now < prev.circuitOpenUntilMs) {
            val remaining = prev.circuitOpenUntilMs - now
            states[pluginId] = prev.copy(lastCrashMs = now)
            return CrashRecoveryDecision.CircuitOpen(
                attempt = prev.consecutiveCount,
                cooldownRemainingMs = remaining,
            )
        }

        val streakReset =
            prev == null ||
                prev.circuitOpenUntilMs > 0L ||
                now - prev.lastCrashMs > windowMs
        val nextCount = if (streakReset) 1 else prev.consecutiveCount + 1
        if (nextCount >= maxRapidCrashes) {
            states[pluginId] =
                CrashState(
                    consecutiveCount = nextCount,
                    lastCrashMs = now,
                    circuitOpenUntilMs = now + cooldownMs,
                )
            return CrashRecoveryDecision.CircuitOpen(
                attempt = nextCount,
                cooldownRemainingMs = cooldownMs,
            )
        }

        val shift = (nextCount - 1).coerceAtMost(10)
        val delayMs = (baseDelayMs shl shift).coerceAtMost(maxDelayMs)
        states[pluginId] =
            CrashState(
                consecutiveCount = nextCount,
                lastCrashMs = now,
                circuitOpenUntilMs = 0L,
            )
        return CrashRecoveryDecision.RetryAfter(delayMs = delayMs, attempt = nextCount)
    }

    fun reset(pluginId: String) {
        states.remove(pluginId)
    }

    fun clearAll() {
        states.clear()
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 60_000L
        const val DEFAULT_BASE_DELAY_MS = 500L
        const val DEFAULT_MAX_DELAY_MS = 8_000L
        const val DEFAULT_MAX_RAPID_CRASHES = 5
        const val DEFAULT_COOLDOWN_MS = 30_000L
    }
}

/**
 * Coordinates delayed main-thread WebView crash recovery backed by [WebViewCrashBackoff].
 */
internal class WebViewCrashRecoveryCoordinator(
    private val backoff: WebViewCrashBackoff = WebViewCrashBackoff(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    private val pending = LinkedHashMap<String, Runnable>()

    fun schedule(
        pluginId: String,
        onRecover: () -> Unit,
    ) {
        cancel(pluginId, resetBackoff = false)
        when (val decision = backoff.recordCrashAndDecide(pluginId)) {
            is CrashRecoveryDecision.RetryAfter -> {
                Log.w(
                    TAG,
                    "Scheduling WebView recovery for plugin=$pluginId attempt=${decision.attempt} in ${decision.delayMs}ms",
                )
                val task =
                    Runnable {
                        pending.remove(pluginId)
                        onRecover()
                    }
                pending[pluginId] = task
                mainHandler.postDelayed(task, decision.delayMs)
            }

            is CrashRecoveryDecision.CircuitOpen -> {
                Log.e(
                    TAG,
                    "WebView crash circuit breaker open for plugin=$pluginId " +
                        "(attempts=${decision.attempt}, cooldownMs=${decision.cooldownRemainingMs}); halting auto-recovery",
                )
            }
        }
    }

    fun cancel(
        pluginId: String,
        resetBackoff: Boolean,
    ) {
        pending.remove(pluginId)?.let(mainHandler::removeCallbacks)
        if (resetBackoff) backoff.reset(pluginId)
    }

    fun clearAll() {
        for (task in pending.values) {
            mainHandler.removeCallbacks(task)
        }
        pending.clear()
        backoff.clearAll()
    }

    companion object {
        private const val TAG = "SideVeilCrashRecovery"
    }
}
