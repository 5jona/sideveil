//! Bidirectional Host WebSocket event and `:sensor` UDS telemetry routing for `HubState`.

use std::sync::PoisonError;

use serde::Serialize;
use serde_json::Value;
use sideveil_protocol::config::{PanelState, SideveilConfig};
use sideveil_protocol::plugin::{
    PluginActiveChangedPayload, PluginBackendState, PluginBackendStatePayload,
    PluginScopedEventPayload, PluginSelectParams,
};
use sideveil_protocol::rpc::{
    ConfigUpdatedPayload, GestureDragEndPayload, GestureDragMovePayload, GestureDragStartPayload,
    HostEventEnvelope, HostHelloPayload, PanelCommand, PanelCommandPayload,
    PanelStateChangedPayload, ScopeChangedPayload, events,
};
use sideveil_protocol::sensor::{HubToSensor, SensorToHub};

use crate::state::{HostWakeSignal, HubState};

impl HubState {
    pub fn on_config_changed(&self, _next: &SideveilConfig) {
        self.sync_sensor_state();
        self.push_config_snapshot_to_host();
        self.push_plugins_snapshot_to_host();
    }

    pub fn on_plugins_changed(&self) {
        self.sync_sensor_state();
        self.push_plugins_snapshot_to_host();
    }

    pub fn push_plugins_snapshot_to_host(&self) {
        let snapshot = self.plugins_snapshot();
        self.emit_host_event_critical(events::PLUGINS_UPDATED, &snapshot);
    }

    pub fn emit_plugin_backend_state(&self, plugin_id: &str, state: PluginBackendState) {
        self.emit_host_event_critical(
            events::PLUGIN_BACKEND_STATE,
            &PluginBackendStatePayload {
                plugin_id: plugin_id.to_owned(),
                state,
            },
        );
        self.push_plugins_snapshot_to_host();
    }

    pub fn emit_plugin_scoped_event(&self, plugin_id: &str, event: String, payload: Option<Value>) {
        self.emit_host_event_critical(
            events::PLUGIN_EVENT,
            &PluginScopedEventPayload {
                plugin_id: plugin_id.to_owned(),
                event,
                payload,
            },
        );
    }

    pub fn select_active_plugin(
        &self,
        plugin_id: &str,
        package_override: Option<&str>,
    ) -> Result<PluginActiveChangedPayload, String> {
        let fg = match package_override {
            Some(p) if !p.trim().is_empty() => p.trim().to_owned(),
            _ => self
                .runtime
                .read()
                .unwrap_or_else(PoisonError::into_inner)
                .foreground_package
                .clone(),
        };
        self.plugin_registry().select_plugin(plugin_id, &fg)?;
        let snapshot = self.plugins_snapshot();
        let payload = PluginActiveChangedPayload {
            plugin_id: plugin_id.to_owned(),
            foreground_package: fg,
            matched_plugin_ids: snapshot.matched_plugin_ids,
        };
        self.emit_host_event_critical(events::PLUGINS_ACTIVE_CHANGED, &payload);
        Ok(payload)
    }

    pub fn send_panel_command(&self, command: PanelCommand) {
        self.emit_host_event_critical(events::PANEL_COMMAND, &PanelCommandPayload { command });
    }

    pub fn request_sensor_permission_refresh(&self) {
        let guard = self
            .sensor_session
            .read()
            .unwrap_or_else(PoisonError::into_inner);
        if let Some(handle) = guard.as_ref() {
            let _ = handle.sender.send(HubToSensor::RefreshPermissions);
        }
    }

    pub fn sync_sensor_state(&self) {
        let cfg = self.config_store().current();
        let snapshot = self.plugins_snapshot();
        let scope_active = !snapshot.matched_plugin_ids.is_empty();
        let panel_state = self
            .runtime
            .read()
            .unwrap_or_else(PoisonError::into_inner)
            .panel_state;
        let guard = self
            .sensor_session
            .read()
            .unwrap_or_else(PoisonError::into_inner);
        if let Some(handle) = guard.as_ref() {
            let _ = handle.sender.send(HubToSensor::SyncState {
                config: cfg,
                scope_active,
                panel_state,
            });
        }
    }

    pub fn push_config_snapshot_to_host(&self) {
        let cfg = self.config_store().current();
        let snapshot = self.plugins_snapshot();
        let scope_active = !snapshot.matched_plugin_ids.is_empty();
        self.emit_host_event_critical(
            events::CONFIG_UPDATED,
            &ConfigUpdatedPayload {
                config: cfg,
                scope_active,
                foreground_package: snapshot.foreground_package,
            },
        );
    }

    pub fn emit_host_event_critical<T: Serialize>(&self, event: &str, payload: &T) {
        let Some(wire) = encode_event(event, payload) else {
            return;
        };
        let guard = self
            .host_session
            .read()
            .unwrap_or_else(PoisonError::into_inner);
        if let Some(handle) = guard.as_ref() {
            let _ = handle.sender.send_critical(wire);
        }
    }

    pub fn emit_host_event_ephemeral<T: Serialize>(&self, event: &str, payload: &T) {
        let Some(wire) = encode_event(event, payload) else {
            return;
        };
        let guard = self
            .host_session
            .read()
            .unwrap_or_else(PoisonError::into_inner);
        if let Some(handle) = guard.as_ref() {
            let _ = handle.sender.send_ephemeral(wire);
        }
    }

