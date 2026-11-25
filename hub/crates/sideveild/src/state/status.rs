//! Daemon status and health snapshot builders for `HubState`.

use std::sync::PoisonError;

use sideveil_protocol::rpc::{DaemonHealth, DaemonStatus};
use sideveil_protocol::{PLUGIN_API_VERSION, PROTOCOL_VERSION};

use super::HubState;
use super::runtime::current_uid;

impl HubState {
    #[must_use]
    pub fn build_status(&self) -> DaemonStatus {
        let cfg = self.config.current();
        let snap = self
            .runtime
            .read()
            .unwrap_or_else(PoisonError::into_inner)
            .clone();
        let online = self.backends.online_ids();
        let daemons = self.daemon_manager.snapshot_all();
        let plugins_snap = self.plugins.snapshot_with_daemons(
            &snap.foreground_package,
            &snap.foreground_activity,
            &cfg,
            &online,
            &daemons,
        );
        let scope_active = !plugins_snap.matched_plugin_ids.is_empty();
        DaemonStatus {
            pid: std::process::id(),
            uid: current_uid(),
            uptime_ms: self.uptime_ms(),
            protocol_version: PROTOCOL_VERSION,
            plugin_api_version: PLUGIN_API_VERSION,
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
            gesture_stats: snap.gesture_stats,
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
}
