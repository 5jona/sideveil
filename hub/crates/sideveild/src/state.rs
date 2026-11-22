//! Shared runtime state, session registries, and event routing for `sideveild`.

mod runtime;
mod sessions;

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU16, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, PoisonError, RwLock};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use sideveil_protocol::plugin::{HostPluginDiagnosticsPayload, PluginsSnapshotPayload};
use sideveil_protocol::rpc::RpcResponse;
use sideveil_protocol::sensor::NetworkContextSnapshot;
use tokio::sync::{mpsc, oneshot};

pub use self::runtime::HostWakeSignal;
use self::runtime::generate_hex_token;
pub(crate) use self::runtime::{HostSessionHandle, RuntimeSnapshot, SensorSessionHandle};
use crate::audit_ring::AuditRing;
use crate::config_store::ConfigStore;
use crate::log_ring::LogRing;
use crate::plugin_backends::PluginBackends;
use crate::plugin_registry::PluginRegistry;

#[derive(Debug)]
pub struct HubState {
    started_at: Instant,
    pub(super) host_package: String,
    pub(super) config: Arc<ConfigStore>,
    pub(super) plugins: Arc<PluginRegistry>,
    pub(super) backends: Arc<PluginBackends>,
    data_dir: PathBuf,
    cache_dir: PathBuf,
    pub(super) logs: Arc<LogRing>,
    audit: Arc<AuditRing>,
    ws_port: AtomicU16,
    ws_token: String,
    pub(super) next_session_id: AtomicU64,
    pub(super) next_host_rpc_id: AtomicU64,
    pub(super) pending_host_rpcs: Mutex<HashMap<String, oneshot::Sender<RpcResponse>>>,
    pub(crate) runtime: RwLock<RuntimeSnapshot>,
    pub(crate) host_session: RwLock<Option<HostSessionHandle>>,
    pub(crate) sensor_session: RwLock<Option<SensorSessionHandle>>,
    pub(super) host_wake_tx: mpsc::UnboundedSender<HostWakeSignal>,
}

impl HubState {
    pub fn new(
        host_package: String,
        config: Arc<ConfigStore>,
        plugins: Arc<PluginRegistry>,
        data_dir: PathBuf,
        logs: Arc<LogRing>,
        host_wake_tx: mpsc::UnboundedSender<HostWakeSignal>,
    ) -> Arc<Self> {
        let cache_dir = data_dir
            .parent()
            .map_or_else(|| data_dir.join("cache"), |p| p.join("cache"));
        Arc::new(Self {
            started_at: Instant::now(),
            host_package,
            config,
            plugins,
            backends: Arc::new(PluginBackends::new()),
            data_dir,
            cache_dir,
            logs,
            audit: Arc::new(AuditRing::new()),
            ws_port: AtomicU16::new(0),
            ws_token: generate_hex_token(),
            next_session_id: AtomicU64::new(1),
            next_host_rpc_id: AtomicU64::new(1),
            pending_host_rpcs: Mutex::new(HashMap::new()),
            runtime: RwLock::new(RuntimeSnapshot::default()),
            host_session: RwLock::new(None),
            sensor_session: RwLock::new(None),
            host_wake_tx,
        })
    }

    #[must_use]
    pub fn config_store(&self) -> &Arc<ConfigStore> {
        &self.config
    }

    #[must_use]
    pub fn plugin_registry(&self) -> &Arc<PluginRegistry> {
        &self.plugins
    }

    #[must_use]
    pub fn plugin_backends(&self) -> &Arc<PluginBackends> {
        &self.backends
    }

    #[must_use]
    pub fn data_dir(&self) -> &Path {
        &self.data_dir
    }

    #[must_use]
    pub fn cache_dir(&self) -> &Path {
        &self.cache_dir
    }

    #[must_use]
    pub fn audit(&self) -> &Arc<AuditRing> {
        &self.audit
    }

    #[must_use]
    pub fn host_diagnostics(&self) -> HostPluginDiagnosticsPayload {
        let rt = self.runtime.read().unwrap_or_else(PoisonError::into_inner);
        let mut diag = rt.host_diagnostics.clone();
        if diag.active_hud_plugin_id.is_none() {
            diag.active_hud_plugin_id = rt.active_hud_plugin.clone();
        }
        diag
    }

    #[must_use]
    pub fn network_context(&self) -> NetworkContextSnapshot {
        self.runtime
            .read()
            .unwrap_or_else(PoisonError::into_inner)
            .network_context
            .clone()
    }

    #[must_use]
    pub fn plugins_snapshot(&self) -> PluginsSnapshotPayload {
        let cfg = self.config.current();
        let (fg_pkg, fg_act) = {
            let rt = self.runtime.read().unwrap_or_else(PoisonError::into_inner);
            (
                rt.foreground_package.clone(),
                rt.foreground_activity.clone(),
            )
        };
        let online = self.backends.online_ids();
        self.plugins.snapshot(&fg_pkg, &fg_act, &cfg, &online)
    }

    #[must_use]
    pub fn logs(&self) -> &Arc<LogRing> {
        &self.logs
    }

    #[must_use]
    pub fn host_package(&self) -> &str {
        &self.host_package
    }

    #[must_use]
    pub fn ws_token(&self) -> &str {
        &self.ws_token
    }

    pub fn set_ws_port(&self, port: u16) {
        self.ws_port.store(port, Ordering::Release);
    }

    #[must_use]
    pub fn ws_port(&self) -> u16 {
        self.ws_port.load(Ordering::Acquire)
    }

    #[must_use]
    pub fn uptime_ms(&self) -> u64 {
        u64::try_from(self.started_at.elapsed().as_millis()).unwrap_or(u64::MAX)
    }

    #[must_use]
    pub fn now_unix_ms() -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| u64::try_from(d.as_millis()).unwrap_or(u64::MAX))
            .unwrap_or(0)
    }
}
