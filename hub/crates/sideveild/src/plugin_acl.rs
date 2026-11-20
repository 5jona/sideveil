//! Capability-gated per-plugin storage (`fs.read` / `fs.writeAtomic`) and `system.exec`.

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::path::{Component, Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;

use serde_json::{Value, json};
use sideveil_protocol::plugin::PluginCapability;
use sideveil_protocol::rpc::error_codes;
use tokio::process::Command;

use crate::plugin_registry::PluginRegistry;

const MAX_READ_BYTES: usize = 2 * 1024 * 1024;
const DEFAULT_EXEC_TIMEOUT_MS: u64 = 8_000;
const MAX_EXEC_TIMEOUT_MS: u64 = 30_000;

/// Handles `fs.read` scoped strictly to `<data_dir>/<plugin_id>/<relative_path>`.
pub fn handle_fs_read(
    registry: &PluginRegistry,
    data_dir: &Path,
    plugin_id: &str,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    ensure_capability(registry, plugin_id, PluginCapability::Storage)?;
    let rel = extract_str_param(params.as_ref(), "path")?;
    let target = resolve_jailed_path(data_dir, plugin_id, &rel)?;

    if !target.exists() {
        return Ok(json!({ "content": Value::Null }));
    }
    let canonical_root = canonicalize_plugin_data_root(data_dir, plugin_id)?;
    let canonical_target = fs::canonicalize(&target).map_err(|e| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed resolving path: {e}"),
        )
    })?;
    if !canonical_target.starts_with(&canonical_root) {
        return Err((
            error_codes::PERMISSION_DENIED,
            "Symlink escape outside plugin data directory denied".to_owned(),
        ));
    }

    let bytes = fs::read(&canonical_target).map_err(|e| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed reading plugin file: {e}"),
        )
    })?;
    if bytes.len() > MAX_READ_BYTES {
        return Err((
            error_codes::INVALID_PARAMS,
            "File exceeds 2 MiB plugin storage limit".to_owned(),
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
    if content.len() > MAX_READ_BYTES {
        return Err((
            error_codes::INVALID_PARAMS,
            "Content exceeds 2 MiB plugin storage limit".to_owned(),
        ));
    }

    let target = resolve_jailed_path(data_dir, plugin_id, &rel)?;
    let canonical_root = canonicalize_plugin_data_root(data_dir, plugin_id)?;
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
        if !canonical_parent.starts_with(&canonical_root) {
            return Err((
                error_codes::PERMISSION_DENIED,
                "Path escapes plugin data directory".to_owned(),
            ));
        }
    }

    let file_name = target
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("data");
    let tmp = target.with_file_name(format!(".{file_name}.tmp.{}", std::process::id()));
    let mut file = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&tmp)
        .map_err(|e| {
            (
                error_codes::INTERNAL_ERROR,
                format!("Failed opening temp file: {e}"),
            )
        })?;
    file.write_all(content.as_bytes()).map_err(|e| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed writing temp file: {e}"),
        )
    })?;
    let _ = file.sync_all();
    let _ = fs::set_permissions(&tmp, fs::Permissions::from_mode(0o600));
    drop(file);
    fs::rename(&tmp, &target).map_err(|e| {
        let _ = fs::remove_file(&tmp);
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed committing atomic write: {e}"),
        )
    })?;

    Ok(json!({ "errno": 0, "stdout": "OK", "stderr": "" }))
}

/// Handles `system.exec` gated strictly by `PluginCapability::SystemExec`.
pub async fn handle_system_exec(
    registry: &PluginRegistry,
    plugin_id: &str,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    ensure_capability(registry, plugin_id, PluginCapability::SystemExec)?;
    let cmd = extract_str_param(params.as_ref(), "cmd")?;
    let timeout_ms = params
        .as_ref()
        .and_then(|v| v.get("options"))
        .and_then(|o| o.get("timeoutMs"))
        .and_then(Value::as_u64)
        .unwrap_or(DEFAULT_EXEC_TIMEOUT_MS)
        .clamp(200, MAX_EXEC_TIMEOUT_MS);

    let shell = if Path::new("/system/bin/sh").is_file() {
        "/system/bin/sh"
    } else {
        "/bin/sh"
    };

    let fut = Command::new(shell)
        .args(["-c", &cmd])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .output();

    match tokio::time::timeout(Duration::from_millis(timeout_ms), fut).await {
        Ok(Ok(out)) => Ok(json!({
            "errno": out.status.code().unwrap_or(-1),
            "stdout": String::from_utf8_lossy(&out.stdout),
            "stderr": String::from_utf8_lossy(&out.stderr),
        })),
        Ok(Err(err)) => Err((
            error_codes::INTERNAL_ERROR,
            format!("Failed executing command: {err}"),
        )),
        Err(_) => Ok(json!({
            "errno": 124,
            "stdout": "",
            "stderr": format!("Command timed out after {timeout_ms}ms"),
        })),
    }
}

fn ensure_capability(
    registry: &PluginRegistry,
    plugin_id: &str,
    capability: PluginCapability,
) -> Result<(), (i32, String)> {
    if registry.has_capability(plugin_id, capability) {
        Ok(())
    } else {
        Err((
            error_codes::PERMISSION_DENIED,
            format!("Plugin '{plugin_id}' lacks required capability {capability:?}"),
        ))
    }
}

fn extract_str_param(params: Option<&Value>, key: &str) -> Result<String, (i32, String)> {
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

fn canonicalize_plugin_data_root(
    data_dir: &Path,
    plugin_id: &str,
) -> Result<PathBuf, (i32, String)> {
    let root = data_dir.join(plugin_id);
    fs::create_dir_all(&root).map_err(|e| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed creating plugin data dir: {e}"),
        )
    })?;
    fs::canonicalize(&root).map_err(|e| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed canonicalizing plugin data dir: {e}"),
        )
    })
}

fn resolve_jailed_path(
    data_dir: &Path,
    plugin_id: &str,
    relative: &str,
) -> Result<PathBuf, (i32, String)> {
    let trimmed = relative.trim();
    if trimmed.is_empty() || trimmed.starts_with('/') || trimmed.contains('\\') {
        return Err((
            error_codes::PERMISSION_DENIED,
            "Plugin storage path must be relative without leading '/'".to_owned(),
        ));
    }
    let rel = Path::new(trimmed);
    let mut safe_rel = PathBuf::new();
    for comp in rel.components() {
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
    Ok(data_dir.join(plugin_id).join(safe_rel))
}
