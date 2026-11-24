//! Security hardening tests: storage symlink/quota limits, `system.exec` 256 KiB cap & audit,
//! `app.sock` package allowlist & UID hijack defense, `host.localAudit`, `reloadWebView`, and
//! duplicate `Origin` rejection.
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::os::unix::fs::symlink;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::{Value, json};
use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use sideveil_protocol::rpc::{HostEventEnvelope, RpcRequest, error_codes, events, methods};
use sideveild::config_store::ConfigStore;
use sideveild::host_ws::HostWsServer;
use sideveild::log_ring::LogRing;
use sideveild::outbound_queue;
use sideveild::plugin_registry::PluginRegistry;
use sideveild::rpc::{self, RpcCaller};
use sideveild::state::HubState;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;

fn make_state(tag: &str) -> (PathBuf, Arc<HubState>) {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let dir =
        std::env::temp_dir().join(format!("sideveil-acl-{tag}-{}-{nanos}", std::process::id()));
    let logs = Arc::new(LogRing::new());
    let store = ConfigStore::open(dir.join("config.json"), Arc::clone(&logs));
    let registry = PluginRegistry::open(
        dir.join("plugins"),
        dir.join("plugins.json"),
        Arc::clone(&logs),
    );
    let (wake_tx, _) = mpsc::unbounded_channel();
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

fn write_plugin_dir(root: &Path, id: &str, manifest: Value) {
    let pdir = root.join(id);
    fs::create_dir_all(pdir.join("web")).expect("mkdir");
    fs::write(pdir.join("web/index.html"), "<h1>ok</h1>").expect("html");
    fs::write(pdir.join("manifest.json"), manifest.to_string()).expect("manifest");
}

#[tokio::test]
async fn enforces_storage_symlink_quota_exec_cap_app_sock_and_duplicate_origin() {
    let (dir, state) = make_state("hardening");
    write_plugin_dir(
        &dir.join("plugins"),
        "com.example.priv",
        json!({
            "id": "com.example.priv",
            "name": "Priv",
            "version": "1.0.0",
            "entry": "web/index.html",
            "backend": "uds-app",
            "backendPackages": ["com.allowed.app"],
            "triggers": { "global": true },
            "capabilities": ["storage", "system-exec"]
        }),
    );
    state.plugin_registry().rescan();
    let grant = RpcRequest::new(
        "g",
        methods::PLUGINS_GRANT_CAPABILITIES,
        Some(json!({ "pluginId": "com.example.priv", "grantAllPending": true })),
    );
    assert!(
        rpc::dispatch_rpc_request(&state, grant, RpcCaller::Ctl)
            .await
            .error
            .is_none()
    );

    // 1. app.sock enforces explicit backendPackages even when triggers.global == true.
    assert!(
        state
            .plugin_registry()
            .authorize_backend("com.example.priv", true, Some("com.allowed.app"))
            .is_ok()
    );
    assert!(
        state
            .plugin_registry()
            .authorize_backend("com.example.priv", true, Some("com.evil.app"))
            .is_err()
    );

    // 2. Backend UID hijack protection.
    let (_s1, _rx1, _d1) = state
        .plugin_backends()
        .register("com.example.priv", false, 10200)
        .unwrap();
    assert!(
        state
            .plugin_backends()
            .register("com.example.priv", false, 10299)
            .is_err()
    );

    // 3. Storage symlink escape blocked BEFORE write, and >1 MiB file returns QUOTA_EXCEEDED.
    let outside = dir.join("outside");
    fs::create_dir_all(&outside).unwrap();
    let pdata = dir.join("data/com.example.priv");
    fs::create_dir_all(&pdata).unwrap();
    symlink(&outside, pdata.join("escape")).unwrap();
    let mut sym_req = RpcRequest::new(
        "fs-sym",
        methods::FS_WRITE_ATOMIC,
        Some(json!({ "path": "escape/pwn.txt", "content": "nope" })),
    );
    sym_req.plugin_id = Some("com.example.priv".to_owned());
    assert_eq!(
        rpc::dispatch_rpc_request(&state, sym_req, RpcCaller::HostWebView)
            .await
            .error
            .map(|e| e.code),
        Some(error_codes::PERMISSION_DENIED)
    );
    assert!(!outside.join("pwn.txt").exists());

    let mut big_req = RpcRequest::new(
        "fs-big",
        methods::FS_WRITE_ATOMIC,
        Some(json!({ "path": "big.bin", "content": "x".repeat(1_048_600) })),
    );
    big_req.plugin_id = Some("com.example.priv".to_owned());
    assert_eq!(
        rpc::dispatch_rpc_request(&state, big_req, RpcCaller::HostWebView)
            .await
            .error
            .map(|e| e.code),
        Some(error_codes::QUOTA_EXCEEDED)
    );

    // 4. system.exec bounds stdout to 256 KiB and records command details in audit ring.
    let mut exec_req = RpcRequest::new(
        "ex-cap",
        methods::SYSTEM_EXEC,
        Some(json!({ "cmd": "dd if=/dev/zero bs=4096 count=80 2>/dev/null | tr '\\0' 'A'" })),
    );
    exec_req.plugin_id = Some("com.example.priv".to_owned());
    let exec_resp = rpc::dispatch_rpc_request(&state, exec_req, RpcCaller::HostWebView).await;
    let out = exec_resp.result.unwrap()["stdout"]
        .as_str()
        .unwrap()
        .to_owned();
    assert!(out.contains("truncated"));
    assert!(
        state
            .audit()
            .recent()
            .iter()
            .any(|e| e.action == "system.exec" && !e.detail.is_empty())
    );

    // 5. host.localAudit & plugins.reloadWebView wire events.
    state.on_host_event(HostEventEnvelope {
        event: events::HOST_LOCAL_AUDIT.to_owned(),
        payload: Some(json!({
            "pluginId": "com.example.priv",
            "method": "clipboard.read",
            "allowed": false,
            "detail": "missing clipboard grant"
        })),
    });
    assert!(
        state
            .audit()
            .recent()
            .iter()
            .any(|e| e.action == "clipboard.read" && !e.allowed)
    );

    let (tx, mut rx) = outbound_queue::channel();
    state.register_host_session(tx);
    let reload = RpcRequest::new(
        "rv1",
        methods::PLUGINS_RELOAD_WEBVIEW,
        Some(json!({ "pluginId": "com.example.priv" })),
    );
    assert!(
        rpc::dispatch_rpc_request(&state, reload, RpcCaller::Ctl)
            .await
            .error
            .is_none()
    );
    let mut saw_reload = false;
    while let Ok(Some(msg)) = tokio::time::timeout(Duration::from_millis(50), rx.recv()).await {
        if msg.contains("reloadPlugin") && msg.contains("com.example.priv") {
            saw_reload = true;
            break;
        }
    }
    assert!(saw_reload, "expected panel.command reloadPlugin frame");

    // 6. Duplicate Origin header rejected on Asset HTTP server with nosniff.
    let ws_server = HostWsServer::bind().await.expect("bind");
    let port = ws_server.port();
    let tok = state.ws_token().to_owned();
    let srv = tokio::spawn(ws_server.serve(Arc::clone(&state)));
    let mut tcp = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
    let req = format!(
        "GET /plugins/com.example.priv/web/index.html HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer {tok}\r\nOrigin: https://a.test\r\nOrigin: https://b.test\r\nConnection: close\r\n\r\n"
    );
    tcp.write_all(req.as_bytes()).await.unwrap();
    let mut resp_buf = String::new();
    let _ = tcp.read_to_string(&mut resp_buf).await;
    assert!(resp_buf.starts_with("HTTP/1.1 403"));
    assert!(resp_buf.contains("X-Content-Type-Options: nosniff"));

    srv.abort();
    let _ = fs::remove_dir_all(&dir);
}
