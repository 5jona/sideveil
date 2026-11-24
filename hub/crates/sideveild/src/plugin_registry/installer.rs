//! Atomic staged plugin installer supporting directories and `.svp` archives with symlink guards.

use std::fs;
use std::io;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};

use sideveil_protocol::plugin::{BUILTIN_DIAG_PLUGIN_ID, PluginManifest};
use sideveil_protocol::svp::SvpArchive;

use super::error::PluginRegistryError;
use super::grants::compute_binary_fingerprint;
use crate::fs_util::chown_path;

static INSTALL_SEQ: AtomicU64 = AtomicU64::new(1);

/// Checks whether `plugin_id` is reserved for immutable built-in or internal SideVeil system IDs.
pub(super) fn is_reserved_plugin_id(plugin_id: &str) -> bool {
    matches!(
        plugin_id,
        BUILTIN_DIAG_PLUGIN_ID | "sideveil.host" | "sideveil.hud.demo"
    )
}

/// Validates `source_dir/manifest.json` and installs the plugin package atomically into
/// `<plugins_dir>/<manifest.id>` using a dot-prefixed staging directory swap.
pub(super) fn install_plugin_dir(
    plugins_dir: &Path,
    bundled_dir: Option<&Path>,
    source_dir: &Path,
) -> Result<(PluginManifest, Option<String>), PluginRegistryError> {
    if !source_dir.is_dir() {
        return Err(PluginRegistryError::InvalidSourceDir(
            source_dir.to_path_buf(),
        ));
    }

    fs::create_dir_all(plugins_dir).map_err(|source| PluginRegistryError::Io {
        context: format!("failed to create plugins dir '{}'", plugins_dir.display()),
        source,
    })?;

    let src_canon = fs::canonicalize(source_dir).map_err(|source| PluginRegistryError::Io {
        context: format!("failed to canonicalize '{}'", source_dir.display()),
        source,
    })?;
    let plugins_canon =
        fs::canonicalize(plugins_dir).map_err(|source| PluginRegistryError::Io {
            context: format!("failed to canonicalize '{}'", plugins_dir.display()),
            source,
        })?;
    if src_canon.starts_with(&plugins_canon) {
        return Err(PluginRegistryError::InstallConstraint(
            "source directory must lie outside the live SideVeil plugins directory".to_owned(),
        ));
    }

    let manifest_path = src_canon.join("manifest.json");
    let manifest_raw =
        fs::read_to_string(&manifest_path).map_err(|source| PluginRegistryError::Io {
            context: format!("failed to read '{}'", manifest_path.display()),
            source,
        })?;
    let manifest: PluginManifest =
        serde_json::from_str(&manifest_raw).map_err(|source| PluginRegistryError::Json {
            context: format!("invalid manifest JSON in '{}'", manifest_path.display()),
            source,
        })?;
    manifest.validate()?;

    if is_reserved_plugin_id(&manifest.id)
        || bundled_dir.is_some_and(|d| d.join(&manifest.id).is_dir())
    {
        return Err(PluginRegistryError::BuiltinProtected(manifest.id));
    }

    if manifest.embed.is_none() {
        let entry = manifest.effective_entry();
        let entry_path = src_canon.join(entry);
        let entry_meta =
            fs::symlink_metadata(&entry_path).map_err(|source| PluginRegistryError::Io {
                context: format!("manifest entry '{entry}' missing in source directory"),
                source,
            })?;
        if !entry_meta.file_type().is_file() {
            return Err(PluginRegistryError::InstallConstraint(format!(
                "manifest entry '{entry}' must be a regular file"
            )));
        }
    }
    if let Some(daemon) = &manifest.daemon {
        let bin_path = src_canon.join(&daemon.binary);
        let bin_meta =
            fs::symlink_metadata(&bin_path).map_err(|source| PluginRegistryError::Io {
                context: format!(
                    "declared daemon binary '{}' missing in source directory",
                    daemon.binary
                ),
                source,
            })?;
        if !bin_meta.file_type().is_file() {
            return Err(PluginRegistryError::InstallConstraint(format!(
                "declared daemon binary '{}' must be a regular file",
                daemon.binary
            )));
        }
    }

    let daemon_rel = manifest.daemon.as_ref().map(|d| d.binary.as_str());
    activate_staged(plugins_dir, &manifest, |staging_dir| {
        copy_dir_no_symlinks(&src_canon, &src_canon, staging_dir, daemon_rel)
    })
}

