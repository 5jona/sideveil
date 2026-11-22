//! Inbound `:sensor` UDS telemetry and gesture message routing for `HubState`.

use std::sync::PoisonError;

use sideveil_protocol::config::{EdgePatchParams, PanelState};
use sideveil_protocol::rpc::{
    EdgeRepositionPayload, EdgeRepositionPhase, GestureDragEndPayload, GestureDragMovePayload,
    GestureDragStartPayload, GestureFullscreenHintPayload, ScopeChangedPayload, events,
};
use sideveil_protocol::sensor::SensorToHub;

use crate::state::HubState;

impl HubState {
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
                foreground_activity,
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
                        rt.foreground_activity = foreground_activity;
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
            SensorToHub::ForegroundChanged {
                package_name,
                activity_name,
            } => {
                {
                    let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
                    rt.foreground_package = package_name.clone();
                    rt.foreground_activity = activity_name.clone();
                }
                let snapshot = self.plugins_snapshot();
                let active = !snapshot.matched_plugin_ids.is_empty();
                self.sync_sensor_state();
                self.emit_host_event_critical(
                    events::SCOPE_CHANGED,
                    &ScopeChangedPayload {
                        foreground_package: package_name,
                        foreground_activity: activity_name,
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
                canceled,
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
                        canceled,
                    },
                );
            }
            SensorToHub::GestureFullscreenHint { side } => {
                self.emit_host_event_critical(
                    events::GESTURE_FULLSCREEN_HINT,
                    &GestureFullscreenHintPayload { side },
                );
            }
            SensorToHub::EdgeRepositionStart { side, top_percent } => {
                self.emit_host_event_critical(
                    events::EDGE_REPOSITION,
                    &EdgeRepositionPayload {
                        phase: EdgeRepositionPhase::Start,
                        side,
                        top_percent,
                        committed: false,
                    },
                );
            }
            SensorToHub::EdgeRepositionMove { side, top_percent } => {
                self.emit_host_event_ephemeral(
                    events::EDGE_REPOSITION,
                    &EdgeRepositionPayload {
                        phase: EdgeRepositionPhase::Move,
                        side,
                        top_percent,
                        committed: false,
                    },
                );
            }
            SensorToHub::EdgeRepositionEnd {
                side,
                top_percent,
                committed,
            } => {
                self.emit_host_event_critical(
                    events::EDGE_REPOSITION,
                    &EdgeRepositionPayload {
                        phase: EdgeRepositionPhase::End,
                        side,
                        top_percent,
                        committed,
                    },
                );
                if committed {
                    match self.config_store().patch_edge(&EdgePatchParams {
                        side: Some(side),
                        top_percent: Some(top_percent),
                    }) {
                        Ok(updated) => {
                            self.logs().info(format!(
                                "Persisted repositioned edge handle: side={side:?} topPercent={}",
                                updated.edge.top_percent
                            ));
                            self.on_config_changed(&updated);
                        }
                        Err(err) => {
                            self.logs()
                                .warn(format!("Failed to persist repositioned edge handle: {err}"));
                        }
                    }
                }
            }
            SensorToHub::NetworkContextChanged { context } => {
                {
                    let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
                    rt.network_context = context.clone();
                }
                self.emit_host_event_critical(events::NETWORK_CONTEXT_CHANGED, &context);
                if let Ok(val) = serde_json::to_value(&context) {
                    self.plugin_backends()
                        .broadcast_system_event(events::NETWORK_CONTEXT_CHANGED, val);
                }
            }
            SensorToHub::Pong { .. } => {}
        }
    }
}