    pub fn on_host_event(&self, envelope: HostEventEnvelope) {
        match envelope.event.as_str() {
            events::HOST_HELLO => {
                if let Some(payload) = envelope.payload
                    && let Ok(hello) = serde_json::from_value::<HostHelloPayload>(payload)
                {
                    {
                        let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
                        rt.host_pid = Some(hello.pid);
                        rt.cross_window_blur_supported = Some(hello.cross_window_blur_supported);
                    }
                    self.logs().info(format!(
                        "Host hello pid={} version={} blurSupported={}",
                        hello.pid, hello.version, hello.cross_window_blur_supported
                    ));
                    self.send_host_wake(HostWakeSignal::WatchHostPid(hello.pid));
                }
            }
            events::PANEL_STATE_CHANGED => {
                if let Some(payload) = envelope.payload
                    && let Ok(changed) = serde_json::from_value::<PanelStateChangedPayload>(payload)
                {
                    let updated = {
                        let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
                        if rt.panel_state == changed.state {
                            false
                        } else {
                            rt.panel_state = changed.state;
                            true
                        }
                    };
                    if updated {
                        self.sync_sensor_state();
                    }
                }
            }
            events::HOST_SELECT_PLUGIN => {
                if let Some(payload) = envelope.payload
                    && let Ok(params) = serde_json::from_value::<PluginSelectParams>(payload)
                    && let Err(err) =
                        self.select_active_plugin(&params.plugin_id, params.package_name.as_deref())
                {
                    self.logs().warn(format!(
                        "Failed host.selectPlugin '{}': {err}",
                        params.plugin_id
                    ));
                }
            }
            other => {
                self.logs().info(format!("Ignored host event '{other}'"));
            }
        }
    }

    pub fn on_sensor_message(&self, msg: SensorToHub) {
        match msg {
            SensorToHub::Hello {
                pid,
                version,
                display,
                task_listener_active,
                gesture_monitor_active,
                overlay_permission_granted,
                foreground_package,
            } => {
                {
                    let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
                    rt.sensor_pid = Some(pid);
                    rt.display = display;
                    rt.task_listener_active = task_listener_active;
                    rt.gesture_monitor_active = gesture_monitor_active;
                    rt.overlay_permission_granted = overlay_permission_granted;
                    if !foreground_package.is_empty() {
                        rt.foreground_package = foreground_package;
                    }
                }
                self.logs().info(format!(
                    "Sensor hello pid={pid} version={version} display={}x{}@{} overlay={overlay_permission_granted}",
                    display.width_px, display.height_px, display.density
                ));
                self.sync_sensor_state();
                self.push_config_snapshot_to_host();
                self.push_plugins_snapshot_to_host();
            }
            SensorToHub::ForegroundChanged { package_name } => {
                {
                    let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
                    rt.foreground_package = package_name.clone();
                }
                let snapshot = self.plugins_snapshot();
                let active = !snapshot.matched_plugin_ids.is_empty();
                self.sync_sensor_state();
                self.emit_host_event_critical(
                    events::SCOPE_CHANGED,
                    &ScopeChangedPayload {
                        foreground_package: package_name,
                        active,
                    },
                );
                self.emit_host_event_critical(events::PLUGINS_UPDATED, &snapshot);
            }
            SensorToHub::DisplayChanged { display } => {
                {
                    let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
                    rt.display = display;
                }
                self.push_config_snapshot_to_host();
            }
            SensorToHub::PermissionsStatus {
                overlay_granted, ..
            } => {
                let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
                rt.overlay_permission_granted = overlay_granted;
            }
            SensorToHub::GestureDragStart {
                side,
                start_y_fraction,
            } => {
                if self.is_host_connected() {
                    let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
                    rt.panel_state = PanelState::Dragging;
                }
                self.emit_host_event_critical(
                    events::GESTURE_DRAG_START,
                    &GestureDragStartPayload {
                        side,
                        start_y_fraction,
                    },
                );
            }
            SensorToHub::GestureDragMove {
                side,
                progress,
                raw_x,
                raw_y,
                delta_x,
            } => {
                self.emit_host_event_ephemeral(
                    events::GESTURE_DRAG_MOVE,
                    &GestureDragMovePayload {
                        side,
                        progress,
                        raw_x,
                        raw_y,
                        delta_x,
                    },
                );
            }
            SensorToHub::GestureDragEnd {
                should_open,
                velocity_px_per_sec,
            } => {
                if !self.is_host_connected() {
                    let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
                    rt.panel_state = PanelState::Closed;
                }
                self.emit_host_event_critical(
                    events::GESTURE_DRAG_END,
                    &GestureDragEndPayload {
                        should_open,
                        velocity_px_per_sec,
                    },
                );
            }
            SensorToHub::Pong { .. } => {}
        }
    }
}

fn encode_event<T: Serialize>(event: &str, payload: &T) -> Option<String> {
    let val: Value = serde_json::to_value(payload).ok()?;
    serde_json::to_string(&HostEventEnvelope {
        event: event.to_owned(),
        payload: Some(val),
    })
    .ok()
}
