package pochita.sideveil.host

import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.WindowManager
import android.webkit.WebView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.protocol.ConfigUpdatedPayload
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.HostFullscreenChangedPayload
import pochita.sideveil.protocol.HostHelloPayload
import pochita.sideveil.protocol.PanelCommand
import pochita.sideveil.protocol.PanelState
import pochita.sideveil.protocol.PanelStateChangedPayload
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

/**
 * Headless foreground service running inside the AMS-managed SideveilHost priv-app process.
 * Hosts warm overlay windows (edge handle, multi-plugin Rail, sidebar panel) and manages
 * isolated per-plugin WebView sandboxes bridged to `sideveild`.
 */
class HostService : LifecycleService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private lateinit var daemonClient: DaemonClient
    private lateinit var haptics: EdgeHapticsController
    private lateinit var iconLoader: RailIconLoader
    private lateinit var edgeIndicator: EdgeIndicatorWindow
    private lateinit var pluginRail: PluginRailWindow
    private lateinit var panelWindow: SidebarPanelWindow
    private lateinit var pluginManager: PluginWebViewManager
    private lateinit var gestureCoordinator: HostGestureCoordinator
    private lateinit var eventDispatcher: HostEventDispatcher

    private var latestConfig: SideveilConfig = SideveilConfig.DEFAULT
    private var latestScopeActive: Boolean = false
    private var latestDarkSurface: Boolean = true
    private var latestThemeId: String = "coloros-glass"
    private var latestForegroundPackage: String = ""

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        latestDarkSurface = HostServiceSupport.resolveDarkSurface(latestConfig, resources.configuration)
        latestThemeId = latestConfig.panel.themeId
        HostServiceSupport.promoteForeground(this)
        initializeRuntimeComponents()
        attachWarmWindows()
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
        latestDarkSurface = HostServiceSupport.resolveDarkSurface(latestConfig, newConfig)
        panelWindow.onSystemConfigurationChanged()
        refreshPluginRailStyle()
        refreshEdgeIndicator()
    }

    override fun onDestroy() {
        daemonClient.close()
        iconLoader.clear()
        edgeIndicator.detach()
        pluginRail.detach()
        panelWindow.detach()
        pluginManager.destroyAll()
        super.onDestroy()
    }

    private fun initializeRuntimeComponents() {
        daemonClient = createDaemonClient()
        haptics = EdgeHapticsController(this)
        iconLoader = RailIconLoader(lifecycleScope) { daemonClient.currentEndpoint() }
        edgeIndicator =
            EdgeIndicatorWindow(
                context = this,
                windowManager = windowManager,
                onFullscreenChanged = ::handleFullscreenChanged,
            )
        pluginRail =
            PluginRailWindow(
                context = this,
                windowManager = windowManager,
                iconLoader = iconLoader,
                onSelectPlugin = ::handleRailPluginSelected,
                onQuickAction = { pluginId, action ->
                    gestureCoordinator.executeRailQuickAction(pluginId, action)
                },
            )
        panelWindow =
            SidebarPanelWindow(
                context = this,
                windowManager = windowManager,
                onPanelStateChanged = ::handlePanelStateChanged,
                onBackPressedIntercept = { pluginManager.dispatchBackPress() },
                isBackIntercepted = { pluginManager.isBackIntercepted() },
                onRailMotionProgress = { progress -> pluginRail.updateMotionProgress(progress) },
            )
        pluginManager =
            PluginWebViewManager(
                context = this,
                scope = lifecycleScope,
                daemonClient = daemonClient,
                surfaceDelegate = createSurfaceDelegate(),
            )
        gestureCoordinator =
            HostGestureCoordinator(
                context = this,
                windowManager = windowManager,
                scope = lifecycleScope,
                haptics = haptics,
                edgeIndicator = edgeIndicator,
                pluginRail = pluginRail,
                panelWindow = panelWindow,
                daemonClient = daemonClient,
                pluginManager = pluginManager,
                configProvider = { latestConfig },
                onSelectPlugin = ::handleRailPluginSelected,
            )
        eventDispatcher = HostEventDispatcher(createEventCallbacks(), gestureCoordinator)
    }

    private fun attachWarmWindows() {
        edgeIndicator.attach()
        pluginRail.attachWarm()
        panelWindow.attachWarm()
        pluginManager.warmStartDefault()
    }

    private fun handleRailPluginSelected(pluginId: String) {
        if (!pluginManager.selectPlugin(pluginId)) return
        haptics.onRailSwitch()
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

    private fun handleFullscreenChanged(fullscreen: Boolean) {
        daemonClient.sendEvent(
            SideveilEvents.HOST_FULLSCREEN_CHANGED,
            SideveilWireJson.encodeToJsonElement(HostFullscreenChangedPayload(fullscreen)),
        )
    }

    private fun createDaemonClient(): DaemonClient =
        DaemonClient(
            scope = lifecycleScope,
            onConnected = {
                mainHandler.post { sendHostConnectedState() }
            },
            onDisconnected = {
                mainHandler.post { handleDaemonDisconnected() }
            },
            onEvent = { env ->
                mainHandler.post { eventDispatcher.dispatch(env) }
            },
        )

    private fun sendHostConnectedState() {
        daemonClient.sendEvent(
            SideveilEvents.HOST_HELLO,
            SideveilWireJson.encodeToJsonElement(
                HostHelloPayload(
                    pid = Process.myPid(),
                    version = BuildConfig.VERSION_NAME,
                    crossWindowBlurSupported = panelWindow.isSystemBlurSupported,
                ),
            ),
        )
        val currentState = panelWindow.currentState
        if (currentState != PanelState.CLOSED) {
            daemonClient.sendEvent(
                SideveilEvents.PANEL_STATE_CHANGED,
                SideveilWireJson.encodeToJsonElement(PanelStateChangedPayload(currentState)),
            )
        }
        handleFullscreenChanged(edgeIndicator.isFullscreenActive())
    }

    private fun handleDaemonDisconnected() {
        latestScopeActive = false
        pluginRail.setPanelOpen(false)
        if (panelWindow.currentState != PanelState.CLOSED) {
            panelWindow.close()
        }
        refreshEdgeIndicator()
    }

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

            override fun onDismissPanel() = panelWindow.close()

            override fun onSetImeFocused(focused: Boolean) = panelWindow.setImeFocused(focused)

            override fun onSyncAppearance(
                themeId: String,
                colorScheme: String,
            ) {
                latestThemeId = themeId
                latestDarkSurface = colorScheme != "light"
                panelWindow.syncRuntimeAppearance(themeId, colorScheme)
                refreshPluginRailStyle()
                refreshEdgeIndicator()
            }

            override fun onPluginSizeChanged(sizeOverride: PluginSizeOverride) = panelWindow.setPluginSizeOverride(sizeOverride)

            override fun onPluginBackdropChanged(backdrop: PluginSurfaceBackdrop) = panelWindow.setPluginBackdrop(backdrop)

            override fun onMatchedPluginsUpdated(
                matchedPlugins: List<PluginDescriptor>,
                activePluginId: String,
            ) {
                pluginRail.updatePlugins(matchedPlugins, activePluginId)
                panelWindow.setRailInsetDp(pluginRail.railInsetDp())
            }
        }

    private fun createEventCallbacks(): HostEventCallbacks =
        object : HostEventCallbacks {
            override fun onConfigUpdated(
                decoded: ConfigUpdatedPayload,
                rawEnvelope: HostEventEnvelope,
            ) {
                latestConfig = decoded.config
                latestScopeActive = decoded.scopeActive
                latestForegroundPackage = decoded.foregroundPackage
                latestThemeId = decoded.config.panel.themeId
                latestDarkSurface = HostServiceSupport.resolveDarkSurface(decoded.config, resources.configuration)
                panelWindow.updateConfig(decoded.config.edge.side, decoded.config.panel)
                refreshPluginRailStyle()
                refreshEdgeIndicator()
                pluginManager.broadcastGlobalEvent(rawEnvelope)
            }

            override fun onScopeChanged(
                decoded: ScopeChangedPayload,
                rawEnvelope: HostEventEnvelope,
            ) {
                latestScopeActive = decoded.active
                latestForegroundPackage = decoded.foregroundPackage
                refreshEdgeIndicator()
                pluginManager.broadcastGlobalEvent(rawEnvelope)
            }

            override fun onPluginsUpdated(decoded: PluginsSnapshotPayload) {
                latestForegroundPackage = decoded.foregroundPackage
                latestScopeActive = decoded.matchedPluginIds.isNotEmpty()
                iconLoader.clear()
                pluginManager.onPluginsSnapshot(decoded)
                refreshPluginRailStyle()
                refreshEdgeIndicator()
            }

            override fun onPluginActiveChanged(pluginId: String) {
                pluginManager.selectPlugin(pluginId)
            }

            override fun onPluginBackendState(decoded: PluginBackendStatePayload) = pluginManager.routeBackendState(decoded)

            override fun onPluginScopedEvent(decoded: PluginScopedEventPayload) = pluginManager.routePluginScopedEvent(decoded)

            override fun onPanelCommand(command: PanelCommand) {
                when (command) {
                    PanelCommand.OPEN -> panelWindow.open()
                    PanelCommand.CLOSE -> panelWindow.close()
                    PanelCommand.TOGGLE -> panelWindow.toggle()
                }
            }

            override fun onFallbackGlobalEvent(envelope: HostEventEnvelope) = pluginManager.broadcastGlobalEvent(envelope)
        }

    private fun handlePanelStateChanged(state: PanelState) {
        daemonClient.sendEvent(
            SideveilEvents.PANEL_STATE_CHANGED,
            SideveilWireJson.encodeToJsonElement(PanelStateChangedPayload(state)),
        )
        if (state == PanelState.CLOSED) {
            pluginRail.setPanelOpen(false)
        }
        refreshEdgeIndicator()
        pluginManager.onPanelStateChanged(state)
    }

    private fun refreshEdgeIndicator() {
        edgeIndicator.update(
            latestConfig.edge,
            latestScopeActive,
            panelWindow.currentState,
            latestDarkSurface,
        )
    }

    private fun refreshPluginRailStyle() {
        pluginRail.updateStyle(
            side = latestConfig.edge.side,
            config = latestConfig.panel,
            themeId = latestThemeId,
            dark = latestDarkSurface,
            blurSupported = panelWindow.isSystemBlurSupported,
        )
    }

    private fun syncEndpointFromDisk() {
        HostServiceSupport.readEndpointFromDisk(this)?.let { endpoint ->
            daemonClient.updateEndpoint(endpoint.port, endpoint.token)
        }
    }

    companion object {
        private const val TAG = "SideveilHostService"
    }
}
