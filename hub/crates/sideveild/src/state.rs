//! Shared runtime state, session registries, and event routing for `sideveild`.

use std::fs::File;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU16, AtomicU64, Ordering};
use std::sync::{Arc, PoisonError, RwLock};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use sideveil_protocol::config::PanelState;
use sideveil_protocol::plugin::PluginsSnapshotPayload;
use sideveil_protocol::rpc::{DaemonHealth, DaemonStatus};
use sideveil_protocol::sensor::{DisplayGeometry, HubToSensor};
use tokio::sync::mpsc;

use crate::config_store::ConfigStore;
use crate::log_ring::LogRing;
use crate::outbound_queue::OutboundSender;
use crate::plugin_backends::PluginBackends;
use crate::plugin_registry::PluginRegistry;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HostWakeSignal {
    SyncAndWake,
    ForceRestart,
    WatchHostPid(u32),
    HostDisconnected,
}

#[derive(Debug, Clone)]
pub(crate) struct HostSessionHandle {
    pub(crate) id: u64,
    pub(crate) sender: OutboundSender,
}

#[derive(Debug, Clone)]
pub(crate) struct SensorSessionHandle {
    pub(crate) id: u64,
    pub(crate) sender: mpsc::UnboundedSender<HubToSensor>,
}

#[derive(Debug, Clone)]
pub(crate) struct RuntimeSnapshot {
    pub(crate) foreground_package: String,
    pub(crate) display: DisplayGeometry,
    pub(crate) task_listener_active: bool,
    pub(crate) gesture_monitor_active: bool,
    pub(crate) overlay_permission_granted: bool,
    pub(crate) panel_state: PanelState,
    pub(crate) host_pid: Option<u32>,
    pub(crate) cross_window_blur_supported: Option<bool>,
    pub(crate) sensor_pid: Option<u32>,
}

impl Default for RuntimeSnapshot {
    fn default() -> Self {
        Self {
            foreground_package: String::new(),
            display: DisplayGeometry::default(),
            task_listener_active: false,
            gesture_monitor_active: false,
            overlay_permission_granted: false,
            panel_state: PanelState::Closed,
            host_pid: None,
            cross_window_blur_supported: None,
            sensor_pid: None,
        }
    }
}

#[derive(Debug)]
pub struct HubState {
    started_at: Instant,
    host_package: String,
    config: Arc<ConfigStore>,
    plugins: Arc<PluginRegistry>,
    backends: Arc<PluginBackends>,
    data_dir: PathBuf,
    logs: Arc<LogRing>,
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
        Arc::new(Self {
            started_at: Instant::now(),
            host_package,
            config,
            plugins,
            backends: Arc::new(PluginBackends::new()),
            data_dir,
            logs,
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
    pub fn plugins_snapshot(&self) -> PluginsSnapshotPayload {
        let cfg = self.config.current();
        let fg = self
            .runtime
            .read()
            .unwrap_or_else(PoisonError::into_inner)
            .foreground_package
            .clone();
        let online = self.backends.online_ids();
        self.plugins.snapshot(&fg, &cfg, &online)
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
        let plugins_snap = self
            .plugins
            .snapshot(&snap.foreground_package, &cfg, &online);
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
        self.logs.info("Requested SideveilHost restart");
        self.send_host_wake(HostWakeSignal::ForceRestart);
    }

    pub(crate) fn send_host_wake(&self, signal: HostWakeSignal) {
        let _ = self.host_wake_tx.send(signal);
    }
}

fn current_uid() -> u32 {
    unsafe {
        // SAFETY: `getuid` has no preconditions and always returns the process real UID.
        libc::getuid()
    }
}

fn generate_hex_token() -> String {
    let mut bytes = [0u8; 32];
    if let Ok(mut f) = File::open("/dev/urandom")
        && f.read_exact(&mut bytes).is_ok()
    {
        return encode_hex(&bytes);
    }
    let seed = HubState::now_unix_ms() ^ (u64::from(std::process::id()) << 32);
    for (idx, chunk) in bytes.chunks_mut(8).enumerate() {
        let mixed = seed
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(idx as u64);
        chunk.copy_from_slice(&mixed.to_le_bytes());
    }
    encode_hex(&bytes)
}

fn encode_hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for &b in bytes {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 0x0f) as usize] as char);
    }
    out
}
