//! Built-in diagnostic manifest, descriptor assembly, directory scanning, and atomic `plugins.json` persistence.

use std::collections::BTreeMap;
use std::fs;
use std::io;
use std::path::{Component, Path, PathBuf};

use sideveil_protocol::config::{ScopeMode, SideVeilConfig};
use sideveil_protocol::generated::protocol_constants::builtin_diagnostic as diag;
use sideveil_protocol::plugin::{
    BUILTIN_DIAG_PLUGIN_ID, PluginBackendKind, PluginBackendState, PluginCapability,
    PluginDaemonStatus, PluginDescriptor, PluginManifest, PluginManifestSurfacePrefs,
    PluginManifestTriggers, PluginOrigin, PluginOverrideEntry, PluginOverridesFile,
    PluginSurfaceBackdrop,
};
use sideveil_protocol::{MANIFEST_SCHEMA_VERSION, PLUGIN_API_VERSION};

use super::LoadedPlugin;
use super::grants::{compute_binary_fingerprint, compute_effective_grants};
use super::installer::is_reserved_plugin_id;
use crate::fs_util::write_file_atomic;
use crate::log_ring::LogRing;
use crate::supervisor::DaemonRuntimeInfo;

pub(super) fn builtin_diag_manifest() -> PluginManifest {
    PluginManifest {
        schema_version: Some(MANIFEST_SCHEMA_VERSION),
        api_version: Some(PLUGIN_API_VERSION),
        min_host_version: None,
        id: BUILTIN_DIAG_PLUGIN_ID.to_owned(),
        name: diag::NAME.to_owned(),
        version: env!("CARGO_PKG_VERSION").to_owned(),
        description: Some("Built-in SideVeil diagnostic panel".to_owned()),
        author: Some("SideVeil".to_owned()),
        homepage: None,
        icon: None,
        default_enabled: Some(true),
        triggers: Some(PluginManifestTriggers {
            packages: Some(Vec::new()),
            activities: Some(Vec::new()),
            global: Some(diag::GLOBAL),
            priority: Some(diag::PRIORITY),
        }),
        entry: Some(diag::ENTRY.to_owned()),
        backend: Some(PluginBackendKind::None),
        backend_packages: None,
        daemon: None,
        capabilities: Some(vec![PluginCapability::Storage, PluginCapability::Toast]),
        surface: Some(PluginManifestSurfacePrefs {
            backdrop: Some(PluginSurfaceBackdrop::NativeGlass),
            ..PluginManifestSurfacePrefs::default()
        }),
        embed: None,
        network: None,
    }
}

pub(super) fn build_descriptor(
    loaded: &LoadedPlugin,
    ov: Option<&PluginOverrideEntry>,
    config: &SideVeilConfig,
    backend_online: bool,
    daemon_info: Option<&DaemonRuntimeInfo>,
) -> PluginDescriptor {
    let clean_scope = config.scope.sanitized();
    let default_enabled = loaded.manifest.effective_default_enabled();
    let enabled = ov.and_then(|o| o.enabled).unwrap_or(default_enabled);
    let triggers = loaded.manifest.effective_triggers();
    let priority = ov.and_then(|o| o.priority).unwrap_or(triggers.priority);
    let has_scope_override =
        ov.is_some_and(|o| o.packages_override.is_some() || o.global_override.is_some());
    let is_builtin = loaded.origin == PluginOrigin::Builtin;

    let (packages, global) = if is_builtin && !has_scope_override {
        (clean_scope.allowlist, clean_scope.mode == ScopeMode::Global)
    } else {
        (
            ov.and_then(|o| o.packages_override.clone())
                .unwrap_or(triggers.packages),
            ov.and_then(|o| o.global_override)
                .unwrap_or(triggers.global),
        )
    };

    let backend = loaded.manifest.effective_backend();
    let backend_state = match backend {
        PluginBackendKind::None => PluginBackendState::None,
        PluginBackendKind::UdsNative | PluginBackendKind::UdsApp => {
            if backend_online {
                PluginBackendState::Online
            } else {
                PluginBackendState::Offline
            }
        }
    };

    let mut surface = loaded.manifest.effective_surface();
    let mut embed = loaded.manifest.effective_embed();
    let mut network = loaded.manifest.effective_network();
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

    let grants = compute_effective_grants(loaded, ov);
    let (daemon_status, daemon_pid, daemon_restart_count, daemon_last_error) = if !grants.has_daemon
    {
        (PluginDaemonStatus::NotConfigured, None, 0, None)
    } else if let Some(info) = daemon_info {
        (
            info.status,
            info.pid,
            info.restart_count,
            info.last_error.clone(),
        )
    } else if !enabled {
        (PluginDaemonStatus::Stopped, None, 0, None)
    } else if !grants.daemon_approved {
        (PluginDaemonStatus::AwaitingApproval, None, 0, None)
    } else {
        (PluginDaemonStatus::Stopped, None, 0, None)
    };

    PluginDescriptor {
        id: loaded.manifest.id.clone(),
        name: loaded.manifest.name.clone(),
        version: loaded.manifest.version.clone(),
        origin: loaded.origin,
        schema_version: loaded.manifest.effective_schema_version(),
        api_version: loaded.manifest.effective_api_version(),
        min_host_version: loaded.manifest.min_host_version.clone(),
        description: loaded.manifest.description.clone(),
        author: loaded.manifest.author.clone(),
        homepage: loaded.manifest.homepage.clone(),
        icon: loaded.manifest.icon.clone(),
        entry: loaded.manifest.effective_entry().to_owned(),
        asset_revision: loaded.asset_revision.clone(),
        builtin: is_builtin,
        default_enabled,
        enabled,
        priority,
        packages,
        activities: triggers.activities,
        global,
        has_scope_override,
        dev_server_url: ov.and_then(|o| o.dev_server_url.clone()),
        backend,
        backend_state,
        backend_packages: loaded.manifest.effective_backend_packages().to_vec(),
        capabilities: loaded.manifest.effective_capabilities().to_vec(),
        granted_capabilities: grants.granted,
        pending_capabilities: grants.pending,
        approval_status: grants.approval_status,
        daemon_approved: grants.daemon_approved,
        daemon_status,
        daemon_pid,
        daemon_restart_count,
        daemon_last_error,
        has_daemon: grants.has_daemon,
        surface,
        embed,
        network,
    }
}

