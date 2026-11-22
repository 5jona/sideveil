//! Unit tests for `PluginBackends` RPC multiplexing and Hub -> Backend system notifications.

use std::sync::Arc;
use std::time::Duration;

use serde_json::json;
use sideveil_protocol::rpc::{RpcResponse, error_codes};

use super::*;

#[tokio::test]
async fn invoke_honors_custom_timeout_and_resolves_responses() {
    let backends = Arc::new(PluginBackends::new());
    let (session_id, mut rx) = backends.register("demo.backend", true);

    let b_clone = Arc::clone(&backends);
    let responder = tokio::spawn(async move {
        if let Some(BackendOutboundFrame::Rpc(req)) = rx.recv().await {
            assert_eq!(req.method, "fast.ping");
            b_clone.complete_response(
                "demo.backend",
                session_id,
                RpcResponse::ok(req.id, json!({ "pong": true })),
            );
        }
        // Second request intentionally left unanswered to verify custom timeout.
        let _slow = rx.recv().await;
        tokio::time::sleep(Duration::from_millis(200)).await;
    });

    let ok = backends
        .invoke(
            "demo.backend",
            "fast.ping".to_owned(),
            None,
            Duration::from_secs(2),
        )
        .await
        .expect("fast rpc succeeds");
    assert_eq!(ok["pong"], true);

    let timed_out = backends
        .invoke(
            "demo.backend",
            "slow.op".to_owned(),
            None,
            Duration::from_millis(40),
        )
        .await
        .expect_err("slow rpc should time out");
    assert_eq!(timed_out.0, error_codes::BACKEND_TIMEOUT);
    assert!(timed_out.1.contains("40ms"));

    responder.abort();
}

#[tokio::test]
async fn broadcast_system_event_reaches_native_only_and_never_blocks() {
    let backends = Arc::new(PluginBackends::new());
    let (_native_id, mut native_rx) = backends.register("native.plugin", true);
    let (_app_id, mut app_rx) = backends.register("app.plugin", false);

    backends.broadcast_system_event("network.contextChanged", json!({ "transport": "wifi" }));

    let frame = native_rx
        .try_recv()
        .expect("native backend receives system event");
    let encoded = serde_json::to_value(&frame).expect("serialize system event");
    assert_eq!(encoded["event"], "network.contextChanged");
    assert_eq!(encoded["payload"]["transport"], "wifi");

    assert!(
        app_rx.try_recv().is_err(),
        "uds-app backend must not receive uds-native system events"
    );

    // Saturate channel capacity; try_send must never block or panic.
    for i in 0..(BACKEND_OUTBOUND_CAPACITY + 16) {
        backends.broadcast_system_event("appearance.changed", json!({ "seq": i }));
    }
}
