//! Capability-gated per-plugin storage (`fs.read` / `fs.writeAtomic`) and `system.exec`.

mod exec;
#[cfg(test)]
mod tests;

use std::fs::{self, File};
use std::io::{self, Read};
use std::os::unix::fs::PermissionsExt;
use std::path::{Component, Path, PathBuf};

use serde_json::{Value, json};
use sideveil_protocol::plugin::PluginCapability;
use sideveil_protocol::rpc::error_codes;

pub use self::exec::{ExecOutcome, MAX_EXEC_STREAM_BYTES, handle_system_exec};
use crate::fs_util::write_file_atomic;
use crate::plugin_registry::PluginRegistry;

/// Maximum single file size permitted in per-plugin storage (1 MiB).
pub const MAX_PLUGIN_FILE_BYTES: usize = 1024 * 1024;
/// Maximum total directory storage quota per plugin (16 MiB).
pub const MAX_PLUGIN_QUOTA_BYTES: u64 = 16 * 1024 * 1024;

/// Tightens `data_dir` and all direct non-symlink subdirectories to mode `0700`.
///
/// Legacy plugin data subdirectories created before `0700` hardening (e.g. `0755`)
/// are tightened automatically during `sideveild` startup while symlinks and regular
/// files are left untouched.
pub fn harden_data_dir_permissions(data_dir: &Path) -> io::Result<()> {
    fs::create_dir_all(data_dir)?;
    let root_meta = fs::symlink_metadata(data_dir)?;
    if root_meta.file_type().is_symlink() || !root_meta.file_type().is_dir() {
        return Ok(());
    }
    if root_meta.permissions().mode() & 0o777 != 0o700 {
        fs::set_permissions(data_dir, fs::Permissions::from_mode(0o700))?;
    }
    for entry in fs::read_dir(data_dir)? {
        let Ok(entry) = entry else {
            continue;
        };
        let path = entry.path();
        let Ok(meta) = fs::symlink_metadata(&path) else {
            continue;
        };
        if meta.file_type().is_symlink() || !meta.file_type().is_dir() {
            continue;
        }
        if meta.permissions().mode() & 0o777 != 0o700 {
            let _ = fs::set_permissions(&path, fs::Permissions::from_mode(0o700));
        }
    }
    Ok(())
}

/// Handles `fs.read` scoped strictly to `<data_dir>/<plugin_id>/<relative_path>`.
pub fn handle_fs_read(
    registry: &PluginRegistry,
    data_dir: &Path,
    plugin_id: &str,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    ensure_capability(registry, plugin_id, PluginCapability::Storage)?;
    let rel = extract_str_param(params.as_ref(), "path")?;
    let safe_rel = sanitize_relative_path(&rel)?;
    let plugin_root = ensure_clean_plugin_root(data_dir, plugin_id)?;
    verify_no_symlinks_along(&plugin_root, &safe_rel)?;

    let target = plugin_root.join(&safe_rel);
    if !target.exists() {
        return Ok(json!({ "content": Value::Null }));
    }
    let canonical_target = fs::canonicalize(&target).map_err(|e| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed resolving path: {e}"),
        )
    })?;
    if !canonical_target.starts_with(&plugin_root) {
        return Err((
            error_codes::PERMISSION_DENIED,
            "Symlink escape outside plugin data directory denied".to_owned(),
        ));
    }

    let meta = fs::symlink_metadata(&canonical_target).map_err(|e| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed inspecting plugin file: {e}"),
        )
    })?;
    if !meta.file_type().is_file() {
        return Err((
            error_codes::PERMISSION_DENIED,
            "Plugin storage path must reference a regular file".to_owned(),
        ));
    }
    if meta.len() > MAX_PLUGIN_FILE_BYTES as u64 {
        return Err((
            error_codes::QUOTA_EXCEEDED,
            "File exceeds 1 MiB per-file plugin storage limit".to_owned(),
        ));
    }
    let file = File::open(&canonical_target).map_err(|e| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed opening plugin file: {e}"),
        )
    })?;
    let mut bytes = Vec::with_capacity((meta.len() as usize).min(MAX_PLUGIN_FILE_BYTES));
    file.take((MAX_PLUGIN_FILE_BYTES + 1) as u64)
        .read_to_end(&mut bytes)
        .map_err(|e| {
            (
                error_codes::INTERNAL_ERROR,
                format!("Failed reading plugin file: {e}"),
            )
        })?;
    if bytes.len() > MAX_PLUGIN_FILE_BYTES {
        return Err((
            error_codes::QUOTA_EXCEEDED,
            "File exceeds 1 MiB per-file plugin storage limit".to_owned(),
        ));
    }
    let content = String::from_utf8(bytes).map_err(|e| {
        (
            error_codes::INTERNAL_ERROR,
            format!("File is not valid UTF-8: {e}"),
        )
    })?;
    Ok(json!({ "content": content }))
}

