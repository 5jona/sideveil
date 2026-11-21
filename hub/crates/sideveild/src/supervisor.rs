//! Zero-polling process supervisor using Linux `pidfd_open` + Tokio `AsyncFd` (`epoll`).
//!
//! Supervises the thin UID 1000 Kotlin `:sensor` child directly (no `su` wrapper) and
//! keeps the AMS-managed `SideVeilHost` foreground service synced and alive on demand.

mod host_wake;

use std::fs;
use std::io;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use std::time::Duration;

use tokio::process::Command;
use tokio::sync::{mpsc, watch};

use self::host_wake::{chown_path, force_stop_host, wake_host_service};
use crate::pidfd::PidFd;
use crate::state::{HostWakeSignal, HubState};

const SENSOR_UID: u32 = 1000;
const SENSOR_PROC_NAME: &str = "sideveil-sensor";
const SENSOR_MAIN_CLASS: &str = "pochita.sideveil.sensor.SensorMain";

#[derive(Debug, Clone)]
pub struct SupervisorConfig {
    pub module_dir: PathBuf,
    pub state_dir: PathBuf,
    pub hub_socket: PathBuf,
    pub host_package: String,
}

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
    let host_handle = tokio::spawn(run_host_supervisor(cfg, state, host_wake_rx, shutdown_rx));
    vec![sensor_handle, host_handle]
}

async fn run_sensor_supervisor(
    cfg: SupervisorConfig,
    state: Arc<HubState>,
    mut shutdown_rx: watch::Receiver<bool>,
) {
    let module_dex = cfg.module_dir.join("bin/sideveil-sensor.dex");
    let live_dex = cfg.state_dir.join("sideveil-sensor.dex");
    let mut backoff_secs = 1u64;

    loop {
        if *shutdown_rx.borrow() || cfg.module_dir.join("remove").exists() {
            break;
        }
        if cfg.module_dir.join("disable").exists() {
            tokio::select! {
                _ = tokio::time::sleep(Duration::from_secs(5)) => continue,
                _ = shutdown_rx.changed() => break,
            }
        }
        if let Err(err) = stage_sensor_dex(&module_dex, &live_dex) {
            state
                .logs()
                .warn(format!("Failed to stage sensor DEX: {err}"));
            tokio::select! {
                _ = tokio::time::sleep(Duration::from_secs(3)) => continue,
                _ = shutdown_rx.changed() => break,
            }
        }

        match spawn_sensor_process(&live_dex, &cfg.hub_socket, &cfg.host_package) {
            Ok(mut child) => {
                let Some(pid) = child.id() else {
                    let _ = child.wait().await;
                    continue;
                };
                state.logs().info(format!(
                    "Spawned UID {SENSOR_UID} {SENSOR_PROC_NAME} (pid={pid})"
                ));
                let started = tokio::time::Instant::now();
                let pidfd = PidFd::open(pid).ok();

                tokio::select! {
                    () = wait_pid_or_child(pidfd.as_ref(), &mut child) => {
                        let status = child.wait().await.ok();
                        state.logs().warn(format!(
                            "{SENSOR_PROC_NAME} (pid={pid}) exited: {status:?}"
                        ));
                    }
                    _ = shutdown_rx.changed() => {
                        terminate_child(pid, &mut child).await;
                        break;
                    }
                }

                if started.elapsed() > Duration::from_secs(15) {
                    backoff_secs = 1;
                } else {
                    backoff_secs = (backoff_secs * 2).min(5);
                }
            }
            Err(err) => {
                state
                    .logs()
                    .error(format!("Failed spawning {SENSOR_PROC_NAME}: {err}"));
                backoff_secs = (backoff_secs * 2).min(5);
            }
        }

        tokio::select! {
            _ = tokio::time::sleep(Duration::from_secs(backoff_secs)) => {}
            _ = shutdown_rx.changed() => break,
        }
    }
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
                        wake_host_service(&cfg.host_package, &state).await;
                        retry_deadline = Some(tokio::time::Instant::now() + Duration::from_secs(5));
                    }
                    HostWakeSignal::ForceRestart => {
                        watched_pidfd = None;
                        state.request_sensor_permission_refresh();
                        force_stop_host(&cfg.host_package).await;
                        tokio::time::sleep(Duration::from_millis(500)).await;
                        wake_host_service(&cfg.host_package, &state).await;
                        retry_delay_secs = 3;
                        retry_deadline = Some(tokio::time::Instant::now() + Duration::from_secs(5));
                    }
                    HostWakeSignal::WatchHostPid(pid) => {
                        retry_delay_secs = 3;
                        retry_deadline = None;
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
            Ok(()) = wait_optional_pidfd(watched_pidfd.as_ref()) => {
                let dead_pid = watched_pidfd.as_ref().map(PidFd::pid).unwrap_or(0);
                watched_pidfd = None;
                state.logs().warn(format!("SideVeilHost pid={dead_pid} exited (pidfd wakeup); scheduling wake"));
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
                } else {
                    state.request_sensor_permission_refresh();
                    wake_host_service(&cfg.host_package, &state).await;
                    retry_delay_secs = (retry_delay_secs * 2).min(30);
                    retry_deadline = Some(tokio::time::Instant::now() + Duration::from_secs(retry_delay_secs));
                }
            }
        }
    }
}

async fn wait_pid_or_child(pidfd: Option<&PidFd>, child: &mut tokio::process::Child) {
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

async fn terminate_child(pid: u32, child: &mut tokio::process::Child) {
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

fn stage_sensor_dex(src: &Path, dst: &Path) -> io::Result<()> {
    if !src.is_file() {
        return Err(io::Error::new(
            io::ErrorKind::NotFound,
            format!("sensor DEX missing at {}", src.display()),
        ));
    }
    if let Some(parent) = dst.parent() {
        fs::create_dir_all(parent)?;
    }
    let tmp = dst.with_extension(format!("dex.tmp.{}", std::process::id()));
    let _ = fs::remove_file(&tmp);
    fs::copy(src, &tmp)?;
    fs::set_permissions(&tmp, fs::Permissions::from_mode(0o640))?;
    let _ = chown_path(&tmp, 0, SENSOR_UID);
    fs::rename(&tmp, dst)?;
    Ok(())
}

fn spawn_sensor_process(
    live_dex: &Path,
    hub_socket: &Path,
    host_package: &str,
) -> io::Result<tokio::process::Child> {
    let mut cmd = Command::new("/system/bin/app_process64");
    cmd.env("CLASSPATH", live_dex)
        .args([
            "/system/bin",
            &format!("--nice-name={SENSOR_PROC_NAME}"),
            SENSOR_MAIN_CLASS,
            "--socket",
            &hub_socket.to_string_lossy(),
            "--host-pkg",
            host_package,
        ])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());

    unsafe {
        // SAFETY: Only async-signal-safe syscalls (`prctl`, `setgroups`, `setgid`, `setuid`)
        // are invoked in `pre_exec` between `fork` and `exec`.
        cmd.pre_exec(|| {
            let _ = libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM);
            if libc::getuid() == 0 {
                let groups: [libc::gid_t; 1] = [SENSOR_UID];
                if libc::setgroups(groups.len(), groups.as_ptr()) != 0 {
                    return Err(io::Error::last_os_error());
                }
                if libc::setgid(SENSOR_UID) != 0 {
                    return Err(io::Error::last_os_error());
                }
                if libc::setuid(SENSOR_UID) != 0 {
                    return Err(io::Error::last_os_error());
                }
            }
            Ok(())
        });
    }
    cmd.spawn()
}
