//! Inbound Host WebSocket event handling for `HubState`.

use std::sync::PoisonError;

use serde_json::json;
use sideveil_protocol::config::PanelState;
use sideveil_protocol::plugin::{HostPluginDiagnosticsPayload, PluginSelectParams};
use sideveil_protocol::rpc::{
    HostEventEnvelope, HostFullscreenChangedPayload, HostHelloPayload, HostWebViewConsolePayload,
    PanelStateChangedPayload, events,
};
use sideveil_protocol::theme::HostDynamicColorsPayload;

use crate::state::{HostWakeSignal, HubState};

impl HubState {
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
            events::HOST_FULLSCREEN_CHANGED => {
                if let Some(payload) = envelope.payload
                    && let Ok(changed) =
                        serde_json::from_value::<HostFullscreenChangedPayload>(payload)
                {
                    let updated = {
                        let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
                        if rt.fullscreen_active == changed.fullscreen {
                            false
                        } else {
                            rt.fullscreen_active = changed.fullscreen;
                            true
                        }
                    };
                    if updated {
                        self.sync_sensor_state();
                    }
                }
            }
            events::HOST_DYNAMIC_COLORS => {
                if let Some(payload) = envelope.payload
                    && let Ok(colors) = serde_json::from_value::<HostDynamicColorsPayload>(payload)
                {
                    {
                        let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
                        rt.dynamic_colors = Some(colors);
                    }
                    self.push_dynamic_palette_if_available();
                }
            }
            events::HOST_DIAGNOSTICS => {
                if let Some(payload) = envelope.payload
                    && let Ok(diag) =
                        serde_json::from_value::<HostPluginDiagnosticsPayload>(payload)
                {
                    let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
                    rt.host_diagnostics = diag;
                }
            }
            events::HOST_WEBVIEW_CONSOLE => {
                if let Some(payload) = envelope.payload
                    && let Ok(msg) = serde_json::from_value::<HostWebViewConsolePayload>(payload)
                {
                    self.logs().info(format!(
                        "[WebView:{}][{}] {}",
                        msg.plugin_id, msg.level, msg.message
                    ));
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
                        self.plugin_backends().broadcast_system_event(
                            events::PANEL_VISIBILITY_CHANGED,
                            json!({
                                "state": changed.state,
                                "visible": changed.state != PanelState::Closed,
                            }),
                        );
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
}
