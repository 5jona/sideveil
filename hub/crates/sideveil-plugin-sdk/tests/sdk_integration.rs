//! End-to-end integration tests for `sideveil-plugin-sdk` against a temporary mock UDS Hub.

use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use serde_json::{Value, json};
use sideveil_plugin_sdk::codec::{
    DEFAULT_MAX_FRAME_BYTES, ndjson_framed, recv_json_frame, send_json_frame,
};
use sideveil_plugin_sdk::{
    CancellationToken, PluginBackendConfig, PluginBackendRunner, PluginRpcContext, PluginRpcError,
    PluginRpcHandler,
};
use sideveil_protocol::plugin::PluginBackendEvent;
use sideveil_protocol::rpc::{RpcRequest, RpcResponse, error_codes, events};
use sideveil_protocol::uds::{BackendOutboundFrame, UdsClientHello, UdsHandshakeAck};
use tokio::net::{UnixListener, UnixStream};
use tokio::sync::{Notify, mpsc};
use tokio_util::codec::{Framed, LinesCodec};

struct TestHandler {
    connects: Arc<AtomicUsize>,
    disconnects: Arc<AtomicUsize>,
    connected_notify: Arc<Notify>,
    sys_events_tx: mpsc::UnboundedSender<(String, Value)>,
}

impl PluginRpcHandler for TestHandler {
    async fn handle_rpc(
        &self,
        req: RpcRequest,
        ctx: PluginRpcContext,
    ) -> Result<Value, PluginRpcError> {
        match req.method.as_str() {
            "echo" => Ok(json!({
                "pluginId": ctx.plugin_id(),
                "params": req.params,
                "connected": ctx.handle().is_connected(),
            })),
            "fail" => {
                Err(PluginRpcError::invalid_params("bad input")
                    .with_data(json!({ "field": "count" })))
            }
            other => Err(PluginRpcError::method_not_found(other)),
        }
    }

    async fn on_system_event(&self, event: String, payload: Value, _ctx: PluginRpcContext) {
        let _ = self.sys_events_tx.send((event, payload));
    }

    async fn on_connected(&self, _ctx: PluginRpcContext) {
        self.connects.fetch_add(1, Ordering::SeqCst);
        self.connected_notify.notify_waiters();
    }

    async fn on_disconnected(&self, _plugin_id: String) {
        self.disconnects.fetch_add(1, Ordering::SeqCst);
    }
}

fn temp_socket_path(tag: &str) -> PathBuf {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .expect("system clock after epoch")
        .as_nanos();
    std::env::temp_dir().join(format!(
        "sideveil-sdk-{tag}-{}-{nanos}.sock",
        std::process::id()
    ))
}

async fn accept_and_handshake(
    listener: &UnixListener,
    expected_plugin_id: &str,
    timeout: Duration,
) -> Framed<UnixStream, LinesCodec> {
    let (stream, _) = tokio::time::timeout(timeout, listener.accept())
        .await
        .expect("accept timeout")
        .expect("accept client");
    let mut framed = ndjson_framed(stream, DEFAULT_MAX_FRAME_BYTES);
    let hello: UdsClientHello = recv_json_frame(&mut framed, DEFAULT_MAX_FRAME_BYTES)
        .await
        .expect("read hello")
        .expect("hello frame");
    assert_eq!(
        hello,
        UdsClientHello::PluginBackend {
            plugin_id: expected_plugin_id.to_owned(),
            package_name: None,
        }
    );
    send_json_frame(
        &mut framed,
        &UdsHandshakeAck::accepted(),
        DEFAULT_MAX_FRAME_BYTES,
    )
    .await
    .expect("send ack");
    framed
}

