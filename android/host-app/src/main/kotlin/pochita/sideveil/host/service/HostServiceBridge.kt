package pochita.sideveil.host.service

import android.content.Context
import android.content.res.Configuration
import android.os.Process
import kotlinx.serialization.json.encodeToJsonElement
import pochita.sideveil.host.BuildConfig
import pochita.sideveil.host.edge.EdgeIndicatorWindow
import pochita.sideveil.host.overlay.DynamicThemeExtractor
import pochita.sideveil.host.overlay.EdgeHapticsController
import pochita.sideveil.host.panel.SidebarPanelWindow
import pochita.sideveil.host.plugin.PluginWebViewManager
import pochita.sideveil.host.rail.PluginRailWindow
import pochita.sideveil.host.rail.RailIconLoader
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
 * Coordinates daemon session callbacks, inbound [HostEventCallbacks], window/rail interactions,
 * and shared runtime config/theme state on behalf of [pochita.sideveil.host.HostService].
 */
class HostServiceBridge(
    private val context: Context,
    private val daemonClient: DaemonClient,
    private val haptics: EdgeHapticsController,
    private val iconLoader: RailIconLoader,
    private val edgeIndicator: EdgeIndicatorWindow,
    private val pluginRail: PluginRailWindow,
    private val panelWindow: SidebarPanelWindow,
    private val dynamicThemeExtractor: DynamicThemeExtractor,
    private val pluginManager: PluginWebViewManager,
    private val overlayCoordinator: HostOverlayCoordinator,
) : HostEventCallbacks {
    var latestConfig: SideVeilConfig = SideVeilConfig.DEFAULT
        private set
    var latestScopeActive: Boolean = false
        private set
    var latestDarkSurface: Boolean =
        HostServiceSupport.resolveDarkSurface(SideVeilConfig.DEFAULT, context.resources.configuration)
        private set
    var latestThemeId: String = SideVeilConfig.DEFAULT.panel.themeId
        private set

    private var latestForegroundPackage: String = ""
    private var latestPluginIconSignature: String = ""

    /** Handles Android configuration changes (rotation, density, night mode). */
    fun onConfigurationChanged(newConfig: Configuration) {
        latestDarkSurface = HostServiceSupport.resolveDarkSurface(latestConfig, newConfig)
        panelWindow.onSystemConfigurationChanged()
        overlayCoordinator.refreshOverlayStyles()
        refreshEdgeIndicator()
        dynamicThemeExtractor.refresh(latestDarkSurface)
    }

    /** Synchronizes runtime appearance requested by a plugin's `syncTheme` container action. */
    fun onSyncAppearance(
        themeId: String,
        colorScheme: String,
    ) {
        latestThemeId = themeId
        latestDarkSurface = colorScheme != "light"
        panelWindow.syncRuntimeAppearance(themeId, colorScheme)
        overlayCoordinator.refreshOverlayStyles()
        refreshEdgeIndicator()
        dynamicThemeExtractor.refresh(latestDarkSurface)
    }

    /** Handles user selection of a plugin slot in [PluginRailWindow]. */
    fun onRailPluginSelected(pluginId: String) {
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

    /** Reports fullscreen status-bar visibility transitions to `sideveild`. */
    fun onFullscreenChanged(fullscreen: Boolean) {
        daemonClient.sendEvent(
            SideVeilEvents.HOST_FULLSCREEN_CHANGED,
            SideVeilWireJson.encodeToJsonElement(HostFullscreenChangedPayload(fullscreen)),
        )
    }

    /** Reports [SidebarPanelWindow] state transitions to `sideveild` and updates auxiliary windows. */
    fun onPanelStateChanged(state: PanelState) {
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

    /** Closes the panel when Android requests system dialogs to dismiss. */
    fun onSystemDialogCloseRequested() {
        if (panelWindow.currentState == PanelState.OPEN) {
            panelWindow.close()
        }
    }

    /** Sends initial handshake and state events when [DaemonClient] connects to `sideveild`. */
    fun onDaemonConnected() {
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
        onFullscreenChanged(edgeIndicator.isFullscreenActive())
        dynamicThemeExtractor.refresh(latestDarkSurface, force = true)
    }

    /** Collapses transient overlays and force-closes the panel if the daemon connection drops. */
    fun onDaemonDisconnected() {
        latestScopeActive = false
        pluginRail.setPanelOpen(false)
        overlayCoordinator.hideTransientOverlays()
        panelWindow.forceCloseImmediate()
        refreshEdgeIndicator()
    }

    override fun onConfigUpdated(
        decoded: ConfigUpdatedPayload,
        rawEnvelope: HostEventEnvelope,
    ) {
        latestConfig = decoded.config
        latestScopeActive = decoded.scopeActive
        latestForegroundPackage = decoded.foregroundPackage
        latestThemeId = decoded.config.panel.themeId
        latestDarkSurface = HostServiceSupport.resolveDarkSurface(decoded.config, context.resources.configuration)
        pluginManager.setConfigWebViewDebugging(decoded.config.debug.webviewDebuggingEnabled)
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

    override fun onNetworkContextChanged(rawEnvelope: HostEventEnvelope) = pluginManager.routeNetworkContextChanged(rawEnvelope)

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

    /** Synchronizes [EdgeIndicatorWindow] state with the latest config, scope, and panel state. */
    fun refreshEdgeIndicator() {
        edgeIndicator.update(
            latestConfig.edge,
            latestScopeActive,
            panelWindow.currentState,
            latestDarkSurface,
        )
    }
}
