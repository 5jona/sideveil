//! Unit tests for web-embed lifecycle RPCs, uninstall, and `network.context` ACL + sensor routing.

use std::fs;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::json;
use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use sideveil_protocol::rpc::{RpcRequest, error_codes, methods};
use sideveil_protocol::sensor::{NetworkContextSnapshot, NetworkTransportKind, SensorToHub};
use tokio::sync::mpsc;

use super::{RpcCaller, dispatch_rpc_request};
use crate::config_store::ConfigStore;
use crate::log_ring::LogRing;
use crate::plugin_registry::PluginRegistry;
use crate::state::HubState;

fn make_test_state(label: &str) -> (PathBuf, Arc<HubState>) {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let dir = std::env::temp_dir().join(format!(
        "sideveil-embed-{label}-{}-{nanos}",
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
async fn web_embed_create_override_and_uninstall_lifecycle() {
    let (dir, state) = make_test_state("lifecycle");

    // 1. Create a LAN cleartext web-embed plugin via privileged RPC.
    let created = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "c1",
            methods::PLUGINS_CREATE_WEB_EMBED,
            Some(json!({
                "id": "embed.router",
                "name": "路由器后台",
                "url": "http://192.168.1.1",
                "allowCleartext": true,
                "keepAlive": true
            })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    assert!(created.error.is_none(), "create error: {:?}", created.error);
    assert!(state.plugin_registry().contains_plugin("embed.router"));

    // 2. Reject public cleartext HTTP in createWebEmbed.
    let bad_public = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "c2",
            methods::PLUGINS_CREATE_WEB_EMBED,
            Some(json!({
                "id": "embed.public-http",
                "name": "Bad",
                "url": "http://example.com",
                "allowCleartext": true
            })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    assert_eq!(
        bad_public.error.as_ref().map(|e| e.code),
        Some(error_codes::INVALID_PARAMS)
    );

    // 3. Apply embed config override via plugins.setEmbedConfig.
    let updated = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "c3",
            methods::PLUGINS_SET_EMBED_CONFIG,
            Some(json!({
                "pluginId": "embed.router",
                "embedUrl": "http://10.0.0.1:8080/ui",
                "allowedOrigins": ["http://10.0.0.2:8080"],
                "allowCleartext": true,
                "keepAlive": false
            })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    assert!(updated.error.is_none(), "update error: {:?}", updated.error);
    let snap = state.plugins_snapshot();
    let desc = snap
        .plugins
        .iter()
        .find(|p| p.id == "embed.router")
        .expect("embed descriptor present");
    assert_eq!(
        desc.embed.as_ref().map(|e| e.url.as_str()),
        Some("http://10.0.0.1:8080/ui")
    );
    assert!(!desc.surface.keep_alive);

    // 4. External plugin cannot call privileged create/set/uninstall methods.
    for method in [
        methods::PLUGINS_CREATE_WEB_EMBED,
        methods::PLUGINS_SET_EMBED_CONFIG,
        methods::PLUGINS_UNINSTALL,
    ] {
        let mut req = RpcRequest::new("deny", method, Some(json!({ "pluginId": "embed.router" })));
        req.plugin_id = Some("embed.router".to_owned());
        let resp = dispatch_rpc_request(&state, req, RpcCaller::HostWebView).await;
        assert_eq!(
            resp.error.as_ref().map(|e| e.code),
            Some(error_codes::PERMISSION_DENIED)
        );
    }

    // 5. Refuse uninstalling built-in diagnostic plugin.
    let builtin_del = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "c4",
            methods::PLUGINS_UNINSTALL,
            Some(json!({ "pluginId": "sideveil.diagnostic" })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    assert!(builtin_del.error.is_some());

    // 6. Uninstall external web-embed plugin cleanly.
    let uninst = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "c5",
            methods::PLUGINS_UNINSTALL,
            Some(json!({ "pluginId": "embed.router" })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    assert!(
        uninst.error.is_none(),
        "uninstall error: {:?}",
        uninst.error
    );
    assert!(!state.plugin_registry().contains_plugin("embed.router"));
    assert!(!dir.join("plugins/embed.router").exists());

    let _ = fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn network_context_acl_gate_and_sensor_broadcast() {
    let (dir, state) = make_test_state("netctx");

    // Register two plugins: one with network-context capability, one without.
    let with_cap_dir = dir.join("plugins/com.net.reader");
    fs::create_dir_all(with_cap_dir.join("web")).expect("mkdir reader");
    fs::write(with_cap_dir.join("web/index.html"), "<html></html>").expect("write html");
    fs::write(
        with_cap_dir.join("manifest.json"),
        r#"{
            "id": "com.net.reader",
            "name": "Net Reader",
            "version": "1.0.0",
            "entry": "web/index.html",
            "capabilities": ["network-context"]
        }"#,
    )
    .expect("write manifest");

    let no_cap_dir = dir.join("plugins/com.net.denied");
    fs::create_dir_all(no_cap_dir.join("web")).expect("mkdir denied");
    fs::write(no_cap_dir.join("web/index.html"), "<html></html>").expect("write html");
    fs::write(
        no_cap_dir.join("manifest.json"),
        r#"{
            "id": "com.net.denied",
            "name": "No Cap",
            "version": "1.0.0",
            "entry": "web/index.html"
        }"#,
    )
    .expect("write manifest");
    state.plugin_registry().rescan();

    // Connect a uds-native backend to verify system notification forwarding.
    let (_sid, mut backend_rx) = state.plugin_backends().register("com.net.reader", true);

    let ctx = NetworkContextSnapshot {
        transport: NetworkTransportKind::Wifi,
        vpn_active: true,
        validated: true,
        metered: false,
        interface_name: Some("wlan0".to_owned()),
        ssid: Some("Office5G".to_owned()),
        gateway: Some("192.168.10.1".to_owned()),
        local_ipv4: Some("192.168.10.25".to_owned()),
    };
    state.on_sensor_message(SensorToHub::NetworkContextChanged {
        context: ctx.clone(),
    });

    // Connected native backend received network.contextChanged notification.
    let frame = backend_rx.try_recv().expect("backend system event");
    let frame_json = serde_json::to_value(&frame).expect("encode frame");
    assert_eq!(frame_json["event"], "network.contextChanged");
    assert_eq!(frame_json["payload"]["localIpv4"], "192.168.10.25");

    // Privileged Ctl caller succeeds.
    let ctl_resp = dispatch_rpc_request(
        &state,
        RpcRequest::new("n1", methods::NETWORK_CONTEXT, None),
        RpcCaller::Ctl,
    )
    .await;
    assert!(ctl_resp.error.is_none());
    assert_eq!(
        ctl_resp.result.as_ref().and_then(|v| v.get("transport")),
        Some(&json!("wifi"))
    );

    // Plugin with network-context capability succeeds.
    let mut allowed_req = RpcRequest::new("n2", methods::NETWORK_CONTEXT, None);
    allowed_req.plugin_id = Some("com.net.reader".to_owned());
    let allowed_resp = dispatch_rpc_request(&state, allowed_req, RpcCaller::HostWebView).await;
    assert!(allowed_resp.error.is_none());
    assert_eq!(
        allowed_resp
            .result
            .as_ref()
            .and_then(|v| v.get("vpnActive")),
        Some(&json!(true))
    );

    // Plugin without network-context capability gets PERMISSION_DENIED.
    let mut denied_req = RpcRequest::new("n3", methods::NETWORK_CONTEXT, None);
    denied_req.plugin_id = Some("com.net.denied".to_owned());
    let denied_resp = dispatch_rpc_request(&state, denied_req, RpcCaller::HostWebView).await;
    assert_eq!(
        denied_resp.error.as_ref().map(|e| e.code),
        Some(error_codes::PERMISSION_DENIED)
    );

    let _ = fs::remove_dir_all(&dir);
}
