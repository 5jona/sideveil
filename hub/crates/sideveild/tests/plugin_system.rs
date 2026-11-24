//! End-to-end integration tests for Phase 2 Open Plugin System:
//! inotify discovery, MRU selection, isolated HTTP static assets, UDS backend RPC, and ACLs.
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::os::unix::fs::symlink;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::{Value, json};
use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use sideveil_protocol::plugin::BUILTIN_DIAG_PLUGIN_ID;
use sideveil_protocol::rpc::{RpcRequest, RpcResponse, error_codes, methods};
use sideveil_protocol::uds::{UdsClientHello, UdsHandshakeAck};
use sideveild::config_store::ConfigStore;
use sideveild::host_ws::HostWsServer;
use sideveild::log_ring::LogRing;
use sideveild::peercred::PeerAuthConfig;
use sideveild::plugin_registry::PluginRegistry;
use sideveild::rpc::{self, RpcCaller};
use sideveild::state::HubState;
use sideveild::uds_gateway::UdsGateway;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpStream, UnixStream};
use tokio::sync::mpsc;

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
        std::env::temp_dir().join(format!("sideveil-plg-{tag}-{}-{nanos}", std::process::id()));
    fs::create_dir_all(&dir).expect("create temp dir");
    dir
}

fn write_plugin(dir: &Path, id: &str, manifest: Value, html: &str) {
    let root = dir.join(id);
    fs::create_dir_all(root.join("web")).expect("mkdir web");
    fs::write(root.join("manifest.json"), manifest.to_string()).expect("write manifest");
    fs::write(root.join("web/index.html"), html).expect("write index.html");
}

async fn http_get(port: u16, path: &str, token: Option<&str>, origin: Option<&str>) -> String {
    let mut stream = TcpStream::connect(("127.0.0.1", port))
        .await
        .expect("connect http");
    let mut req = format!("GET {path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n");
    if let Some(tok) = token {
        req.push_str(&format!("Authorization: Bearer {tok}\r\n"));
    }
    if let Some(orig) = origin {
        req.push_str(&format!("Origin: {orig}\r\n"));
    }
    req.push_str("Connection: close\r\n\r\n");
    stream.write_all(req.as_bytes()).await.expect("write http");
    let mut out = String::new();
    stream.read_to_string(&mut out).await.expect("read http");
    out
}