pub(super) fn matches_activity(activities: &[String], fg_activity: &str) -> bool {
    if activities.is_empty() {
        return true;
    }
    let fg = fg_activity.trim();
    if fg.is_empty() {
        return false;
    }
    activities.iter().any(|rule| {
        let clean = rule.trim();
        !clean.is_empty() && (fg == clean || fg.ends_with(clean))
    })
}

/// Computes a fast deterministic hex fingerprint (FNV-1a 64-bit + byte length) for a plugin's
/// entry file (e.g. `web/index.html`) so warm WebViews detect asset bundle rebuilds even when
/// `manifest.json` itself is unchanged.
pub(super) fn compute_asset_revision(plugin_root: &Path, entry: &str) -> Option<String> {
    let trimmed = entry.trim().trim_start_matches('/');
    if trimmed.is_empty() || trimmed.contains('\\') {
        return None;
    }
    let mut clean_rel = PathBuf::new();
    for comp in Path::new(trimmed).components() {
        match comp {
            Component::Normal(seg) => clean_rel.push(seg),
            _ => return None,
        }
    }
    let entry_path = plugin_root.join(clean_rel);
    let bytes = fs::read(entry_path).ok()?;
    const FNV_OFFSET_BASIS: u64 = 0xcbf2_9ce4_8422_2325;
    const FNV_PRIME: u64 = 0x0000_0100_0000_01b3;
    let mut hash = FNV_OFFSET_BASIS;
    for &b in &bytes {
        hash ^= u64::from(b);
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    Some(format!("{hash:016x}-{:x}", bytes.len()))
}

pub(super) fn scan_plugins_dirs(
    plugins_dir: &Path,
    bundled_dir: Option<&Path>,
    logs: &LogRing,
) -> BTreeMap<String, LoadedPlugin> {
    let mut discovered = BTreeMap::new();
    discovered.insert(
        BUILTIN_DIAG_PLUGIN_ID.to_owned(),
        LoadedPlugin {
            manifest: builtin_diag_manifest(),
            root_dir: None,
            asset_revision: None,
            origin: PluginOrigin::Builtin,
            daemon_binary_fingerprint: None,
        },
    );

    if let Some(dir) = bundled_dir {
        scan_single_dir(dir, true, logs, &mut discovered);
    }
    scan_single_dir(plugins_dir, false, logs, &mut discovered);
    discovered
}

fn scan_single_dir(
    root: &Path,
    bundled: bool,
    logs: &LogRing,
    discovered: &mut BTreeMap<String, LoadedPlugin>,
) {
    let Ok(entries) = fs::read_dir(root) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let Some(dir_name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        if dir_name.starts_with('.') || is_reserved_plugin_id(&dir_name) {
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
                if !bundled
                    && discovered.get(&manifest.id).is_some_and(|e| {
                        matches!(e.origin, PluginOrigin::Builtin | PluginOrigin::Bundled)
                    })
                {
                    logs.warn(format!(
                        "Skipped external plugin '{}': cannot shadow built-in or bundled plugin ID",
                        manifest.id
                    ));
                    continue;
                }
                let origin = if bundled {
                    PluginOrigin::Bundled
                } else if manifest.embed.is_some() {
                    PluginOrigin::WebEmbed
                } else {
                    PluginOrigin::Installed
                };
                let asset_revision = compute_asset_revision(&path, manifest.effective_entry());
                let daemon_binary_fingerprint = compute_binary_fingerprint(&path, &manifest);
                discovered.insert(
                    manifest.id.clone(),
                    LoadedPlugin {
                        manifest,
                        root_dir: Some(path),
                        asset_revision,
                        origin,
                        daemon_binary_fingerprint,
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