/// Handles `fs.writeAtomic` scoped strictly to `<data_dir>/<plugin_id>/<relative_path>`.
pub fn handle_fs_write_atomic(
    registry: &PluginRegistry,
    data_dir: &Path,
    plugin_id: &str,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    ensure_capability(registry, plugin_id, PluginCapability::Storage)?;
    let rel = extract_str_param(params.as_ref(), "path")?;
    let content = extract_str_param(params.as_ref(), "content")?;
    if content.len() > MAX_PLUGIN_FILE_BYTES {
        return Err((
            error_codes::QUOTA_EXCEEDED,
            "Content exceeds 1 MiB per-file plugin storage limit".to_owned(),
        ));
    }

    let safe_rel = sanitize_relative_path(&rel)?;
    let plugin_root = ensure_clean_plugin_root(data_dir, plugin_id)?;
    verify_no_symlinks_along(&plugin_root, &safe_rel)?;

    let target = plugin_root.join(&safe_rel);
    let existing_len = fs::symlink_metadata(&target)
        .ok()
        .filter(|m| m.file_type().is_file())
        .map_or(0, |m| m.len());
    let current_usage = directory_bytes_used(&plugin_root)?;
    let projected = current_usage
        .saturating_sub(existing_len)
        .saturating_add(content.len() as u64);
    if projected > MAX_PLUGIN_QUOTA_BYTES {
        return Err((
            error_codes::QUOTA_EXCEEDED,
            format!(
                "Write exceeds {} MiB total plugin storage quota",
                MAX_PLUGIN_QUOTA_BYTES / (1024 * 1024)
            ),
        ));
    }

    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent).map_err(|e| {
            (
                error_codes::INTERNAL_ERROR,
                format!("Failed creating parent directory: {e}"),
            )
        })?;
        let canonical_parent = fs::canonicalize(parent).map_err(|e| {
            (
                error_codes::INTERNAL_ERROR,
                format!("Failed resolving parent directory: {e}"),
            )
        })?;
        if !canonical_parent.starts_with(&plugin_root) {
            return Err((
                error_codes::PERMISSION_DENIED,
                "Path escapes plugin data directory".to_owned(),
            ));
        }
    }

    write_file_atomic(&target, content.as_bytes(), 0o600, None, false).map_err(|e| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed committing atomic write: {e}"),
        )
    })?;

    Ok(json!({ "errno": 0, "stdout": "OK", "stderr": "" }))
}

pub(super) fn ensure_capability(
    registry: &PluginRegistry,
    plugin_id: &str,
    capability: PluginCapability,
) -> Result<(), (i32, String)> {
    if registry.has_capability(plugin_id, capability) {
        Ok(())
    } else {
        Err((
            error_codes::PERMISSION_DENIED,
            format!("Plugin '{plugin_id}' lacks granted capability {capability:?}"),
        ))
    }
}

pub(super) fn extract_str_param(
    params: Option<&Value>,
    key: &str,
) -> Result<String, (i32, String)> {
    params
        .and_then(|v| v.get(key))
        .and_then(Value::as_str)
        .map(str::to_owned)
        .ok_or_else(|| {
            (
                error_codes::INVALID_PARAMS,
                format!("Missing string parameter '{key}'"),
            )
        })
}

fn ensure_clean_plugin_root(data_dir: &Path, plugin_id: &str) -> Result<PathBuf, (i32, String)> {
    fs::create_dir_all(data_dir).map_err(|e| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed creating data dir: {e}"),
        )
    })?;
    let canonical_base = fs::canonicalize(data_dir).map_err(|e| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed canonicalizing data dir: {e}"),
        )
    })?;
    let root = canonical_base.join(plugin_id);
    if let Ok(meta) = fs::symlink_metadata(&root)
        && meta.file_type().is_symlink()
    {
        return Err((
            error_codes::PERMISSION_DENIED,
            "Plugin data root must not be a symlink".to_owned(),
        ));
    }
    fs::create_dir_all(&root).map_err(|e| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed creating plugin data dir: {e}"),
        )
    })?;
    let _ = fs::set_permissions(&root, fs::Permissions::from_mode(0o700));
    let canonical_root = fs::canonicalize(&root).map_err(|e| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed canonicalizing plugin data dir: {e}"),
        )
    })?;
    if !canonical_root.starts_with(&canonical_base) {
        return Err((
            error_codes::PERMISSION_DENIED,
            "Plugin data root escapes base data directory".to_owned(),
        ));
    }
    Ok(canonical_root)
}

fn verify_no_symlinks_along(root: &Path, rel: &Path) -> Result<(), (i32, String)> {
    let mut cur = root.to_path_buf();
    for comp in rel.components() {
        cur.push(comp);
        if let Ok(meta) = fs::symlink_metadata(&cur)
            && meta.file_type().is_symlink()
        {
            return Err((
                error_codes::PERMISSION_DENIED,
                "Symlink component inside plugin storage directory denied".to_owned(),
            ));
        }
    }
    Ok(())
}

fn directory_bytes_used(dir: &Path) -> Result<u64, (i32, String)> {
    let mut total = 0_u64;
    let Ok(entries) = fs::read_dir(dir) else {
        return Ok(0);
    };
    for entry in entries.flatten() {
        let path = entry.path();
        let Ok(meta) = fs::symlink_metadata(&path) else {
            continue;
        };
        if meta.file_type().is_symlink() {
            continue;
        }
        if meta.file_type().is_dir() {
            total = total.saturating_add(directory_bytes_used(&path)?);
        } else if meta.file_type().is_file() {
            total = total.saturating_add(meta.len());
        }
    }
    Ok(total)
}

fn sanitize_relative_path(relative: &str) -> Result<PathBuf, (i32, String)> {
    let trimmed = relative.trim();
    if trimmed.is_empty() || trimmed.starts_with('/') || trimmed.contains('\\') {
        return Err((
            error_codes::PERMISSION_DENIED,
            "Plugin storage path must be relative without leading '/'".to_owned(),
        ));
    }
    let mut safe_rel = PathBuf::new();
    for comp in Path::new(trimmed).components() {
        match comp {
            Component::Normal(part) => safe_rel.push(part),
            _ => {
                return Err((
                    error_codes::PERMISSION_DENIED,
                    "Plugin storage path cannot traverse directories".to_owned(),
                ));
            }
        }
    }
    if safe_rel.as_os_str().is_empty() {
        return Err((
            error_codes::INVALID_PARAMS,
            "Plugin storage path must reference a file".to_owned(),
        ));
    }
    Ok(safe_rel)
}
