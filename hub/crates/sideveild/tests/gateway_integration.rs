//! End-to-end integration tests covering UDS `SO_PEERCRED` auth, Sensor -> Hub -> Host WS routing,
//! anti-CSWSH WebSocket handshakes, and dual-priority gesture delivery.

use std::fs;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use sideveil_protocol::config::EdgeSide;
use sideveil_protocol::rpc::{DaemonStatus, HostEventEnvelope, RpcRequest, RpcResponse, methods};
use sideveil_protocol::sensor::{DisplayGeometry, HubToSensor, SensorToHub};
use sideveil_protocol::uds::{UdsClientHello, UdsHandshakeAck};
use sideveild::config_store::ConfigStore;
use sideveild::host_ws::HostWsServer;
use sideveild::log_ring::LogRing;
use sideveild::peercred::PeerAuthConfig;
use sideveild::plugin_registry::PluginRegistry;
use sideveild::state::HubState;
use sideveild::uds_gateway::UdsGateway;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;
use tokio::sync::mpsc;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::http::HeaderValue;

fn current_uid() -> u32 {
    unsafe {
        // SAFETY: `getuid` has no preconditions.
        libc::getuid()
    }
}

fn temp_test_dir(tag: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let dir =
        std::env::temp_dir().join(format!("sideveil-it-{tag}-{}-{nanos}", std::process::id()));
    fs::create_dir_all(&dir).expect("create temp test dir");
    dir
}

