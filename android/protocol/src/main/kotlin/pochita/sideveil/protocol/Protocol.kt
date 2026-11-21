package pochita.sideveil.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

object SideVeilEvents {
    const val CONFIG_UPDATED = "config.updated"
    const val SCOPE_CHANGED = "scope.changed"
    const val GESTURE_DRAG_START = "gesture.dragStart"
    const val GESTURE_DRAG_MOVE = "gesture.dragMove"
    const val GESTURE_DRAG_END = "gesture.dragEnd"
    const val GESTURE_FULLSCREEN_HINT = "gesture.fullscreenHint"
    const val EDGE_REPOSITION = "edge.reposition"
    const val PANEL_COMMAND = "panel.command"
    const val HOST_HELLO = "host.hello"
    const val HOST_FULLSCREEN_CHANGED = "host.fullscreenChanged"
    const val HOST_DYNAMIC_COLORS = "host.dynamicColors"
    const val HOST_DIAGNOSTICS = "host.diagnostics"
    const val HOST_WEBVIEW_CONSOLE = "host.webviewConsole"
    const val HOST_SELECT_PLUGIN = "host.selectPlugin"
    const val PANEL_STATE_CHANGED = "panel.stateChanged"
    const val PANEL_VISIBILITY = "panel.visibility"
    const val PANEL_BACK_PRESSED = "panel.backPressed"
    const val CONTAINER_PRESENTATION_CHANGED = "container.presentationChanged"
    const val CONTAINER_CLICK_THROUGH_CHANGED = "container.clickThroughChanged"
    const val PLUGINS_UPDATED = "plugins.updated"
    const val PLUGINS_ACTIVE_CHANGED = "plugins.activeChanged"
    const val PLUGIN_BACKEND_STATE = "plugin.backendState"
    const val PLUGIN_EVENT = "plugin.event"
    const val PLUGIN_BUS_MESSAGE = "plugin.busMessage"
    const val THEME_PALETTE_UPDATED = "theme.paletteUpdated"
    const val HUD_SCENE_UPDATED = "hud.sceneUpdated"
    const val HUD_DISMISSED = "hud.dismissed"
}

object SideVeilMethods {
    const val DAEMON_PING = "daemon.ping"
    const val DAEMON_STATUS = "daemon.status"
    const val DAEMON_LOGS = "daemon.logs"
    const val CONFIG_GET = "config.get"
    const val CONFIG_PATCH_APPEARANCE = "config.patchAppearance"
    const val CONFIG_PATCH_EDGE = "config.patchEdge"
    const val CONFIG_RELOAD = "config.reload"
    const val PANEL_OPEN = "panel.open"
    const val PANEL_CLOSE = "panel.close"
    const val PANEL_TOGGLE = "panel.toggle"
    const val HOST_RESTART = "host.restart"
    const val CONTAINER_ACTION = "container.action"
    const val PLUGINS_LIST = "plugins.list"
    const val PLUGINS_RELOAD = "plugins.reload"
    const val PLUGINS_SET_ENABLED = "plugins.setEnabled"
    const val PLUGINS_SET_PRIORITY = "plugins.setPriority"
    const val PLUGINS_SET_DEV_URL = "plugins.setDevUrl"
    const val PLUGINS_AUDIT = "plugins.audit"
    const val PLUGIN_INVOKE = "plugin.invoke"
    const val PLUGIN_BUS_PUBLISH = "plugin.busPublish"
    const val TELEMETRY_SNAPSHOT = "telemetry.snapshot"
    const val CLIPBOARD_GET = "clipboard.get"
    const val CLIPBOARD_SET = "clipboard.set"
    const val HAPTICS_TRIGGER = "haptics.trigger"
    const val UI_TOAST = "ui.toast"
    const val HUD_UPDATE_SCENE = "hud.updateScene"
    const val HUD_DISMISS = "hud.dismiss"
}

@Serializable
data class DaemonEndpoint(
    val port: Int,
    val token: String,
)

@Serializable
enum class PanelCommand {
    @SerialName("open")
    OPEN,

    @SerialName("close")
    CLOSE,

    @SerialName("toggle")
    TOGGLE,
}

