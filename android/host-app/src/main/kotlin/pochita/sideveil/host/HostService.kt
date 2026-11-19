package pochita.sideveil.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pochita.sideveil.shared.ConfigUpdatedPayload
import pochita.sideveil.shared.GestureDragEndPayload
import pochita.sideveil.shared.GestureDragMovePayload
import pochita.sideveil.shared.HostEventEnvelope
import pochita.sideveil.shared.HostHelloPayload
import pochita.sideveil.shared.PanelCommandPayload
import pochita.sideveil.shared.PanelState
import pochita.sideveil.shared.PanelStateChangedPayload
import pochita.sideveil.shared.PanelVisibilityPayload
import pochita.sideveil.shared.ScopeChangedPayload
import pochita.sideveil.shared.SideveilConfig
import pochita.sideveil.shared.SideveilEvents
import pochita.sideveil.shared.SideveilWireJson
import java.io.File

/**
 * Headless foreground service running inside the AMS-managed SideveilHost priv-app process.
 * Hosts warm overlay windows and bridges the panel WebView to the UID 1000 daemon.
 */
class HostService : LifecycleService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var daemonClient: DaemonClient
    private lateinit var edgeIndicator: EdgeIndicatorWindow
    private lateinit var webBridge: PanelWebViewBridge
    private lateinit var panelWindow: SidebarPanelWindow

    private var latestConfig: SideveilConfig = SideveilConfig.DEFAULT
    private var latestScopeActive: Boolean = false
    private var latestDarkSurface: Boolean = true

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        latestDarkSurface = resolveDarkSurface(latestConfig)
        promoteForeground()

        daemonClient =
            DaemonClient(
                scope = lifecycleScope,
                onConnected = {
                    mainHandler.post {
                        daemonClient.sendEvent(
                            SideveilEvents.HOST_HELLO,
                            SideveilWireJson.encodeToJsonElement(
                                HostHelloPayload(
                                    pid = Process.myPid(),
                                    version = "0.1.0",
                                    crossWindowBlurSupported = panelWindow.isSystemBlurSupported(),
                                ),
                            ),
                        )
                        val currentState = panelWindow.getCurrentState()
                        if (currentState != PanelState.CLOSED) {
                            daemonClient.sendEvent(
                                SideveilEvents.PANEL_STATE_CHANGED,
                                SideveilWireJson.encodeToJsonElement(PanelStateChangedPayload(currentState)),
                            )
                        }
                    }
                },
                onDisconnected = {
                    mainHandler.post {
                        latestScopeActive = false
                        if (panelWindow.getCurrentState() != PanelState.CLOSED) {
                            panelWindow.close()
                        }
                        edgeIndicator.update(
                            latestConfig.edge,
                            scopeActive = false,
                            panelState = panelWindow.getCurrentState(),
                            isDarkSurface = latestDarkSurface,
                        )
                    }
                },
                onEvent = { env ->
                    mainHandler.post { dispatchDaemonEvent(env) }
                },
            )

        edgeIndicator = EdgeIndicatorWindow(this, windowManager)
        webBridge =
            PanelWebViewBridge(
                context = this,
                scope = lifecycleScope,
                daemonClient = daemonClient,
                containerDelegate =
                    object : PanelContainerDelegate {
                        override fun dismissPanel() {
                            panelWindow.close()
                        }

                        override fun setImeFocused(focused: Boolean) {
                            panelWindow.setImeFocused(focused)
                        }

                        override fun syncAppearance(
                            themeId: String,
                            colorScheme: String,
                            wantsNativeBlur: Boolean,
                            cornerRadiusPx: Int,
                        ) {
                            latestDarkSurface = colorScheme != "light"
                            panelWindow.syncRuntimeAppearance(themeId, colorScheme, wantsNativeBlur, cornerRadiusPx)
                            edgeIndicator.update(
                                latestConfig.edge,
                                latestScopeActive,
                                panelWindow.getCurrentState(),
                                latestDarkSurface,
                            )
                        }
                    },
            )

        panelWindow =
            SidebarPanelWindow(
                context = this,
                windowManager = windowManager,
                webBridge = webBridge,
                onPanelStateChanged = { state ->
                    daemonClient.sendEvent(
                        SideveilEvents.PANEL_STATE_CHANGED,
                        SideveilWireJson.encodeToJsonElement(PanelStateChangedPayload(state)),
                    )
                    edgeIndicator.update(latestConfig.edge, latestScopeActive, state, latestDarkSurface)
                    webBridge.pushEvent(
                        SideveilEvents.PANEL_VISIBILITY,
                        SideveilWireJson.encodeToJsonElement(
                            PanelVisibilityPayload(visible = state != PanelState.CLOSED, state = state),
                        ),
                    )
                },
            )

        edgeIndicator.attach()
        panelWindow.attachWarm()
        webBridge.warmLoad()
        syncEndpointFromDisk()
        Log.i(TAG, "HostService created (pid=${Process.myPid()}, uid=${Process.myUid()})")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)
        syncEndpointFromDisk()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        latestDarkSurface = resolveDarkSurface(latestConfig, newConfig)
        panelWindow.onSystemConfigurationChanged()
        panelWindow.updateConfig(latestConfig.edge.side, latestConfig.panel)
        edgeIndicator.update(
            latestConfig.edge,
            latestScopeActive,
            panelWindow.getCurrentState(),
            latestDarkSurface,
        )
    }

    override fun onDestroy() {
        daemonClient.close()
        edgeIndicator.detach()
        panelWindow.detach()
        webBridge.destroy()
        super.onDestroy()
    }

    private fun dispatchDaemonEvent(envelope: HostEventEnvelope) {
        val payload = envelope.payload
        when (envelope.event) {
            SideveilEvents.CONFIG_UPDATED -> {
                val decoded =
                    payload?.let {
                        runCatching { SideveilWireJson.decodeFromJsonElement(ConfigUpdatedPayload.serializer(), it) }.getOrNull()
                    } ?: return
                latestConfig = decoded.config
                latestScopeActive = decoded.scopeActive
                latestDarkSurface = resolveDarkSurface(decoded.config)
                panelWindow.updateConfig(decoded.config.edge.side, decoded.config.panel)
                edgeIndicator.update(
                    decoded.config.edge,
                    decoded.scopeActive,
                    panelWindow.getCurrentState(),
                    latestDarkSurface,
                )
                webBridge.pushDaemonEvent(envelope)
            }

            SideveilEvents.SCOPE_CHANGED -> {
                val decoded =
                    payload?.let {
                        runCatching { SideveilWireJson.decodeFromJsonElement(ScopeChangedPayload.serializer(), it) }.getOrNull()
                    } ?: return
                latestScopeActive = decoded.active
                edgeIndicator.update(
                    latestConfig.edge,
                    decoded.active,
                    panelWindow.getCurrentState(),
                    latestDarkSurface,
                )
                webBridge.pushDaemonEvent(envelope)
            }

            SideveilEvents.GESTURE_DRAG_START -> {
                panelWindow.onGestureStart()
            }

            SideveilEvents.GESTURE_DRAG_MOVE -> {
                val decoded =
                    payload?.let {
                        runCatching { SideveilWireJson.decodeFromJsonElement(GestureDragMovePayload.serializer(), it) }.getOrNull()
                    } ?: return
                edgeIndicator.setDragPulse(decoded.progress)
                panelWindow.onGestureMove(decoded.progress)
            }

            SideveilEvents.GESTURE_DRAG_END -> {
                val decoded =
                    payload?.let {
                        runCatching { SideveilWireJson.decodeFromJsonElement(GestureDragEndPayload.serializer(), it) }.getOrNull()
                    } ?: return
                edgeIndicator.setDragPulse(0f)
                panelWindow.onGestureEnd(decoded.shouldOpen, decoded.velocityPxPerSec)
            }

            SideveilEvents.PANEL_COMMAND -> {
                val decoded =
                    payload?.let {
                        runCatching { SideveilWireJson.decodeFromJsonElement(PanelCommandPayload.serializer(), it) }.getOrNull()
                    } ?: return
                when (decoded.command) {
                    "open" -> panelWindow.open()
                    "close" -> panelWindow.close()
                    "toggle" -> panelWindow.toggle()
                }
            }

            else -> {
                webBridge.pushDaemonEvent(envelope)
            }
        }
    }

    private fun promoteForeground() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
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
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_sideveil_stat)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()

        startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun syncEndpointFromDisk() {
        val endpointFile = File(noBackupFilesDir, ENDPOINT_FILE_NAME)
        if (!endpointFile.isFile || !endpointFile.canRead()) return
        runCatching {
            val root = SideveilWireJson.parseToJsonElement(endpointFile.readText(Charsets.UTF_8)).jsonObject
            val port = root["port"]?.jsonPrimitive?.intOrNull ?: 0
            val token = root["token"]?.jsonPrimitive?.content.orEmpty()
            if (port in 1..65535 && TOKEN_REGEX.matches(token)) {
                daemonClient.updateEndpoint(port, token)
            } else {
                Log.w(TAG, "Ignored malformed daemon endpoint file")
            }
        }.onFailure { err ->
            Log.w(TAG, "Failed to read daemon endpoint file: ${err.message}")
        }
    }

    private fun resolveDarkSurface(
        config: SideveilConfig,
        sysConfig: Configuration = resources.configuration,
    ): Boolean =
        when (config.panel.colorMode) {
            "light" -> false
            "dark" -> true
            else -> (sysConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }

    companion object {
        private const val TAG = "SideveilHostService"
        private const val CHANNEL_ID = "sideveil_host_channel"
        private const val NOTIF_ID = 1001
        private const val ENDPOINT_FILE_NAME = "daemon_endpoint.json"
        private val TOKEN_REGEX = Regex("^[0-9a-f]{64}$")
    }
}
