package pochita.sideveil.host.plugin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import pochita.sideveil.host.overlay.EdgeHapticsController

/**
 * Platform side-effects required by [PluginLocalRpcHandler], abstracted for unit testing.
 */
interface LocalRpcPlatformActions {
    fun showToast(message: String)

    fun readClipboardText(): String?

    fun writeClipboardText(text: String)

    fun triggerHaptics(kind: String)
}

internal sealed interface ParsedStringParam {
    data class Valid(
        val value: String,
    ) : ParsedStringParam

    data class Invalid(
        val message: String,
    ) : ParsedStringParam
}

/**
 * Sliding-window per-(plugin, method) rate limiter for host-local RPCs.
 */
internal class LocalRpcRateLimiter(
    private val windowMs: Long = WINDOW_MS,
    private val clockMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
    private val buckets = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun allow(
        pluginId: String,
        method: String,
        maxPerWindow: Int,
    ): Boolean {
        val now = clockMs()
        val key = "$pluginId|$method"
        val deque = buckets.getOrPut(key) { ArrayDeque(maxPerWindow + 1) }
        while (deque.isNotEmpty() && now - deque.first() >= windowMs) {
            deque.removeFirst()
        }
        if (deque.size >= maxPerWindow) return false
        deque.addLast(now)
        return true
    }

    companion object {
        const val WINDOW_MS = 1_000L
        private const val NANOS_PER_MS = 1_000_000L
    }
}

internal class AndroidLocalRpcActions(
    private val context: Context,
    private val haptics: EdgeHapticsController,
) : LocalRpcPlatformActions {
    override fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    override fun readClipboardText(): String? {
        val cm = context.getSystemService(ClipboardManager::class.java)
        return cm
            ?.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
    }

    override fun writeClipboardText(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("SideVeil Plugin", text))
    }

    override fun triggerHaptics(kind: String) {
        when (kind) {
            "click" -> haptics.onThresholdCrossed()
            "heavy" -> haptics.onRepositionStart()
            "cancel" -> haptics.onCancel()
            else -> haptics.onRailSwitch()
        }
    }
}
