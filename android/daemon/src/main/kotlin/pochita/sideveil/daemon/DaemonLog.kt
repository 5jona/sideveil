package pochita.sideveil.daemon

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread-safe log sink writing both to Android logcat and a bounded in-memory ring buffer.
 */
object DaemonLog {
    private const val TAG = "SideveilDaemon"
    private const val MAX_ENTRIES = 200
    private val lock = Any()
    private val entries = ArrayDeque<String>(MAX_ENTRIES)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun info(message: String) {
        runCatching { Log.i(TAG, message) }
        append("I", message)
    }

    fun warn(
        message: String,
        error: Throwable? = null,
    ) {
        if (error != null) {
            runCatching { Log.w(TAG, message, error) }
            append("W", "$message: ${error.javaClass.simpleName}: ${error.message}")
        } else {
            runCatching { Log.w(TAG, message) }
            append("W", message)
        }
    }

    fun error(
        message: String,
        error: Throwable? = null,
    ) {
        if (error != null) {
            runCatching { Log.e(TAG, message, error) }
            append("E", "$message: ${error.javaClass.simpleName}: ${error.message}")
        } else {
            runCatching { Log.e(TAG, message) }
            append("E", message)
        }
    }

    fun recent(limit: Int = MAX_ENTRIES): List<String> =
        synchronized(lock) {
            val count = limit.coerceIn(1, MAX_ENTRIES)
            if (entries.size <= count) {
                entries.toList()
            } else {
                entries.drop(entries.size - count)
            }
        }

    private fun append(
        level: String,
        message: String,
    ) {
        val ts = synchronized(timeFormat) { timeFormat.format(Date()) }
        val line = "$ts [$level] $message"
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(line)
        }
    }
}
