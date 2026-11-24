//! Per-plugin daemon worker loop with exponential backoff and circuit-breaker protection.

use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use sideveil_protocol::plugin::{PluginDaemonRestartPolicy, PluginDaemonStatus};
use tokio::sync::watch;
use tokio::time::Instant;

use super::daemon_spawn::{
    resolve_safe_daemon_binary, spawn_plugin_daemon, spawn_stderr_pump, terminate_daemon_group,
};
use super::daemon_state::{DaemonRuntimeInfo, PluginDaemonManager};
use super::wait_pid_or_child;
use crate::log_ring::LogRing;
use crate::pidfd::PidFd;
use crate::plugin_registry::PluginDaemonCandidate;

const CIRCUIT_WINDOW: Duration = Duration::from_secs(300);
const MAX_BACKOFF_MS: u64 = 60_000;

pub(super) async fn run_single_daemon_worker(
    candidate: PluginDaemonCandidate,
    hub_socket: PathBuf,
    data_dir: PathBuf,
    manager: Arc<PluginDaemonManager>,
    logs: Arc<LogRing>,
    mut stop_rx: watch::Receiver<bool>,
) {
    let plugin_id = candidate.plugin_id;
    let policy = candidate.spec.effective_restart_policy();
    let max_restarts = candidate.spec.effective_max_restarts();
    let base_backoff_ms = candidate.spec.effective_backoff_ms();
    let mut failure_times: VecDeque<Instant> = VecDeque::new();
    let mut restart_count: u32 = 0;

    loop {
        if *stop_rx.borrow() {
            break;
        }

        let bin_path = match resolve_safe_daemon_binary(&candidate.root_dir, &candidate.spec.binary)
        {
            Ok(path) => path,
            Err(err) => {
                let msg = format!("daemon binary check failed: {err}");
                logs.warn(format!("Plugin '{plugin_id}': {msg}"));
                manager.set_status(
                    &plugin_id,
                    DaemonRuntimeInfo {
                        status: PluginDaemonStatus::CircuitOpen,
                        pid: None,
                        restart_count,
                        last_error: Some(msg),
                    },
                );
                return;
            }
        };

        match spawn_plugin_daemon(
            &plugin_id,
            &bin_path,
            &candidate.spec,
            &hub_socket,
            &data_dir,
        ) {
            Ok(mut child) => {
                let Some(pid) = child.id() else {
                    let _ = child.wait().await;
                    continue;
                };
                let stderr_pump = child
                    .stderr
                    .take()
                    .map(|se| spawn_stderr_pump(plugin_id.clone(), se, Arc::clone(&manager)));

                logs.info(format!("Spawned plugin daemon '{plugin_id}' (pid={pid})"));
                manager.set_status(
                    &plugin_id,
                    DaemonRuntimeInfo {
                        status: PluginDaemonStatus::Running,
                        pid: Some(pid),
                        restart_count,
                        last_error: None,
                    },
                );

                let pidfd = PidFd::open(pid).ok();
                let stopped = tokio::select! {
                    () = wait_pid_or_child(pidfd.as_ref(), &mut child) => false,
                    _ = stop_rx.changed() => {
                        terminate_daemon_group(pid, &mut child).await;
                        true
                    }
                };
                if let Some(mut pump) = stderr_pump
                    && tokio::time::timeout(Duration::from_millis(200), &mut pump)
                        .await
                        .is_err()
                {
                    pump.abort();
                }
                if stopped {
                    manager.set_status(
                        &plugin_id,
                        DaemonRuntimeInfo {
                            status: PluginDaemonStatus::Stopped,
                            pid: None,
                            restart_count,
                            last_error: None,
                        },
                    );
                    return;
                }

                let exit_status = child.wait().await.ok();
                let clean_exit = exit_status.is_some_and(|s| s.success());
                let should_restart = match policy {
                    PluginDaemonRestartPolicy::Never => false,
                    PluginDaemonRestartPolicy::OnFailure => !clean_exit,
                    PluginDaemonRestartPolicy::Always => true,
                };
                if !should_restart {
                    logs.info(format!(
                        "Plugin daemon '{plugin_id}' exited cleanly ({exit_status:?}); not restarting"
                    ));
                    manager.set_status(
                        &plugin_id,
                        DaemonRuntimeInfo {
                            status: PluginDaemonStatus::Stopped,
                            pid: None,
                            restart_count,
                            last_error: None,
                        },
                    );
                    return;
                }

                let err_msg = format!("exited with {exit_status:?}");
                logs.warn(format!("Plugin daemon '{plugin_id}' (pid={pid}) {err_msg}"));
                if !record_failure_and_backoff(
                    &plugin_id,
                    &err_msg,
                    max_restarts,
                    base_backoff_ms,
                    &mut failure_times,
                    &mut restart_count,
                    &manager,
                    &mut stop_rx,
                )
                .await
                {
                    return;
                }
            }
            Err(err) => {
                let err_msg = format!("spawn failed: {err}");
                logs.warn(format!("Plugin daemon '{plugin_id}' {err_msg}"));
                if !record_failure_and_backoff(
                    &plugin_id,
                    &err_msg,
                    max_restarts,
                    base_backoff_ms,
                    &mut failure_times,
                    &mut restart_count,
                    &manager,
                    &mut stop_rx,
                )
                .await
                {
                    return;
                }
            }
        }
    }
}

#[allow(clippy::too_many_arguments)]
async fn record_failure_and_backoff(
    plugin_id: &str,
    err_msg: &str,
    max_restarts: u32,
    base_backoff_ms: u64,
    failure_times: &mut VecDeque<Instant>,
    restart_count: &mut u32,
    manager: &PluginDaemonManager,
    stop_rx: &mut watch::Receiver<bool>,
) -> bool {
    let now = Instant::now();
    while let Some(&front) = failure_times.front() {
        if now.saturating_duration_since(front) > CIRCUIT_WINDOW {
            let _ = failure_times.pop_front();
        } else {
            break;
        }
    }
    failure_times.push_back(now);
    *restart_count = restart_count.saturating_add(1);

    let recent_failures = u32::try_from(failure_times.len()).unwrap_or(u32::MAX);
    if max_restarts == 0 || recent_failures >= max_restarts {
        manager.set_status(
            plugin_id,
            DaemonRuntimeInfo {
                status: PluginDaemonStatus::CircuitOpen,
                pid: None,
                restart_count: *restart_count,
                last_error: Some(format!(
                    "circuit breaker opened after {recent_failures} failures in 5m ({err_msg})"
                )),
            },
        );
        return false;
    }

    manager.set_status(
        plugin_id,
        DaemonRuntimeInfo {
            status: PluginDaemonStatus::Crashed,
            pid: None,
            restart_count: *restart_count,
            last_error: Some(err_msg.to_owned()),
        },
    );

    let shift = recent_failures.saturating_sub(1).min(10);
    let delay_ms = base_backoff_ms
        .saturating_mul(1u64 << shift)
        .min(MAX_BACKOFF_MS);
    tokio::select! {
        () = tokio::time::sleep(Duration::from_millis(delay_ms)) => !*stop_rx.borrow(),
        _ = stop_rx.changed() => false,
    }
}
