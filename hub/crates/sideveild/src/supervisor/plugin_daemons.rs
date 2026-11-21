//! Zero-polling `pidfd` supervision for bundled `uds-native` reference plugin daemons.

use std::io;
use std::path::Path;
use std::process::Stdio;
use std::sync::Arc;
use std::time::Duration;

use tokio::process::Command;
use tokio::sync::watch;

use super::{SupervisorConfig, terminate_child, wait_pid_or_child};
use crate::pidfd::PidFd;
use crate::state::HubState;

const PERF_HUD_BIN_REL: &str = "bin/sideveil-example-perf-hud";
const PERF_HUD_PROC_NAME: &str = "sideveil-example-perf-hud";

pub(super) async fn run_perf_hud_supervisor(
    cfg: SupervisorConfig,
    state: Arc<HubState>,
    mut shutdown_rx: watch::Receiver<bool>,
) {
    let bin_path = cfg.module_dir.join(PERF_HUD_BIN_REL);
    let mut backoff_secs = 2u64;

    loop {
        if *shutdown_rx.borrow() || cfg.module_dir.join("remove").exists() {
            break;
        }
        if cfg.module_dir.join("disable").exists() || !bin_path.is_file() {
            tokio::select! {
                _ = tokio::time::sleep(Duration::from_secs(5)) => continue,
                _ = shutdown_rx.changed() => break,
            }
        }

        match spawn_perf_hud_process(&bin_path, &cfg.hub_socket) {
            Ok(mut child) => {
                let Some(pid) = child.id() else {
                    let _ = child.wait().await;
                    continue;
                };
                state
                    .logs()
                    .info(format!("Spawned {PERF_HUD_PROC_NAME} (pid={pid})"));
                let started = tokio::time::Instant::now();
                let pidfd = PidFd::open(pid).ok();

                tokio::select! {
                    () = wait_pid_or_child(pidfd.as_ref(), &mut child) => {
                        let status = child.wait().await.ok();
                        state.logs().warn(format!(
                            "{PERF_HUD_PROC_NAME} (pid={pid}) exited: {status:?}"
                        ));
                    }
                    _ = shutdown_rx.changed() => {
                        terminate_child(pid, &mut child).await;
                        break;
                    }
                }

                if started.elapsed() > Duration::from_secs(15) {
                    backoff_secs = 2;
                } else {
                    backoff_secs = (backoff_secs * 2).min(10);
                }
            }
            Err(err) => {
                state
                    .logs()
                    .warn(format!("Failed spawning {PERF_HUD_PROC_NAME}: {err}"));
                backoff_secs = (backoff_secs * 2).min(10);
            }
        }

        tokio::select! {
            _ = tokio::time::sleep(Duration::from_secs(backoff_secs)) => {}
            _ = shutdown_rx.changed() => break,
        }
    }
}

fn spawn_perf_hud_process(bin_path: &Path, hub_socket: &Path) -> io::Result<tokio::process::Child> {
    let mut cmd = Command::new(bin_path);
    cmd.arg(hub_socket.as_os_str())
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());

    unsafe {
        // SAFETY: `prctl(PR_SET_PDEATHSIG)` is async-signal-safe between `fork` and `exec`.
        cmd.pre_exec(|| {
            let _ = libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM);
            Ok(())
        });
    }
    cmd.spawn()
}
