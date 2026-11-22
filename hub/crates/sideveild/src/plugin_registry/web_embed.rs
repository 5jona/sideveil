//! Atomic `web-embed` plugin creation and external plugin directory removal.

use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};

use sideveil_protocol::plugin::{
    BUILTIN_DIAG_PLUGIN_ID, PluginBackendKind, PluginCreateWebEmbedParams, PluginEmbedPrefs,
    PluginManifest, PluginSurfacePrefs, PluginTriggers, validate_plugin_id,
};

use super::installer::try_chown_root_system;

static EMBED_SEQ: AtomicU64 = AtomicU64::new(1);

/// Creates a zero-privilege `web-embed` plugin by atomically writing `<plugins_dir>/<id>/manifest.json`.
pub(super) fn create_web_embed_plugin(
    plugins_dir: &Path,
    params: &PluginCreateWebEmbedParams,
) -> Result<PluginManifest, String> {
    let id = params.id.trim().to_owned();
    if id == BUILTIN_DIAG_PLUGIN_ID {
        return Err(format!(
            "cannot overwrite built-in plugin '{BUILTIN_DIAG_PLUGIN_ID}'"
        ));
    }

    let manifest = PluginManifest {
        id: id.clone(),
        name: params.name.trim().to_owned(),
        version: "1.0.0".to_owned(),
        icon: None,
        triggers: PluginTriggers {
            packages: params
                .packages
                .iter()
                .map(|p| p.trim().to_owned())
                .filter(|p| !p.is_empty())
                .collect(),
            activities: Vec::new(),
            global: params.global,
            priority: params.priority.unwrap_or(10).clamp(-1000, 1000),
        },
        entry: String::new(),
        backend: PluginBackendKind::None,
        capabilities: Vec::new(),
        surface: PluginSurfacePrefs {
            keep_alive: params.keep_alive,
            ..PluginSurfacePrefs::default()
        },
        embed: Some(PluginEmbedPrefs {
            url: params.url.trim().to_owned(),
            allowed_origins: params
                .allowed_origins
                .iter()
                .map(|o| o.trim().to_owned())
                .filter(|o| !o.is_empty())
                .collect(),
            allow_cleartext: params.allow_cleartext,
            wide_viewport: true,
            allow_zoom: false,
            web_history_back: true,
        }),
        network: None,
    };
    manifest
        .validate()
        .map_err(|e| format!("invalid web-embed manifest: {e}"))?;

    fs::create_dir_all(plugins_dir).map_err(|e| {
        format!(
            "failed to create plugins dir '{}': {e}",
            plugins_dir.display()
        )
    })?;

    let plugin_dir = plugins_dir.join(&id);
    fs::create_dir_all(&plugin_dir).map_err(|e| {
        format!(
            "failed to create plugin dir '{}': {e}",
            plugin_dir.display()
        )
    })?;
    let _ = fs::set_permissions(&plugin_dir, fs::Permissions::from_mode(0o755));
    try_chown_root_system(&plugin_dir);

    let json = serde_json::to_string_pretty(&manifest)
        .map_err(|e| format!("failed to serialize manifest: {e}"))?;
    let manifest_path = plugin_dir.join("manifest.json");
    let seq = EMBED_SEQ.fetch_add(1, Ordering::Relaxed);
    let tmp_path = plugin_dir.join(format!(".manifest.json.tmp.{}.{seq}", std::process::id()));

    let write_res: io::Result<()> = (|| {
        let mut f = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(&tmp_path)?;
        f.write_all(json.as_bytes())?;
        f.write_all(b"\n")?;
        f.sync_all()?;
        fs::set_permissions(&tmp_path, fs::Permissions::from_mode(0o644))?;
        drop(f);
        try_chown_root_system(&tmp_path);
        fs::rename(&tmp_path, &manifest_path)?;
        if let Ok(dir) = File::open(&plugin_dir) {
            let _ = dir.sync_all();
        }
        Ok(())
    })();
    if let Err(err) = write_res {
        let _ = fs::remove_file(&tmp_path);
        return Err(format!("failed to write manifest.json atomically: {err}"));
    }

    Ok(manifest)
}

/// Removes an installed external plugin directory after canonicalization checks.
pub(super) fn uninstall_plugin_dir(plugins_dir: &Path, plugin_id: &str) -> Result<(), String> {
    let id = plugin_id.trim();
    validate_plugin_id(id).map_err(|e| e.to_string())?;
    if id == BUILTIN_DIAG_PLUGIN_ID {
        return Err(format!(
            "cannot uninstall built-in plugin '{BUILTIN_DIAG_PLUGIN_ID}'"
        ));
    }

    let plugins_canon = fs::canonicalize(plugins_dir)
        .map_err(|e| format!("failed to canonicalize plugins dir: {e}"))?;
    let target = plugins_dir.join(id);
    if !target.exists() {
        return Err(format!("plugin '{id}' is not installed"));
    }
    let target_canon =
        fs::canonicalize(&target).map_err(|e| format!("failed to canonicalize plugin dir: {e}"))?;
    if !target_canon.starts_with(&plugins_canon) || target_canon == plugins_canon {
        return Err("refusing to remove path outside plugins directory".to_owned());
    }
    fs::remove_dir_all(&target_canon)
        .map_err(|e| format!("failed to remove plugin directory '{id}': {e}"))
}
