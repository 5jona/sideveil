//! Open plugin manifest schema, trigger rules, overrides, and capability ACL definitions.

mod capabilities;
mod rpc_params;
mod url_validation;
mod validation;

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::Value;

pub use self::rpc_params::*;
pub use self::url_validation::{
    is_loopback_host, is_private_or_lan_host, validate_network_url,
    validate_origin_for_capabilities,
};
pub use self::validation::{
    ManifestValidationError, is_forbidden_daemon_env_key, validate_android_package_name,
    validate_daemon_binary_path, validate_plugin_id,
};
pub use crate::generated::plugin_manifest::{
    CapabilityRiskTier, PluginApprovalStatus, PluginBackendKind, PluginBackendState,
    PluginCapability, PluginDaemonRestartPolicy, PluginDaemonSpec, PluginDaemonStatus,
    PluginEmbedPrefs, PluginManifest, PluginManifestEmbedPrefs, PluginManifestNetworkPrefs,
    PluginManifestSurfacePrefs, PluginManifestTriggers, PluginNetworkPrefs, PluginOrigin,
    PluginPresentationMode, PluginRuntimeDescriptor, PluginScopeOverride, PluginSurfaceBackdrop,
    PluginSurfacePrefs,
};
use crate::{MANIFEST_SCHEMA_VERSION, PLUGIN_API_VERSION};

/// Backward-compatible alias for [`PluginRuntimeDescriptor`].
pub type PluginDescriptor = PluginRuntimeDescriptor;

/// Canonical identifier for the built-in diagnostic panel plugin.
pub const BUILTIN_DIAG_PLUGIN_ID: &str =
    crate::generated::protocol_constants::builtin_diagnostic::ID;

/// Normalized foreground trigger rules derived from [`PluginManifest`].
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PluginTriggers {
    pub packages: Vec<String>,
    pub activities: Vec<String>,
    pub global: bool,
    pub priority: i32,
}

impl PluginDaemonSpec {
    /// Returns the effective restart policy, defaulting to [`PluginDaemonRestartPolicy::OnFailure`].
    #[must_use]
    pub fn effective_restart_policy(&self) -> PluginDaemonRestartPolicy {
        self.restart_policy
            .unwrap_or(PluginDaemonRestartPolicy::OnFailure)
    }

    /// Returns the effective maximum restart count within the circuit-breaker window.
    #[must_use]
    pub fn effective_max_restarts(&self) -> u32 {
        self.max_restarts.unwrap_or(5).clamp(1, 50)
    }

    /// Returns the effective initial backoff delay in milliseconds (`100..=60_000`).
    #[must_use]
    pub fn effective_backoff_ms(&self) -> u64 {
        self.backoff_ms.unwrap_or(1_000).clamp(100, 60_000)
    }
}

impl PluginManifest {
    /// Returns the declared manifest schema version, defaulting to [`MANIFEST_SCHEMA_VERSION`].
    #[must_use]
    pub fn effective_schema_version(&self) -> u32 {
        self.schema_version.unwrap_or(MANIFEST_SCHEMA_VERSION)
    }

    /// Returns the declared plugin API version, defaulting to [`PLUGIN_API_VERSION`].
    #[must_use]
    pub fn effective_api_version(&self) -> u32 {
        self.api_version.unwrap_or(PLUGIN_API_VERSION)
    }

    /// Returns the declared default-enabled state (defaults to `true`).
    #[must_use]
    pub fn effective_default_enabled(&self) -> bool {
        self.default_enabled.unwrap_or(true)
    }

    /// Returns the declared entry path (empty string when omitted, e.g., web-embed plugins).
    #[must_use]
    pub fn effective_entry(&self) -> &str {
        self.entry.as_deref().unwrap_or("")
    }

    /// Returns the declared backend kind (defaults to [`PluginBackendKind::None`]).
    #[must_use]
    pub fn effective_backend(&self) -> PluginBackendKind {
        self.backend.unwrap_or(PluginBackendKind::None)
    }

