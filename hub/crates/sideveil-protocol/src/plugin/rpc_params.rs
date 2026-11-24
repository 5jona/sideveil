//! Plugin control-plane RPC parameters, backend event envelopes, and timeout bounds.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::{CapabilityRiskTier, PluginCapability, PluginDaemonStatus, PluginManifest};
use crate::generated::protocol_constants::timeouts;
pub use crate::generated::protocol_wire::HostPluginDiagnosticsPayload;

/// Minimum configurable backend RPC timeout for `plugin.invoke` (1 second).
pub const MIN_PLUGIN_INVOKE_TIMEOUT_MS: u64 = timeouts::MIN_PLUGIN_INVOKE_MS;

/// Default backend RPC timeout for `plugin.invoke` when callers omit `timeoutMs` (8 seconds).
pub const DEFAULT_PLUGIN_INVOKE_TIMEOUT_MS: u64 = timeouts::DEFAULT_PLUGIN_INVOKE_MS;

/// Maximum configurable backend RPC timeout for `plugin.invoke` (60 seconds).
pub const MAX_PLUGIN_INVOKE_TIMEOUT_MS: u64 = timeouts::MAX_PLUGIN_INVOKE_MS;

/// Sub-event name emitted inside `plugin.event` when a noisy backend exceeds its event rate budget.
pub const PLUGIN_EVENT_LAGGED_SUBEVENT: &str = timeouts::PLUGIN_EVENT_LAGGED_SUBEVENT;

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

/// Parameters for `plugins.install` supporting directory, `.svp`/`.zip` path, or base64 upload.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PluginInstallParams {
    /// Legacy/canonical local source directory path.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_dir: Option<String>,
    /// Local path to either a plugin directory or a `.svp`/`.zip` archive.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_path: Option<String>,
    /// Base64-encoded `.svp` archive payload (max 16 MiB decoded).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub archive_base64: Option<String>,
    /// Optional archive filename for diagnostic/audit logging.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub filename: Option<String>,
    /// Optional explicit capability grants applied atomically on install/upgrade.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub granted_capabilities: Option<Vec<PluginCapability>>,
    /// When `true`, approves any declared `uds-native` daemon immediately upon install.
    pub approve_daemon: bool,
}

/// Parameters for `plugins.inspectArchive` pre-install capability/manifest inspection.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PluginInspectArchiveParams {
    /// Local path to a `.svp`/`.zip` archive or plugin directory.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    /// Base64-encoded `.svp` archive payload (max 16 MiB decoded).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub archive_base64: Option<String>,
    /// Optional archive filename for diagnostic output.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub filename: Option<String>,
}

/// Per-capability risk and diff item returned by `plugins.inspectArchive`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginCapabilityReviewItem {
    pub capability: PluginCapability,
    pub risk: CapabilityRiskTier,
    pub requires_approval: bool,
    pub is_new: bool,
    #[serde(default)]
    pub already_granted: bool,
}

/// Result returned by `plugins.inspectArchive`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginInspectArchiveResult {
    pub manifest: PluginManifest,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub existing_version: Option<String>,
    pub is_upgrade: bool,
    pub capabilities: Vec<PluginCapabilityReviewItem>,
    pub has_daemon: bool,
    #[serde(default)]
    pub daemon_already_approved: bool,
    pub warnings: Vec<String>,
}

/// Parameters for `plugins.grantCapabilities` capability and daemon approval mutations.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PluginGrantCapabilitiesParams {
    pub plugin_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub capabilities: Option<Vec<PluginCapability>>,
    pub grant_all_pending: bool,
    pub revoke_all: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub approve_daemon: Option<bool>,
}

/// Parameters for `plugins.daemonLogs` querying a supervised plugin daemon's stderr ring buffer.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginDaemonLogsParams {
    pub plugin_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub limit: Option<usize>,
}

/// Result returned by `plugins.daemonLogs`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginDaemonLogsResult {
    pub plugin_id: String,
    pub status: PluginDaemonStatus,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pid: Option<u32>,
    pub restart_count: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_error: Option<String>,
    pub lines: Vec<String>,
}

/// Parameters for `plugins.reloadWebView`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginReloadWebViewParams {
    pub plugin_id: String,
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
        assert_eq!(
            PluginInvokeParams {
                timeout_ms: Some(10),
                ..default_req.clone()
            }
            .effective_timeout_ms(),
            MIN_PLUGIN_INVOKE_TIMEOUT_MS
        );
        assert_eq!(
            PluginInvokeParams {
                timeout_ms: Some(999_999),
                ..default_req
            }
            .effective_timeout_ms(),
            MAX_PLUGIN_INVOKE_TIMEOUT_MS
        );
    }

    #[test]
    fn backend_event_defaults_ephemeral_to_false() {
        let parsed: PluginBackendEvent =
            serde_json::from_str(r#"{"event":"task.output","payload":{"chunk":"ok"}}"#)
                .expect("parse backend event");
        assert_eq!(parsed.event, "task.output");
        assert!(!parsed.ephemeral);
    }
}
