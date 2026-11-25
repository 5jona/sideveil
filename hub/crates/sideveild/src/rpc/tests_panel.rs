//! Unit tests for `panel.open` plugin switching behavior.

use std::fs;
use std::path::PathBuf;
use std::sync::{Arc, PoisonError};
use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::json;
use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use sideveil_protocol::rpc::{RpcRequest, methods};
use tokio::sync::mpsc;

use super::{RpcCaller, dispatch_rpc_request};
use crate::config_store::ConfigStore;
use crate::log_ring::LogRing;
use crate::outbound_queue;
use crate::plugin_registry::PluginRegistry;
use crate::state::HubState;

fn make_test_state(label: &str) -> (PathBuf, Arc<HubState>) {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let dir = std::env::temp_dir().join(format!(
        "sideveil-rpc-panel-{label}-{}-{nanos}",
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
async fn panel_open_switches_active_plugin_when_already_open() {
    let (dir, state) = make_test_state("panel-open-switch");

    // Create a second enabled web-embed plugin alongside built-in diagnostic.
    let created = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "p1",
            methods::PLUGINS_CREATE_WEB_EMBED,
            Some(json!({
                "id": "lan.homeassistant",
                "name": "Home Assistant",
                "url": "https://ha.example.com"
            })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    assert!(created.error.is_none());

    // Simulate drawer already open on built-in diagnostic with a foreground app & host session.
    {
        let mut rt = state
            .runtime
            .write()
            .unwrap_or_else(PoisonError::into_inner);
        rt.foreground_package = "com.example.app".to_owned();
        rt.panel_state = sideveil_protocol::config::PanelState::Open;
    }
    let _ = state.select_active_plugin(sideveil_protocol::plugin::BUILTIN_DIAG_PLUGIN_ID, None);
    let (tx, mut rx) = outbound_queue::channel();
    let _sid = state.register_host_session(tx);
    let _ = rx.recv().await; // initial config.updated
    let _ = rx.recv().await; // initial network.contextChanged
    let _ = rx.recv().await; // initial plugins.updated

    let resp = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "p2",
            methods::PANEL_OPEN,
            Some(json!({ "pluginId": "lan.homeassistant" })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    assert!(resp.error.is_none());
    assert_eq!(
        resp.result.as_ref().and_then(|v| v.get("activePluginId")),
        Some(&json!("lan.homeassistant"))
    );
    assert_eq!(
        state.plugins_snapshot().active_plugin_id.as_deref(),
        Some("lan.homeassistant")
    );

    // Host must receive plugins.activeChanged followed by panel.command(open).
    let ev1: serde_json::Value =
        serde_json::from_str(&rx.recv().await.expect("activeChanged event")).expect("valid json");
    assert_eq!(
        ev1.get("event"),
        Some(&json!(
            sideveil_protocol::rpc::events::PLUGINS_ACTIVE_CHANGED
        ))
    );
    assert_eq!(
        ev1.pointer("/payload/pluginId"),
        Some(&json!("lan.homeassistant"))
    );

    let ev2: serde_json::Value =
        serde_json::from_str(&rx.recv().await.expect("panel.command event")).expect("valid json");
    assert_eq!(
        ev2.get("event"),
        Some(&json!(sideveil_protocol::rpc::events::PANEL_COMMAND))
    );
    assert_eq!(ev2.pointer("/payload/command"), Some(&json!("open")));

    let _ = fs::remove_dir_all(&dir);
}
