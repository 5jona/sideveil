//! Integration tests for `/plugins/<id>/__icon` SVG rasterization & disk caching.

use std::fs;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::json;
use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use sideveild::config_store::ConfigStore;
use sideveild::host_ws::HostWsServer;
use sideveild::log_ring::LogRing;
use sideveild::plugin_registry::PluginRegistry;
use sideveild::state::HubState;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;

#[tokio::test]
async fn serves_and_caches_rasterized_svg_icons_over_authenticated_loopback() {
    let unique = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let dir =
        std::env::temp_dir().join(format!("sideveil-icon-e2e-{}-{unique}", std::process::id()));
    let plugins_dir = dir.join("plugins");
    let plugin_web = plugins_dir.join("com.example.svgicon/web");
    fs::create_dir_all(&plugin_web).expect("create plugin web dir");

    fs::write(
        plugins_dir.join("com.example.svgicon/manifest.json"),
        serde_json::to_vec_pretty(&json!({
            "id": "com.example.svgicon",
            "name": "SVG Icon Demo",
            "version": "0.1.0",
            "icon": "web/icon.svg",
            "entry": "web/index.html",
            "triggers": { "global": true, "priority": 10 }
        }))
        .unwrap(),
    )
    .expect("write manifest");
    fs::write(plugin_web.join("index.html"), "<h1>ok</h1>").expect("write index");
    fs::write(
        plugin_web.join("icon.svg"),
        r#"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="8"/></svg>"#,
    )
    .expect("write icon.svg");

    let logs = Arc::new(LogRing::new());
    let store = ConfigStore::open(dir.join("config.json"), Arc::clone(&logs));
    let registry = PluginRegistry::open(plugins_dir, dir.join("plugins.json"), Arc::clone(&logs));
    let (wake_tx, _wake_rx) = mpsc::unbounded_channel();
    let state = HubState::new(
        DEFAULT_HOST_PACKAGE.to_owned(),
        store,
        registry,
        dir.join("data"),
        logs,
        wake_tx,
    );

    let ws_server = HostWsServer::bind().await.expect("bind ws");
    let port = ws_server.port();
    let ws_task = tokio::spawn(ws_server.serve(Arc::clone(&state)));

    let (dark_status, dark_png) = http_get_bytes(
        port,
        "/plugins/com.example.svgicon/__icon?size=64&theme=dark",
        state.ws_token(),
    )
    .await;
    let (light_status, light_png) = http_get_bytes(
        port,
        "/plugins/com.example.svgicon/__icon?size=64&theme=light",
        state.ws_token(),
    )
    .await;
    assert_eq!(dark_status, 200);
    assert_eq!(light_status, 200);
    assert!(dark_png.starts_with(b"\x89PNG\r\n\x1a\n"));
    assert!(light_png.starts_with(b"\x89PNG\r\n\x1a\n"));
    assert_ne!(dark_png, light_png);
    assert!(state.cache_dir().join("icons").is_dir());

    // Unauthenticated requests must be rejected.
    let (unauth_status, _) = http_get_bytes(
        port,
        "/plugins/com.example.svgicon/__icon?size=64&theme=dark",
        "wrong-token",
    )
    .await;
    assert_eq!(unauth_status, 401);

    ws_task.abort();
    let _ = fs::remove_dir_all(&dir);
}

async fn http_get_bytes(port: u16, path: &str, token: &str) -> (u16, Vec<u8>) {
    let mut stream = TcpStream::connect(("127.0.0.1", port))
        .await
        .expect("connect asset server");
    let req = format!(
        "GET {path} HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer {token}\r\nConnection: close\r\n\r\n"
    );
    stream.write_all(req.as_bytes()).await.expect("write req");
    let mut buf = Vec::new();
    stream.read_to_end(&mut buf).await.expect("read resp");
    let sep = buf
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .expect("http header separator");
    let head = String::from_utf8_lossy(&buf[..sep]);
    let status = head
        .lines()
        .next()
        .and_then(|line| line.split_whitespace().nth(1))
        .and_then(|s| s.parse::<u16>().ok())
        .unwrap_or(0);
    (status, buf[sep + 4..].to_vec())
}
