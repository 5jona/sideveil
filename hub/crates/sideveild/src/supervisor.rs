//! Zero-polling process supervisor using Linux `pidfd_open` + Tokio `AsyncFd` (`epoll`).
//!
//! Supervises the thin UID 1000 Kotlin `:sensor` child directly (no `su` wrapper),
//! bundled native plugin daemons, and the AMS-managed `SideVeilHost` foreground service.

mod daemon_spawn;
mod daemon_state;
mod daemon_worker;
mod host_wake;
mod plugin_daemons;
mod sensor;

pub use self::daemon_state::{DaemonRuntimeInfo, MAX_DAEMON_STDERR_LINES, PluginDaemonManager};
#[cfg(test)]
pub(crate) use self::plugin_daemons::run_daemon_supervisor_loop;

#[cfg(test)]
mod tests;

use std::io;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use tokio::sync::{mpsc, watch};

use self::host_wake::{HostWakeOutcome, force_stop_host, wake_host_service};
use self::plugin_daemons::run_plugin_daemon_supervisor;
use self::sensor::run_sensor_supervisor;
use crate::pidfd::PidFd;
use crate::state::{HostWakeSignal, HubState};

/// Paths and package identifiers shared across all child-process supervisor tasks.
#[derive(Debug, Clone)]
pub struct SupervisorConfig {
    /// KernelSU module root directory (e.g. `/data/adb/modules/sideveil`).
    pub module_dir: PathBuf,
    /// Shared runtime state directory (e.g. `/data/system/sideveil`).
    pub state_dir: PathBuf,
    /// Path to the privileged `hub.sock` Unix domain socket.
    pub hub_socket: PathBuf,
    /// Android package name of the Render Host `priv-app`.
    pub host_package: String,
}

/// Spawns the `:sensor`, bundled plugin daemon, and Render Host supervisor tasks.
pub fn spawn_supervisors(
    cfg: SupervisorConfig,
    state: Arc<HubState>,
    host_wake_rx: mpsc::UnboundedReceiver<HostWakeSignal>,
    shutdown_rx: watch::Receiver<bool>,
) -> Vec<tokio::task::JoinHandle<()>> {
    let sensor_handle = tokio::spawn(run_sensor_supervisor(
        cfg.clone(),
        Arc::clone(&state),
        shutdown_rx.clone(),
    ));
    let plugin_daemons_handle = tokio::spawn(run_plugin_daemon_supervisor(
        cfg.clone(),
        Arc::clone(&state),
        shutdown_rx.clone(),
    ));
    let host_handle = tokio::spawn(run_host_supervisor(cfg, state, host_wake_rx, shutdown_rx));
    vec![sensor_handle, plugin_daemons_handle, host_handle]
}

