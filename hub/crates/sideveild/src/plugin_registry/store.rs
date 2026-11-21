//! Built-in diagnostic manifest, descriptor assembly, and atomic `plugins.json` persistence.

use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

use sideveil_protocol::config::{ScopeMode, SideveilConfig};
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
            global: false,
            priority: 0,
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
    config: &SideveilConfig,
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
        global,
        has_scope_override,
        backend: loaded.manifest.backend,
        backend_state,
        capabilities: loaded.manifest.capabilities.clone(),
        surface: loaded.manifest.surface.clone(),
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
