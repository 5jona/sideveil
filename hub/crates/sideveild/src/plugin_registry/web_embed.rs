//! Atomic `web-embed` plugin creation and external plugin directory & data cleanup.

use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};

use sideveil_protocol::plugin::{
    PluginBackendKind, PluginCreateWebEmbedParams, PluginManifest, PluginManifestEmbedPrefs,
    PluginManifestSurfacePrefs, PluginManifestTriggers, validate_plugin_id,
};
use sideveil_protocol::{MANIFEST_SCHEMA_VERSION, PLUGIN_API_VERSION};

use super::error::PluginRegistryError;
use super::installer::{is_reserved_plugin_id, try_chown_root_system};

static EMBED_SEQ: AtomicU64 = AtomicU64::new(1);

/// Creates a zero-privilege `web-embed` plugin by atomically writing `<plugins_dir>/<id>/manifest.json`.
pub(super) fn create_web_embed_plugin(
    plugins_dir: &Path,
    bundled_dir: Option<&Path>,
    params: &PluginCreateWebEmbedParams,
) -> Result<PluginManifest, PluginRegistryError> {
    let id = params.id.trim().to_owned();
    if is_reserved_plugin_id(&id) || bundled_dir.is_some_and(|d| d.join(&id).is_dir()) {
        return Err(PluginRegistryError::BuiltinProtected(id));
    }

    if let Some(w) = params.width_dp
        && !(240..=720).contains(&w)
    {
        return Err(PluginRegistryError::InvalidParameter {
            field: "widthDp",
            reason: "must be within 240..=720",
        });
    }
    if let Some(h) = params.height_percent
        && !(30..=100).contains(&h)
    {
        return Err(PluginRegistryError::InvalidParameter {
            field: "heightPercent",
            reason: "must be within 30..=100",
        });
    }

    let remote_url = params
        .remote_url
        .as_deref()
        .map(str::trim)
        .filter(|u| !u.is_empty())
        .map(str::to_owned);

    let manifest = PluginManifest {
        schema_version: Some(MANIFEST_SCHEMA_VERSION),
        api_version: Some(PLUGIN_API_VERSION),
        min_host_version: None,
        id: id.clone(),
        name: params.name.trim().to_owned(),
        version: "1.0.0".to_owned(),
        description: None,
        author: None,
        homepage: None,
        icon: None,
        default_enabled: Some(true),
        triggers: Some(PluginManifestTriggers {
            packages: Some(
                params
                    .packages
                    .iter()
                    .map(|p| p.trim().to_owned())
                    .filter(|p| !p.is_empty())
                    .collect(),
            ),
            activities: Some(Vec::new()),
            global: Some(params.global),
            priority: Some(params.priority.unwrap_or(10).clamp(-1000, 1000)),
        }),
        entry: Some(String::new()),
        backend: Some(PluginBackendKind::None),
        backend_packages: None,
        daemon: None,
        capabilities: Some(Vec::new()),
        surface: Some(PluginManifestSurfacePrefs {
            backdrop: None,
            presentation: None,
            cross_origin_isolated: None,
            keep_alive: Some(params.keep_alive),
            width_dp: params.width_dp,
            height_percent: params.height_percent,
        }),
        embed: Some(PluginManifestEmbedPrefs {
            url: params.url.trim().to_owned(),
            remote_url,
            lan_ssids: Some(
                params
                    .lan_ssids
                    .iter()
                    .map(|s| s.trim().to_owned())
                    .filter(|s| !s.is_empty())
                    .collect(),
            ),
            allowed_origins: Some(
                params
                    .allowed_origins
                    .iter()
                    .map(|o| o.trim().to_owned())
                    .filter(|o| !o.is_empty())
                    .collect(),
            ),
            allow_cleartext: Some(params.allow_cleartext),
            wide_viewport: Some(params.wide_viewport.unwrap_or(true)),
            allow_zoom: Some(params.allow_zoom.unwrap_or(false)),
            web_history_back: Some(params.web_history_back.unwrap_or(true)),
            text_zoom: params.text_zoom,
            show_toolbar: Some(params.show_toolbar.unwrap_or(true)),
            keep_alive_grace_sec: params.keep_alive_grace_sec,
        }),
        network: None,
    };
    manifest.validate()?;

    fs::create_dir_all(plugins_dir).map_err(|source| PluginRegistryError::Io {
        context: format!("failed to create plugins dir '{}'", plugins_dir.display()),
        source,
    })?;

    let plugin_dir = plugins_dir.join(&id);
    if plugin_dir.exists() {
        return Err(PluginRegistryError::InstallConstraint(format!(
            "plugin '{id}' already exists; uninstall it before creating a web-embed with the same ID"
        )));
    }
    fs::create_dir_all(&plugin_dir).map_err(|source| PluginRegistryError::Io {
        context: format!("failed to create plugin dir '{}'", plugin_dir.display()),
        source,
    })?;
    let _ = fs::set_permissions(&plugin_dir, fs::Permissions::from_mode(0o755));
    try_chown_root_system(&plugin_dir);

    let json =
        serde_json::to_string_pretty(&manifest).map_err(|source| PluginRegistryError::Json {
            context: "failed to serialize manifest".to_owned(),
            source,
        })?;
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
    if let Err(source) = write_res {
        let _ = fs::remove_file(&tmp_path);
        let _ = fs::remove_dir(&plugin_dir);
        return Err(PluginRegistryError::Io {
            context: "failed to write manifest.json atomically".to_owned(),
            source,
        });
    }

    Ok(manifest)
}

/// Removes an installed external plugin directory after canonicalization checks.
pub(super) fn uninstall_plugin_dir(
    plugins_dir: &Path,
    plugin_id: &str,
) -> Result<(), PluginRegistryError> {
    let id = plugin_id.trim();
    validate_plugin_id(id)?;
    if is_reserved_plugin_id(id) {
        return Err(PluginRegistryError::BuiltinProtected(id.to_owned()));
    }

    let plugins_canon =
        fs::canonicalize(plugins_dir).map_err(|source| PluginRegistryError::Io {
            context: "failed to canonicalize plugins dir".to_owned(),
            source,
        })?;
    let target = plugins_dir.join(id);
    if !target.exists() {
        return Err(PluginRegistryError::NotInstalled(id.to_owned()));
    }
    let target_canon = fs::canonicalize(&target).map_err(|source| PluginRegistryError::Io {
        context: "failed to canonicalize plugin dir".to_owned(),
        source,
    })?;
    if !target_canon.starts_with(&plugins_canon) || target_canon == plugins_canon {
        return Err(PluginRegistryError::InstallConstraint(
            "refusing to remove path outside plugins directory".to_owned(),
        ));
    }
    fs::remove_dir_all(&target_canon).map_err(|source| PluginRegistryError::Io {
        context: format!("failed to remove plugin directory '{id}'"),
        source,
    })
}

/// Safely removes `/data/system/sideveil/data/<plugin_id>/` when uninstalling a plugin.
pub(super) fn remove_plugin_data_dir(data_dir: &Path, plugin_id: &str) {
    let Ok(base_canon) = fs::canonicalize(data_dir) else {
        return;
    };
    let candidate = base_canon.join(plugin_id);
    let Ok(meta) = fs::symlink_metadata(&candidate) else {
        return;
    };
    if meta.file_type().is_symlink() || !meta.file_type().is_dir() {
        return;
    }
    if let Ok(target_canon) = fs::canonicalize(&candidate)
        && target_canon.starts_with(&base_canon)
        && target_canon != base_canon
    {
        let _ = fs::remove_dir_all(target_canon);
    }
}