async fn run_host_supervisor(
    cfg: SupervisorConfig,
    state: Arc<HubState>,
    mut wake_rx: mpsc::UnboundedReceiver<HostWakeSignal>,
    mut shutdown_rx: watch::Receiver<bool>,
) {
    let mut watched_pidfd: Option<PidFd> = None;
    let mut retry_delay_secs = 3u64;
    let mut retry_deadline: Option<tokio::time::Instant> = Some(tokio::time::Instant::now());
    let mut logged_data_dir_not_ready = false;

    loop {
        tokio::select! {
            _ = shutdown_rx.changed() => {
                if *shutdown_rx.borrow() {
                    break;
                }
            }
            Some(sig) = wake_rx.recv() => {
                match sig {
                    HostWakeSignal::SyncAndWake => {
                        let outcome = wake_host_service(&cfg.host_package, &state, !logged_data_dir_not_ready).await;
                        logged_data_dir_not_ready = outcome == HostWakeOutcome::DataDirNotReady;
                        let next_secs = if outcome == HostWakeOutcome::DataDirNotReady { 2 } else { 5 };
                        retry_deadline = Some(tokio::time::Instant::now() + Duration::from_secs(next_secs));
                    }
                    HostWakeSignal::ForceRestart => {
                        watched_pidfd = None;
                        state.request_sensor_permission_refresh();
                        force_stop_host(&cfg.host_package).await;
                        tokio::time::sleep(Duration::from_millis(500)).await;
                        let outcome = wake_host_service(&cfg.host_package, &state, true).await;
                        logged_data_dir_not_ready = outcome == HostWakeOutcome::DataDirNotReady;
                        retry_delay_secs = 3;
                        let next_secs = if outcome == HostWakeOutcome::DataDirNotReady { 2 } else { 5 };
                        retry_deadline = Some(tokio::time::Instant::now() + Duration::from_secs(next_secs));
                    }
                    HostWakeSignal::WatchHostPid(pid) => {
                        retry_delay_secs = 3;
                        retry_deadline = None;
                        logged_data_dir_not_ready = false;
                        watched_pidfd = PidFd::open(pid).ok();
                        if watched_pidfd.is_some() {
                            state.logs().info(format!("Armed pidfd watcher on SideVeilHost pid={pid}"));
                        }
                    }
                    HostWakeSignal::HostDisconnected => {
                        if !state.is_host_connected() && retry_deadline.is_none() {
                            retry_deadline = Some(tokio::time::Instant::now() + Duration::from_secs(2));
                        }
                    }
                }
            }
            res = wait_optional_pidfd(watched_pidfd.as_ref()) => {
                let dead_pid = watched_pidfd.as_ref().map(PidFd::pid).unwrap_or(0);
                watched_pidfd = None;
                match res {
                    Ok(()) => {
                        state.logs().warn(format!("SideVeilHost pid={dead_pid} exited (pidfd wakeup); scheduling wake"));
                    }
                    Err(err) => {
                        state.logs().warn(format!("SideVeilHost pid={dead_pid} pidfd wait failed ({err}); scheduling wake"));
                    }
                }
                state.request_sensor_permission_refresh();
                retry_delay_secs = 2;
                retry_deadline = Some(tokio::time::Instant::now() + Duration::from_secs(1));
            }
            () = sleep_until_optional(retry_deadline) => {
                if cfg.module_dir.join("disable").exists() || cfg.module_dir.join("remove").exists() {
                    retry_deadline = None;
                    continue;
                }
                if state.is_host_connected() {
                    retry_deadline = None;
                    retry_delay_secs = 3;
                    logged_data_dir_not_ready = false;
                } else {
                    state.request_sensor_permission_refresh();
                    let outcome = wake_host_service(&cfg.host_package, &state, !logged_data_dir_not_ready).await;
                    if outcome == HostWakeOutcome::DataDirNotReady {
                        logged_data_dir_not_ready = true;
                        retry_delay_secs = 3;
                    } else {
                        logged_data_dir_not_ready = false;
                        retry_delay_secs = (retry_delay_secs * 2).min(30);
                    }
                    retry_deadline = Some(tokio::time::Instant::now() + Duration::from_secs(retry_delay_secs));
                }
            }
        }
    }
}

pub(super) async fn wait_pid_or_child(pidfd: Option<&PidFd>, child: &mut tokio::process::Child) {
    if let Some(pfd) = pidfd {
        let _ = pfd.wait_exit().await;
    } else {
        let _ = child.wait().await;
    }
}

async fn wait_optional_pidfd(pidfd: Option<&PidFd>) -> io::Result<()> {
    match pidfd {
        Some(pfd) => pfd.wait_exit().await,
        None => std::future::pending().await,
    }
}

async fn sleep_until_optional(deadline: Option<tokio::time::Instant>) {
    match deadline {
        Some(at) => tokio::time::sleep_until(at).await,
        None => std::future::pending().await,
    }
}

pub(super) async fn terminate_child(pid: u32, child: &mut tokio::process::Child) {
    if let Ok(pid_t) = libc::pid_t::try_from(pid) {
        unsafe {
            // SAFETY: Sending SIGTERM to our own active child PID.
            libc::kill(pid_t, libc::SIGTERM);
        }
    }
    if tokio::time::timeout(Duration::from_secs(2), child.wait())
        .await
        .is_err()
    {
        let _ = child.kill().await;
        let _ = child.wait().await;
    }
}
