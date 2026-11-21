//! JSON-RPC 2.0 wire types, method/event constants, and host/control payloads.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::config::{EdgeSide, PanelState, SideveilConfig};

pub mod events {
    pub const CONFIG_UPDATED: &str = "config.updated";
    pub const SCOPE_CHANGED: &str = "scope.changed";
    pub const GESTURE_DRAG_START: &str = "gesture.dragStart";
    pub const GESTURE_DRAG_MOVE: &str = "gesture.dragMove";
    pub const GESTURE_DRAG_END: &str = "gesture.dragEnd";
    pub const GESTURE_FULLSCREEN_HINT: &str = "gesture.fullscreenHint";
    pub const EDGE_REPOSITION: &str = "edge.reposition";
    pub const PANEL_COMMAND: &str = "panel.command";
    pub const HOST_HELLO: &str = "host.hello";
    pub const HOST_FULLSCREEN_CHANGED: &str = "host.fullscreenChanged";
    pub const HOST_DYNAMIC_COLORS: &str = "host.dynamicColors";
    pub const HOST_DIAGNOSTICS: &str = "host.diagnostics";
    pub const HOST_WEBVIEW_CONSOLE: &str = "host.webviewConsole";
    pub const HOST_SELECT_PLUGIN: &str = "host.selectPlugin";
    pub const PANEL_STATE_CHANGED: &str = "panel.stateChanged";
    pub const PANEL_VISIBILITY: &str = "panel.visibility";
    pub const PANEL_BACK_PRESSED: &str = "panel.backPressed";
    pub const PLUGINS_UPDATED: &str = "plugins.updated";
    pub const PLUGINS_ACTIVE_CHANGED: &str = "plugins.activeChanged";
    pub const PLUGIN_BACKEND_STATE: &str = "plugin.backendState";
    pub const PLUGIN_EVENT: &str = "plugin.event";
    pub const PLUGIN_BUS_MESSAGE: &str = "plugin.busMessage";
    pub const THEME_PALETTE_UPDATED: &str = "theme.paletteUpdated";
    pub const HUD_SCENE_UPDATED: &str = "hud.sceneUpdated";
    pub const HUD_DISMISSED: &str = "hud.dismissed";
}

pub mod methods {
    pub const DAEMON_PING: &str = "daemon.ping";
    pub const DAEMON_STATUS: &str = "daemon.status";
    pub const DAEMON_HEALTH: &str = "daemon.health";
    pub const DAEMON_LOGS: &str = "daemon.logs";
    pub const CONFIG_GET: &str = "config.get";
    pub const CONFIG_PATCH_APPEARANCE: &str = "config.patchAppearance";
    pub const CONFIG_PATCH_EDGE: &str = "config.patchEdge";
    pub const CONFIG_RELOAD: &str = "config.reload";
    pub const PANEL_OPEN: &str = "panel.open";
    pub const PANEL_CLOSE: &str = "panel.close";
    pub const PANEL_TOGGLE: &str = "panel.toggle";
    pub const HOST_RESTART: &str = "host.restart";
    pub const CONTAINER_ACTION: &str = "container.action";
    pub const PLUGINS_LIST: &str = "plugins.list";
    pub const PLUGINS_RELOAD: &str = "plugins.reload";
    pub const PLUGINS_SET_ENABLED: &str = "plugins.setEnabled";
    pub const PLUGINS_SET_PRIORITY: &str = "plugins.setPriority";
    pub const PLUGINS_SET_SCOPE_OVERRIDE: &str = "plugins.setScopeOverride";
    pub const PLUGINS_SET_DEV_URL: &str = "plugins.setDevUrl";
    pub const PLUGINS_AUDIT: &str = "plugins.audit";
    pub const PLUGINS_SELECT: &str = "plugins.select";
    pub const PLUGIN_INVOKE: &str = "plugin.invoke";
    pub const PLUGIN_BUS_PUBLISH: &str = "plugin.busPublish";
    pub const FS_READ: &str = "fs.read";
    pub const FS_WRITE_ATOMIC: &str = "fs.writeAtomic";
    pub const SYSTEM_EXEC: &str = "system.exec";
    pub const TELEMETRY_SNAPSHOT: &str = "telemetry.snapshot";
    pub const CLIPBOARD_GET: &str = "clipboard.get";
    pub const CLIPBOARD_SET: &str = "clipboard.set";
    pub const HAPTICS_TRIGGER: &str = "haptics.trigger";
    pub const UI_TOAST: &str = "ui.toast";
    pub const HUD_UPDATE_SCENE: &str = "hud.updateScene";
    pub const HUD_DISMISS: &str = "hud.dismiss";
}