#[tokio::test]
async fn plugin_registry_assets_backend_rpc_and_acls_work_end_to_end() {
    let dir = temp_test_dir("e2e");
    let plugins_dir = dir.join("plugins");
    let data_dir = dir.join("data");
    fs::create_dir_all(&plugins_dir).unwrap();
    fs::create_dir_all(&data_dir).unwrap();

    // Seed two external plugins matching the same foreground app ("com.example.game").
    write_plugin(
        &plugins_dir,
        "com.example.alpha",
        json!({
            "id": "com.example.alpha",
            "name": "Alpha",
            "version": "1.0.0",
            "entry": "web/index.html",
            "backend": "uds-native",
            "triggers": { "packages": ["com.example.game"], "priority": 20 },
            "capabilities": ["storage", "toast"]
        }),
        "<h1>Alpha Panel</h1>",
    );
    write_plugin(
        &plugins_dir,
        "com.example.beta",
        json!({
            "id": "com.example.beta",
            "name": "Beta",
            "version": "1.0.0",
            "entry": "web/index.html",
            "backend": "none",
            "triggers": { "packages": ["com.example.game"], "priority": 10 },
            "capabilities": ["system-exec"]
        }),
        "<h1>Beta Panel</h1>",
    );

    // Plant a sibling file outside web/ and a symlink inside web/ escaping the plugin root.
    fs::write(plugins_dir.join("com.example.alpha/private.key"), "secret").unwrap();
    let outside_secret = dir.join("secret.txt");
    fs::write(&outside_secret, "top-secret").unwrap();
    symlink(
        &outside_secret,
        plugins_dir.join("com.example.alpha/web/escape.txt"),
    )
    .unwrap();

    let logs = Arc::new(LogRing::new());
    let store = ConfigStore::open(dir.join("config.json"), Arc::clone(&logs));
    let registry = PluginRegistry::open(
        plugins_dir.clone(),
        dir.join("plugins.json"),
        Arc::clone(&logs),
    );
    let _plugin_watcher = registry.spawn_watcher().expect("plugin inotify watcher");
    let (wake_tx, _wake_rx) = mpsc::unbounded_channel();
    let state = HubState::new(
        DEFAULT_HOST_PACKAGE.to_owned(),
        store,
        Arc::clone(&registry),
        data_dir,
        logs,
        wake_tx,
    );

    let ws_server = HostWsServer::bind().await.expect("bind ws/http");
    let port = ws_server.port();
    state.set_ws_port(port);
    let ws_task = tokio::spawn(ws_server.serve(Arc::clone(&state)));

    let hub_sock = dir.join("hub.sock");
    let app_sock = dir.join("app.sock");
    let auth = PeerAuthConfig {
        extra_privileged_uids: vec![current_uid()],
        packages_list_path: None,
    };
    let gateway = UdsGateway::bind(hub_sock.clone(), app_sock.clone(), auth).expect("bind uds");
    let uds_task = tokio::spawn(gateway.serve(Arc::clone(&state)));

    // 1. Verify priority sorting and MRU persistence for com.example.game.
    let snap = registry.snapshot(
        "com.example.game",
        "",
        &state.config_store().current(),
        &Default::default(),
    );
    assert_eq!(
        snap.matched_plugin_ids,
        vec!["com.example.alpha", "com.example.beta"]
    );
    assert_eq!(snap.active_plugin_id.as_deref(), Some("com.example.alpha"));

    registry
        .select_plugin("com.example.beta", "com.example.game")
        .expect("select beta");
    let snap_after_mru = registry.snapshot(
        "com.example.game",
        "",
        &state.config_store().current(),
        &Default::default(),
    );
    assert_eq!(
        snap_after_mru.active_plugin_id.as_deref(),
        Some("com.example.beta")
    );

    // 2. Verify authenticated HTTP static asset serving + traversal / symlink / manifest blocking.
    let tok = state.ws_token();
    let ok_resp = http_get(
        port,
        "/plugins/com.example.alpha/web/index.html",
        Some(tok),
        None,
    )
    .await;
    assert!(ok_resp.starts_with("HTTP/1.1 200 OK"));
    assert!(ok_resp.contains("<h1>Alpha Panel</h1>"));

    let unauth = http_get(
        port,
        "/plugins/com.example.alpha/web/index.html",
        None,
        None,
    )
    .await;
    assert!(unauth.starts_with("HTTP/1.1 401"));

    let origin_blocked = http_get(
        port,
        "/plugins/com.example.alpha/web/index.html",
        Some(tok),
        Some("https://evil.example"),
    )
    .await;
    assert!(origin_blocked.starts_with("HTTP/1.1 403"));

    let manifest_blocked = http_get(
        port,
        "/plugins/com.example.alpha/manifest.json",
        Some(tok),
        None,
    )
    .await;
    assert!(manifest_blocked.starts_with("HTTP/1.1 403"));

    let sibling_blocked = http_get(
        port,
        "/plugins/com.example.alpha/private.key",
        Some(tok),
        None,
    )
    .await;
    assert!(sibling_blocked.starts_with("HTTP/1.1 403"));

    let traversal_blocked = http_get(
        port,
        "/plugins/com.example.alpha/web/..%2F..%2Fsecret.txt",
        Some(tok),
        None,
    )
    .await;
    assert!(traversal_blocked.starts_with("HTTP/1.1 403"));

    let symlink_blocked = http_get(
        port,
        "/plugins/com.example.alpha/web/escape.txt",
        Some(tok),
        None,
    )
    .await;
    assert!(symlink_blocked.starts_with("HTTP/1.1 403"));

    // 3. Connect Alpha's UDS native backend and test plugin.invoke round-trip.
    let stream = UnixStream::connect(&hub_sock)
        .await
        .expect("connect alpha backend");
    let (br, mut bw) = stream.into_split();
    let mut b_reader = BufReader::new(br);
    let hello = serde_json::to_string(&UdsClientHello::PluginBackend {
        plugin_id: "com.example.alpha".to_owned(),
        package_name: None,
        protocol_version: 1,
    })
    .unwrap();
    bw.write_all(format!("{hello}\n").as_bytes()).await.unwrap();
    let mut ack_line = String::new();
    b_reader.read_line(&mut ack_line).await.unwrap();
    assert!(
        serde_json::from_str::<UdsHandshakeAck>(ack_line.trim())
            .unwrap()
            .ok
    );

    let backend_echo_task = tokio::spawn(async move {
        let mut req_line = String::new();
        b_reader.read_line(&mut req_line).await.unwrap();
        let req: RpcRequest = serde_json::from_str(req_line.trim()).unwrap();
        assert_eq!(req.method, "echo");
        let resp = RpcResponse::ok(req.id, json!({ "echoed": req.params }));
        bw.write_all(format!("{}\n", serde_json::to_string(&resp).unwrap()).as_bytes())
            .await
            .unwrap();
        tokio::time::sleep(Duration::from_millis(50)).await;
    });

    let mut invoke_req = RpcRequest::new(
        "inv-1",
        methods::PLUGIN_INVOKE,
        Some(json!({ "method": "echo", "params": { "hello": 42 } })),
    );
    invoke_req.plugin_id = Some("com.example.alpha".to_owned());
    let invoke_resp = rpc::dispatch_rpc_request(&state, invoke_req, RpcCaller::HostWebView).await;
    assert!(invoke_resp.error.is_none());
    assert_eq!(
        invoke_resp.result,
        Some(json!({ "echoed": { "hello": 42 } }))
    );
    backend_echo_task.await.unwrap();

    for id in ["com.example.alpha", "com.example.beta"] {
        let params = Some(json!({ "pluginId": id, "grantAllPending": true }));
        let resp = rpc::dispatch_rpc_request(
            &state,
            RpcRequest::new("g", methods::PLUGINS_GRANT_CAPABILITIES, params),
            RpcCaller::Ctl,
        )
        .await;
        assert!(resp.error.is_none());
    }
    let mut write_req = RpcRequest::new(
        "fs-1",
        methods::FS_WRITE_ATOMIC,
        Some(json!({ "path": "prefs/settings.json", "content": "{\"v\":1}" })),
    );
    write_req.plugin_id = Some("com.example.alpha".to_owned());
    assert!(
        rpc::dispatch_rpc_request(&state, write_req, RpcCaller::HostWebView)
            .await
            .error
            .is_none()
    );
    let mut read_req = RpcRequest::new(
        "fs-2",
        methods::FS_READ,
        Some(json!({ "path": "prefs/settings.json" })),
    );
    read_req.plugin_id = Some("com.example.alpha".to_owned());
    let read_resp = rpc::dispatch_rpc_request(&state, read_req, RpcCaller::HostWebView).await;
    assert_eq!(read_resp.result, Some(json!({ "content": "{\"v\":1}" })));
    let mut bad_read = RpcRequest::new(
        "fs-3",
        methods::FS_READ,
        Some(json!({ "path": "../com.example.beta/secret" })),
    );
    bad_read.plugin_id = Some("com.example.alpha".to_owned());
    assert_eq!(
        rpc::dispatch_rpc_request(&state, bad_read, RpcCaller::HostWebView)
            .await
            .error
            .map(|e| e.code),
        Some(error_codes::PERMISSION_DENIED)
    );

    // Alpha lacks system-exec -> denied; Beta has system-exec -> succeeds.
    let mut denied_exec = RpcRequest::new(
        "ex-1",
        methods::SYSTEM_EXEC,
        Some(json!({ "cmd": "echo hi" })),
    );
    denied_exec.plugin_id = Some("com.example.alpha".to_owned());
    assert_eq!(
        rpc::dispatch_rpc_request(&state, denied_exec, RpcCaller::HostWebView)
            .await
            .error
            .map(|e| e.code),
        Some(error_codes::PERMISSION_DENIED)
    );

    let mut ok_exec = RpcRequest::new(
        "ex-2",
        methods::SYSTEM_EXEC,
        Some(json!({ "cmd": "echo sideveil_ok" })),
    );
    ok_exec.plugin_id = Some("com.example.beta".to_owned());
    let exec_resp = rpc::dispatch_rpc_request(&state, ok_exec, RpcCaller::HostWebView).await;
    assert_eq!(
        exec_resp.result.as_ref().and_then(|v| v.get("errno")),
        Some(&json!(0))
    );
    assert!(
        exec_resp
            .result
            .as_ref()
            .and_then(|v| v.get("stdout"))
            .and_then(Value::as_str)
            .unwrap_or("")
            .contains("sideveil_ok")
    );

    // 5. Verify inotify hot-discovers a newly dropped plugin directory automatically.
    let mut rx = registry.subscribe();
    write_plugin(
        &plugins_dir,
        "com.example.gamma",
        json!({
            "id": "com.example.gamma",
            "name": "Gamma",
            "version": "0.2.0",
            "entry": "web/index.html",
            "triggers": { "packages": ["com.example.game"], "priority": 30 }
        }),
        "<h1>Gamma</h1>",
    );
    tokio::time::timeout(Duration::from_secs(2), rx.recv())
        .await
        .expect("inotify plugin discovery timeout")
        .expect("plugin update signal");
    assert!(registry.contains_plugin("com.example.gamma"));

    // Built-in diagnostic plugin must always be present.
    assert!(registry.contains_plugin(BUILTIN_DIAG_PLUGIN_ID));

    ws_task.abort();
    uds_task.abort();
    let _ = fs::remove_dir_all(&dir);
}
