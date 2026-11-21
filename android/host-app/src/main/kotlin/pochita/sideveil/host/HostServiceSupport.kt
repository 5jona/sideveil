package pochita.sideveil.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.util.Log
import androidx.core.app.NotificationCompat
import pochita.sideveil.protocol.DaemonEndpoint
import pochita.sideveil.protocol.SideveilConfig
import pochita.sideveil.protocol.SideveilWireJson
import java.io.File

/**
 * Foreground notification, daemon endpoint bootstrap, and color-mode helpers for [HostService].
 */
internal object HostServiceSupport {
    private const val TAG = "SideveilHostService"
    private const val CHANNEL_ID = "sideveil_host_channel"
    private const val NOTIF_ID = 1001
    private const val ENDPOINT_FILE_NAME = "daemon_endpoint.json"
    private val TOKEN_REGEX = Regex("^[0-9a-f]{64}$")

    fun promoteForeground(service: Service) {
        val mgr = service.getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                service.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        mgr?.createNotificationChannel(channel)

        val notification =
            NotificationCompat
                .Builder(service, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sideveil_stat)
                .setContentTitle(service.getString(R.string.notif_title))
                .setContentText(service.getString(R.string.notif_text))
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()

        service.startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    fun readEndpointFromDisk(context: Context): DaemonEndpoint? {
        val endpointFile = File(context.noBackupFilesDir, ENDPOINT_FILE_NAME)
        if (!endpointFile.isFile || !endpointFile.canRead()) return null
        return runCatching {
            val endpoint = SideveilWireJson.decodeFromString<DaemonEndpoint>(endpointFile.readText(Charsets.UTF_8))
            if (endpoint.port in 1..65535 && TOKEN_REGEX.matches(endpoint.token)) {
                endpoint
            } else {
                Log.w(TAG, "Ignored malformed daemon endpoint file")
                null
            }
        }.onFailure { err ->
            Log.w(TAG, "Failed to read daemon endpoint file: ${err.message}")
        }.getOrNull()
    }

    fun resolveDarkSurface(
        config: SideveilConfig,
        sysConfig: Configuration,
    ): Boolean =
        when (config.panel.colorMode) {
            "light" -> false
            "dark" -> true
            else -> (sysConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
}