pub mod error_codes {
    pub const PARSE_ERROR: i32 = -32700;
    pub const INVALID_REQUEST: i32 = -32600;
    pub const METHOD_NOT_FOUND: i32 = -32601;
    pub const INVALID_PARAMS: i32 = -32602;
    pub const INTERNAL_ERROR: i32 = -32000;
    pub const PERMISSION_DENIED: i32 = -32003;
    pub const BACKEND_OFFLINE: i32 = -32010;
    pub const BACKEND_TIMEOUT: i32 = -32011;
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DaemonEndpoint {
    pub port: u16,
    pub token: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PanelCommand {
    Open,
    Close,
    Toggle,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DaemonStatus {
    pub pid: u32,
    pub uid: u32,
    pub uptime_ms: u64,
    pub config_version: i32,
    pub enabled: bool,
    pub scope_active: bool,
    pub foreground_package: String,
    pub host_package: String,
    pub host_connected: bool,
    pub host_pid: Option<u32>,
    pub overlay_permission_granted: bool,
    pub cross_window_blur_supported: Option<bool>,
    pub gesture_monitor_active: bool,
    pub task_listener_active: bool,
    pub panel_state: PanelState,
    pub screen_width_px: i32,
    pub screen_height_px: i32,
    pub density: f32,
    pub ws_port: u16,
    pub sensor_connected: bool,
    pub sensor_pid: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub active_plugin_id: Option<String>,
    #[serde(default)]
    pub matched_plugins_count: usize,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DaemonHealth {
    pub ok: bool,
    pub pid: u32,
    pub uptime_ms: u64,
    pub sensor_connected: bool,
    pub host_connected: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RpcError {
    pub code: i32,
    pub message: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RpcRequest {
    #[serde(default = "default_jsonrpc")]
    pub jsonrpc: String,
    pub id: String,
    pub method: String,
    #[serde(default)]
    pub params: Option<Value>,
    /// Server-stamped plugin caller identity when forwarded from a `SideveilHost` WebView.
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "pluginId")]
    pub plugin_id: Option<String>,
}

impl RpcRequest {
    #[must_use]
    pub fn new(id: impl Into<String>, method: impl Into<String>, params: Option<Value>) -> Self {
        Self {
            jsonrpc: default_jsonrpc(),
            id: id.into(),
            method: method.into(),
            params,
            plugin_id: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RpcResponse {
    #[serde(default = "default_jsonrpc")]
    pub jsonrpc: String,
    pub id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<RpcError>,
}

impl RpcResponse {
    #[must_use]
    pub fn ok(id: impl Into<String>, result: Value) -> Self {
        Self {
            jsonrpc: default_jsonrpc(),
            id: id.into(),
            result: Some(result),
            error: None,
        }
    }

    #[must_use]
    pub fn err(id: impl Into<String>, code: i32, message: impl Into<String>) -> Self {
        Self {
            jsonrpc: default_jsonrpc(),
            id: id.into(),
            result: None,
            error: Some(RpcError {
                code,
                message: message.into(),
            }),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HostEventEnvelope {
    pub event: String,
    #[serde(default)]
    pub payload: Option<Value>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ConfigUpdatedPayload {
    pub config: SideveilConfig,
    pub scope_active: bool,
    pub foreground_package: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScopeChangedPayload {
    pub foreground_package: String,
    #[serde(default)]
    pub foreground_activity: String,
    pub active: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostWebViewConsolePayload {
    pub plugin_id: String,
    pub level: String,
    pub message: String,
    #[serde(default)]
    pub source_id: String,
    #[serde(default)]
    pub line_number: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GestureDragStartPayload {
    pub side: EdgeSide,
    pub start_y_fraction: f32,
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GestureDragMovePayload {
    pub side: EdgeSide,
    pub progress: f32,
    pub raw_x: f32,
    pub raw_y: f32,
    pub delta_x: f32,
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GestureDragEndPayload {
    pub should_open: bool,
    pub velocity_px_per_sec: f32,
    #[serde(default)]
    pub canceled: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GestureFullscreenHintPayload {
    pub side: EdgeSide,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum EdgeRepositionPhase {
    Start,
    Move,
    End,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EdgeRepositionPayload {
    pub phase: EdgeRepositionPhase,
    pub side: EdgeSide,
    pub top_percent: i32,
    #[serde(default)]
    pub committed: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostFullscreenChangedPayload {
    pub fullscreen: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
#[derive(Default)]
pub struct EdgeStatusPayload {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub badge_text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub accent_color: Option<String>,
    pub pulse: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub progress: Option<f32>,
    pub clear: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PanelCommandPayload {
    pub command: PanelCommand,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostHelloPayload {
    pub pid: u32,
    pub version: String,
    pub cross_window_blur_supported: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PanelStateChangedPayload {
    pub state: PanelState,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PingResult {
    pub pong: bool,
    pub daemon_uptime_ms: u64,
    pub timestamp_ms: u64,
}

fn default_jsonrpc() -> String {
    "2.0".to_owned()
}
