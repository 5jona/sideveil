package pochita.sideveil.sensor

import android.util.Log

/**
 * Minimal structured logger for the thin UID 1000 `:sensor` process.
 */
object SensorLog {
    private const val TAG = "SideVeilSensor"

    /** Logs an informational message to logcat under [TAG]. */
    fun info(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    /** Logs a warning message and optional [throwable] to logcat under [TAG]. */
    fun warn(
        message: String,
        throwable: Throwable? = null,
    ) {
        runCatching {
            if (throwable != null) {
                Log.w(TAG, message, throwable)
            } else {
                Log.w(TAG, message)
            }
        }
    }
}
