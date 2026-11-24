//! Hardened `system.exec` handler with bounded output capture, RAII process-group kill, and auditing.

use std::path::Path;
use std::process::Stdio;
use std::time::Duration;

use serde_json::{Value, json};
use sideveil_protocol::plugin::PluginCapability;
use sideveil_protocol::rpc::error_codes;
use tokio::io::{AsyncRead, AsyncReadExt};
use tokio::process::Command;
use tokio::time::Instant;

use super::{ensure_capability, extract_str_param};
use crate::plugin_registry::PluginRegistry;

/// Maximum stdout or stderr bytes captured from `system.exec` (256 KiB each).
pub const MAX_EXEC_STREAM_BYTES: usize = 256 * 1024;

const MIN_EXEC_TIMEOUT_MS: u64 = 100;
const DEFAULT_EXEC_TIMEOUT_MS: u64 = 8_000;
const MAX_EXEC_TIMEOUT_MS: u64 = 30_000;

/// Audit metadata returned alongside `system.exec` results.
#[derive(Debug, Clone)]
pub struct ExecOutcome {
    pub response: Value,
    pub audit_detail: String,
}

/// RAII guard guaranteeing the child's isolated process group is SIGKILLed on every exit or
/// future cancellation path so no orphaned descendants survive.
struct ProcessGroupGuard(Option<u32>);

impl Drop for ProcessGroupGuard {
    fn drop(&mut self) {
        kill_process_group(self.0);
    }
}

/// Handles `system.exec` gated strictly by granted `PluginCapability::SystemExec`.
pub async fn handle_system_exec(
    registry: &PluginRegistry,
    plugin_id: &str,
    params: Option<Value>,
) -> Result<ExecOutcome, (i32, String)> {
    ensure_capability(registry, plugin_id, PluginCapability::SystemExec)?;
    let cmd = extract_str_param(params.as_ref(), "cmd")?;
    let timeout_ms = params
        .as_ref()
        .and_then(|v| v.get("options"))
        .and_then(|o| o.get("timeoutMs"))
        .and_then(Value::as_u64)
        .unwrap_or(DEFAULT_EXEC_TIMEOUT_MS)
        .clamp(MIN_EXEC_TIMEOUT_MS, MAX_EXEC_TIMEOUT_MS);

    let shell = if Path::new("/system/bin/sh").is_file() {
        "/system/bin/sh"
    } else {
        "/bin/sh"
    };

    let mut command = Command::new(shell);
    command
        .args(["-c", &cmd])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);

    unsafe {
        // SAFETY: `setpgid(0, 0)` is async-signal-safe between `fork` and `exec` and isolates
        // child descendants into their own process group for clean timeout termination.
        command.pre_exec(|| {
            let _ = libc::setpgid(0, 0);
            Ok(())
        });
    }

    let mut child = command.spawn().map_err(|err| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed executing command: {err}"),
        )
    })?;

    let child_pid = child.id();
    let _pg_guard = ProcessGroupGuard(child_pid);
    let stdout_pipe = child.stdout.take();
    let stderr_pipe = child.stderr.take();
    let stdout_task = tokio::spawn(async move { read_bounded_stream(stdout_pipe).await });
    let stderr_task = tokio::spawn(async move { read_bounded_stream(stderr_pipe).await });
    let stdout_abort = stdout_task.abort_handle();
    let stderr_abort = stderr_task.abort_handle();

    let preview = summarize_cmd_for_audit(&cmd);
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    let wait_res = tokio::time::timeout_at(deadline, child.wait()).await;

    match wait_res {
        Ok(Ok(status)) => {
            // Reap any lingering background descendants immediately so held stdout/stderr fds close.
            kill_process_group(child_pid);
            let drain =
                tokio::time::timeout_at(deadline, async { tokio::join!(stdout_task, stderr_task) })
                    .await;
            let (stdout, out_trunc, stderr, err_trunc) = match drain {
                Ok((out_res, err_res)) => {
                    let (out, ot) = out_res.unwrap_or_default();
                    let (err, et) = err_res.unwrap_or_default();
                    (out, ot, err, et)
                }
                Err(_) => {
                    stdout_abort.abort();
                    stderr_abort.abort();
                    (
                        String::new(),
                        true,
                        format!("Command output drain timed out after {timeout_ms}ms"),
                        true,
                    )
                }
            };
            let code = status.code().unwrap_or(-1);
            let truncated = out_trunc || err_trunc;
            Ok(ExecOutcome {
                response: json!({
                    "errno": code,
                    "stdout": stdout,
                    "stderr": stderr,
                    "truncated": truncated,
                }),
                audit_detail: format!("cmd='{preview}' exit={code} truncated={truncated}"),
            })
        }
        Ok(Err(err)) => {
            stdout_abort.abort();
            stderr_abort.abort();
            Err((
                error_codes::INTERNAL_ERROR,
                format!("Failed executing command: {err}"),
            ))
        }
        Err(_) => {
            kill_process_group(child_pid);
            let _ = child.kill().await;
            let _ = child.wait().await;
            stdout_abort.abort();
            stderr_abort.abort();
            Ok(ExecOutcome {
                response: json!({
                    "errno": 124,
                    "stdout": "",
                    "stderr": format!("Command timed out after {timeout_ms}ms"),
                    "truncated": false,
                }),
                audit_detail: format!("cmd='{preview}' timeout={timeout_ms}ms"),
            })
        }
    }
}

async fn read_bounded_stream<R: AsyncRead + Unpin>(stream: Option<R>) -> (String, bool) {
    let Some(mut reader) = stream else {
        return (String::new(), false);
    };
    let mut buf = Vec::new();
    let mut chunk = [0u8; 8192];
    let mut truncated = false;
    loop {
        match reader.read(&mut chunk).await {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                let remaining = MAX_EXEC_STREAM_BYTES.saturating_sub(buf.len());
                if remaining > 0 {
                    let take = n.min(remaining);
                    buf.extend_from_slice(&chunk[..take]);
                    if take < n {
                        truncated = true;
                    }
                } else {
                    truncated = true;
                }
            }
        }
    }
    let mut text = String::from_utf8_lossy(&buf).into_owned();
    if truncated {
        text.push_str("\n...[truncated]");
    }
    (text, truncated)
}

fn kill_process_group(pid: Option<u32>) {
    if let Some(raw) = pid
        && let Ok(pgid) = libc::pid_t::try_from(raw)
        && pgid > 1
    {
        unsafe {
            // SAFETY: Killing the dedicated child process group created via `setpgid(0, 0)`.
            libc::kill(-pgid, libc::SIGKILL);
        }
    }
}

fn summarize_cmd_for_audit(cmd: &str) -> String {
    let clean: String = cmd
        .chars()
        .map(|c| if c.is_control() { ' ' } else { c })
        .take(96)
        .collect();
    clean.trim().to_owned()
}
