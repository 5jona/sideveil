//! Secure child-process spawning, symlink-free binary resolution, and stderr capture for `uds-native` daemons.

use std::fs;
use std::io;
use std::os::unix::fs::PermissionsExt;
use std::path::{Component, Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use std::time::Duration;

use sideveil_protocol::plugin::PluginDaemonSpec;
use tokio::io::AsyncReadExt;
use tokio::process::{Child, ChildStderr, Command};

use super::daemon_state::PluginDaemonManager;

const MAX_STDERR_LINE_BYTES: usize = 2048;

#[cfg(target_os = "android")]
const MINIMAL_DAEMON_PATH: &str = "/system/bin:/vendor/bin";
#[cfg(not(target_os = "android"))]
const MINIMAL_DAEMON_PATH: &str = "/usr/bin:/bin";

const FORBIDDEN_ENV_KEYS: &[&str] = &[
    "LD_PRELOAD",
    "LD_LIBRARY_PATH",
    "LD_AUDIT",
    "LD_DEBUG",
    "PATH",
    "HOME",
    "SHELL",
    "USER",
    "LOGNAME",
    "IFS",
    "ENV",
    "BASH_ENV",
];

/// Resolves `daemon.binary` relative to `plugin_root`, rejecting any symlink component or escape.
pub(super) fn resolve_safe_daemon_binary(
    plugin_root: &Path,
    rel_binary: &str,
) -> io::Result<PathBuf> {
    let trimmed = rel_binary.trim();
    if trimmed.is_empty() || trimmed.starts_with('/') || trimmed.contains('\\') {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "daemon.binary must be a safe relative path",
        ));
    }
    let root_canon = fs::canonicalize(plugin_root)?;
    let mut current = root_canon.clone();
    let mut segments = 0usize;
    for comp in Path::new(trimmed).components() {
        match comp {
            Component::Normal(seg) => {
                segments += 1;
                current.push(seg);
                let meta = fs::symlink_metadata(&current)?;
                if meta.file_type().is_symlink() {
                    return Err(io::Error::new(
                        io::ErrorKind::PermissionDenied,
                        format!("daemon.binary path contains symlink: {}", current.display()),
                    ));
                }
            }
            _ => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "daemon.binary must not contain parent or root components",
                ));
            }
        }
    }
    if segments == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "daemon.binary must not be empty",
        ));
    }
    let meta = fs::symlink_metadata(&current)?;
    if !meta.file_type().is_file() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "daemon.binary must point to a regular file",
        ));
    }
    let canon = fs::canonicalize(&current)?;
    if !canon.starts_with(&root_canon) || canon == root_canon {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "daemon.binary resolved outside plugin root",
        ));
    }
    Ok(canon)
}

/// Spawns a supervised `uds-native` plugin daemon with a clean minimal environment and piped stderr.
pub(super) fn spawn_plugin_daemon(
    plugin_id: &str,
    bin_path: &Path,
    spec: &PluginDaemonSpec,
    hub_socket: &Path,
    data_dir: &Path,
) -> io::Result<Child> {
    fs::create_dir_all(data_dir)?;
    fs::set_permissions(data_dir, fs::Permissions::from_mode(0o700))?;

    let mut cmd = Command::new(bin_path);
    cmd.env_clear()
        .env("PATH", MINIMAL_DAEMON_PATH)
        .env("SIDEVEIL_PLUGIN_ID", plugin_id)
        .env("SIDEVEIL_HUB_SOCK", hub_socket.as_os_str())
        .env("SIDEVEIL_DATA_DIR", data_dir.as_os_str())
        .current_dir(data_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::piped());

    if let Some(args) = &spec.args {
        cmd.args(args);
    }
    if let Some(env_map) = &spec.env {
        for (k, v) in env_map {
            if is_allowed_daemon_env_key(k) {
                cmd.env(k, v);
            }
        }
    }

    unsafe {
        // SAFETY: `prctl(PR_SET_PDEATHSIG)` and `setpgid(0, 0)` are async-signal-safe between `fork` and `exec`.
        cmd.pre_exec(|| {
            let _ = libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM);
            let _ = libc::setpgid(0, 0);
            Ok(())
        });
    }

    cmd.spawn()
}

/// Drains a daemon's stderr stream with strict per-line byte bounds (2 KiB) into the ring buffer,
/// discarding excess bytes until `\n` without allocating.
pub(super) fn spawn_stderr_pump(
    plugin_id: String,
    mut stderr: ChildStderr,
    manager: Arc<PluginDaemonManager>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let mut chunk = [0u8; 4096];
        let mut line_buf: Vec<u8> = Vec::with_capacity(256);
        let mut truncated = false;

        loop {
            match stderr.read(&mut chunk).await {
                Ok(0) | Err(_) => {
                    if !line_buf.is_empty() || truncated {
                        flush_stderr_line(&plugin_id, &mut line_buf, &mut truncated, &manager);
                    }
                    break;
                }
                Ok(n) => {
                    for &b in &chunk[..n] {
                        if b == b'\n' {
                            flush_stderr_line(&plugin_id, &mut line_buf, &mut truncated, &manager);
                        } else if b == b'\r' {
                            continue;
                        } else if line_buf.len() < MAX_STDERR_LINE_BYTES {
                            line_buf.push(b);
                        } else {
                            truncated = true;
                        }
                    }
                }
            }
        }
    })
}

fn flush_stderr_line(
    plugin_id: &str,
    line_buf: &mut Vec<u8>,
    truncated: &mut bool,
    manager: &PluginDaemonManager,
) {
    let mut text = String::from_utf8_lossy(line_buf).into_owned();
    if *truncated {
        text.push_str("...[truncated]");
    }
    manager.push_stderr_line(plugin_id, text);
    line_buf.clear();
    *truncated = false;
}

/// Sends `SIGTERM` to the daemon's process group, escalating to `SIGKILL` after 2 seconds.
pub(super) async fn terminate_daemon_group(pid: u32, child: &mut Child) {
    if let Ok(pgid) = libc::pid_t::try_from(pid) {
        unsafe {
            // SAFETY: Killing the process group we isolated via `setpgid(0, 0)` in `pre_exec`.
            libc::kill(-pgid, libc::SIGTERM);
            libc::kill(pgid, libc::SIGTERM);
        }
    }
    if tokio::time::timeout(Duration::from_secs(2), child.wait())
        .await
        .is_err()
    {
        if let Ok(pgid) = libc::pid_t::try_from(pid) {
            unsafe {
                // SAFETY: Escalating to SIGKILL for our isolated daemon process group.
                libc::kill(-pgid, libc::SIGKILL);
                libc::kill(pgid, libc::SIGKILL);
            }
        }
        let _ = child.kill().await;
        let _ = child.wait().await;
    }
}

fn is_allowed_daemon_env_key(key: &str) -> bool {
    let upper = key.to_ascii_uppercase();
    if FORBIDDEN_ENV_KEYS.contains(&upper.as_str())
        || upper.starts_with("LD_")
        || upper.starts_with("ANDROID_")
        || upper.starts_with("SIDEVEIL_")
    {
        return false;
    }
    !key.is_empty() && key.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
}
