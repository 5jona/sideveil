//! Unit tests for debugging RPCs, CDP status, LogRing queries, gesture stats, and WebView events.

use std::fs;
use std::path::PathBuf;
use std::sync::{Arc, PoisonError};
use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::json;
use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use sideveil_protocol::plugin::BUILTIN_DIAG_PLUGIN_ID;
use sideveil_protocol::rpc::{
    HostEventEnvelope, RpcRequest, RpcResponse, error_codes, events, methods,
};
use sideveil_protocol::sensor::{DebugGestureStats, SensorToHub};
use tokio::sync::mpsc;

use super::{RpcCaller, dispatch_rpc_request};
use crate::config_store::ConfigStore;
use crate::log_ring::LogRing;
use crate::outbound_queue;
use crate::plugin_registry::PluginRegistry;
use crate::state::HubState;

fn make_test_state(label: &str) -> (PathBuf, Arc<HubState>) {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let dir = std::env::temp_dir().join(format!(
        "sideveil-rpc-debug-{label}-{}-{nanos}",
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
async fn cdp_status_reflects_dev_server_override_as_single_source_of_truth() {
    let (dir, state) = make_test_state("cdp-dev-server");
    {
        let mut rt = state
            .runtime
            .write()
            .unwrap_or_else(PoisonError::into_inner);
        rt.host_pid = Some(5150);
    }

    let initial = dispatch_rpc_request(
        &state,
        RpcRequest::new("c1", methods::DEBUG_CDP_STATUS, None),
        RpcCaller::Ctl,
    )
    .await;
    let initial_val = initial.result.expect("cdp status result");
    assert_eq!(initial_val.get("effectiveEnabled"), Some(&json!(false)));
    assert_eq!(initial_val.get("devServerActive"), Some(&json!(false)));
    assert_eq!(initial_val.get("cdpSocket"), Some(&json!(null)));

    // Setting a loopback dev server URL on the built-in plugin activates effective CDP.
    state
        .plugin_registry()
        .set_dev_url(BUILTIN_DIAG_PLUGIN_ID, Some("http://127.0.0.1:5175"), false)
        .expect("set dev url");

    let active = dispatch_rpc_request(
        &state,
        RpcRequest::new("c2", methods::DEBUG_CDP_STATUS, None),
        RpcCaller::Ctl,
    )
    .await;
    let active_val = active.result.expect("cdp active result");
    assert_eq!(
        active_val.get("webviewDebuggingEnabled"),
        Some(&json!(false))
    );
    assert_eq!(active_val.get("devServerActive"), Some(&json!(true)));
    assert_eq!(active_val.get("effectiveEnabled"), Some(&json!(true)));
    assert_eq!(
        active_val.get("cdpSocket"),
        Some(&json!("webview_devtools_remote_5150"))
    );

    // Turning config flag on then off while devServer is active keeps effectiveEnabled=true
    // so restartRecommended remains false.
    let _ = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "c3",
            methods::DEBUG_SET_WEBVIEW_DEBUGGING,
            Some(json!({ "enabled": true })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    let off = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "c4",
            methods::DEBUG_SET_WEBVIEW_DEBUGGING,
            Some(json!({ "enabled": false })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    let off_val = off.result.expect("cdp off result");
    assert_eq!(off_val.get("effectiveEnabled"), Some(&json!(true)));
    assert_eq!(off_val.get("restartRecommended"), Some(&json!(false)));

    let _ = fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn inspect_ui_propagates_timeout_attaches_gesture_stats_and_resets_frames() {
    let (dir, state) = make_test_state("inspect-timeout");

    // Seed sensor gesture stats.
    state.on_sensor_message(SensorToHub::GestureStatsUpdated {
        stats: DebugGestureStats {
            edge_swipes_triggered: 6,
            edge_swipes_rejected: 2,
            hotzone_misses: 15,
            pilfer_invocations: 8,
            last_reject_reason: Some("vertical_scroll".to_owned()),
            last_event_timestamp_ms: 1_700_000_123_000,
        },
    });

    // daemon.status exposes gestureStats.
    let status = dispatch_rpc_request(
        &state,
        RpcRequest::new("s1", methods::DAEMON_STATUS, None),
        RpcCaller::Ctl,
    )
    .await;
    assert_eq!(
        status
            .result
            .as_ref()
            .and_then(|v| v.pointer("/gestureStats/edgeSwipesTriggered")),
        Some(&json!(6))
    );

    // Register mock host session.
    let (tx, mut rx) = outbound_queue::channel();
    let _session_id = state.register_host_session(tx);
    let _ = rx.recv().await;
    let _ = rx.recv().await;

    let st_reply = Arc::clone(&state);
    let responder = tokio::spawn(async move {
        let raw_inspect = rx.recv().await.expect("inspect request");
        let req_inspect: RpcRequest = serde_json::from_str(&raw_inspect).expect("decode inspect");
        assert_eq!(req_inspect.method, methods::DEBUG_INSPECT_UI);
        assert_eq!(req_inspect.params, Some(json!({ "timeoutMs": 60_000 })));
        assert!(
            st_reply.resolve_host_rpc_response(RpcResponse::ok(
                req_inspect.id,
                json!({ "hostPid": 777 }),
            ))
        );

        let raw_reset = rx.recv().await.expect("reset frames request");
        let req_reset: RpcRequest = serde_json::from_str(&raw_reset).expect("decode reset");
        assert_eq!(req_reset.method, methods::DEBUG_RESET_FRAME_METRICS);
        assert!(
            st_reply
                .resolve_host_rpc_response(RpcResponse::ok(req_reset.id, json!({ "ok": true }),))
        );
    });

    // Request with out-of-range timeout is clamped to 60_000.
    let inspect = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "i1",
            methods::DEBUG_INSPECT_UI,
            Some(json!({ "timeoutMs": 999_999 })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    assert!(inspect.error.is_none());
    let inspect_val = inspect.result.expect("inspect result");
    assert_eq!(inspect_val.get("hostPid"), Some(&json!(777)));
    assert_eq!(
        inspect_val.pointer("/gestureStats/lastRejectReason"),
        Some(&json!("vertical_scroll"))
    );

    // Built-in diagnostic WebView can call debug.resetFrameMetrics.
    let mut diag_reset = RpcRequest::new("rf1", methods::DEBUG_RESET_FRAME_METRICS, None);
    diag_reset.plugin_id = Some(BUILTIN_DIAG_PLUGIN_ID.to_owned());
    let reset_resp = dispatch_rpc_request(&state, diag_reset, RpcCaller::HostWebView).await;
    assert_eq!(reset_resp.result, Some(json!({ "ok": true })));

    responder.await.expect("responder join");

    // External plugin is denied debug.resetFrameMetrics, debug.cdpStatus, and daemon.logsClear.
    for method in [
        methods::DEBUG_RESET_FRAME_METRICS,
        methods::DEBUG_CDP_STATUS,
        methods::DAEMON_LOGS_CLEAR,
    ] {
        let mut ext = RpcRequest::new("ext", method, None);
        ext.plugin_id = Some("com.example.untrusted".to_owned());
        let denied = dispatch_rpc_request(&state, ext, RpcCaller::HostWebView).await;
        assert_eq!(
            denied.error.as_ref().map(|e| e.code),
            Some(error_codes::PERMISSION_DENIED)
        );
    }

    let _ = fs::remove_dir_all(&dir);
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
