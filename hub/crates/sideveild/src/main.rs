//! SideVeil core hub daemon entry point.

use std::fs;
use std::io;
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::Arc;

use sideveil_protocol::{DEFAULT_HOST_PACKAGE, paths};
use sideveild::config_store::ConfigStore;
use sideveild::host_ws::HostWsServer;
use sideveild::log_ring::LogRing;
use sideveild::peercred::PeerAuthConfig;
use sideveild::plugin_registry::PluginRegistry;
use sideveild::singleton::{LOCK_FILE_NAME, SingletonGuard};
use sideveild::state::HubState;
use sideveild::supervisor::{SupervisorConfig, spawn_supervisors};
use sideveild::uds_gateway::UdsGateway;
use tokio::signal::unix::{SignalKind, signal};
use tokio::sync::{mpsc, watch};

#[derive(Debug)]
struct DaemonArgs {
    config_path: PathBuf,
    run_dir: PathBuf,
    module_dir: PathBuf,
    state_dir: PathBuf,
    host_package: String,
    supervise: bool,
}

impl DaemonArgs {
    fn parse() -> Self {
        let mut config_path = PathBuf::from(paths::CONFIG_PATH);
        let mut run_dir = PathBuf::from(paths::RUN_DIR);
        let mut module_dir = PathBuf::from(paths::MODULE_DIR);
        let mut state_dir = PathBuf::from(paths::STATE_DIR);
        let mut host_package = DEFAULT_HOST_PACKAGE.to_owned();
        let mut supervise = true;

        let mut args = std::env::args().skip(1);
        while let Some(arg) = args.next() {
            match arg.as_str() {
                "--config" => {
                    if let Some(val) = args.next() {
                        config_path = PathBuf::from(val);
                    }
                }
                "--run-dir" => {
                    if let Some(val) = args.next() {
                        run_dir = PathBuf::from(val);
                    }
                }
                "--module-dir" => {
                    if let Some(val) = args.next() {
                        module_dir = PathBuf::from(val);
                    }
                }
                "--state-dir" => {
                    if let Some(val) = args.next() {
                        state_dir = PathBuf::from(val);
                    }
                }
                "--host-pkg" => {
                    if let Some(val) = args.next() {
                        host_package = val;
                    }
                }
                "--no-supervise" => {
                    supervise = false;
                }
                _ => {}
            }
        }

        Self {
            config_path,
            run_dir,
            module_dir,
            state_dir,
            host_package,
            supervise,
        }
    }
}

#[tokio::main(flavor = "multi_thread", worker_threads = 2)]
async fn main() -> ExitCode {
    let args = DaemonArgs::parse();
    if let Err(err) = run(args).await {
        eprintln!("[SideVeilHub] Fatal error: {err}");
        return ExitCode::FAILURE;
    }
    ExitCode::SUCCESS
}

