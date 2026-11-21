//! Shared runtime state, session registries, and event routing for `sideveild`.

mod runtime;

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU16, AtomicU64, Ordering};
use std::sync::{Arc, PoisonError, RwLock};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use sideveil_protocol::config::PanelState;
use sideveil_protocol::plugin::{HostPluginDiagnosticsPayload, PluginsSnapshotPayload};
use sideveil_protocol::rpc::{DaemonHealth, DaemonStatus};
use sideveil_protocol::sensor::HubToSensor;
use tokio::sync::mpsc;

pub use self::runtime::HostWakeSignal;
pub(crate) use self::runtime::{HostSessionHandle, RuntimeSnapshot, SensorSessionHandle};
use self::runtime::{current_uid, generate_hex_token};
use crate::audit_ring::AuditRing;
use crate::config_store::ConfigStore;
use crate::log_ring::LogRing;
use crate::outbound_queue::OutboundSender;
use crate::plugin_backends::PluginBackends;
use crate::plugin_registry::PluginRegistry;

#[derive(Debug)]
pub struct HubState {
    started_at: Instant,
    host_package: String,
    config: Arc<ConfigStore>,
    plugins: Arc<PluginRegistry>,
    backends: Arc<PluginBackends>,
    data_dir: PathBuf,
    cache_dir: PathBuf,
    logs: Arc<LogRing>,
    audit: Arc<AuditRing>,
    ws_port: AtomicU16,
    ws_token: String,
    next_session_id: AtomicU64,
    pub(crate) runtime: RwLock<RuntimeSnapshot>,
    pub(crate) host_session: RwLock<Option<HostSessionHandle>>,
    pub(crate) sensor_session: RwLock<Option<SensorSessionHandle>>,
    host_wake_tx: mpsc::UnboundedSender<HostWakeSignal>,
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

    #[must_use]
    pub fn is_host_connected(&self) -> bool {
        self.host_session
            .read()
            .unwrap_or_else(PoisonError::into_inner)
            .is_some()
    }

    #[must_use]
    pub fn is_sensor_connected(&self) -> bool {
        self.sensor_session
            .read()
            .unwrap_or_else(PoisonError::into_inner)
            .is_some()
    }

    #[must_use]
    pub fn build_status(&self) -> DaemonStatus {
        let cfg = self.config.current();
        let snap = self
            .runtime
            .read()
            .unwrap_or_else(PoisonError::into_inner)
            .clone();
        let online = self.backends.online_ids();
        let plugins_snap = self.plugins.snapshot(
            &snap.foreground_package,
            &snap.foreground_activity,
            &cfg,
            &online,
        );
        let scope_active = !plugins_snap.matched_plugin_ids.is_empty();
        DaemonStatus {
            pid: std::process::id(),
            uid: current_uid(),
            uptime_ms: self.uptime_ms(),
            config_version: cfg.version,
            enabled: cfg.enabled,
            scope_active,
            foreground_package: snap.foreground_package,
            host_package: self.host_package.clone(),
            host_connected: self.is_host_connected(),
            host_pid: snap.host_pid,
            overlay_permission_granted: snap.overlay_permission_granted,
            cross_window_blur_supported: snap.cross_window_blur_supported,
            gesture_monitor_active: snap.gesture_monitor_active,
            task_listener_active: snap.task_listener_active,
            panel_state: snap.panel_state,
            screen_width_px: snap.display.width_px,
            screen_height_px: snap.display.height_px,
            density: snap.display.density,
            ws_port: self.ws_port(),
            sensor_connected: self.is_sensor_connected(),
            sensor_pid: snap.sensor_pid,
            active_plugin_id: plugins_snap.active_plugin_id,
            matched_plugins_count: plugins_snap.matched_plugin_ids.len(),
        }
    }

    #[must_use]
    pub fn build_health(&self) -> DaemonHealth {
        DaemonHealth {
            ok: true,
            pid: std::process::id(),
            uptime_ms: self.uptime_ms(),
            sensor_connected: self.is_sensor_connected(),
            host_connected: self.is_host_connected(),
        }
    }

    pub fn register_host_session(&self, sender: OutboundSender) -> u64 {
        let id = self.next_session_id.fetch_add(1, Ordering::Relaxed);
        let prev = {
            let mut guard = self
                .host_session
                .write()
                .unwrap_or_else(PoisonError::into_inner);
            guard.replace(HostSessionHandle { id, sender })
        };
        if let Some(old) = prev {
            old.sender.close();
        }
        self.logs
            .info(format!("Host WebSocket session #{id} connected"));
        self.push_config_snapshot_to_host();
        self.push_plugins_snapshot_to_host();
        id
    }

    pub fn clear_host_session(&self, id: u64) {
        let removed = {
            let mut guard = self
                .host_session
                .write()
                .unwrap_or_else(PoisonError::into_inner);
            if guard.as_ref().is_some_and(|h| h.id == id) {
                guard.take();
                true
            } else {
                false
            }
        };
        if removed {
            {
                let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
                rt.host_pid = None;
                rt.panel_state = PanelState::Closed;
            }
            self.sync_sensor_state();
            self.logs
                .info(format!("Host WebSocket session #{id} disconnected"));
            let _ = self.host_wake_tx.send(HostWakeSignal::HostDisconnected);
        }
    }

    pub fn register_sensor_session(&self, sender: mpsc::UnboundedSender<HubToSensor>) -> u64 {
        let id = self.next_session_id.fetch_add(1, Ordering::Relaxed);
        {
            let mut guard = self
                .sensor_session
                .write()
                .unwrap_or_else(PoisonError::into_inner);
            *guard = Some(SensorSessionHandle { id, sender });
        }
        self.logs
            .info(format!("Sensor UDS session #{id} connected"));
        self.sync_sensor_state();
        id
    }

    pub fn clear_sensor_session(&self, id: u64) {
        let removed = {
            let mut guard = self
                .sensor_session
                .write()
                .unwrap_or_else(PoisonError::into_inner);
            if guard.as_ref().is_some_and(|h| h.id == id) {
                guard.take();
                true
            } else {
                false
            }
        };
        if removed {
            let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
            rt.sensor_pid = None;
            rt.gesture_monitor_active = false;
            rt.task_listener_active = false;
            self.logs
                .warn(format!("Sensor UDS session #{id} disconnected"));
        }
    }

    pub fn request_host_restart(&self) {
        self.logs.info("Requested SideVeilHost restart");
        self.send_host_wake(HostWakeSignal::ForceRestart);
    }

    pub(crate) fn send_host_wake(&self, signal: HostWakeSignal) {
        let _ = self.host_wake_tx.send(signal);
    }
}