/// Extracts a validated [`SvpArchive`] atomically into `<plugins_dir>/<manifest.id>`.
pub(super) fn install_plugin_archive(
    plugins_dir: &Path,
    bundled_dir: Option<&Path>,
    archive: &SvpArchive,
) -> Result<(PluginManifest, Option<String>), PluginRegistryError> {
    archive.manifest.validate()?;
    if is_reserved_plugin_id(&archive.manifest.id)
        || bundled_dir.is_some_and(|d| d.join(&archive.manifest.id).is_dir())
    {
        return Err(PluginRegistryError::BuiltinProtected(
            archive.manifest.id.clone(),
        ));
    }
    fs::create_dir_all(plugins_dir).map_err(|source| PluginRegistryError::Io {
        context: format!("failed to create plugins dir '{}'", plugins_dir.display()),
        source,
    })?;

    let daemon_rel = archive.manifest.daemon.as_ref().map(|d| d.binary.as_str());
    activate_staged(plugins_dir, &archive.manifest, |staging_dir| {
        fs::create_dir_all(staging_dir)?;
        fs::set_permissions(staging_dir, fs::Permissions::from_mode(0o755))?;
        try_chown_root_system(staging_dir);
        for (rel, entry) in &archive.files {
            let dst = staging_dir.join(rel);
            if let Some(parent) = dst.parent() {
                fs::create_dir_all(parent)?;
                fs::set_permissions(parent, fs::Permissions::from_mode(0o755))?;
                try_chown_root_system(parent);
            }
            fs::write(&dst, &entry.bytes)?;
            let mode = if daemon_rel == Some(rel.as_str()) {
                0o755
            } else {
                0o644
            };
            fs::set_permissions(&dst, fs::Permissions::from_mode(mode))?;
            try_chown_root_system(&dst);
        }
        Ok(())
    })
}

fn activate_staged(
    plugins_dir: &Path,
    manifest: &PluginManifest,
    populate: impl FnOnce(&Path) -> io::Result<()>,
) -> Result<(PluginManifest, Option<String>), PluginRegistryError> {
    let seq = INSTALL_SEQ.fetch_add(1, Ordering::Relaxed);
    let pid = std::process::id();
    let staging_dir = plugins_dir.join(format!(".{}.staging.{pid}.{seq}", manifest.id));
    let backup_dir = plugins_dir.join(format!(".{}.old.{pid}.{seq}", manifest.id));
    let target_dir = plugins_dir.join(&manifest.id);

    let _ = fs::remove_dir_all(&staging_dir);
    let _ = fs::remove_dir_all(&backup_dir);

    if let Err(source) = populate(&staging_dir) {
        let _ = fs::remove_dir_all(&staging_dir);
        return Err(PluginRegistryError::Io {
            context: "failed staging plugin files".to_owned(),
            source,
        });
    }

    let fingerprint = compute_binary_fingerprint(&staging_dir, manifest);

    let had_existing = target_dir.exists();
    if had_existing && let Err(source) = fs::rename(&target_dir, &backup_dir) {
        let _ = fs::remove_dir_all(&staging_dir);
        return Err(PluginRegistryError::Io {
            context: "failed moving previous plugin dir aside".to_owned(),
            source,
        });
    }

    if let Err(source) = fs::rename(&staging_dir, &target_dir) {
        let _ = fs::remove_dir_all(&staging_dir);
        if had_existing {
            let _ = fs::rename(&backup_dir, &target_dir);
        }
        return Err(PluginRegistryError::Io {
            context: "failed activating staged plugin dir".to_owned(),
            source,
        });
    }

    let _ = fs::remove_dir_all(&backup_dir);
    Ok((manifest.clone(), fingerprint))
}

fn copy_dir_no_symlinks(
    src_root: &Path,
    src: &Path,
    dst: &Path,
    daemon_rel: Option<&str>,
) -> io::Result<()> {
    fs::create_dir_all(dst)?;
    fs::set_permissions(dst, fs::Permissions::from_mode(0o755))?;
    try_chown_root_system(dst);

    for entry in fs::read_dir(src)? {
        let entry = entry?;
        let name = entry.file_name();
        if name.to_str().is_some_and(|n| n.starts_with('.')) {
            continue;
        }
        let src_path = entry.path();
        let dst_path = dst.join(&name);
        let meta = fs::symlink_metadata(&src_path)?;
        let ftype = meta.file_type();
        if ftype.is_symlink() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!(
                    "symlinks are disallowed in plugin packages: {}",
                    src_path.display()
                ),
            ));
        }
        if ftype.is_dir() {
            copy_dir_no_symlinks(src_root, &src_path, &dst_path, daemon_rel)?;
        } else if ftype.is_file() {
            fs::copy(&src_path, &dst_path)?;
            let rel_str = src_path
                .strip_prefix(src_root)
                .ok()
                .and_then(Path::to_str)
                .unwrap_or_default();
            let mode = if daemon_rel == Some(rel_str) {
                0o755
            } else {
                0o644
            };
            fs::set_permissions(&dst_path, fs::Permissions::from_mode(mode))?;
            try_chown_root_system(&dst_path);
        }
    }
    Ok(())
}

pub(super) fn try_chown_root_system(path: &Path) {
    let _ = chown_path(path, 0, 1000);
}
