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
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.protocol.ConfigUpdatedPayload
import pochita.sideveil.protocol.DaemonEndpoint
import pochita.sideveil.protocol.GestureDragEndPayload
import pochita.sideveil.protocol.GestureDragMovePayload
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.HostHelloPayload
import pochita.sideveil.protocol.PanelCommand
import pochita.sideveil.protocol.PanelCommandPayload
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PanelStateChangedPayload
import pochita.sideveil.protocol.PluginActiveChangedPayload
import pochita.sideveil.protocol.PluginBackendStatePayload
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginScopedEventPayload
import pochita.sideveil.protocol.PluginSelectEventPayload
import pochita.sideveil.protocol.PluginSurfaceBackdrop
import pochita.sideveil.protocol.PluginsSnapshotPayload
import pochita.sideveil.protocol.ScopeChangedPayload
import pochita.sideveil.protocol.SideveilConfig
import pochita.sideveil.protocol.SideveilEvents
import pochita.sideveil.protocol.SideveilWireJson
import java.io.File

/**
 * Headless foreground service running inside the AMS-managed SideveilHost priv-app process.
 * Hosts warm overlay windows (edge handle, multi-plugin Rail, sidebar panel) and manages
 * isolated per-plugin WebView sandboxes bridged to `sideveild`.
 */
