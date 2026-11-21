//! Open plugin manifest schema, trigger rules, overrides, and capability ACL definitions.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::Value;
use thiserror::Error;

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
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum PluginSurfaceBackdrop {
    #[default]
    NativeGlass,
    Transparent,
    Opaque,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PluginTriggers {
    pub packages: Vec<String>,
    pub global: bool,
    pub priority: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PluginSurfacePrefs {
    pub backdrop: PluginSurfaceBackdrop,
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
    pub entry: String,
    #[serde(default)]
    pub backend: PluginBackendKind,
    #[serde(default)]
    pub capabilities: Vec<PluginCapability>,
    #[serde(default)]
    pub surface: PluginSurfacePrefs,
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
    pub global: bool,
    pub has_scope_override: bool,
    pub backend: PluginBackendKind,
    pub backend_state: PluginBackendState,
    pub capabilities: Vec<PluginCapability>,
    pub surface: PluginSurfacePrefs,
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

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PluginInvokeParams {
    pub method: String,
    #[serde(default)]
    pub params: Option<Value>,
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum ManifestValidationError {
    #[error(
        "invalid plugin id '{0}': must be 2..=64 ASCII [a-z0-9.-] chars starting with a letter"
    )]
    InvalidId(String),
    #[error("plugin name must not be blank")]
    BlankName,
    #[error("plugin version must not be blank")]
    BlankVersion,
    #[error("plugin entry '{0}' must be a relative path without '..' or leading '/'")]
    InvalidEntry(String),
    #[error("plugin icon '{0}' must be a relative path without '..' or leading '/'")]
    InvalidIcon(String),
}

/// Validates a plugin identifier for safe use in directory names and isolated virtual origins.
pub fn validate_plugin_id(id: &str) -> Result<(), ManifestValidationError> {
    let bytes = id.as_bytes();
    if bytes.len() < 2 || bytes.len() > 64 {
        return Err(ManifestValidationError::InvalidId(id.to_owned()));
    }
    if !bytes[0].is_ascii_lowercase() || !bytes[bytes.len() - 1].is_ascii_alphanumeric() {
        return Err(ManifestValidationError::InvalidId(id.to_owned()));
    }
    let mut prev_sep = false;
    for &b in bytes {
        let is_sep = b == b'.' || b == b'-';
        if !(b.is_ascii_lowercase() || b.is_ascii_digit() || is_sep) {
            return Err(ManifestValidationError::InvalidId(id.to_owned()));
        }
        if is_sep && prev_sep {
            return Err(ManifestValidationError::InvalidId(id.to_owned()));
        }
        prev_sep = is_sep;
    }
    Ok(())
}

impl PluginManifest {
    pub fn validate(&self) -> Result<(), ManifestValidationError> {
        validate_plugin_id(&self.id)?;
        if self.name.trim().is_empty() {
            return Err(ManifestValidationError::BlankName);
        }
        if self.version.trim().is_empty() {
            return Err(ManifestValidationError::BlankVersion);
        }
        if !is_safe_relative_asset_path(&self.entry) {
            return Err(ManifestValidationError::InvalidEntry(self.entry.clone()));
        }
        if let Some(icon) = &self.icon
            && !is_safe_relative_asset_path(icon)
        {
            return Err(ManifestValidationError::InvalidIcon(icon.clone()));
        }
        Ok(())
    }
}

fn is_safe_relative_asset_path(raw: &str) -> bool {
    let trimmed = raw.trim();
    !trimmed.is_empty()
        && !trimmed.starts_with('/')
        && !trimmed.contains('\\')
        && !trimmed
            .split('/')
            .any(|seg| seg.is_empty() || seg == "." || seg == ".." || seg.starts_with('.'))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_plugin_ids_and_entries() {
        assert!(validate_plugin_id("sideveil.diagnostic").is_ok());
        assert!(validate_plugin_id("com.example.my-plugin-1").is_ok());
        assert!(validate_plugin_id("../escape").is_err());
        assert!(validate_plugin_id("UpperCase").is_err());
        assert!(validate_plugin_id("a..b").is_err());

        let valid = PluginManifest {
            id: "sideveil.diagnostic".to_owned(),
            name: "诊断面板".to_owned(),
            version: "0.1.0".to_owned(),
            icon: None,
            triggers: PluginTriggers::default(),
            entry: "web/index.html".to_owned(),
            backend: PluginBackendKind::None,
            capabilities: vec![PluginCapability::Toast],
            surface: PluginSurfacePrefs::default(),
        };
        assert!(valid.validate().is_ok());

        let bad_entry = PluginManifest {
            entry: "../secret/index.html".to_owned(),
            ..valid
        };
        assert!(matches!(
            bad_entry.validate(),
            Err(ManifestValidationError::InvalidEntry(_))
        ));
    }
}