@Serializable
data class DaemonStatus(
    val pid: Int,
    val uid: Int,
    val uptimeMs: Long,
    val configVersion: Int,
    val enabled: Boolean,
    val scopeActive: Boolean,
    val foregroundPackage: String,
    val hostPackage: String,
    val hostConnected: Boolean,
    val hostPid: Int?,
    val overlayPermissionGranted: Boolean,
    val crossWindowBlurSupported: Boolean?,
    val gestureMonitorActive: Boolean,
    val taskListenerActive: Boolean,
    val panelState: PanelState,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val density: Float,
    val wsPort: Int,
    val sensorConnected: Boolean = false,
    val sensorPid: Int? = null,
    val activePluginId: String? = null,
    val matchedPluginsCount: Int = 0,
)

@Serializable
data class RpcError(
    val code: Int,
    val message: String,
)

@Serializable
data class RpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonElement? = null,
    val pluginId: String? = null,
)

@Serializable
data class RpcResponse(
    val jsonrpc: String = "2.0",
    val id: String,
    val result: JsonElement? = null,
    val error: RpcError? = null,
)

@Serializable
data class HostEventEnvelope(
    val event: String,
    val payload: JsonElement? = null,
)

@Serializable
data class ConfigUpdatedPayload(
    val config: SideVeilConfig,
    val scopeActive: Boolean,
    val foregroundPackage: String,
)

@Serializable
data class ScopeChangedPayload(
    val foregroundPackage: String,
    val foregroundActivity: String = "",
    val active: Boolean,
)

@Serializable
data class HostWebViewConsolePayload(
    val pluginId: String,
    val level: String,
    val message: String,
    val sourceId: String = "",
    val lineNumber: Int = 0,
)

@Serializable
data class GestureDragStartPayload(
    val side: EdgeSide,
    val startYFraction: Float,
)

@Serializable
data class GestureDragMovePayload(
    val side: EdgeSide,
    val progress: Float,
    val rawX: Float,
    val rawY: Float,
    val deltaX: Float,
)

@Serializable
data class GestureDragEndPayload(
    val shouldOpen: Boolean,
    val velocityPxPerSec: Float,
    val canceled: Boolean = false,
)

@Serializable
data class GestureFullscreenHintPayload(
    val side: EdgeSide,
)

@Serializable
enum class EdgeRepositionPhase {
    @SerialName("start")
    START,

    @SerialName("move")
    MOVE,

    @SerialName("end")
    END,
}

@Serializable
data class EdgeRepositionPayload(
    val phase: EdgeRepositionPhase,
    val side: EdgeSide,
    val topPercent: Int,
    val committed: Boolean = false,
)

@Serializable
data class HostFullscreenChangedPayload(
    val fullscreen: Boolean,
)

@Serializable
data class PanelCommandPayload(
    val command: PanelCommand,
)

/**
 * Payload carried by `syncTheme` container actions.
 *
 * [wantsNativeBlur] and [cornerRadiusPx] are optional advisory fields sent by standalone or
 * external web clients, and are omitted (null) when the host or daemon is authoritative.
 */
@Serializable
data class ContainerThemePayload(
    val themeId: String = "coloros-glass",
    val colorScheme: String = "dark",
    val wantsNativeBlur: Boolean? = null,
    val cornerRadiusPx: Int? = null,
)

@Serializable
data class EdgeStatusPayload(
    val badgeText: String? = null,
    val accentColor: String? = null,
    val pulse: Boolean = false,
    val progress: Float? = null,
    val clear: Boolean = false,
)

@Serializable
data class ContainerActionPayload(
    val type: String,
    val focused: Boolean? = null,
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val heightPercent: Int? = null,
    val xPercent: Float? = null,
    val yPercent: Float? = null,
    val clickThrough: Boolean? = null,
    val enabled: Boolean? = null,
    val backdrop: String? = null,
    val mode: String? = null,
    val status: EdgeStatusPayload? = null,
    val payload: ContainerThemePayload? = null,
)

@Serializable
data class HostHelloPayload(
    val pid: Int,
    val version: String,
    val crossWindowBlurSupported: Boolean,
)

@Serializable
data class PanelStateChangedPayload(
    val state: PanelState,
)

@Serializable
data class PanelVisibilityPayload(
    val visible: Boolean,
    val state: PanelState,
)

@Serializable
data class PingResult(
    val pong: Boolean = true,
    val daemonUptimeMs: Long,
    val timestampMs: Long,
)

@Serializable
data class AppearancePatchParams(
    val themeId: String? = null,
    val colorMode: String? = null,
    val nativeBlurEnabled: Boolean? = null,
    val blurRadiusPx: Int? = null,
    val cornerRadiusDp: Int? = null,
)