async fn run(args: DaemonArgs) -> io::Result<()> {
    let plugins_dir = args.state_dir.join("plugins");
    let data_dir = args.state_dir.join("data");
    let overrides_path = args.state_dir.join("plugins.json");
    fs::create_dir_all(&args.state_dir)?;
    fs::create_dir_all(&args.run_dir)?;
    fs::create_dir_all(&plugins_dir)?;
    fs::create_dir_all(&data_dir)?;

    let logs = Arc::new(LogRing::new());
    let lock_path = args.run_dir.join(LOCK_FILE_NAME);
    let singleton_guard = match SingletonGuard::acquire(&lock_path) {
        Ok(guard) => guard,
        Err(err) => {
            logs.error(format!("Singleton lock rejected duplicate startup: {err}"));
            return Err(err.into());
        }
    };
    logs.info(format!(
        "Starting sideveild v{} (pid={}, config={}, run_dir={})",
        env!("CARGO_PKG_VERSION"),
        std::process::id(),
        args.config_path.display(),
        args.run_dir.display()
    ));

    let config_store = ConfigStore::open(args.config_path.clone(), Arc::clone(&logs));
    let plugin_registry = PluginRegistry::open(plugins_dir, overrides_path, Arc::clone(&logs));
    let (wake_tx, wake_rx) = mpsc::unbounded_channel();
    let state = HubState::new(
        args.host_package.clone(),
        Arc::clone(&config_store),
        Arc::clone(&plugin_registry),
        data_dir,
        Arc::clone(&logs),
        wake_tx,
    );

    let watcher_handle = match config_store.spawn_watcher() {
        Ok(h) => Some(h),
        Err(err) => {
            logs.warn(format!("Config inotify watcher unavailable: {err}"));
            None
        }
    };

    let plugin_watcher_handle = match plugin_registry.spawn_watcher() {
        Ok(h) => Some(h),
        Err(err) => {
            logs.warn(format!("Plugin inotify watcher unavailable: {err}"));
            None
        }
    };

    let mut cfg_rx = config_store.subscribe();
    let cfg_state = Arc::clone(&state);
    let cfg_forward_task = tokio::spawn(async move {
        while let Ok(next_cfg) = cfg_rx.recv().await {
            cfg_state.on_config_changed(&next_cfg);
        }
    });

    let mut plugin_rx = plugin_registry.subscribe();
    let plugin_state = Arc::clone(&state);
    let plugin_forward_task = tokio::spawn(async move {
        while let Ok(()) = plugin_rx.recv().await {
            plugin_state.on_plugins_changed();
        }
    });

    let ws_server = HostWsServer::bind().await?;
    state.set_ws_port(ws_server.port());
    let ws_state = Arc::clone(&state);
    let ws_task = tokio::spawn(async move {
        ws_server.serve(ws_state).await;
    });

    let hub_socket = args.run_dir.join("hub.sock");
    let app_socket = args.run_dir.join("app.sock");
    let gateway = UdsGateway::bind(
        hub_socket.clone(),
        app_socket.clone(),
        PeerAuthConfig::default(),
    )?;
    let uds_state = Arc::clone(&state);
    let uds_task = tokio::spawn(async move {
        gateway.serve(uds_state).await;
    });

    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    let supervisor_handles = if args.supervise {
        spawn_supervisors(
            SupervisorConfig {
                module_dir: args.module_dir,
                state_dir: args.state_dir,
                hub_socket: hub_socket.clone(),
                host_package: args.host_package,
            },
            Arc::clone(&state),
            wake_rx,
            shutdown_rx,
        )
    } else {
        drop(wake_rx);
        Vec::new()
    };

    wait_for_shutdown_signal().await;
    logs.info("Received shutdown signal; stopping sideveild...");
    let _ = shutdown_tx.send(true);

    for handle in supervisor_handles {
        let _ = tokio::time::timeout(std::time::Duration::from_secs(4), handle).await;
    }

    if let Some(h) = watcher_handle {
        h.abort();
    }
    if let Some(h) = plugin_watcher_handle {
        h.abort();
    }
    cfg_forward_task.abort();
    plugin_forward_task.abort();
    ws_task.abort();
    uds_task.abort();

    let _ = fs::remove_file(&hub_socket);
    let _ = fs::remove_file(&app_socket);
    drop(singleton_guard);
    Ok(())
}

async fn wait_for_shutdown_signal() {
    let mut sigterm = signal(SignalKind::terminate()).ok();
    let mut sigint = signal(SignalKind::interrupt()).ok();

    match (sigterm.as_mut(), sigint.as_mut()) {
        (Some(t), Some(i)) => {
            tokio::select! {
                _ = t.recv() => {}
                _ = i.recv() => {}
            }
        }
        (Some(t), None) => {
            let _ = t.recv().await;
        }
        (None, Some(i)) => {
            let _ = i.recv().await;
        }
        (None, None) => {
            let _ = tokio::signal::ctrl_c().await;
        }
    }
}
