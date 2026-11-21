//! Built-in diagnostic manifest, descriptor assembly, and atomic `plugins.json` persistence.

use std::collections::BTreeMap;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

use sideveil_protocol::config::{ScopeMode, SideVeilConfig};
use sideveil_protocol::plugin::{
    BUILTIN_DIAG_PLUGIN_ID, PluginBackendKind, PluginBackendState, PluginCapability,
    PluginDescriptor, PluginManifest, PluginOverrideEntry, PluginOverridesFile,
    PluginSurfaceBackdrop, PluginSurfacePrefs, PluginTriggers,
};

use super::LoadedPlugin;
use crate::log_ring::LogRing;

pub(super) fn builtin_diag_manifest() -> PluginManifest {
    PluginManifest {
        id: BUILTIN_DIAG_PLUGIN_ID.to_owned(),
        name: "内置诊断".to_owned(),
        version: env!("CARGO_PKG_VERSION").to_owned(),
        icon: None,
        triggers: PluginTriggers {
            packages: Vec::new(),
            activities: Vec::new(),
            global: false,
            priority: 20,
        },
        entry: "index.html".to_owned(),
        backend: PluginBackendKind::None,
        capabilities: vec![PluginCapability::Toast],
        surface: PluginSurfacePrefs {
            backdrop: PluginSurfaceBackdrop::NativeGlass,
            ..PluginSurfacePrefs::default()
        },
    }
}

pub(super) fn build_descriptor(
    loaded: &LoadedPlugin,
    ov: Option<&PluginOverrideEntry>,
    config: &SideVeilConfig,
    backend_online: bool,
) -> PluginDescriptor {
    let clean_scope = config.scope.sanitized();
    let enabled = ov.and_then(|o| o.enabled).unwrap_or(true);
    let priority = ov
        .and_then(|o| o.priority)
        .unwrap_or(loaded.manifest.triggers.priority);
    let has_scope_override =
        ov.is_some_and(|o| o.packages_override.is_some() || o.global_override.is_some());

    let (packages, global) = if loaded.builtin && !has_scope_override {
        (clean_scope.allowlist, clean_scope.mode == ScopeMode::Global)
    } else {
        (
            ov.and_then(|o| o.packages_override.clone())
                .unwrap_or_else(|| loaded.manifest.triggers.packages.clone()),
            ov.and_then(|o| o.global_override)
                .unwrap_or(loaded.manifest.triggers.global),
        )
    };

    let backend_state = match loaded.manifest.backend {
        PluginBackendKind::None => PluginBackendState::None,
        PluginBackendKind::UdsNative | PluginBackendKind::UdsApp => {
            if backend_online {
                PluginBackendState::Online
            } else {
                PluginBackendState::Offline
            }
        }
    };

    PluginDescriptor {
        id: loaded.manifest.id.clone(),
        name: loaded.manifest.name.clone(),
        version: loaded.manifest.version.clone(),
        icon: loaded.manifest.icon.clone(),
        entry: loaded.manifest.entry.clone(),
        builtin: loaded.builtin,
        enabled,
        priority,
        packages,
        activities: loaded.manifest.triggers.activities.clone(),
        global,
        has_scope_override,
        dev_server_url: ov.and_then(|o| o.dev_server_url.clone()),
        backend: loaded.manifest.backend,
        backend_state,
        capabilities: loaded.manifest.capabilities.clone(),
        surface: loaded.manifest.surface.clone(),
    }
}

/// Checks whether a plugin's optional `activities` filter matches `foreground_activity`.
#[must_use]
pub(super) fn matches_activity(activities: &[String], foreground_activity: &str) -> bool {
    if activities.is_empty() {
        return true;
    }
    let fg = foreground_activity.trim();
    if fg.is_empty() {
        return false;
    }
    activities.iter().any(|rule| {
        let clean = rule.trim();
        !clean.is_empty() && (fg == clean || fg.ends_with(clean))
    })
}

