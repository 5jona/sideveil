//! Unit tests for web-embed lifecycle RPCs, uninstall, and `network.context` ACL + sensor routing.

use std::fs;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::{Value, json};
use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use sideveil_protocol::plugin::PluginDescriptor;
use sideveil_protocol::rpc::{RpcRequest, RpcResponse, error_codes, methods};
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

async fn call_ctl(state: &Arc<HubState>, method: &str, params: Option<Value>) -> RpcResponse {
    dispatch_rpc_request(
        state,
        RpcRequest::new("req", method, params),
        RpcCaller::Ctl,
    )
    .await
}

fn find_plugin(state: &Arc<HubState>, id: &str) -> PluginDescriptor {
    state
        .plugins_snapshot()
        .plugins
        .into_iter()
        .find(|p| p.id == id)
        .expect("plugin descriptor present")
}

#[tokio::test]
async fn web_embed_create_override_and_uninstall_lifecycle() {
    let (dir, state) = make_test_state("lifecycle");

    // 1. Create a LAN cleartext web-embed plugin via privileged RPC.
    let created = call_ctl(
        &state,
        methods::PLUGINS_CREATE_WEB_EMBED,
        Some(json!({
            "id": "embed.router",
            "name": "路由器后台",
            "url": "http://192.168.1.1",
            "allowCleartext": true,
            "keepAlive": true
        })),
    )
    .await;
    assert!(created.error.is_none(), "create error: {:?}", created.error);
    assert!(state.plugin_registry().contains_plugin("embed.router"));

    // 2. Reject public cleartext HTTP in createWebEmbed.
    let bad_public = call_ctl(
        &state,
        methods::PLUGINS_CREATE_WEB_EMBED,
        Some(json!({
            "id": "embed.public-http",
            "name": "Bad",
            "url": "http://example.com",
            "allowCleartext": true
        })),
    )
    .await;
    assert_eq!(
        bad_public.error.as_ref().map(|e| e.code),
        Some(error_codes::INVALID_PARAMS)
    );

    // 3. Apply embed config override via plugins.setEmbedConfig.
    let updated = call_ctl(
        &state,
        methods::PLUGINS_SET_EMBED_CONFIG,
        Some(json!({
            "pluginId": "embed.router",
            "embedUrl": "http://10.0.0.1:8080/ui",
            "allowedOrigins": ["http://10.0.0.2:8080"],
            "allowCleartext": true,
            "keepAlive": false
        })),
    )
    .await;
    assert!(updated.error.is_none(), "update error: {:?}", updated.error);
    let desc = find_plugin(&state, "embed.router");
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
    let builtin_del = call_ctl(
        &state,
        methods::PLUGINS_UNINSTALL,
        Some(json!({ "pluginId": "sideveil.diagnostic" })),
    )
    .await;
    assert!(builtin_del.error.is_some());

    // 6. Uninstall external web-embed plugin cleanly.
    let uninst = call_ctl(
        &state,
        methods::PLUGINS_UNINSTALL,
        Some(json!({ "pluginId": "embed.router" })),
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
async fn web_embed_home_assistant_dual_url_and_prefs_overrides() {
    let (dir, state) = make_test_state("ha-phase1");

    let created = call_ctl(
        &state,
        methods::PLUGINS_CREATE_WEB_EMBED,
        Some(json!({
            "id": "embed.ha",
            "name": "Home Assistant",
            "url": "http://homeassistant.local:8123",
            "remoteUrl": "  https://ha.example.com:8123  ",
            "lanSsids": ["  HomeIoT  ", "", "HomeIoT-5G"],
            "allowCleartext": true,
            "keepAlive": true,
            "wideViewport": false,
            "allowZoom": true,
            "webHistoryBack": false,
            "textZoom": 115,
            "showToolbar": false,
            "keepAliveGraceSec": 300,
            "widthDp": 420,
            "heightPercent": 80
        })),
    )
    .await;
    assert!(created.error.is_none(), "create error: {:?}", created.error);

    let desc = find_plugin(&state, "embed.ha");
    assert_eq!(desc.surface.width_dp, Some(420));
    assert_eq!(desc.surface.height_percent, Some(80));
    let embed = desc.embed.expect("embed prefs");
    assert_eq!(embed.url, "http://homeassistant.local:8123");
    assert_eq!(
        embed.remote_url.as_deref(),
        Some("https://ha.example.com:8123")
    );
    assert_eq!(embed.lan_ssids, vec!["HomeIoT", "HomeIoT-5G"]);
    assert!(
        !embed.wide_viewport && embed.allow_zoom && !embed.web_history_back && !embed.show_toolbar
    );
    assert_eq!(embed.text_zoom, Some(115));
    assert_eq!(embed.keep_alive_grace_sec, Some(300));

    // Reject invalid createWebEmbed and setEmbedConfig parameters.
    for bad_create in [
        json!({ "id": "embed.b1", "name": "Bad", "url": "http://192.168.1.50:8123", "remoteUrl": "http://ha.example.com:8123", "allowCleartext": true }),
        json!({ "id": "embed.b2", "name": "Bad", "url": "https://ha.example.com:8123", "textZoom": 250 }),
        json!({ "id": "embed.b3", "name": "Bad", "url": "https://ha.example.com:8123", "keepAliveGraceSec": 2000 }),
    ] {
        let resp = call_ctl(&state, methods::PLUGINS_CREATE_WEB_EMBED, Some(bad_create)).await;
        assert_eq!(
            resp.error.as_ref().map(|e| e.code),
            Some(error_codes::INVALID_PARAMS)
        );
    }
    for bad_set in [
        json!({ "pluginId": "embed.ha", "remoteUrl": "http://ha.example.com:8123" }),
        json!({ "pluginId": "embed.ha", "textZoom": 40 }),
        json!({ "pluginId": "embed.ha", "keepAliveGraceSec": -5 }),
        json!({ "pluginId": "embed.ha", "widthDp": 200 }),
        json!({ "pluginId": "embed.ha", "heightPercent": 110 }),
    ] {
        let resp = call_ctl(&state, methods::PLUGINS_SET_EMBED_CONFIG, Some(bad_set)).await;
        assert_eq!(
            resp.error.as_ref().map(|e| e.code),
            Some(error_codes::INVALID_PARAMS)
        );
    }

    let updated = call_ctl(
        &state,
        methods::PLUGINS_SET_EMBED_CONFIG,
        Some(json!({
            "pluginId": "embed.ha",
            "embedUrl": "http://192.168.1.50:8123",
            "remoteUrl": "https://ha2.example.com:8123",
            "lanSsids": ["LabWifi"],
            "wideViewport": true,
            "allowZoom": false,
            "webHistoryBack": true,
            "textZoom": 90,
            "showToolbar": true,
            "keepAliveGraceSec": 60,
            "widthDp": 360,
            "heightPercent": 70
        })),
    )
    .await;
    assert!(updated.error.is_none(), "update error: {:?}", updated.error);
    let desc2 = find_plugin(&state, "embed.ha");
    assert_eq!(
        (desc2.surface.width_dp, desc2.surface.height_percent),
        (Some(360), Some(70))
    );
    let embed2 = desc2.embed.unwrap();
    assert_eq!(embed2.url, "http://192.168.1.50:8123");
    assert_eq!(
        embed2.remote_url.as_deref(),
        Some("https://ha2.example.com:8123")
    );
    assert_eq!(embed2.lan_ssids, vec!["LabWifi"]);
    assert!(
        embed2.wide_viewport
            && !embed2.allow_zoom
            && embed2.web_history_back
            && embed2.show_toolbar
    );
    assert_eq!(
        (embed2.text_zoom, embed2.keep_alive_grace_sec),
        (Some(90), Some(60))
    );

    // Whitespace remoteUrl clears entry.remote_url override; clear: true restores all manifest values.
    let clear_remote = call_ctl(
        &state,
        methods::PLUGINS_SET_EMBED_CONFIG,
        Some(json!({ "pluginId": "embed.ha", "remoteUrl": "  " })),
    )
    .await;
    assert!(clear_remote.error.is_none());
    assert_eq!(
        find_plugin(&state, "embed.ha")
            .embed
            .unwrap()
            .remote_url
            .as_deref(),
        Some("https://ha.example.com:8123")
    );

    let cleared = call_ctl(
        &state,
        methods::PLUGINS_SET_EMBED_CONFIG,
        Some(json!({ "pluginId": "embed.ha", "clear": true })),
    )
    .await;
    assert!(cleared.error.is_none());
    let desc4 = find_plugin(&state, "embed.ha");
    assert_eq!(desc4.surface.width_dp, Some(420));
    assert_eq!(desc4.embed.unwrap().text_zoom, Some(115));

    let _ = fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn network_context_acl_gate_and_sensor_broadcast() {
    let (dir, state) = make_test_state("netctx");

    // Register two plugins: one with network-context capability, one without.
    for (id, caps) in [
        ("com.net.reader", r#"["network-context"]"#),
        ("com.net.denied", "[]"),
    ] {
        let pdir = dir.join("plugins").join(id);
        fs::create_dir_all(pdir.join("web")).expect("mkdir");
        fs::write(pdir.join("web/index.html"), "<html></html>").expect("write html");
        fs::write(
            pdir.join("manifest.json"),
            format!(
                r#"{{"id":"{id}","name":"{id}","version":"1.0.0","entry":"web/index.html","capabilities":{caps}}}"#
            ),
        )
        .expect("write manifest");
    }
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
    state.on_sensor_message(SensorToHub::NetworkContextChanged { context: ctx });

    // Connected native backend received network.contextChanged notification.
    let frame = backend_rx.try_recv().expect("backend system event");
    let frame_json = serde_json::to_value(&frame).expect("encode frame");
    assert_eq!(frame_json["event"], "network.contextChanged");
    assert_eq!(frame_json["payload"]["localIpv4"], "192.168.10.25");

    // Privileged Ctl caller succeeds.
    let ctl_resp = call_ctl(&state, methods::NETWORK_CONTEXT, None).await;
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
