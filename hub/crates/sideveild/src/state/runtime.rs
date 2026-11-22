//! Runtime telemetry snapshot, session handles, and token helpers for `HubState`.

use std::fs::File;
use std::io::Read;

use sideveil_protocol::config::PanelState;
use sideveil_protocol::plugin::HostPluginDiagnosticsPayload;
use sideveil_protocol::sensor::{
    DebugGestureStats, DisplayGeometry, HubToSensor, NetworkContextSnapshot,
};
use sideveil_protocol::theme::HostDynamicColorsPayload;
use tokio::sync::mpsc;

use crate::outbound_queue::OutboundSender;
use crate::state::HubState;

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
    pub(crate) foreground_activity: String,
    pub(crate) display: DisplayGeometry,
    pub(crate) task_listener_active: bool,
    pub(crate) gesture_monitor_active: bool,
    pub(crate) overlay_permission_granted: bool,
    pub(crate) panel_state: PanelState,
    pub(crate) fullscreen_active: bool,
    pub(crate) dynamic_colors: Option<HostDynamicColorsPayload>,
    pub(crate) active_hud_plugin: Option<String>,
    pub(crate) host_diagnostics: HostPluginDiagnosticsPayload,
    pub(crate) host_pid: Option<u32>,
    pub(crate) cross_window_blur_supported: Option<bool>,
    pub(crate) sensor_pid: Option<u32>,
    pub(crate) network_context: NetworkContextSnapshot,
    pub(crate) gesture_stats: Option<DebugGestureStats>,
}

impl Default for RuntimeSnapshot {
    fn default() -> Self {
        Self {
            foreground_package: String::new(),
            foreground_activity: String::new(),
            display: DisplayGeometry::default(),
            task_listener_active: false,
            gesture_monitor_active: false,
            overlay_permission_granted: false,
            panel_state: PanelState::Closed,
            fullscreen_active: false,
            dynamic_colors: None,
            active_hud_plugin: None,
            host_diagnostics: HostPluginDiagnosticsPayload::default(),
            host_pid: None,
            cross_window_blur_supported: None,
            sensor_pid: None,
            network_context: NetworkContextSnapshot::default(),
            gesture_stats: None,
        }
    }
}

pub(super) fn current_uid() -> u32 {
    unsafe {
        // SAFETY: `getuid` has no preconditions and always returns the process real UID.
        libc::getuid()
    }
}

pub(super) fn generate_hex_token() -> String {
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