    /// Returns the declared capability slice.
    #[must_use]
    pub fn effective_capabilities(&self) -> &[PluginCapability] {
        self.capabilities.as_deref().unwrap_or(&[])
    }

    /// Returns the explicit Android package allowlist for `uds-app` backends.
    #[must_use]
    pub fn effective_backend_packages(&self) -> &[String] {
        self.backend_packages.as_deref().unwrap_or(&[])
    }

    /// Normalizes optional manifest trigger fields into [`PluginTriggers`].
    #[must_use]
    pub fn effective_triggers(&self) -> PluginTriggers {
        match &self.triggers {
            Some(t) => PluginTriggers {
                packages: t.packages.clone().unwrap_or_default(),
                activities: t.activities.clone().unwrap_or_default(),
                global: t.global.unwrap_or(false),
                priority: t.priority.unwrap_or(0).clamp(-1000, 1000),
            },
            None => PluginTriggers::default(),
        }
    }

    /// Normalizes optional manifest surface preferences into [`PluginSurfacePrefs`].
    #[must_use]
    pub fn effective_surface(&self) -> PluginSurfacePrefs {
        match &self.surface {
            Some(s) => PluginSurfacePrefs {
                backdrop: s.backdrop.unwrap_or(PluginSurfaceBackdrop::NativeGlass),
                presentation: s.presentation.unwrap_or(PluginPresentationMode::Drawer),
                cross_origin_isolated: s.cross_origin_isolated.unwrap_or(false),
                keep_alive: s.keep_alive.unwrap_or(false),
                width_dp: s.width_dp,
                height_percent: s.height_percent,
            },
            None => PluginSurfacePrefs::default(),
        }
    }

    /// Normalizes optional manifest embed preferences into [`PluginEmbedPrefs`].
    #[must_use]
    pub fn effective_embed(&self) -> Option<PluginEmbedPrefs> {
        self.embed.as_ref().map(|e| PluginEmbedPrefs {
            url: e.url.clone(),
            remote_url: e.remote_url.clone(),
            lan_ssids: e.lan_ssids.clone().unwrap_or_default(),
            allowed_origins: e.allowed_origins.clone().unwrap_or_default(),
            allow_cleartext: e.allow_cleartext.unwrap_or(false),
            wide_viewport: e.wide_viewport.unwrap_or(true),
            allow_zoom: e.allow_zoom.unwrap_or(false),
            web_history_back: e.web_history_back.unwrap_or(true),
            text_zoom: e.text_zoom,
            show_toolbar: e.show_toolbar.unwrap_or(true),
            keep_alive_grace_sec: e.keep_alive_grace_sec,
        })
    }

    /// Normalizes optional manifest network preferences into [`PluginNetworkPrefs`].
    #[must_use]
    pub fn effective_network(&self) -> Option<PluginNetworkPrefs> {
        self.network.as_ref().map(|n| PluginNetworkPrefs {
            allowed_origins: n.allowed_origins.clone().unwrap_or_default(),
            allow_cleartext: n.allow_cleartext.unwrap_or(false),
            spa_fallback: n.spa_fallback.unwrap_or(false),
        })
    }
}

/// Persisted user override and capability approval entry under `/data/system/sideveil/plugins.json`.
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
    pub remote_url: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub lan_ssids: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub allowed_origins: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub allow_cleartext: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub keep_alive: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wide_viewport: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub allow_zoom: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub web_history_back: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text_zoom: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub show_toolbar: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub keep_alive_grace_sec: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub width_dp: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub height_percent: Option<i32>,
    /// Explicitly approved medium/high-risk capabilities persisted by admin/user grant.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub granted_capabilities: Vec<PluginCapability>,
    /// Capabilities previously reviewed by the admin (used to detect upgrade capability diffs).
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub reviewed_capabilities: Vec<PluginCapability>,
    /// Whether this plugin's supervised `uds-native` daemon binary is explicitly approved to run.
    #[serde(default)]
    pub daemon_approved: bool,
    /// Content fingerprint of the approved daemon binary so upgrades reset approval when changed.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub daemon_binary_fingerprint: Option<String>,
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
