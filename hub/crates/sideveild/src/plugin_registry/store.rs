//! Built-in diagnostic manifest, descriptor assembly, and atomic `plugins.json` persistence.

use std::collections::BTreeMap;
use std::fs;
use std::io;
use std::path::Path;

use sideveil_protocol::config::{ScopeMode, SideVeilConfig};
use sideveil_protocol::generated::protocol_constants::builtin_diagnostic as diag;
use sideveil_protocol::plugin::{
    BUILTIN_DIAG_PLUGIN_ID, PluginBackendKind, PluginBackendState, PluginCapability,
    PluginDescriptor, PluginManifest, PluginOverrideEntry, PluginOverridesFile,
    PluginSurfaceBackdrop, PluginSurfacePrefs, PluginTriggers,
};

use super::LoadedPlugin;
use crate::fs_util::write_file_atomic;
use crate::log_ring::LogRing;

pub(super) fn builtin_diag_manifest() -> PluginManifest {
    PluginManifest {
        id: BUILTIN_DIAG_PLUGIN_ID.to_owned(),
        name: diag::NAME.to_owned(),
        version: env!("CARGO_PKG_VERSION").to_owned(),
        icon: None,
        triggers: PluginTriggers {
            packages: Vec::new(),
            activities: Vec::new(),
            global: diag::GLOBAL,
            priority: diag::PRIORITY,
        },
        entry: diag::ENTRY.to_owned(),
        backend: PluginBackendKind::None,
        capabilities: vec![PluginCapability::Storage, PluginCapability::Toast],
        surface: PluginSurfacePrefs {
            backdrop: PluginSurfaceBackdrop::NativeGlass,
            ..PluginSurfacePrefs::default()
        },
        embed: None,
        network: None,
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

    let mut surface = loaded.manifest.surface.clone();
    let mut embed = loaded.manifest.embed.clone();
    let mut network = loaded.manifest.network.clone();
    if let Some(o) = ov {
        if let Some(keep_alive) = o.keep_alive {
            surface.keep_alive = keep_alive;
        }
        surface.width_dp = o.width_dp.or(surface.width_dp);
        surface.height_percent = o.height_percent.or(surface.height_percent);
        if let Some(e) = embed.as_mut() {
            if let Some(url) = &o.embed_url {
                e.url = url.clone();
            }
            if let Some(remote_url) = &o.remote_url {
                e.remote_url = Some(remote_url.clone());
            }
            if let Some(lan_ssids) = &o.lan_ssids {
                e.lan_ssids = lan_ssids.clone();
            }
            if let Some(origins) = &o.allowed_origins {
                e.allowed_origins = origins.clone();
            }
            e.allow_cleartext = o.allow_cleartext.unwrap_or(e.allow_cleartext);
            e.wide_viewport = o.wide_viewport.unwrap_or(e.wide_viewport);
            e.allow_zoom = o.allow_zoom.unwrap_or(e.allow_zoom);
            e.web_history_back = o.web_history_back.unwrap_or(e.web_history_back);
            e.text_zoom = o.text_zoom.or(e.text_zoom);
            e.show_toolbar = o.show_toolbar.unwrap_or(e.show_toolbar);
            e.keep_alive_grace_sec = o.keep_alive_grace_sec.or(e.keep_alive_grace_sec);
        }
        if let Some(n) = network.as_mut() {
            if let Some(origins) = &o.allowed_origins {
                n.allowed_origins = origins.clone();
            }
            n.allow_cleartext = o.allow_cleartext.unwrap_or(n.allow_cleartext);
        }
    }

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
        surface,
        embed,
        network,
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
    let mut json = serde_json::to_string_pretty(data)
        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
    json.push('\n');
    write_file_atomic(path, json.as_bytes(), 0o644, None, true)
}
