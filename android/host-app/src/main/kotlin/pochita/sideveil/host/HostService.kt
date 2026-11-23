package pochita.sideveil.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.WindowManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import pochita.sideveil.protocol.RpcError
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.RpcResponse
import pochita.sideveil.protocol.SideVeilErrorCodes

/**
 * Headless foreground service running inside the AMS-managed SideVeilHost priv-app process.
 * Hosts warm overlay windows (edge handle, multi-plugin Rail, sidebar panel, pinned container,
 * native HUD) and wires them to `sideveild` via [HostServiceBridge].
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
    private lateinit var frameMetricsTracker: WindowFrameMetricsTracker
    private lateinit var debugInspector: HostDebugInspector
    private lateinit var gestureCoordinator: HostGestureCoordinator
    private lateinit var overlayCoordinator: HostOverlayCoordinator
    private lateinit var serviceBridge: HostServiceBridge
    private lateinit var eventDispatcher: HostEventDispatcher
    private var systemDialogsReceiverRegistered = false
    private val systemDialogsReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                @Suppress("DEPRECATION")
                if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
                val reason = intent.getStringExtra(SYSTEM_DIALOG_REASON_KEY)
                if (reason in DISMISS_SYSTEM_DIALOG_REASONS && ::serviceBridge.isInitialized) {
                    serviceBridge.onSystemDialogCloseRequested()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        HostServiceSupport.promoteForeground(this)
        initializeWindows()
        initializeCoordinators()
        attachWarmWindows()
        registerSystemDialogsReceiver()
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
        serviceBridge.onConfigurationChanged(newConfig)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::pluginManager.isInitialized) {
            pluginManager.onTrimMemory(level)
        }
    }

    override fun onDestroy() {
        unregisterSystemDialogsReceiver()
        frameMetricsTracker.destroy()
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

    private fun initializeWindows() {
        frameMetricsTracker = WindowFrameMetricsTracker()
        daemonClient =
            DaemonClient(
                scope = lifecycleScope,
                onConnected = { mainHandler.post { serviceBridge.onDaemonConnected() } },
                onDisconnected = { mainHandler.post { serviceBridge.onDaemonDisconnected() } },
                onEvent = { env -> mainHandler.post { eventDispatcher.dispatch(env) } },
                onHostRpcRequest = ::handleHostRpcRequest,
            )
        haptics = EdgeHapticsController(this)
        iconLoader = RailIconLoader(lifecycleScope) { daemonClient.currentEndpoint() }
        edgeIndicator =
            EdgeIndicatorWindow(this, windowManager) { fullscreen ->
                if (::serviceBridge.isInitialized) {
                    serviceBridge.onFullscreenChanged(fullscreen)
                }
            }
        pluginRail =
            PluginRailWindow(
                context = this,
                windowManager = windowManager,
                iconLoader = iconLoader,
                onSelectPlugin = { pluginId -> serviceBridge.onRailPluginSelected(pluginId) },
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
                onPanelStateChanged = { state -> serviceBridge.onPanelStateChanged(state) },
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
                configProvider = { serviceBridge.latestConfig },
                themeIdProvider = { serviceBridge.latestThemeId },
                darkSurfaceProvider = { serviceBridge.latestDarkSurface },
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
                        serviceBridge.onSyncAppearance(themeId, colorScheme)
                    },
            )
        serviceBridge =
            HostServiceBridge(
                context = this,
                daemonClient = daemonClient,
                haptics = haptics,
                iconLoader = iconLoader,
                edgeIndicator = edgeIndicator,
                pluginRail = pluginRail,
                panelWindow = panelWindow,
                dynamicThemeExtractor = dynamicThemeExtractor,
                pluginManager = pluginManager,
                overlayCoordinator = overlayCoordinator,
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
                configProvider = { serviceBridge.latestConfig },
                onSelectPlugin = { pluginId -> serviceBridge.onRailPluginSelected(pluginId) },
                onBeforeGestureOpen = { overlayCoordinator.ensureDrawerMountedBeforeOpen() },
            )
        eventDispatcher = HostEventDispatcher(serviceBridge, gestureCoordinator, overlayCoordinator)
        debugInspector =
            HostDebugInspector(
                context = this,
                windowManager = windowManager,
                mainHandler = mainHandler,
                panelWindow = panelWindow,
                pinnedWindow = pinnedWindow,
                nativeHudWindow = nativeHudWindow,
                pluginRail = pluginRail,
                edgeIndicator = edgeIndicator,
                pluginManager = pluginManager,
                frameMetricsTracker = frameMetricsTracker,
            )
    }

    private fun attachWarmWindows() {
        edgeIndicator.attach()
        pluginRail.attachWarm()
        pinnedWindow.attachWarm()
        nativeHudWindow.attachWarm()
        panelWindow.attachWarm()
        panelWindow.overlayWindow?.let { frameMetricsTracker.attach("panel", it) }
        pinnedWindow.overlayWindow?.let { frameMetricsTracker.attach("pinned", it) }
        nativeHudWindow.overlayWindow?.let { frameMetricsTracker.attach("hud", it) }
        pluginManager.warmStartDefault()
        dynamicThemeExtractor.start(serviceBridge.latestDarkSurface)
    }

    private suspend fun handleHostRpcRequest(req: RpcRequest): RpcResponse =
        if (::debugInspector.isInitialized) {
            debugInspector.handleRpcRequest(req)
        } else {
            RpcResponse(
                id = req.id,
                error = RpcError(SideVeilErrorCodes.INTERNAL_ERROR, "Host inspector not ready"),
            )
        }

    @Suppress("DEPRECATION")
    private fun registerSystemDialogsReceiver() {
        if (systemDialogsReceiverRegistered) return
        runCatching {
            registerReceiver(systemDialogsReceiver, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), RECEIVER_EXPORTED)
            systemDialogsReceiverRegistered = true
        }.onFailure { Log.w(TAG, "Failed to register ACTION_CLOSE_SYSTEM_DIALOGS receiver", it) }
    }

    private fun unregisterSystemDialogsReceiver() {
        if (!systemDialogsReceiverRegistered) return
        systemDialogsReceiverRegistered = false
        runCatching { unregisterReceiver(systemDialogsReceiver) }
    }

    private fun syncEndpointFromDisk() {
        HostServiceSupport.readEndpointFromDisk(this)?.let { endpoint ->
            daemonClient.updateEndpoint(endpoint.port, endpoint.token)
        }
    }

    companion object {
        private const val TAG = "SideVeilHostService"
        private const val SYSTEM_DIALOG_REASON_KEY = "reason"
        private val DISMISS_SYSTEM_DIALOG_REASONS =
            setOf("notification_shade", "status_bar", "homekey", "recentapps")
    }
}
