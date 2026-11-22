//! Open plugin manifest schema, trigger rules, overrides, and capability ACL definitions.

mod rpc_params;
mod validation;

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::Value;

pub use rpc_params::*;
pub use validation::{
    ManifestValidationError, is_private_or_lan_host, validate_network_url, validate_plugin_id,
};

fn default_true() -> bool {
    true
}

/// Canonical identifier for the built-in diagnostic panel plugin.
pub const BUILTIN_DIAG_PLUGIN_ID: &str = "sideveil.diagnostic";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum PluginBackendKind {
    #[default]
    None,
    UdsNative,
    UdsApp,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PluginBackendState {
    #[default]
    None,
    Online,
    Offline,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum PluginCapability {
    Storage,
    Toast,
    SystemExec,
    NativeHud,
    Clipboard,
    Haptics,
    Telemetry,
    InterPluginBus,
    NetworkLoopback,
    NetworkLan,
    NetworkContext,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginEmbedPrefs {
    pub url: String,
    #[serde(default)]
    pub allowed_origins: Vec<String>,
    #[serde(default)]
    pub allow_cleartext: bool,
    #[serde(default = "default_true")]
    pub wide_viewport: bool,
    #[serde(default)]
    pub allow_zoom: bool,
    #[serde(default = "default_true")]
    pub web_history_back: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PluginNetworkPrefs {
    pub allowed_origins: Vec<String>,
    pub allow_cleartext: bool,
    pub spa_fallback: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum PluginSurfaceBackdrop {
    #[default]
    NativeGlass,
    Transparent,
    Opaque,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum PluginPresentationMode {
    #[default]
    Drawer,
    Peek,
    Pinned,
    Wide,
    NativeHud,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PluginTriggers {
    pub packages: Vec<String>,
    pub activities: Vec<String>,
    pub global: bool,
    pub priority: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PluginSurfacePrefs {
    pub backdrop: PluginSurfaceBackdrop,
    pub presentation: PluginPresentationMode,
    pub cross_origin_isolated: bool,
    pub keep_alive: bool,
    pub width_dp: Option<i32>,
    pub height_percent: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginManifest {
    pub id: String,
    pub name: String,
    pub version: String,
    #[serde(default)]
    pub icon: Option<String>,
    #[serde(default)]
    pub triggers: PluginTriggers,
    #[serde(default)]
    pub entry: String,
    #[serde(default)]
    pub backend: PluginBackendKind,
    #[serde(default)]
    pub capabilities: Vec<PluginCapability>,
    #[serde(default)]
    pub surface: PluginSurfacePrefs,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub embed: Option<PluginEmbedPrefs>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub network: Option<PluginNetworkPrefs>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginSummary {
    pub id: String,
    pub name: String,
    pub version: String,
    pub enabled: bool,
    pub backend: PluginBackendKind,
    pub backend_state: PluginBackendState,
    pub triggers: PluginTriggers,
}

/// Full runtime descriptor for a registered plugin (built-in or external).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginDescriptor {
    pub id: String,
    pub name: String,
    pub version: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub icon: Option<String>,
    pub entry: String,
    pub builtin: bool,
    pub enabled: bool,
    pub priority: i32,
    pub packages: Vec<String>,
    #[serde(default)]
    pub activities: Vec<String>,
    pub global: bool,
    pub has_scope_override: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub dev_server_url: Option<String>,
    pub backend: PluginBackendKind,
    pub backend_state: PluginBackendState,
    pub capabilities: Vec<PluginCapability>,
    pub surface: PluginSurfacePrefs,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub embed: Option<PluginEmbedPrefs>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub network: Option<PluginNetworkPrefs>,
}

/// Persisted user override for a single plugin under `/data/system/sideveil/plugins.json`.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PluginOverrideEntry {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub enabled: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub priority: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub packages_override: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub global_override: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dev_server_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub embed_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub allowed_origins: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub allow_cleartext: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub keep_alive: Option<bool>,
}

/// Persisted plugin override and per-app MRU selection state.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PluginOverridesFile {
    pub overrides: BTreeMap<String, PluginOverrideEntry>,
    pub mru_by_package: BTreeMap<String, String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginsSnapshotPayload {
    pub plugins: Vec<PluginDescriptor>,
    pub matched_plugin_ids: Vec<String>,
    pub active_plugin_id: Option<String>,
    pub foreground_package: String,
    #[serde(default)]
    pub foreground_activity: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginActiveChangedPayload {
    pub plugin_id: String,
    pub foreground_package: String,
    pub matched_plugin_ids: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginBackendStatePayload {
    pub plugin_id: String,
    pub state: PluginBackendState,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginScopedEventPayload {
    pub plugin_id: String,
    pub event: String,
    #[serde(default)]
    pub payload: Option<Value>,
}

#[cfg(test)]
mod tests;
