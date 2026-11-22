//! Debugging, log-ring, and WebView error/crash diagnostic RPC payloads.

use serde::{Deserialize, Serialize};

/// Minimum configurable `debug.inspectUi` timeout in milliseconds.
pub const MIN_INSPECT_UI_TIMEOUT_MS: u64 = 1_000;

/// Default `debug.inspectUi` timeout in milliseconds when omitted.
pub const DEFAULT_INSPECT_UI_TIMEOUT_MS: u64 = 5_000;

/// Maximum configurable `debug.inspectUi` timeout in milliseconds.
pub const MAX_INSPECT_UI_TIMEOUT_MS: u64 = 60_000;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct DebugInspectUiParams {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timeout_ms: Option<u64>,
}

impl DebugInspectUiParams {
    /// Returns the bounded `debug.inspectUi` timeout in milliseconds (`1_000..=60_000`).
    #[must_use]
    pub fn effective_timeout_ms(&self) -> u64 {
        self.timeout_ms.map_or(DEFAULT_INSPECT_UI_TIMEOUT_MS, |ms| {
            ms.clamp(MIN_INSPECT_UI_TIMEOUT_MS, MAX_INSPECT_UI_TIMEOUT_MS)
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DebugSetWebViewDebuggingParams {
    pub enabled: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DebugCdpStatusResult {
    pub ok: bool,
    pub webview_debugging_enabled: bool,
    #[serde(default)]
    pub effective_enabled: bool,
    #[serde(default)]
    pub dev_server_active: bool,
    #[serde(default)]
    pub host_pid: Option<u32>,
    #[serde(default)]
    pub cdp_socket: Option<String>,
    pub restart_recommended: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct DaemonLogsParams {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub since_seq: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub limit: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub level: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DaemonLogsResult {
    pub lines: Vec<String>,
    pub next_seq: u64,
    pub total_dropped: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostWebViewErrorPayload {
    pub plugin_id: String,
    pub kind: String,
    pub code: i32,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub is_main_frame: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostWebViewCrashPayload {
    pub plugin_id: String,
    pub did_crash: bool,
    pub renderer_priority_at_exit: i32,
    pub crash_count: u32,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn debug_inspect_ui_timeout_clamps_to_protocol_bounds() {
        assert_eq!(
            DebugInspectUiParams::default().effective_timeout_ms(),
            DEFAULT_INSPECT_UI_TIMEOUT_MS
        );
        assert_eq!(
            DebugInspectUiParams {
                timeout_ms: Some(100)
            }
            .effective_timeout_ms(),
            MIN_INSPECT_UI_TIMEOUT_MS
        );
        assert_eq!(
            DebugInspectUiParams {
                timeout_ms: Some(900_000)
            }
            .effective_timeout_ms(),
            MAX_INSPECT_UI_TIMEOUT_MS
        );
        assert_eq!(
            DebugInspectUiParams {
                timeout_ms: Some(12_000)
            }
            .effective_timeout_ms(),
            12_000
        );
    }

    #[test]
    fn cdp_status_and_logs_and_webview_events_roundtrip_camel_case() {
        let cdp = DebugCdpStatusResult {
            ok: true,
            webview_debugging_enabled: false,
            effective_enabled: true,
            dev_server_active: true,
            host_pid: Some(4242),
            cdp_socket: Some("webview_devtools_remote_4242".to_owned()),
            restart_recommended: false,
        };
        let encoded_cdp = serde_json::to_string(&cdp).expect("encode cdp");
        assert!(encoded_cdp.contains(r#""effectiveEnabled":true"#));
        assert!(encoded_cdp.contains(r#""devServerActive":true"#));
        assert_eq!(
            serde_json::from_str::<DebugCdpStatusResult>(&encoded_cdp).expect("decode cdp"),
            cdp
        );

        let logs_req = DaemonLogsParams {
            since_seq: Some(10),
            limit: Some(50),
            level: Some("warn".to_owned()),
            source: Some("webview:diag".to_owned()),
        };
        let encoded_req = serde_json::to_string(&logs_req).expect("encode logs req");
        assert!(encoded_req.contains(r#""sinceSeq":10"#));
        assert_eq!(
            serde_json::from_str::<DaemonLogsParams>(&encoded_req).expect("decode logs req"),
            logs_req
        );

        let err = HostWebViewErrorPayload {
            plugin_id: "sideveil.diagnostic".to_owned(),
            kind: "http".to_owned(),
            code: 502,
            description: "Bad Gateway".to_owned(),
            url: "https://sideveil.diagnostic.plugins.sideveil.local/index.html".to_owned(),
            is_main_frame: true,
        };
        let encoded_err = serde_json::to_string(&err).expect("encode webview error");
        assert!(encoded_err.contains(r#""isMainFrame":true"#));
        assert_eq!(
            serde_json::from_str::<HostWebViewErrorPayload>(&encoded_err).expect("decode err"),
            err
        );

        let crash = HostWebViewCrashPayload {
            plugin_id: "com.example.demo".to_owned(),
            did_crash: true,
            renderer_priority_at_exit: 2,
            crash_count: 3,
        };
        let encoded_crash = serde_json::to_string(&crash).expect("encode webview crash");
        assert!(encoded_crash.contains(r#""crashCount":3"#));
        assert_eq!(
            serde_json::from_str::<HostWebViewCrashPayload>(&encoded_crash).expect("decode crash"),
            crash
        );
    }
}
