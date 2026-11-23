package pochita.sideveil.host.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.util.Log
import androidx.core.app.NotificationCompat
import pochita.sideveil.host.R
import pochita.sideveil.protocol.DaemonEndpoint
import pochita.sideveil.protocol.SideVeilConfig
import pochita.sideveil.protocol.SideVeilWireJson
import java.io.File

/**
 * Foreground notification, system-dialog dismissal broadcast receiver, daemon endpoint bootstrap,
 * and color-mode helpers for [pochita.sideveil.host.HostService].
 */
object HostServiceSupport {
    private const val TAG = "SideVeilHostService"
    private const val CHANNEL_ID = "sideveil_host_channel"
    private const val NOTIF_ID = 1001
    private const val ENDPOINT_FILE_NAME = "daemon_endpoint.json"
    private const val SYSTEM_DIALOG_REASON_KEY = "reason"
    private val DISMISS_SYSTEM_DIALOG_REASONS =
        setOf("notification_shade", "status_bar", "homekey", "recentapps")
    private val TOKEN_REGEX = Regex("^[0-9a-f]{64}$")

    /** Promotes [service] to a silent `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` foreground service. */
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

    /** Creates a [BroadcastReceiver] listening for home/recents/status-bar system dialog close intents. */
    fun createSystemDialogsReceiver(onDismissRequested: () -> Unit): BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                @Suppress("DEPRECATION")
                if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
                val reason = intent.getStringExtra(SYSTEM_DIALOG_REASON_KEY)
                if (reason in DISMISS_SYSTEM_DIALOG_REASONS) {
                    onDismissRequested()
                }
            }
        }

    /** Registers [receiver] for `ACTION_CLOSE_SYSTEM_DIALOGS`, returning `true` on success. */
    @Suppress("DEPRECATION")
    fun registerSystemDialogsReceiver(
        context: Context,
        receiver: BroadcastReceiver,
    ): Boolean =
        runCatching {
            context.registerReceiver(
                receiver,
                IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
                Context.RECEIVER_EXPORTED,
            )
            true
        }.onFailure {
            Log.w(TAG, "Failed to register ACTION_CLOSE_SYSTEM_DIALOGS receiver", it)
        }.getOrDefault(false)

    /** Safely unregisters [receiver] from [context]. */
    fun unregisterSystemDialogsReceiver(
        context: Context,
        receiver: BroadcastReceiver,
    ) {
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** Reads and validates `daemon_endpoint.json` from [context]'s no-backup files directory. */
    fun readEndpointFromDisk(context: Context): DaemonEndpoint? {
        val endpointFile = File(context.noBackupFilesDir, ENDPOINT_FILE_NAME)
        if (!endpointFile.isFile || !endpointFile.canRead()) return null
        return runCatching {
            val endpoint = SideVeilWireJson.decodeFromString<DaemonEndpoint>(endpointFile.readText(Charsets.UTF_8))
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

    /** Resolves whether overlay surfaces should render in dark mode for [config] and [sysConfig]. */
    fun resolveDarkSurface(
        config: SideVeilConfig,
        sysConfig: Configuration,
    ): Boolean =
        when (config.panel.colorMode) {
            "light" -> false
            "dark" -> true
            else -> (sysConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
}