#[tokio::test]
async fn handshake_rpc_events_system_events_and_graceful_shutdown() {
    let sock_path = temp_socket_path("e2e");
    let _ = std::fs::remove_file(&sock_path);
    let listener = UnixListener::bind(&sock_path).expect("bind mock hub socket");

    let connects = Arc::new(AtomicUsize::new(0));
    let disconnects = Arc::new(AtomicUsize::new(0));
    let connected_notify = Arc::new(Notify::new());
    let (sys_tx, mut sys_rx) = mpsc::unbounded_channel();

    let cancel = CancellationToken::new();
    let config = PluginBackendConfig::new("example.sdk-test")
        .with_socket_path(&sock_path)
        .with_backoff(Duration::from_millis(20), Duration::from_millis(120));
    let (handle, runner) =
        PluginBackendRunner::new(config, cancel.clone()).expect("build sdk runner");

    let runner_task = tokio::spawn(runner.run(TestHandler {
        connects: Arc::clone(&connects),
        disconnects: Arc::clone(&disconnects),
        connected_notify: Arc::clone(&connected_notify),
        sys_events_tx: sys_tx,
    }));

    let mut framed =
        accept_and_handshake(&listener, "example.sdk-test", Duration::from_secs(2)).await;
    tokio::time::timeout(Duration::from_secs(2), connected_notify.notified())
        .await
        .expect("connected notification");
    assert!(handle.is_connected());
    assert_eq!(connects.load(Ordering::SeqCst), 1);

    // RPC success + structured error
    send_json_frame(
        &mut framed,
        &BackendOutboundFrame::Rpc(RpcRequest::new(
            "rpc-1",
            "echo",
            Some(json!({ "ping": true })),
        )),
        DEFAULT_MAX_FRAME_BYTES,
    )
    .await
    .expect("send echo rpc");
    let ok_resp: RpcResponse = recv_json_frame(&mut framed, DEFAULT_MAX_FRAME_BYTES)
        .await
        .expect("read echo resp")
        .expect("some resp");
    assert_eq!(ok_resp.id, "rpc-1");
    assert_eq!(
        ok_resp.result,
        Some(json!({
            "pluginId": "example.sdk-test",
            "params": { "ping": true },
            "connected": true,
        }))
    );

    send_json_frame(
        &mut framed,
        &BackendOutboundFrame::Rpc(RpcRequest::new("rpc-2", "fail", None)),
        DEFAULT_MAX_FRAME_BYTES,
    )
    .await
    .expect("send fail rpc");
    let err_resp: RpcResponse = recv_json_frame(&mut framed, DEFAULT_MAX_FRAME_BYTES)
        .await
        .expect("read fail resp")
        .expect("some resp");
    let rpc_err = err_resp.error.expect("rpc error payload");
    assert_eq!(rpc_err.code, error_codes::INVALID_PARAMS);
    assert_eq!(rpc_err.message, "bad input");

    // Reliable & ephemeral emit_event
    handle
        .emit_event("state.updated", Some(json!({ "ready": true })), false)
        .await
        .expect("emit reliable event");
    handle
        .emit_event("metrics.tick", Some(json!({ "cpu": 42.5 })), true)
        .await
        .expect("emit ephemeral event");
    let ev1: PluginBackendEvent = recv_json_frame(&mut framed, DEFAULT_MAX_FRAME_BYTES)
        .await
        .expect("recv reliable")
        .expect("some ev1");
    let ev2: PluginBackendEvent = recv_json_frame(&mut framed, DEFAULT_MAX_FRAME_BYTES)
        .await
        .expect("recv ephemeral")
        .expect("some ev2");
    assert_eq!(
        (ev1.event.as_str(), ev1.ephemeral),
        ("state.updated", false)
    );
    assert_eq!((ev2.event.as_str(), ev2.ephemeral), ("metrics.tick", true));

    // Host-pushed system event delivery
    send_json_frame(
        &mut framed,
        &BackendOutboundFrame::SystemEvent {
            event: events::APPEARANCE_CHANGED.to_owned(),
            payload: json!({ "isDark": false }),
        },
        DEFAULT_MAX_FRAME_BYTES,
    )
    .await
    .expect("send system event");
    let (ev_name, ev_payload) = tokio::time::timeout(Duration::from_secs(2), sys_rx.recv())
        .await
        .expect("system event timeout")
        .expect("system event item");
    assert_eq!(ev_name, events::APPEARANCE_CHANGED);
    assert_eq!(ev_payload, json!({ "isDark": false }));

    // Graceful CancellationToken shutdown
    handle.shutdown();
    tokio::time::timeout(Duration::from_secs(2), runner_task)
        .await
        .expect("runner shutdown timeout")
        .expect("runner join")
        .expect("runner ok");
    assert!(!handle.is_connected());
    assert_eq!(disconnects.load(Ordering::SeqCst), 1);
    let _ = std::fs::remove_file(&sock_path);
}

#[tokio::test]
async fn reconnects_with_backoff_and_resets_after_healthy_session() {
    let sock_path = temp_socket_path("reconnect");
    let _ = std::fs::remove_file(&sock_path);

    let connects = Arc::new(AtomicUsize::new(0));
    let disconnects = Arc::new(AtomicUsize::new(0));
    let connected_notify = Arc::new(Notify::new());
    let (sys_tx, _sys_rx) = mpsc::unbounded_channel();

    let cancel = CancellationToken::new();
    let config = PluginBackendConfig::new("example.reconnect")
        .with_socket_path(&sock_path)
        .with_backoff(Duration::from_millis(25), Duration::from_millis(200))
        .with_healthy_session_threshold(Duration::from_millis(40));
    let (handle, runner) = PluginBackendRunner::new(config, cancel.clone()).expect("build runner");

    let runner_task = tokio::spawn(runner.run(TestHandler {
        connects: Arc::clone(&connects),
        disconnects: Arc::clone(&disconnects),
        connected_notify,
        sys_events_tx: sys_tx,
    }));

    tokio::time::sleep(Duration::from_millis(75)).await;
    assert!(!handle.is_connected());

    let listener = UnixListener::bind(&sock_path).expect("bind mock hub");
    let f1 = accept_and_handshake(&listener, "example.reconnect", Duration::from_secs(2)).await;

    tokio::time::sleep(Duration::from_millis(60)).await;
    assert!(handle.is_connected());
    let drop_at = Instant::now();
    drop(f1);

    let _f2 =
        accept_and_handshake(&listener, "example.reconnect", Duration::from_millis(500)).await;
    let reconnect_elapsed = drop_at.elapsed();
    assert!(
        reconnect_elapsed < Duration::from_millis(250),
        "expected fast reconnect after healthy session reset, got {reconnect_elapsed:?}"
    );

    tokio::time::timeout(Duration::from_secs(2), async {
        while connects.load(Ordering::SeqCst) < 2 {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    })
    .await
    .expect("wait for second connection");

    cancel.cancel();
    tokio::time::timeout(Duration::from_secs(2), runner_task)
        .await
        .expect("shutdown timeout")
        .expect("join")
        .expect("ok");
    assert_eq!(disconnects.load(Ordering::SeqCst), 2);
    let _ = std::fs::remove_file(&sock_path);
}
