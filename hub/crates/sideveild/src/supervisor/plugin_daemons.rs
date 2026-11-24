//! Event-driven, zero-polling generic supervisor for all manifest-declared `uds-native` plugin daemons.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use sideveil_protocol::plugin::{PluginDaemonRestartPolicy, PluginDaemonStatus};
use tokio::sync::watch;
use tokio::task::JoinHandle;

use super::SupervisorConfig;
use super::daemon_state::{DaemonRuntimeInfo, PluginDaemonManager};
use super::daemon_worker::run_single_daemon_worker;
use crate::log_ring::LogRing;
use crate::plugin_registry::{PluginDaemonCandidate, PluginRegistry};
use crate::state::HubState;

#[derive(Debug, Clone, PartialEq, Eq)]
struct RunningFingerprint {
    root_dir: PathBuf,
    binary: String,
    args: Option<Vec<String>>,
    env: Option<BTreeMap<String, String>>,
    restart_policy: Option<PluginDaemonRestartPolicy>,
    max_restarts: Option<u32>,
    backoff_ms: Option<u64>,
    binary_fingerprint: Option<String>,
}

impl RunningFingerprint {
    fn from_candidate(c: &PluginDaemonCandidate) -> Self {
        Self {
            root_dir: c.root_dir.clone(),
            binary: c.spec.binary.clone(),
            args: c.spec.args.clone(),
            env: c.spec.env.clone(),
            restart_policy: c.spec.restart_policy,
            max_restarts: c.spec.max_restarts,
            backoff_ms: c.spec.backoff_ms,
            binary_fingerprint: c.binary_fingerprint.clone(),
        }
    }
}

struct ActiveWorker {
    fingerprint: RunningFingerprint,
    stop_tx: watch::Sender<bool>,
    handle: JoinHandle<()>,
}

/// Supervises all enabled and explicitly approved `uds-native` plugin daemons from `HubState`.
pub(super) async fn run_plugin_daemon_supervisor(
    cfg: SupervisorConfig,
    state: Arc<HubState>,
    shutdown_rx: watch::Receiver<bool>,
) {
    run_daemon_supervisor_loop(
        cfg.module_dir,
        cfg.state_dir,
        cfg.hub_socket,
        Arc::clone(state.plugin_registry()),
        Arc::clone(state.daemon_manager()),
        Arc::clone(state.logs()),
        shutdown_rx,
    )
    .await;
}

/// Testable core reconciliation loop decoupled from `HubState`.
pub(crate) async fn run_daemon_supervisor_loop(
    module_dir: PathBuf,
    state_dir: PathBuf,
    hub_socket: PathBuf,
    registry: Arc<PluginRegistry>,
    manager: Arc<PluginDaemonManager>,
    logs: Arc<LogRing>,
    mut shutdown_rx: watch::Receiver<bool>,
) {
    let mut registry_rx = registry.subscribe();
    let mut workers: HashMap<String, ActiveWorker> = HashMap::new();

    loop {
        if *shutdown_rx.borrow() || module_dir.join("remove").exists() {
            break;
        }
        let module_disabled = module_dir.join("disable").exists();
        reconcile_daemons(
            module_disabled,
            &state_dir,
            &hub_socket,
            &registry,
            &manager,
            &logs,
            &mut workers,
        )
        .await;

        tokio::select! {
            _ = shutdown_rx.changed() => {
                if *shutdown_rx.borrow() {
                    break;
                }
            }
            res = registry_rx.recv() => {
                if matches!(res, Err(tokio::sync::broadcast::error::RecvError::Closed)) {
                    break;
                }
            }
            () = manager.wait_reconcile() => {}
        }
    }

    for (plugin_id, worker) in workers.drain() {
        let _ = worker.stop_tx.send(true);
        let _ = worker.handle.await;
        manager.set_status(
            &plugin_id,
            DaemonRuntimeInfo {
                status: PluginDaemonStatus::Stopped,
                pid: None,
                restart_count: 0,
                last_error: None,
            },
        );
    }
}

async fn reconcile_daemons(
    module_disabled: bool,
    state_dir: &Path,
    hub_socket: &Path,
    registry: &PluginRegistry,
    manager: &Arc<PluginDaemonManager>,
    logs: &Arc<LogRing>,
    workers: &mut HashMap<String, ActiveWorker>,
) {
    let candidates = registry.daemon_candidates();
    let active_ids: HashSet<String> = candidates.iter().map(|c| c.plugin_id.clone()).collect();

    let stale_ids: Vec<String> = workers
        .keys()
        .filter(|id| !active_ids.contains(*id))
        .cloned()
        .collect();
    for id in stale_ids {
        stop_worker(workers, &id).await;
        manager.remove_plugin(&id);
    }

    for candidate in candidates {
        let id = candidate.plugin_id.clone();
        if module_disabled || !candidate.enabled {
            stop_worker(workers, &id).await;
            manager.set_status(
                &id,
                DaemonRuntimeInfo {
                    status: PluginDaemonStatus::Stopped,
                    pid: None,
                    restart_count: 0,
                    last_error: None,
                },
            );
            continue;
        }
        if !candidate.daemon_approved {
            stop_worker(workers, &id).await;
            manager.set_status(
                &id,
                DaemonRuntimeInfo {
                    status: PluginDaemonStatus::AwaitingApproval,
                    pid: None,
                    restart_count: 0,
                    last_error: None,
                },
            );
            continue;
        }

        let desired_fp = RunningFingerprint::from_candidate(&candidate);
        let needs_spawn = match workers.get(&id) {
            Some(existing) if existing.fingerprint == desired_fp => false,
            Some(_) => {
                stop_worker(workers, &id).await;
                true
            }
            None => true,
        };

        if needs_spawn {
            let (stop_tx, stop_rx) = watch::channel(false);
            let data_dir = state_dir.join("data").join(&id);
            let handle = tokio::spawn(run_single_daemon_worker(
                candidate,
                hub_socket.to_path_buf(),
                data_dir,
                Arc::clone(manager),
                Arc::clone(logs),
                stop_rx,
            ));
            workers.insert(
                id,
                ActiveWorker {
                    fingerprint: desired_fp,
                    stop_tx,
                    handle,
                },
            );
        }
    }
}

async fn stop_worker(workers: &mut HashMap<String, ActiveWorker>, plugin_id: &str) {
    if let Some(worker) = workers.remove(plugin_id) {
        let _ = worker.stop_tx.send(true);
        let _ = worker.handle.await;
    }
}
