//! Unit tests for asset server MIME expansion, hashed asset caching, and SPA fallback.

use std::fs;
use std::path::Path;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;

use super::{handle_http_asset, is_spa_route_path, mime_type_for};
use crate::config_store::ConfigStore;
use crate::log_ring::LogRing;
use crate::plugin_registry::PluginRegistry;
use crate::state::HubState;

#[test]
fn expanded_mime_table_and_spa_route_detection() {
    assert_eq!(mime_type_for(Path::new("favicon.ico")), "image/x-icon");
    assert_eq!(mime_type_for(Path::new("font.woff")), "font/woff");
    assert_eq!(mime_type_for(Path::new("font.ttf")), "font/ttf");
    assert_eq!(mime_type_for(Path::new("font.otf")), "font/otf");
    assert_eq!(
        mime_type_for(Path::new("site.webmanifest")),
        "application/manifest+json; charset=utf-8"
    );
    assert_eq!(mime_type_for(Path::new("hero.avif")), "image/avif");
    assert_eq!(
        mime_type_for(Path::new("app.js.map")),
        "application/json; charset=utf-8"
    );
    assert_eq!(
        mime_type_for(Path::new("robots.txt")),
        "text/plain; charset=utf-8"
    );
    assert_eq!(
        mime_type_for(Path::new("feed.xml")),
        "application/xml; charset=utf-8"
    );

    assert!(is_spa_route_path("web/settings"));
    assert!(is_spa_route_path("web/nodes/42"));
    assert!(is_spa_route_path("web/"));
    assert!(!is_spa_route_path("web/assets/app.js"));
    assert!(!is_spa_route_path("web/missing.png"));
}

async fn send_asset_request_with_host(state: Arc<HubState>, target: &str, host: &str) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
    let addr = listener.local_addr().expect("local_addr");
    let token = state.ws_token().to_owned();
    let st = Arc::clone(&state);

    let server = tokio::spawn(async move {
        if let Ok((stream, _)) = listener.accept().await {
            handle_http_asset(stream, st).await;
        }
    });

    let mut client = TcpStream::connect(addr).await.expect("connect");
    let req =
        format!("GET {target} HTTP/1.1\r\nHost: {host}\r\nAuthorization: Bearer {token}\r\n\r\n");
    client.write_all(req.as_bytes()).await.expect("write req");
    let mut resp = String::new();
    client.read_to_string(&mut resp).await.expect("read resp");
    let _ = server.await;
    resp
}

async fn send_asset_request(state: Arc<HubState>, target: &str) -> String {
    send_asset_request_with_host(state, target, "127.0.0.1").await
}

#[tokio::test]
async fn serves_spa_fallback_and_immutable_asset_cache_headers() {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let dir =
        std::env::temp_dir().join(format!("sideveil-asset-spa-{}-{nanos}", std::process::id()));
    let plugin_dir = dir.join("plugins/com.spa.demo");
    fs::create_dir_all(plugin_dir.join("web/assets")).expect("mkdir");
    fs::write(plugin_dir.join("web/index.html"), "<html>spa-root</html>").expect("write html");
    fs::write(plugin_dir.join("web/assets/app-1234.js"), "console.log(1);").expect("write js");
    fs::write(
        plugin_dir.join("manifest.json"),
        r#"{
            "id": "com.spa.demo",
            "name": "SPA Demo",
            "version": "1.0.0",
            "entry": "web/index.html",
            "network": { "spaFallback": true }
        }"#,
    )
    .expect("write manifest");

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

    // 1. Extensionless route falls back to web/index.html with no-store.
    let spa_resp =
        send_asset_request(Arc::clone(&state), "/plugins/com.spa.demo/web/settings/42").await;
    assert!(
        spa_resp.starts_with("HTTP/1.1 200 OK\r\n"),
        "resp: {spa_resp}"
    );
    assert!(spa_resp.contains("Content-Type: text/html; charset=utf-8\r\n"));
    assert!(spa_resp.contains("Cache-Control: no-store\r\n"));
    assert!(spa_resp.ends_with("<html>spa-root</html>"));

    // 2. Missing static asset with extension still returns 404.
    let missing_js = send_asset_request(
        Arc::clone(&state),
        "/plugins/com.spa.demo/web/assets/missing.js",
    )
    .await;
    assert!(missing_js.starts_with("HTTP/1.1 404 Not Found\r\n"));

    // 3. Traversal outside web/ still returns 403 Forbidden (never falls back!).
    let traversal = send_asset_request(
        Arc::clone(&state),
        "/plugins/com.spa.demo/web/../manifest.json",
    )
    .await;
    assert!(traversal.starts_with("HTTP/1.1 403 Forbidden\r\n"));

    // 4. Hashed asset under /assets/ gets short immutable Cache-Control and weak ETag.
    let asset_resp = send_asset_request(
        Arc::clone(&state),
        "/plugins/com.spa.demo/web/assets/app-1234.js",
    )
    .await;
    assert!(asset_resp.starts_with("HTTP/1.1 200 OK\r\n"));
    assert!(asset_resp.contains("Cache-Control: private, max-age=300, immutable\r\n"));
    assert!(asset_resp.contains("ETag: W/\""));

    // 5. Missing root /favicon.ico returns 204 No Content instead of 403 Forbidden.
    let favicon_204 =
        send_asset_request(Arc::clone(&state), "/plugins/com.spa.demo/favicon.ico").await;
    assert!(
        favicon_204.starts_with("HTTP/1.1 204 No Content\r\n"),
        "expected 204 for missing favicon, got: {favicon_204}"
    );

    // 6. When web/favicon.ico exists, /favicon.ico serves it with 200 OK (image/x-icon).
    fs::write(plugin_dir.join("web/favicon.ico"), b"ICO").expect("write favicon");
    let favicon_200 =
        send_asset_request(Arc::clone(&state), "/plugins/com.spa.demo/favicon.ico").await;
    assert!(
        favicon_200.starts_with("HTTP/1.1 200 OK\r\n"),
        "expected 200 when web/favicon.ico exists, got: {favicon_200}"
    );
    assert!(favicon_200.contains("Content-Type: image/x-icon\r\n"));
    assert!(favicon_200.ends_with("ICO"));

    // 7. Non-favicon files outside web/ remain strictly 403 Forbidden.
    fs::write(plugin_dir.join("secret.txt"), "top-secret").expect("write sibling");
    let sibling_403 =
        send_asset_request(Arc::clone(&state), "/plugins/com.spa.demo/secret.txt").await;
    assert!(sibling_403.starts_with("HTTP/1.1 403 Forbidden\r\n"));

    // 8. DNS-rebinding Host header is rejected with 403 Forbidden.
    let rebind_403 = send_asset_request_with_host(
        Arc::clone(&state),
        "/plugins/com.spa.demo/web/index.html",
        "rebind.attacker.example",
    )
    .await;
    assert!(rebind_403.starts_with("HTTP/1.1 403 Forbidden\r\n"));

    let _ = fs::remove_dir_all(&dir);
}
