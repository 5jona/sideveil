//! Inbound Host WebSocket event handling for `HubState`.

use std::sync::PoisonError;

use serde_json::json;
use sideveil_protocol::config::PanelState;
use sideveil_protocol::plugin::{HostPluginDiagnosticsPayload, PluginSelectParams};
use sideveil_protocol::rpc::{
    HostEventEnvelope, HostFullscreenChangedPayload, HostHelloPayload, HostWebViewConsolePayload,
    HostWebViewCrashPayload, HostWebViewErrorPayload, PanelStateChangedPayload, events,
};
use sideveil_protocol::theme::HostDynamicColorsPayload;

use crate::log_ring::LogLevel;
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
                    self.record_webview_console(&msg);
                }
            }
            events::HOST_WEBVIEW_ERROR => {
                if let Some(payload) = envelope.payload
                    && let Ok(err) = serde_json::from_value::<HostWebViewErrorPayload>(payload)
                {
                    self.record_webview_error(&err);
                }
            }
            events::HOST_WEBVIEW_CRASH => {
                if let Some(payload) = envelope.payload
                    && let Ok(crash) = serde_json::from_value::<HostWebViewCrashPayload>(payload)
                {
                    self.record_webview_crash(&crash);
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

    fn record_webview_console(&self, msg: &HostWebViewConsolePayload) {
        let level = match msg.level.trim().to_ascii_uppercase().as_str() {
            "ERROR" => LogLevel::Error,
            "WARNING" | "WARN" => LogLevel::Warn,
            _ => LogLevel::Info,
        };
        let source = format!("webview:{}", msg.plugin_id);
        let formatted = match sanitize_source_location(&msg.source_id, msg.line_number) {
            Some(loc) => format!(
                "[WebView:{}][{}] ({loc}) {}",
                msg.plugin_id, msg.level, msg.message
            ),
            None => format!("[WebView:{}][{}] {}", msg.plugin_id, msg.level, msg.message),
        };
        self.logs().log_source(level, source, formatted);
    }

    fn record_webview_error(&self, err: &HostWebViewErrorPayload) {
        let level = if err.is_main_frame {
            LogLevel::Error
        } else {
            LogLevel::Warn
        };
        let source = format!("webview:{}", err.plugin_id);
        let url = sanitize_url_without_query(&err.url);
        self.logs().log_source(
            level,
            source,
            format!(
                "[WebView:{}][error:{}] code={} mainFrame={} url={} {}",
                err.plugin_id, err.kind, err.code, err.is_main_frame, url, err.description
            ),
        );
    }

    fn record_webview_crash(&self, crash: &HostWebViewCrashPayload) {
        let source = format!("webview:{}", crash.plugin_id);
        let detail = format!(
            "didCrash={} priority={} crashCount={}",
            crash.did_crash, crash.renderer_priority_at_exit, crash.crash_count
        );
        self.logs().log_source(
            LogLevel::Error,
            source,
            format!(
                "[WebView:{}] render process crashed {detail}",
                crash.plugin_id
            ),
        );
        self.audit()
            .record(crash.plugin_id.clone(), "webview.crash", false, detail);
    }
}

fn sanitize_url_without_query(raw: &str) -> &str {
    raw.trim().split(['?', '#']).next().unwrap_or_default()
}

fn sanitize_source_location(source_id: &str, line_number: i32) -> Option<String> {
    let cleaned = sanitize_url_without_query(source_id);
    if cleaned.is_empty() {
        return None;
    }
    let path = cleaned
        .strip_prefix("https://")
        .or_else(|| cleaned.strip_prefix("http://"))
        .map_or(cleaned, |after_scheme| {
            after_scheme
                .split_once('/')
                .map_or(after_scheme, |(_, rest)| rest)
        })
        .trim_start_matches('/');
    let concise = if path.is_empty() { cleaned } else { path };
    if line_number > 0 {
        Some(format!("{concise}:{line_number}"))
    } else {
        Some(concise.to_owned())
    }
}
