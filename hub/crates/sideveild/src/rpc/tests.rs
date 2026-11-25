//! Unit tests for JSON-RPC 2.0 request dispatching and external plugin ACLs.

use std::fs;
use std::path::PathBuf;
use std::sync::{Arc, PoisonError};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::json;
use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use sideveil_protocol::rpc::{RpcRequest, RpcResponse, error_codes, methods};
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
        "sideveil-rpc-{label}-{}-{nanos}",
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
async fn dispatches_core_rpc_methods_and_enforces_external_plugin_acl() {
    let (dir, state) = make_test_state("core");

    let ping = dispatch_rpc_request(
        &state,
        RpcRequest::new("1", methods::DAEMON_PING, None),
        RpcCaller::Ctl,
    )
    .await;
    assert!(ping.error.is_none());
    assert_eq!(
        ping.result.as_ref().and_then(|v| v.get("pong")),
        Some(&json!(true))
    );

    let patched = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "2",
            methods::CONFIG_PATCH_APPEARANCE,
            Some(json!({ "colorMode": "light", "cornerRadiusDp": 30 })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    assert!(patched.error.is_none());
    assert_eq!(state.config_store().current().panel.color_mode, "light");
    assert_eq!(state.config_store().current().panel.corner_radius_dp, 30);

    // External plugin cannot call host.restart.
    let mut ext_req = RpcRequest::new("3", methods::HOST_RESTART, None);
    ext_req.plugin_id = Some("com.example.external".to_owned());
    let denied = dispatch_rpc_request(&state, ext_req, RpcCaller::HostWebView).await;
    assert_eq!(
        denied.error.as_ref().map(|e| e.code),
        Some(error_codes::PERMISSION_DENIED)
    );

    // Unstamped HostWebView request must also be denied privileged methods.
    let unstamped_req = RpcRequest::new("3b", methods::HOST_RESTART, None);
    let unstamped_denied =
        dispatch_rpc_request(&state, unstamped_req, RpcCaller::HostWebView).await;
    assert_eq!(
        unstamped_denied.error.as_ref().map(|e| e.code),
        Some(error_codes::PERMISSION_DENIED)
    );

    let missing = dispatch_rpc_request(
        &state,
        RpcRequest::new("4", "unknown.method", None),
        RpcCaller::Ctl,
    )
    .await;
    assert_eq!(
        missing.error.as_ref().map(|e| e.code),
        Some(error_codes::METHOD_NOT_FOUND)
    );

    let _ = fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn reverse_host_rpc_roundtrip_timeout_and_disconnect_cancel() {
    let (dir, state) = make_test_state("reverse");

    // 1. Offline host fails immediately with BACKEND_OFFLINE.
    let offline = dispatch_rpc_request(
        &state,
        RpcRequest::new("r0", methods::DEBUG_INSPECT_UI, None),
        RpcCaller::Ctl,
    )
    .await;
    assert_eq!(
        offline.error.as_ref().map(|e| e.code),
        Some(error_codes::BACKEND_OFFLINE)
    );

    // 2. Roundtrip with connected host session.
    let (tx, mut rx) = outbound_queue::channel();
    let session_id = state.register_host_session(tx);
    // Drain initial config.updated, network.contextChanged, and plugins.updated events.
    let _ = rx.recv().await;
    let _ = rx.recv().await;
    let _ = rx.recv().await;

    let st_reply = Arc::clone(&state);
    let responder = tokio::spawn(async move {
        let raw = rx.recv().await.expect("host should receive RPC request");
        let req: RpcRequest = serde_json::from_str(&raw).expect("valid RPC request");
        assert_eq!(req.method, methods::DEBUG_INSPECT_UI);
        let ok = st_reply.resolve_host_rpc_response(RpcResponse::ok(
            req.id,
            json!({ "display": { "refreshHz": 120.0 } }),
        ));
        assert!(ok);
        rx
    });

    let inspect_resp = dispatch_rpc_request(
        &state,
        RpcRequest::new("r1", methods::DEBUG_INSPECT_UI, None),
        RpcCaller::Ctl,
    )
    .await;
    assert!(inspect_resp.error.is_none());
    assert_eq!(
        inspect_resp
            .result
            .as_ref()
            .and_then(|v| v.pointer("/display/refreshHz")),
        Some(&json!(120.0))
    );
    let mut rx = responder.await.expect("responder join");

    // 3. Timeout cleans up pending entry and returns BACKEND_TIMEOUT.
    let timed_out = state
        .invoke_host(methods::DEBUG_INSPECT_UI, None, Duration::from_millis(40))
        .await;
    assert_eq!(
        timed_out.err().map(|e| e.0),
        Some(error_codes::BACKEND_TIMEOUT)
    );
    let _ = rx.recv().await;

    // 4. Host disconnect cancels in-flight RPC immediately with BACKEND_OFFLINE.
    let st_wait = Arc::clone(&state);
    let in_flight = tokio::spawn(async move {
        st_wait
            .invoke_host(methods::DEBUG_INSPECT_UI, None, Duration::from_secs(5))
            .await
    });
    let _ = rx.recv().await;
    state.clear_host_session(session_id);
    let cancelled = in_flight.await.expect("in_flight join");
    assert_eq!(
        cancelled.err().map(|e| e.0),
        Some(error_codes::BACKEND_OFFLINE)
    );

    let _ = fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn debug_rpcs_reject_external_plugins_and_toggle_cdp_state() {
    let (dir, state) = make_test_state("debug-acl");

    for method in [
        methods::DEBUG_INSPECT_UI,
        methods::DEBUG_SET_WEBVIEW_DEBUGGING,
    ] {
        let mut ext_req = RpcRequest::new("ext", method, Some(json!({ "enabled": true })));
        ext_req.plugin_id = Some("com.example.untrusted".to_owned());
        let denied = dispatch_rpc_request(&state, ext_req, RpcCaller::HostWebView).await;
        assert_eq!(
            denied.error.as_ref().map(|e| e.code),
            Some(error_codes::PERMISSION_DENIED)
        );
    }

    {
        let mut rt = state
            .runtime
            .write()
            .unwrap_or_else(PoisonError::into_inner);
        rt.host_pid = Some(4242);
    }

    let enabled_resp = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "d1",
            methods::DEBUG_SET_WEBVIEW_DEBUGGING,
            Some(json!({ "enabled": true })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    assert!(enabled_resp.error.is_none());
    let enabled_val = enabled_resp.result.expect("cdp enable result");
    assert_eq!(
        enabled_val.get("webviewDebuggingEnabled"),
        Some(&json!(true))
    );
    assert_eq!(
        enabled_val.get("cdpSocket"),
        Some(&json!("webview_devtools_remote_4242"))
    );
    assert_eq!(enabled_val.get("restartRecommended"), Some(&json!(false)));

    let disabled_resp = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "d2",
            methods::DEBUG_SET_WEBVIEW_DEBUGGING,
            Some(json!({ "enabled": false })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    assert!(disabled_resp.error.is_none());
    let disabled_val = disabled_resp.result.expect("cdp disable result");
    assert_eq!(
        disabled_val.get("webviewDebuggingEnabled"),
        Some(&json!(false))
    );
    assert_eq!(disabled_val.get("cdpSocket"), Some(&json!(null)));
    assert_eq!(disabled_val.get("restartRecommended"), Some(&json!(true)));

    let _ = fs::remove_dir_all(&dir);
}