/// Validates that a Dev Mode server URL strictly targets loopback (`127.0.0.1` or `localhost`).
pub(super) fn validate_loopback_dev_url(raw: &str) -> Result<String, String> {
    let trimmed = raw.trim().trim_end_matches('/');
    let rest = trimmed.strip_prefix("http://").ok_or_else(|| {
        "devServerUrl must start with http://127.0.0.1 or http://localhost".to_owned()
    })?;
    let host_port = rest.split('/').next().unwrap_or_default();
    let host = host_port.split(':').next().unwrap_or_default();
    if host != "127.0.0.1" && host != "localhost" {
        return Err(
            "devServerUrl is restricted strictly to loopback (127.0.0.1 / localhost)".to_owned(),
        );
    }
    if trimmed.contains(char::is_whitespace) || trimmed.contains('\r') || trimmed.contains('\n') {
        return Err("devServerUrl must not contain whitespace".to_owned());
    }
    Ok(trimmed.to_owned())
}

pub(super) fn scan_plugins_dir(
    plugins_dir: &Path,
    logs: &LogRing,
) -> BTreeMap<String, LoadedPlugin> {
    let mut discovered = BTreeMap::new();
    discovered.insert(
        BUILTIN_DIAG_PLUGIN_ID.to_owned(),
        LoadedPlugin {
            manifest: builtin_diag_manifest(),
            root_dir: None,
            builtin: true,
        },
    );

    if let Ok(entries) = fs::read_dir(plugins_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_dir() {
                continue;
            }
            let Some(dir_name) = entry.file_name().to_str().map(str::to_owned) else {
                continue;
            };
            if dir_name.starts_with('.') || dir_name == BUILTIN_DIAG_PLUGIN_ID {
                continue;
            }
            let manifest_path = path.join("manifest.json");
            let Ok(raw) = fs::read_to_string(&manifest_path) else {
                continue;
            };
            match serde_json::from_str::<PluginManifest>(&raw) {
                Ok(manifest) if manifest.id != dir_name => {
                    logs.warn(format!(
                        "Skipped plugin dir '{dir_name}': manifest.id '{}' does not match directory name",
                        manifest.id
                    ));
                }
                Ok(manifest) => {
                    if let Err(err) = manifest.validate() {
                        logs.warn(format!("Skipped invalid plugin '{dir_name}': {err}"));
                        continue;
                    }
                    discovered.insert(
                        manifest.id.clone(),
                        LoadedPlugin {
                            manifest,
                            root_dir: Some(path),
                            builtin: false,
                        },
                    );
                }
                Err(err) => {
                    logs.warn(format!(
                        "Skipped malformed manifest in '{}': {err}",
                        manifest_path.display()
                    ));
                }
            }
        }
    }
    discovered
}

pub(super) fn load_overrides(path: &Path, logs: &LogRing) -> PluginOverridesFile {
    match fs::read_to_string(path) {
        Ok(raw) => serde_json::from_str(&raw).unwrap_or_else(|err| {
            logs.warn(format!("Invalid JSON in {}: {err}", path.display()));
            PluginOverridesFile::default()
        }),
        Err(_) => PluginOverridesFile::default(),
    }
}

pub(super) fn persist_overrides(path: &Path, data: &PluginOverridesFile) -> io::Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let json = serde_json::to_string_pretty(data)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    let tmp = path.with_extension(format!("json.tmp.{}", std::process::id()));
    let mut f = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&tmp)?;
    f.write_all(json.as_bytes())?;
    f.write_all(b"\n")?;
    f.sync_all()?;
    fs::set_permissions(&tmp, fs::Permissions::from_mode(0o644))?;
    drop(f);
    fs::rename(&tmp, path)?;
    if let Some(parent) = path.parent()
        && let Ok(dir) = File::open(parent)
    {
        let _ = dir.sync_all();
    }
    Ok(())
}