class HostService : LifecycleService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var daemonClient: DaemonClient
    private lateinit var edgeIndicator: EdgeIndicatorWindow
    private lateinit var pluginRail: PluginRailWindow
    private lateinit var panelWindow: SidebarPanelWindow
    private lateinit var pluginManager: PluginWebViewManager

    private var latestConfig: SideveilConfig = SideveilConfig.DEFAULT
    private var latestScopeActive: Boolean = false
    private var latestDarkSurface: Boolean = true
    private var latestThemeId: String = "coloros-glass"
    private var latestForegroundPackage: String = ""

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        latestDarkSurface = resolveDarkSurface(latestConfig)
        latestThemeId = latestConfig.panel.themeId
        promoteForeground()

        daemonClient = createDaemonClient()
        edgeIndicator = EdgeIndicatorWindow(this, windowManager)
        pluginRail =
            PluginRailWindow(
                context = this,
                windowManager = windowManager,
                onSelectPlugin = { pluginId ->
                    if (pluginManager.selectPlugin(pluginId)) {
                        daemonClient.sendEvent(
                            SideveilEvents.HOST_SELECT_PLUGIN,
                            SideveilWireJson.encodeToJsonElement(
                                PluginSelectEventPayload(
                                    pluginId = pluginId,
                                    packageName = latestForegroundPackage.ifBlank { null },
                                ),
                            ),
                        )
                    }
                },
            )
        panelWindow =
            SidebarPanelWindow(
                context = this,
                windowManager = windowManager,
                onPanelStateChanged = { state -> handlePanelStateChanged(state) },
                onBackPressedIntercept = { pluginManager.dispatchBackPress() },
            )
        pluginManager =
            PluginWebViewManager(
                context = this,
                scope = lifecycleScope,
                daemonClient = daemonClient,
                surfaceDelegate = createSurfaceDelegate(),
            )

        edgeIndicator.attach()
        pluginRail.attachWarm()
        panelWindow.attachWarm()
        pluginManager.warmStartDefault()
        syncEndpointFromDisk()
        Log.i(TAG, "HostService created (pid=${Process.myPid()}, uid=${Process.myUid()})")
    }

    private fun createDaemonClient(): DaemonClient =
        DaemonClient(
            scope = lifecycleScope,
            onConnected = {
                mainHandler.post {
                    daemonClient.sendEvent(
                        SideveilEvents.HOST_HELLO,
                        SideveilWireJson.encodeToJsonElement(
                            HostHelloPayload(
                                pid = Process.myPid(),
                                version = HOST_VERSION,
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
                    pluginRail.setPanelOpen(false)
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

    private fun createSurfaceDelegate(): PluginHostSurfaceDelegate =
        object : PluginHostSurfaceDelegate {
            override fun mountPluginWebView(
                webView: WebView,
                backdrop: PluginSurfaceBackdrop,
                sizeOverride: PluginSizeOverride,
            ) {
                panelWindow.setContentWebView(webView)
                panelWindow.setPluginBackdrop(backdrop)
                panelWindow.setPluginSizeOverride(sizeOverride)
            }

            override fun onDismissPanel() {
                panelWindow.close()
            }

            override fun onSetImeFocused(focused: Boolean) {
                panelWindow.setImeFocused(focused)
            }

            override fun onSyncAppearance(
                themeId: String,
                colorScheme: String,
            ) {
                latestThemeId = themeId
                latestDarkSurface = colorScheme != "light"
                panelWindow.syncRuntimeAppearance(themeId, colorScheme)
                refreshPluginRailStyle()
                edgeIndicator.update(
                    latestConfig.edge,
                    latestScopeActive,
                    panelWindow.getCurrentState(),
                    latestDarkSurface,
                )
            }

            override fun onPluginSizeChanged(sizeOverride: PluginSizeOverride) {
                panelWindow.setPluginSizeOverride(sizeOverride)
            }

            override fun onPluginBackdropChanged(backdrop: PluginSurfaceBackdrop) {
                panelWindow.setPluginBackdrop(backdrop)
            }

            override fun onMatchedPluginsUpdated(
                matchedPlugins: List<PluginDescriptor>,
                activePluginId: String,
            ) {
                pluginRail.updatePlugins(matchedPlugins, activePluginId)
                panelWindow.setRailInsetDp(pluginRail.railInsetDp())
            }
        }

    private fun handlePanelStateChanged(state: PanelState) {
        daemonClient.sendEvent(
            SideveilEvents.PANEL_STATE_CHANGED,
            SideveilWireJson.encodeToJsonElement(PanelStateChangedPayload(state)),
        )
        pluginRail.setPanelOpen(state == PanelState.OPEN)
        edgeIndicator.update(latestConfig.edge, latestScopeActive, state, latestDarkSurface)
        pluginManager.onPanelStateChanged(state)
    }

    private fun refreshPluginRailStyle() {
        pluginRail.updateStyle(
            side = latestConfig.edge.side,
            config = latestConfig.panel,
            themeId = latestThemeId,
            dark = latestDarkSurface,
            blurSupported = panelWindow.isSystemBlurSupported(),
        )
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
        refreshPluginRailStyle()
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
        pluginRail.detach()
        panelWindow.detach()
        pluginManager.destroyAll()
        super.onDestroy()
    }

    private fun dispatchDaemonEvent(envelope: HostEventEnvelope) {
        when (envelope.event) {
            SideveilEvents.CONFIG_UPDATED -> handleConfigUpdated(envelope)
            SideveilEvents.SCOPE_CHANGED -> handleScopeChanged(envelope)
            SideveilEvents.PLUGINS_UPDATED -> handlePluginsUpdated(envelope)
            SideveilEvents.PLUGINS_ACTIVE_CHANGED -> handlePluginActiveChanged(envelope)
            SideveilEvents.PLUGIN_BACKEND_STATE -> handlePluginBackendState(envelope)
            SideveilEvents.PLUGIN_EVENT -> handlePluginScopedEvent(envelope)
            SideveilEvents.GESTURE_DRAG_START -> panelWindow.onGestureStart()
            SideveilEvents.GESTURE_DRAG_MOVE -> handleGestureMove(envelope)
            SideveilEvents.GESTURE_DRAG_END -> handleGestureEnd(envelope)
            SideveilEvents.PANEL_COMMAND -> handlePanelCommand(envelope)
            else -> pluginManager.broadcastGlobalEvent(envelope)
        }
    }

    private fun handleConfigUpdated(envelope: HostEventEnvelope) {
        val decoded =
            envelope.payload?.let {
                runCatching { SideveilWireJson.decodeFromJsonElement(ConfigUpdatedPayload.serializer(), it) }.getOrNull()
            } ?: return
        latestConfig = decoded.config
        latestScopeActive = decoded.scopeActive
        latestForegroundPackage = decoded.foregroundPackage
        latestThemeId = decoded.config.panel.themeId
        latestDarkSurface = resolveDarkSurface(decoded.config)
        panelWindow.updateConfig(decoded.config.edge.side, decoded.config.panel)
        refreshPluginRailStyle()
        edgeIndicator.update(
            decoded.config.edge,
            decoded.scopeActive,
            panelWindow.getCurrentState(),
            latestDarkSurface,
        )
        pluginManager.broadcastGlobalEvent(envelope)
    }

    private fun handleScopeChanged(envelope: HostEventEnvelope) {
        val decoded =
            envelope.payload?.let {
                runCatching { SideveilWireJson.decodeFromJsonElement(ScopeChangedPayload.serializer(), it) }.getOrNull()
            } ?: return
        latestScopeActive = decoded.active
        latestForegroundPackage = decoded.foregroundPackage
        edgeIndicator.update(
            latestConfig.edge,
            decoded.active,
            panelWindow.getCurrentState(),
            latestDarkSurface,
        )
        pluginManager.broadcastGlobalEvent(envelope)
    }

    private fun handlePluginsUpdated(envelope: HostEventEnvelope) {
        val decoded =
            envelope.payload?.let {
                runCatching { SideveilWireJson.decodeFromJsonElement(PluginsSnapshotPayload.serializer(), it) }.getOrNull()
            } ?: return
        latestForegroundPackage = decoded.foregroundPackage
        latestScopeActive = decoded.matchedPluginIds.isNotEmpty()
        pluginManager.onPluginsSnapshot(decoded)
        refreshPluginRailStyle()
        edgeIndicator.update(
            latestConfig.edge,
            latestScopeActive,
            panelWindow.getCurrentState(),
            latestDarkSurface,
        )
    }

    private fun handlePluginActiveChanged(envelope: HostEventEnvelope) {
        val decoded =
            envelope.payload?.let {
                runCatching { SideveilWireJson.decodeFromJsonElement(PluginActiveChangedPayload.serializer(), it) }.getOrNull()
            } ?: return
        pluginManager.selectPlugin(decoded.pluginId)
    }

    private fun handlePluginBackendState(envelope: HostEventEnvelope) {
        val decoded =
            envelope.payload?.let {
                runCatching { SideveilWireJson.decodeFromJsonElement(PluginBackendStatePayload.serializer(), it) }.getOrNull()
            } ?: return
        pluginManager.routeBackendState(decoded)
    }

    private fun handlePluginScopedEvent(envelope: HostEventEnvelope) {
        val decoded =
            envelope.payload?.let {
                runCatching { SideveilWireJson.decodeFromJsonElement(PluginScopedEventPayload.serializer(), it) }.getOrNull()
            } ?: return
        pluginManager.routePluginScopedEvent(decoded)
    }

    private fun handleGestureMove(envelope: HostEventEnvelope) {
        val decoded =
            envelope.payload?.let {
                runCatching { SideveilWireJson.decodeFromJsonElement(GestureDragMovePayload.serializer(), it) }.getOrNull()
            } ?: return
        edgeIndicator.setDragPulse(decoded.progress)
        panelWindow.onGestureMove(decoded.progress)
    }

    private fun handleGestureEnd(envelope: HostEventEnvelope) {
        val decoded =
            envelope.payload?.let {
                runCatching { SideveilWireJson.decodeFromJsonElement(GestureDragEndPayload.serializer(), it) }.getOrNull()
            } ?: return
        edgeIndicator.setDragPulse(0f)
        panelWindow.onGestureEnd(decoded.shouldOpen, decoded.velocityPxPerSec)
    }

    private fun handlePanelCommand(envelope: HostEventEnvelope) {
        val decoded =
            envelope.payload?.let {
                runCatching { SideveilWireJson.decodeFromJsonElement(PanelCommandPayload.serializer(), it) }.getOrNull()
            } ?: return
        when (decoded.command) {
            PanelCommand.OPEN -> panelWindow.open()
            PanelCommand.CLOSE -> panelWindow.close()
            PanelCommand.TOGGLE -> panelWindow.toggle()
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
            val endpoint = SideveilWireJson.decodeFromString<DaemonEndpoint>(endpointFile.readText(Charsets.UTF_8))
            if (endpoint.port in 1..65535 && TOKEN_REGEX.matches(endpoint.token)) {
                daemonClient.updateEndpoint(endpoint.port, endpoint.token)
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
        private const val HOST_VERSION = "0.1.0"
        private const val CHANNEL_ID = "sideveil_host_channel"
        private const val NOTIF_ID = 1001
        private const val ENDPOINT_FILE_NAME = "daemon_endpoint.json"
        private val TOKEN_REGEX = Regex("^[0-9a-f]{64}$")
    }
}
