//! Unit tests for WebView console/error/crash event ingestion and log queries.

use std::fs;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::json;
use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use sideveil_protocol::plugin::BUILTIN_DIAG_PLUGIN_ID;
use sideveil_protocol::rpc::{HostEventEnvelope, RpcRequest, events, methods};
use tokio::sync::mpsc;

use super::{RpcCaller, dispatch_rpc_request};
use crate::config_store::ConfigStore;
use crate::log_ring::LogRing;
use crate::plugin_registry::PluginRegistry;
use crate::state::HubState;

fn make_test_state(label: &str) -> (PathBuf, Arc<HubState>) {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let dir = std::env::temp_dir().join(format!(
        "sideveil-rpc-debug-logs-{label}-{}-{nanos}",
        std::process::id()
    ));
    let _ = fs::create_dir_all(&dir);
    let logs = Arc::new(LogRing::new());
    let store = ConfigStore::open(dir.join("config.json"), Arc::clone(&logs));
    let registry = PluginRegistry::open(
        dir.join("plugins"),
        dir.join("plugins.json"),
        Arc::clone(&logs),
    );
    let (wake_tx, _wake_rx) = mpsc::unbounded_channel();
    let state = HubState::new(
        DEFAULT_HOST_PACKAGE.to_owned(),
        store,
        registry,
        dir.join("data"),
        logs,
        wake_tx,
    );
    (dir, state)
}

#[tokio::test]
async fn ingests_webview_console_error_crash_events_and_serves_log_queries() {
    let (dir, state) = make_test_state("webview-events");

    state.on_host_event(HostEventEnvelope {
        event: events::HOST_WEBVIEW_CONSOLE.to_owned(),
        payload: Some(json!({
            "pluginId": "sideveil.diagnostic",
            "level": "ERROR",
            "message": "ReferenceError: boom",
            "sourceId": "https://sideveil.diagnostic.plugins.sideveil.local/assets/app.js?token=secret",
            "lineNumber": 42
        })),
    });
    state.on_host_event(HostEventEnvelope {
        event: events::HOST_WEBVIEW_ERROR.to_owned(),
        payload: Some(json!({
            "pluginId": "com.example.demo",
            "kind": "loopback",
            "code": 502,
            "description": "ConnectException: refused",
            "url": "http://127.0.0.1:5175/index.html?secret=1",
            "isMainFrame": true
        })),
    });
    state.on_host_event(HostEventEnvelope {
        event: events::HOST_WEBVIEW_CRASH.to_owned(),
        payload: Some(json!({
            "pluginId": "com.example.demo",
            "didCrash": true,
            "rendererPriorityAtExit": 2,
            "crashCount": 1
        })),
    });

    let err_logs = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "l1",
            methods::DAEMON_LOGS,
            Some(json!({ "level": "error", "source": "webview:" })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    let val = err_logs.result.expect("logs result");
    let lines = val
        .get("lines")
        .and_then(|v| v.as_array())
        .expect("lines array");
    assert_eq!(lines.len(), 3);
    let console_line = lines[0].as_str().expect("console line");
    assert!(console_line.contains("[E]"));
    assert!(console_line.contains("assets/app.js:42"));
    assert!(!console_line.contains("token=secret"));

    let fetch_line = lines[1].as_str().expect("fetch error line");
    assert!(fetch_line.contains("[error:loopback]"));
    assert!(!fetch_line.contains("secret=1"));

    let crash_line = lines[2].as_str().expect("crash line");
    assert!(crash_line.contains("render process crashed"));
    assert!(crash_line.contains("crashCount=1"));

    let audit_entries = state.audit().recent();
    assert!(
        audit_entries
            .iter()
            .any(|e| e.plugin_id == "com.example.demo"
                && e.action == "webview.crash"
                && !e.allowed)
    );

    // Built-in diagnostic panel can clear logs via daemon.logsClear.
    let mut clear_req = RpcRequest::new("l2", methods::DAEMON_LOGS_CLEAR, None);
    clear_req.plugin_id = Some(BUILTIN_DIAG_PLUGIN_ID.to_owned());
    let cleared = dispatch_rpc_request(&state, clear_req, RpcCaller::HostWebView).await;
    assert_eq!(
        cleared.result.as_ref().and_then(|v| v.get("ok")),
        Some(&json!(true))
    );

    let after = dispatch_rpc_request(
        &state,
        RpcRequest::new("l3", methods::DAEMON_LOGS, None),
        RpcCaller::Ctl,
    )
    .await;
    assert_eq!(
        after
            .result
            .as_ref()
            .and_then(|v| v.get("lines"))
            .and_then(|v| v.as_array())
            .map(Vec::len),
        Some(0)
    );

    let _ = fs::remove_dir_all(&dir);
}
