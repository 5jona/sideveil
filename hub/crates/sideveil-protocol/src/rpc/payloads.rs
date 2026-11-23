//! Host, control-plane, gesture, edge, and daemon status RPC payloads.

use serde::{Deserialize, Serialize};

use crate::config::{EdgeSide, SideVeilConfig};
pub use crate::generated::protocol_wire::{
    DaemonHealth, DaemonStatus, PanelStateChangedPayload, PanelVisibilityPayload, PingResult,
    ScopeChangedPayload,
};

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
pub struct ConfigUpdatedPayload {
    pub config: SideVeilConfig,
    pub scope_active: bool,
    pub foreground_package: String,
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

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PanelOpenParams {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub plugin_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostHelloPayload {
    pub pid: u32,
    pub version: String,
    pub cross_window_blur_supported: bool,
}
