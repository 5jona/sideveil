//! UID 1000 `:sensor` (`app_process64`) staging and `pidfd`-backed restart supervisor.

use std::fs;
use std::io;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::process::Stdio;
use std::sync::Arc;
use std::time::Duration;

use tokio::process::Command;
use tokio::sync::watch;

use super::{SupervisorConfig, terminate_child, wait_pid_or_child};
use crate::fs_util::{chown_path, unique_tmp_path};
use crate::pidfd::PidFd;
use crate::state::HubState;

const SENSOR_UID: u32 = 1000;
const SENSOR_PROC_NAME: &str = "sideveil-sensor";
const SENSOR_MAIN_CLASS: &str = "pochita.sideveil.sensor.SensorMain";

pub(super) async fn run_sensor_supervisor(
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
                state.set_supervised_sensor_pid(Some(pid));
                state.logs().info(format!(
                    "Spawned UID {SENSOR_UID} {SENSOR_PROC_NAME} (pid={pid})"
                ));
                let started = tokio::time::Instant::now();
                let pidfd = PidFd::open(pid).ok();

                tokio::select! {
                    () = wait_pid_or_child(pidfd.as_ref(), &mut child) => {
                        let status = child.wait().await.ok();
                        state.set_supervised_sensor_pid(None);
                        state.logs().warn(format!(
                            "{SENSOR_PROC_NAME} (pid={pid}) exited: {status:?}"
                        ));
                    }
                    _ = shutdown_rx.changed() => {
                        state.set_supervised_sensor_pid(None);
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
    let tmp = unique_tmp_path(dst);
    let _ = fs::remove_file(&tmp);
    fs::copy(src, &tmp)?;
    fs::set_permissions(&tmp, fs::Permissions::from_mode(0o640))?;
    let _ = chown_path(&tmp, 0, SENSOR_UID);
    if let Err(err) = fs::rename(&tmp, dst) {
        let _ = fs::remove_file(&tmp);
        return Err(err);
    }
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
