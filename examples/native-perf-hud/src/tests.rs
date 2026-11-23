use std::time::Duration;

use serde_json::json;
use sideveil_plugin_sdk::codec::{
    DEFAULT_MAX_FRAME_BYTES, ndjson_framed, recv_json_frame, send_json_frame,
};
use sideveil_plugin_sdk::{CancellationToken, PluginBackendConfig, PluginBackendRunner};
use sideveil_protocol::plugin::PluginBackendEvent;
use sideveil_protocol::rpc::{RpcRequest, RpcResponse, events, methods};
use sideveil_protocol::uds::{BackendOutboundFrame, UdsClientHello, UdsHandshakeAck};
use tokio::net::UnixListener;

use super::*;

#[tokio::test]
async fn sdk_session_handles_rpc_appearance_and_exits_on_cancel() {
    let sock_path =
        std::env::temp_dir().join(format!("sideveil-perf-hud-sdk-{}.sock", std::process::id()));
    let _ = std::fs::remove_file(&sock_path);
    let listener = UnixListener::bind(&sock_path).expect("bind unix listener");

    let config = PluginBackendConfig::new(PLUGIN_ID)
        .with_socket_path(&sock_path)
        .with_backoff(Duration::from_millis(20), Duration::from_millis(80));
    let cancel = CancellationToken::new();
    let (handle, runner) = PluginBackendRunner::new(config, cancel.clone()).expect("create runner");
    let state = Arc::new(HudState::new());
    let handler = PerfHudHandler {
        state: Arc::clone(&state),
    };
    let sampler = tokio::spawn(run_sampler_loop(
        handle.clone(),
        Arc::clone(&state),
        cancel.clone(),
    ));
    let runner_task = tokio::spawn(runner.run(handler));

    let (conn, _) = listener.accept().await.expect("accept plugin");
    let mut framed = ndjson_framed(conn, DEFAULT_MAX_FRAME_BYTES);
    let hello: UdsClientHello = recv_json_frame(&mut framed, DEFAULT_MAX_FRAME_BYTES)
        .await
        .expect("read hello")
        .expect("hello");
    assert!(matches!(
        hello,
        UdsClientHello::PluginBackend { plugin_id, .. } if plugin_id == PLUGIN_ID
    ));
    send_json_frame(
        &mut framed,
        &UdsHandshakeAck::accepted(),
        DEFAULT_MAX_FRAME_BYTES,
    )
    .await
    .expect("send ack");

    // Verify system event updates dark_surface state
    send_json_frame(
        &mut framed,
        &BackendOutboundFrame::SystemEvent {
            event: events::APPEARANCE_CHANGED.to_owned(),
            payload: json!({ "isDark": false }),
        },
        DEFAULT_MAX_FRAME_BYTES,
    )
    .await
    .expect("send appearance.changed");

    send_json_frame(
        &mut framed,
        &BackendOutboundFrame::Rpc(RpcRequest::new(
            "1",
            "setEnabled",
            Some(json!({ "enabled": true })),
        )),
        DEFAULT_MAX_FRAME_BYTES,
    )
    .await
    .expect("send setEnabled");
    let resp: RpcResponse = recv_json_frame(&mut framed, DEFAULT_MAX_FRAME_BYTES)
        .await
        .expect("read rpc resp")
        .expect("some resp");
    assert_eq!(resp.id, "1");
    assert_eq!(
        resp.result.as_ref().and_then(|v| v.get("enabled")),
        Some(&json!(true))
    );

    let hud_ev: PluginBackendEvent = tokio::time::timeout(
        Duration::from_secs(2),
        recv_json_frame(&mut framed, DEFAULT_MAX_FRAME_BYTES),
    )
    .await
    .expect("hud event timeout")
    .expect("decode hud event")
    .expect("some hud event");
    assert_eq!(hud_ev.event, methods::HUD_UPDATE_SCENE);
    assert!(hud_ev.ephemeral);

    cancel.cancel();
    tokio::time::timeout(Duration::from_secs(2), runner_task)
        .await
        .expect("runner exit")
        .expect("runner join")
        .expect("runner ok");
    let _ = tokio::time::timeout(Duration::from_secs(2), sampler).await;
    let _ = std::fs::remove_file(&sock_path);
}
