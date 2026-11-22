package pochita.sideveil.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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
    const val NETWORK_CONTEXT_CHANGED = "network.contextChanged"
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
    const val PLUGINS_INSTALL = "plugins.install"
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
    const val NETWORK_CONTEXT = "network.context"
    const val DEBUG_INSPECT_UI = "debug.inspectUi"
    const val DEBUG_SET_WEBVIEW_DEBUGGING = "debug.setWebViewDebugging"
}

object SideVeilErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32000
    const val PERMISSION_DENIED = -32003
    const val BACKEND_OFFLINE = -32010
    const val BACKEND_TIMEOUT = -32011
}

object SideVeilTimeouts {
    const val MIN_PLUGIN_INVOKE_MS = 1_000L
    const val DEFAULT_RPC_MS = 8_000L
    const val MAX_PLUGIN_INVOKE_MS = 60_000L
    const val FORWARD_WIRE_MARGIN_MS = 1_000L

    /**
     * Resolves the bounded WebSocket forwarding timeout for [req], giving `sideveild` a small
     * wire margin so backend timeout diagnostics return cleanly before host cancellation.
     */
    fun resolveForwardTimeoutMs(req: RpcRequest): Long {
        if (req.method != SideVeilMethods.PLUGIN_INVOKE) {
            return DEFAULT_RPC_MS
        }
        val requested =
            (req.params as? JsonObject)
                ?.get("timeoutMs")
                ?.jsonPrimitive
                ?.longOrNull
                ?: return DEFAULT_RPC_MS
        val bounded = requested.coerceIn(MIN_PLUGIN_INVOKE_MS, MAX_PLUGIN_INVOKE_MS)
        return bounded + FORWARD_WIRE_MARGIN_MS
    }
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
