//! Session lifecycle, status/health reporting, and supervisor wake signals for `HubState`.

use std::sync::PoisonError;
use std::sync::atomic::Ordering;
use std::time::Duration;

use serde_json::Value;
use sideveil_protocol::config::PanelState;
use sideveil_protocol::rpc::{DaemonHealth, DaemonStatus, RpcRequest, RpcResponse, error_codes};
use sideveil_protocol::sensor::HubToSensor;
use tokio::sync::{mpsc, oneshot};

use super::HubState;
use super::runtime::{HostSessionHandle, HostWakeSignal, SensorSessionHandle, current_uid};
use crate::outbound_queue::OutboundSender;

struct PendingHostRpcGuard<'a> {
    state: &'a HubState,
    id: String,
}

impl Drop for PendingHostRpcGuard<'_> {
    fn drop(&mut self) {
        let mut pending = self
            .state
            .pending_host_rpcs
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        pending.remove(&self.id);
    }
}

impl HubState {
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
            self.drain_pending_host_rpcs();
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
            self.drain_pending_host_rpcs();
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

    pub async fn invoke_host(
        &self,
        method: &str,
        params: Option<Value>,
        timeout: Duration,
    ) -> Result<Value, (i32, String)> {
        let sender = {
            let guard = self
                .host_session
                .read()
                .unwrap_or_else(PoisonError::into_inner);
            guard.as_ref().map(|h| h.sender.clone())
        }
        .ok_or_else(|| {
            (
                error_codes::BACKEND_OFFLINE,
                "SideVeilHost is not connected".to_owned(),
            )
        })?;

        let seq = self.next_host_rpc_id.fetch_add(1, Ordering::Relaxed);
        let req_id = format!("hub-rpc-{seq}");
        let req = RpcRequest::new(req_id.clone(), method, params);
        let encoded = serde_json::to_string(&req).map_err(|err| {
            (
                error_codes::INTERNAL_ERROR,
                format!("Failed to encode host RPC '{method}': {err}"),
            )
        })?;

        let (tx, rx) = oneshot::channel();
        {
            let mut pending = self
                .pending_host_rpcs
                .lock()
                .unwrap_or_else(PoisonError::into_inner);
            pending.insert(req_id.clone(), tx);
        }
        let _guard = PendingHostRpcGuard {
            state: self,
            id: req_id,
        };

        if !sender.send_critical(encoded) {
            return Err((
                error_codes::BACKEND_OFFLINE,
                "SideVeilHost session is closed".to_owned(),
            ));
        }

        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(resp)) => {
                if let Some(err) = resp.error {
                    Err((err.code, err.message))
                } else {
                    Ok(resp.result.unwrap_or(Value::Null))
                }
            }
            Ok(Err(_)) => Err((
                error_codes::BACKEND_OFFLINE,
                "SideVeilHost disconnected before replying".to_owned(),
            )),
            Err(_) => Err((
                error_codes::BACKEND_TIMEOUT,
                format!(
                    "SideVeilHost RPC '{method}' timed out after {}ms",
                    timeout.as_millis()
                ),
            )),
        }
    }

    pub fn resolve_host_rpc_response(&self, resp: RpcResponse) -> bool {
        let tx = {
            let mut pending = self
                .pending_host_rpcs
                .lock()
                .unwrap_or_else(PoisonError::into_inner);
            pending.remove(&resp.id)
        };
        if let Some(sender) = tx {
            let _ = sender.send(resp);
            true
        } else {
            false
        }
    }

    fn drain_pending_host_rpcs(&self) {
        let mut pending = self
            .pending_host_rpcs
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        pending.clear();
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
