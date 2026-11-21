package pochita.sideveil.host

import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.WindowManager
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
import pochita.sideveil.protocol.PluginScopedEventPayload
import pochita.sideveil.protocol.PluginSelectEventPayload
import pochita.sideveil.protocol.PluginsSnapshotPayload
import pochita.sideveil.protocol.ScopeChangedPayload
import pochita.sideveil.protocol.SideVeilConfig
import pochita.sideveil.protocol.SideVeilEvents
import pochita.sideveil.protocol.SideVeilWireJson

/**
 * Headless foreground service running inside the AMS-managed SideVeilHost priv-app process.
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
    private lateinit var pinnedWindow: PinnedContainerWindow
    private lateinit var nativeHudWindow: NativeHudWindow
    private lateinit var dynamicThemeExtractor: DynamicThemeExtractor
    private lateinit var pluginManager: PluginWebViewManager
    private lateinit var gestureCoordinator: HostGestureCoordinator
    private lateinit var overlayCoordinator: HostOverlayCoordinator
    private lateinit var eventDispatcher: HostEventDispatcher

    private var latestConfig: SideVeilConfig = SideVeilConfig.DEFAULT
    private var latestScopeActive: Boolean = false
    private var latestDarkSurface: Boolean = true
    private var latestThemeId: String = "coloros-glass"
    private var latestForegroundPackage: String = ""
    private var latestPluginIconSignature: String = ""

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
        overlayCoordinator.refreshOverlayStyles()
        refreshEdgeIndicator()
        dynamicThemeExtractor.refresh(latestDarkSurface)
    }

    override fun onDestroy() {
        dynamicThemeExtractor.stop()
        daemonClient.close()
        iconLoader.clear()
        edgeIndicator.detach()
        pluginRail.detach()
        pinnedWindow.detach()
        nativeHudWindow.detach()
        panelWindow.detach()
        pluginManager.destroyAll()
        super.onDestroy()
    }

    private fun initializeRuntimeComponents() {
        initializeWindows()
        initializeCoordinators()
    }

    private fun initializeWindows() {
        daemonClient = createDaemonClient()
        haptics = EdgeHapticsController(this)
        iconLoader = RailIconLoader(lifecycleScope) { daemonClient.currentEndpoint() }
        edgeIndicator = EdgeIndicatorWindow(this, windowManager, ::handleFullscreenChanged)
        pluginRail =
            PluginRailWindow(
                context = this,
                windowManager = windowManager,
                iconLoader = iconLoader,
                onSelectPlugin = ::handleRailPluginSelected,
                onQuickAction = { pluginId, action ->
                    gestureCoordinator.executeRailQuickAction(pluginId, action)
                },
                onTouchActivity = {
                    if (::panelWindow.isInitialized) {
                        panelWindow.cancelPendingTimers(includeCollapse = false)
                    }
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
                shouldIgnoreOutsideTouch = { rawX, rawY ->
                    pluginRail.isTouchActiveOrRecent() || pluginRail.isScreenPointNearRail(rawX, rawY)
                },
            )
        pinnedWindow =
            PinnedContainerWindow(
                context = this,
                windowManager = windowManager,
                onClickThroughChanged = { clickThrough ->
                    overlayCoordinator.handlePinnedClickThroughChanged(clickThrough)
                },
            )
        nativeHudWindow =
            NativeHudWindow(this, windowManager) { activeHudId ->
                if (::pluginManager.isInitialized) {
                    pluginManager.setActiveHudPlugin(activeHudId)
                }
            }
    }

    private fun initializeCoordinators() {
        overlayCoordinator =
            HostOverlayCoordinator(
                daemonClient = daemonClient,
                edgeIndicator = edgeIndicator,
                pluginRail = pluginRail,
                panelWindow = panelWindow,
                pinnedWindow = pinnedWindow,
                nativeHudWindow = nativeHudWindow,
                pluginManagerProvider = { pluginManager },
                configProvider = { latestConfig },
                themeIdProvider = { latestThemeId },
                darkSurfaceProvider = { latestDarkSurface },
            )
        dynamicThemeExtractor =
            DynamicThemeExtractor(this) { colors ->
                overlayCoordinator.onDynamicColorsExtracted(colors)
            }
        pluginManager =
            PluginWebViewManager(
                context = this,
                scope = lifecycleScope,
                daemonClient = daemonClient,
                haptics = haptics,
                surfaceDelegate =
                    overlayCoordinator.createSurfaceDelegate { themeId, colorScheme ->
                        latestThemeId = themeId
                        latestDarkSurface = colorScheme != "light"
                        panelWindow.syncRuntimeAppearance(themeId, colorScheme)
                        overlayCoordinator.refreshOverlayStyles()
                        refreshEdgeIndicator()
                        dynamicThemeExtractor.refresh(latestDarkSurface)
                    },
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
                onBeforeGestureOpen = { overlayCoordinator.ensureDrawerMountedBeforeOpen() },
            )
        eventDispatcher = HostEventDispatcher(createEventCallbacks(), gestureCoordinator, overlayCoordinator)
    }

    private fun attachWarmWindows() {
        edgeIndicator.attach()
        pluginRail.attachWarm()
        pinnedWindow.attachWarm()
        nativeHudWindow.attachWarm()
        panelWindow.attachWarm()
        pluginManager.warmStartDefault()
        dynamicThemeExtractor.start(latestDarkSurface)
    }

    private fun handleRailPluginSelected(pluginId: String) {
        if (!pluginManager.selectPlugin(pluginId)) return
        haptics.onRailSwitch()
        daemonClient.sendEvent(
            SideVeilEvents.HOST_SELECT_PLUGIN,
            SideVeilWireJson.encodeToJsonElement(
                PluginSelectEventPayload(
                    pluginId = pluginId,
                    packageName = latestForegroundPackage.ifBlank { null },
                ),
            ),
        )
    }

    private fun handleFullscreenChanged(fullscreen: Boolean) {
        daemonClient.sendEvent(
            SideVeilEvents.HOST_FULLSCREEN_CHANGED,
            SideVeilWireJson.encodeToJsonElement(HostFullscreenChangedPayload(fullscreen)),
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
            SideVeilEvents.HOST_HELLO,
            SideVeilWireJson.encodeToJsonElement(
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
                SideVeilEvents.PANEL_STATE_CHANGED,
                SideVeilWireJson.encodeToJsonElement(PanelStateChangedPayload(currentState)),
            )
        }
        handleFullscreenChanged(edgeIndicator.isFullscreenActive())
        dynamicThemeExtractor.refresh(latestDarkSurface, force = true)
    }

    private fun handleDaemonDisconnected() {
        latestScopeActive = false
        pluginRail.setPanelOpen(false)
        overlayCoordinator.hideTransientOverlays()
        if (panelWindow.currentState != PanelState.CLOSED) {
            panelWindow.close()
        }
        refreshEdgeIndicator()
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
                overlayCoordinator.refreshOverlayStyles()
                refreshEdgeIndicator()
                dynamicThemeExtractor.refresh(latestDarkSurface)
                pluginManager.broadcastGlobalEvent(rawEnvelope)
            }

            override fun onScopeChanged(
                decoded: ScopeChangedPayload,
                rawEnvelope: HostEventEnvelope,
            ) {
                latestScopeActive = decoded.active
                latestForegroundPackage = decoded.foregroundPackage
                if (!decoded.active) {
                    overlayCoordinator.ensureDrawerMountedBeforeOpen()
                    edgeIndicator.setEdgeStatus(null)
                }
                refreshEdgeIndicator()
                pluginManager.broadcastGlobalEvent(rawEnvelope)
            }

            override fun onPluginsUpdated(decoded: PluginsSnapshotPayload) {
                latestForegroundPackage = decoded.foregroundPackage
                latestScopeActive = decoded.matchedPluginIds.isNotEmpty()
                val iconSig =
                    decoded.plugins.joinToString("|") { p -> "${p.id}:${p.version}:${p.icon.orEmpty()}" }
                if (iconSig != latestPluginIconSignature) {
                    latestPluginIconSignature = iconSig
                    iconLoader.clear()
                }
                pluginManager.onPluginsSnapshot(decoded)
                overlayCoordinator.refreshOverlayStyles()
                refreshEdgeIndicator()
            }

            override fun onPluginActiveChanged(pluginId: String) {
                pluginManager.selectPlugin(pluginId)
            }

            override fun onPluginBackendState(decoded: PluginBackendStatePayload) = pluginManager.routeBackendState(decoded)

            override fun onPluginScopedEvent(decoded: PluginScopedEventPayload) = pluginManager.routePluginScopedEvent(decoded)

            override fun onPanelCommand(command: PanelCommand) {
                when (command) {
                    PanelCommand.OPEN -> {
                        overlayCoordinator.ensureDrawerMountedBeforeOpen()
                        panelWindow.open()
                    }

                    PanelCommand.CLOSE -> {
                        panelWindow.close()
                    }

                    PanelCommand.TOGGLE -> {
                        if (panelWindow.currentState == PanelState.CLOSED) {
                            overlayCoordinator.ensureDrawerMountedBeforeOpen()
                        }
                        panelWindow.toggle()
                    }
                }
            }

            override fun onFallbackGlobalEvent(envelope: HostEventEnvelope) = pluginManager.broadcastGlobalEvent(envelope)
        }

    private fun handlePanelStateChanged(state: PanelState) {
        daemonClient.sendEvent(
            SideVeilEvents.PANEL_STATE_CHANGED,
            SideVeilWireJson.encodeToJsonElement(PanelStateChangedPayload(state)),
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

    private fun syncEndpointFromDisk() {
        HostServiceSupport.readEndpointFromDisk(this)?.let { endpoint ->
            daemonClient.updateEndpoint(endpoint.port, endpoint.token)
        }
    }

    companion object {
        private const val TAG = "SideVeilHostService"
    }
}