#[tokio::test]
async fn sensor_hub_host_and_ctl_pipeline_works_end_to_end() {
    let dir = temp_test_dir("pipeline");
    let logs = Arc::new(LogRing::new());
    let store = ConfigStore::open(dir.join("config.json"), Arc::clone(&logs));
    let registry = PluginRegistry::open(
        dir.join("plugins"),
        dir.join("plugins.json"),
        Arc::clone(&logs),
    );
    let (wake_tx, mut wake_rx) = mpsc::unbounded_channel();
    let state = HubState::new(
        DEFAULT_HOST_PACKAGE.to_owned(),
        store,
        registry,
        dir.join("data"),
        logs,
        wake_tx,
    );

    let ws_server = HostWsServer::bind().await.expect("bind host ws");
    let ws_port = ws_server.port();
    state.set_ws_port(ws_port);
    let ws_task = tokio::spawn(ws_server.serve(Arc::clone(&state)));

    let hub_sock = dir.join("hub.sock");
    let app_sock = dir.join("app.sock");
    let auth = PeerAuthConfig {
        extra_privileged_uids: vec![current_uid()],
        packages_list_path: None,
    };
    let gateway = UdsGateway::bind(hub_sock.clone(), app_sock.clone(), auth).expect("bind uds");
    let uds_task = tokio::spawn(gateway.serve(Arc::clone(&state)));

    // 1. Verify Host WS rejects bad auth and non-empty Origin (anti-CSWSH).
    let mut bad_auth_req = format!("ws://127.0.0.1:{ws_port}/ws")
        .into_client_request()
        .expect("ws req");
    bad_auth_req
        .headers_mut()
        .insert("Authorization", HeaderValue::from_static("Bearer wrong"));
    assert!(connect_async(bad_auth_req).await.is_err());

    let mut cswsh_req = format!("ws://127.0.0.1:{ws_port}/ws")
        .into_client_request()
        .expect("ws req");
    cswsh_req.headers_mut().insert(
        "Authorization",
        HeaderValue::from_str(&format!("Bearer {}", state.ws_token())).expect("auth header"),
    );
    cswsh_req
        .headers_mut()
        .insert("Origin", HeaderValue::from_static("https://evil.example"));
    assert!(connect_async(cswsh_req).await.is_err());

    // 2. Connect authenticated Host WS client.
    let mut ok_req = format!("ws://127.0.0.1:{ws_port}/ws")
        .into_client_request()
        .expect("ws req");
    ok_req.headers_mut().insert(
        "Authorization",
        HeaderValue::from_str(&format!("Bearer {}", state.ws_token())).expect("auth header"),
    );
    let (mut host_ws, _) = connect_async(ok_req).await.expect("connect valid host ws");

    // Host receives initial config.updated + plugins.updated snapshots immediately.
    let first_frame = tokio::time::timeout(Duration::from_secs(2), host_ws.next())
        .await
        .expect("timeout")
        .expect("frame")
        .expect("ws ok");
    let first_env: HostEventEnvelope =
        serde_json::from_str(first_frame.to_text().expect("text")).expect("decode env");
    assert_eq!(first_env.event, "config.updated");

    let second_frame = tokio::time::timeout(Duration::from_secs(2), host_ws.next())
        .await
        .expect("timeout")
        .expect("frame")
        .expect("ws ok");
    let second_env: HostEventEnvelope =
        serde_json::from_str(second_frame.to_text().expect("text")).expect("decode env");
    assert_eq!(second_env.event, "plugins.updated");

    // Host sends host.hello -> Hub wakes supervisor with WatchHostPid.
    let hello_env = json!({
        "event": "host.hello",
        "payload": { "pid": 4242, "version": "0.1.0", "crossWindowBlurSupported": true }
    });
    host_ws
        .send(Message::Text(hello_env.to_string().into()))
        .await
        .expect("send host.hello");
    let wake_sig = tokio::time::timeout(Duration::from_secs(2), wake_rx.recv())
        .await
        .expect("wake timeout")
        .expect("wake signal");
    assert_eq!(
        wake_sig,
        sideveild::state::HostWakeSignal::WatchHostPid(4242)
    );

    // 3. Connect Sensor over hub.sock and report foreground + gesture burst.
    let sensor_stream = UnixStream::connect(&hub_sock)
        .await
        .expect("connect sensor");
    let (sr, mut sw) = sensor_stream.into_split();
    let mut sensor_reader = BufReader::new(sr);
    let sensor_hello = serde_json::to_string(&UdsClientHello::Sensor {
        pid: 31337,
        version: "0.1.0".to_owned(),
    })
    .unwrap();
    sw.write_all(format!("{sensor_hello}\n").as_bytes())
        .await
        .expect("write sensor hello");
    let mut ack_line = String::new();
    sensor_reader
        .read_line(&mut ack_line)
        .await
        .expect("read ack");
    let ack: UdsHandshakeAck = serde_json::from_str(ack_line.trim()).expect("parse ack");
    assert!(ack.ok);

    // Sensor receives initial SyncState from Hub.
    let mut sync_line = String::new();
    sensor_reader
        .read_line(&mut sync_line)
        .await
        .expect("read sync");
    let sync_msg: HubToSensor = serde_json::from_str(sync_line.trim()).expect("parse sync");
    assert!(matches!(sync_msg, HubToSensor::SyncState { .. }));

    // Sensor sends Hello + ForegroundChanged(me.weishu.kernelsu) + drag start/end.
    for msg in [
        SensorToHub::Hello {
            pid: 31337,
            version: "0.1.0".to_owned(),
            display: DisplayGeometry {
                width_px: 3392,
                height_px: 2400,
                density: 2.625,
            },
            task_listener_active: true,
            gesture_monitor_active: true,
            overlay_permission_granted: true,
            foreground_package: "me.weishu.kernelsu".to_owned(),
        },
        SensorToHub::ForegroundChanged {
            package_name: "me.weishu.kernelsu".to_owned(),
        },
        SensorToHub::GestureDragStart {
            side: EdgeSide::Right,
            start_y_fraction: 0.4,
        },
        SensorToHub::GestureDragEnd {
            should_open: true,
            velocity_px_per_sec: 900.0,
        },
    ] {
        let line = format!("{}\n", serde_json::to_string(&msg).unwrap());
        sw.write_all(line.as_bytes())
            .await
            .expect("write sensor msg");
    }

    // Drain Host events and verify critical events all arrive in order.
    let mut seen_events = Vec::new();
    while seen_events.len() < 6 {
        let frame = tokio::time::timeout(Duration::from_secs(2), host_ws.next())
            .await
            .expect("host event timeout")
            .expect("frame")
            .expect("ws ok");
        let env: HostEventEnvelope =
            serde_json::from_str(frame.to_text().expect("text")).expect("decode env");
        seen_events.push(env.event);
    }
    assert_eq!(
        seen_events,
        vec![
            "config.updated",
            "plugins.updated",
            "scope.changed",
            "plugins.updated",
            "gesture.dragStart",
            "gesture.dragEnd"
        ]
    );

    // 4. Query daemon.status via hub.sock Ctl role and verify sensor + host metadata.
    let ctl_stream = UnixStream::connect(&hub_sock).await.expect("connect ctl");
    let (cr, mut cw) = ctl_stream.into_split();
    let mut ctl_reader = BufReader::new(cr);
    cw.write_all(format!("{}\n", serde_json::to_string(&UdsClientHello::Ctl).unwrap()).as_bytes())
        .await
        .expect("write ctl hello");
    let mut ctl_ack = String::new();
    ctl_reader.read_line(&mut ctl_ack).await.expect("ctl ack");
    assert!(
        serde_json::from_str::<UdsHandshakeAck>(ctl_ack.trim())
            .unwrap()
            .ok
    );

    let req = RpcRequest::new("it-1", methods::DAEMON_STATUS, None);
    cw.write_all(format!("{}\n", serde_json::to_string(&req).unwrap()).as_bytes())
        .await
        .expect("write status req");
    let mut resp_line = String::new();
    ctl_reader
        .read_line(&mut resp_line)
        .await
        .expect("read resp");
    let resp: RpcResponse = serde_json::from_str(resp_line.trim()).expect("parse resp");
    let status: DaemonStatus =
        serde_json::from_value(resp.result.expect("status result")).expect("parse status");
    assert!(status.host_connected);
    assert!(status.sensor_connected);
    assert_eq!(status.host_pid, Some(4242));
    assert_eq!(status.sensor_pid, Some(31337));
    assert_eq!(status.screen_width_px, 3392);
    assert!(status.scope_active);

    // 5. Verify app.sock rejects Ctl role and accepts valid PluginBackend.
    let app_ctl = UnixStream::connect(&app_sock)
        .await
        .expect("connect app.sock");
    let (ar, mut aw) = app_ctl.into_split();
    let mut app_reader = BufReader::new(ar);
    aw.write_all(format!("{}\n", serde_json::to_string(&UdsClientHello::Ctl).unwrap()).as_bytes())
        .await
        .expect("write app ctl");
    let mut app_ack_line = String::new();
    app_reader
        .read_line(&mut app_ack_line)
        .await
        .expect("read app ack");
    let app_ack: UdsHandshakeAck = serde_json::from_str(app_ack_line.trim()).expect("parse ack");
    assert!(!app_ack.ok);

    // 6. Verify Host WS can execute JSON-RPC (daemon.ping) and receive response.
    let ping_req = json!({ "jsonrpc": "2.0", "id": "ws-ping", "method": "daemon.ping" });
    host_ws
        .send(Message::Text(ping_req.to_string().into()))
        .await
        .expect("send ws ping");
    let ping_frame = tokio::time::timeout(Duration::from_secs(2), host_ws.next())
        .await
        .expect("ping timeout")
        .expect("frame")
        .expect("ws ok");
    let ping_val: Value = serde_json::from_str(ping_frame.to_text().unwrap()).unwrap();
    assert_eq!(ping_val["id"], "ws-ping");
    assert_eq!(ping_val["result"]["pong"], true);

    ws_task.abort();
    uds_task.abort();
    let _ = fs::remove_dir_all(&dir);
}
