//! Unit tests for debugging RPCs, CDP status, LogRing queries, gesture stats, and WebView events.

use std::fs;
use std::path::PathBuf;
use std::sync::{Arc, PoisonError};
use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::json;
use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use sideveil_protocol::plugin::BUILTIN_DIAG_PLUGIN_ID;
use sideveil_protocol::rpc::{RpcRequest, RpcResponse, error_codes, methods};
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

    // Register mock host session and drain initial snapshot events.
    let (tx, mut rx) = outbound_queue::channel();
    let _session_id = state.register_host_session(tx);
    let _ = rx.recv().await;
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
