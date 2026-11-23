//! Plugin control-plane RPC parameters, backend event envelopes, and timeout bounds.

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// Minimum configurable backend RPC timeout for `plugin.invoke` (1 second).
pub const MIN_PLUGIN_INVOKE_TIMEOUT_MS: u64 = 1_000;

/// Default backend RPC timeout for `plugin.invoke` when callers omit `timeoutMs` (8 seconds).
pub const DEFAULT_PLUGIN_INVOKE_TIMEOUT_MS: u64 = 8_000;

/// Maximum configurable backend RPC timeout for `plugin.invoke` (60 seconds).
pub const MAX_PLUGIN_INVOKE_TIMEOUT_MS: u64 = 60_000;

/// Sub-event name emitted inside `plugin.event` when a noisy backend exceeds its event rate budget.
pub const PLUGIN_EVENT_LAGGED_SUBEVENT: &str = "plugin.eventLagged";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginSetEnabledParams {
    pub plugin_id: String,
    pub enabled: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginSetPriorityParams {
    pub plugin_id: String,
    pub priority: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginSetScopeOverrideParams {
    pub plugin_id: String,
    #[serde(default)]
    pub packages: Option<Vec<String>>,
    #[serde(default)]
    pub global: Option<bool>,
    #[serde(default)]
    pub clear: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginSelectParams {
    pub plugin_id: String,
    #[serde(default)]
    pub package_name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginInstallParams {
    pub source_dir: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginInvokeParams {
    /// Optional target plugin ID honored strictly for privileged `RpcCaller::Ctl` callers.
    /// Sandboxed WebView callers always remain bound to their host-stamped `pluginId`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub plugin_id: Option<String>,
    pub method: String,
    #[serde(default)]
    pub params: Option<Value>,
    /// Optional per-call backend RPC timeout in milliseconds (clamped to `1_000..=60_000`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub timeout_ms: Option<u64>,
}

impl PluginInvokeParams {
    /// Returns the bounded effective backend timeout in milliseconds.
    #[must_use]
    pub fn effective_timeout_ms(&self) -> u64 {
        self.timeout_ms
            .map_or(DEFAULT_PLUGIN_INVOKE_TIMEOUT_MS, |ms| {
                ms.clamp(MIN_PLUGIN_INVOKE_TIMEOUT_MS, MAX_PLUGIN_INVOKE_TIMEOUT_MS)
            })
    }
}

/// Event frame emitted by a `uds-native` or `uds-app` plugin backend over `hub.sock` / `app.sock`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginBackendEvent {
    pub event: String,
    #[serde(default)]
    pub payload: Option<Value>,
    /// When `true`, routes through the coalescing ephemeral host lane so high-frequency frames
    /// never contend with reliable control/RPC frames.
    #[serde(default)]
    pub ephemeral: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginSetDevUrlParams {
    pub plugin_id: String,
    #[serde(default)]
    pub dev_server_url: Option<String>,
    #[serde(default)]
    pub clear: bool,
}

fn default_true_param() -> bool {
    true
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginSetEmbedConfigParams {
    pub plugin_id: String,
    #[serde(default)]
    pub embed_url: Option<String>,
    #[serde(default)]
    pub remote_url: Option<String>,
    #[serde(default)]
    pub lan_ssids: Option<Vec<String>>,
    #[serde(default)]
    pub allowed_origins: Option<Vec<String>>,
    #[serde(default)]
    pub allow_cleartext: Option<bool>,
    #[serde(default)]
    pub keep_alive: Option<bool>,
    #[serde(default)]
    pub wide_viewport: Option<bool>,
    #[serde(default)]
    pub allow_zoom: Option<bool>,
    #[serde(default)]
    pub web_history_back: Option<bool>,
    #[serde(default)]
    pub text_zoom: Option<i32>,
    #[serde(default)]
    pub show_toolbar: Option<bool>,
    #[serde(default)]
    pub keep_alive_grace_sec: Option<i32>,
    #[serde(default)]
    pub width_dp: Option<i32>,
    #[serde(default)]
    pub height_percent: Option<i32>,
    #[serde(default)]
    pub clear: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginCreateWebEmbedParams {
    pub id: String,
    pub name: String,
    pub url: String,
    #[serde(default)]
    pub remote_url: Option<String>,
    #[serde(default)]
    pub lan_ssids: Vec<String>,
    #[serde(default)]
    pub allowed_origins: Vec<String>,
    #[serde(default)]
    pub allow_cleartext: bool,
    #[serde(default)]
    pub keep_alive: bool,
    #[serde(default)]
    pub wide_viewport: Option<bool>,
    #[serde(default)]
    pub allow_zoom: Option<bool>,
    #[serde(default)]
    pub web_history_back: Option<bool>,
    #[serde(default)]
    pub text_zoom: Option<i32>,
    #[serde(default)]
    pub show_toolbar: Option<bool>,
    #[serde(default)]
    pub keep_alive_grace_sec: Option<i32>,
    #[serde(default)]
    pub width_dp: Option<i32>,
    #[serde(default)]
    pub height_percent: Option<i32>,
    #[serde(default)]
    pub packages: Vec<String>,
    #[serde(default = "default_true_param")]
    pub global: bool,
    #[serde(default)]
    pub priority: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginUninstallParams {
    pub plugin_id: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginBusPublishParams {
    pub topic: String,
    #[serde(default)]
    pub payload: Option<Value>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginBusEventPayload {
    pub from_plugin_id: String,
    pub topic: String,
    #[serde(default)]
    pub payload: Option<Value>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginAuditEntry {
    pub timestamp_ms: u64,
    pub plugin_id: String,
    pub action: String,
    pub allowed: bool,
    pub detail: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct HostPluginDiagnosticsPayload {
    pub warm_plugin_ids: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub active_hud_plugin_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub host_native_heap_kb: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub host_pss_kb: Option<u64>,
    #[serde(default)]
    pub webview_crash_count: u32,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plugin_invoke_timeout_clamps_within_bounds() {
        let default_req = PluginInvokeParams {
            plugin_id: None,
            method: "ping".to_owned(),
            params: None,
            timeout_ms: None,
        };
        assert_eq!(
            default_req.effective_timeout_ms(),
            DEFAULT_PLUGIN_INVOKE_TIMEOUT_MS
        );

        let too_small = PluginInvokeParams {
            timeout_ms: Some(10),
            ..default_req.clone()
        };
        assert_eq!(
            too_small.effective_timeout_ms(),
            MIN_PLUGIN_INVOKE_TIMEOUT_MS
        );

        let too_large = PluginInvokeParams {
            timeout_ms: Some(999_999),
            ..default_req.clone()
        };
        assert_eq!(
            too_large.effective_timeout_ms(),
            MAX_PLUGIN_INVOKE_TIMEOUT_MS
        );

        let in_range = PluginInvokeParams {
            timeout_ms: Some(25_000),
            ..default_req
        };
        assert_eq!(in_range.effective_timeout_ms(), 25_000);
    }

    #[test]
    fn backend_event_defaults_ephemeral_to_false() {
        let raw = r#"{"event":"task.output","payload":{"chunk":"ok"}}"#;
        let parsed: PluginBackendEvent = serde_json::from_str(raw).expect("parse backend event");
        assert_eq!(parsed.event, "task.output");
        assert!(!parsed.ephemeral);

        let raw_eph = r#"{"event":"metrics.tick","ephemeral":true}"#;
        let parsed_eph: PluginBackendEvent =
            serde_json::from_str(raw_eph).expect("parse ephemeral event");
        assert!(parsed_eph.ephemeral);
    }
}
