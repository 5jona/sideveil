//! Unit tests for `HubState` host session hydration and initial event ordering.

use std::fs;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::Value;
use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use sideveil_protocol::rpc::events;
use sideveil_protocol::sensor::{NetworkContextSnapshot, NetworkTransportKind, SensorToHub};
use tokio::sync::mpsc;

use super::HubState;
use crate::config_store::ConfigStore;
use crate::log_ring::LogRing;
use crate::outbound_queue;
use crate::plugin_registry::PluginRegistry;

fn make_test_state(label: &str) -> (PathBuf, Arc<HubState>) {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let dir = std::env::temp_dir().join(format!(
        "sideveil-state-{label}-{}-{nanos}",
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
async fn register_host_session_pushes_cached_network_context_before_plugins_snapshot() {
    let (dir, state) = make_test_state("host-register-netctx");

    // Sensor reports Wi-Fi context before Host connects.
    state.on_sensor_message(SensorToHub::NetworkContextChanged {
        context: NetworkContextSnapshot {
            transport: NetworkTransportKind::Wifi,
            vpn_active: false,
            validated: true,
            metered: false,
            interface_name: Some("wlan0".to_owned()),
            ssid: Some("HomeWifi".to_owned()),
            gateway: Some("192.0.2.1".to_owned()),
            local_ipv4: Some("192.0.2.10".to_owned()),
        },
    });

    let (tx, mut rx) = outbound_queue::channel();
    let _session_id = state.register_host_session(tx);

    let ev1: Value =
        serde_json::from_str(&rx.recv().await.expect("config.updated frame")).expect("valid json");
    let ev2: Value = serde_json::from_str(&rx.recv().await.expect("network.contextChanged frame"))
        .expect("valid json");
    let ev3: Value =
        serde_json::from_str(&rx.recv().await.expect("plugins.updated frame")).expect("valid json");

    assert_eq!(
        ev1.get("event").and_then(Value::as_str),
        Some(events::CONFIG_UPDATED)
    );
    assert_eq!(
        ev2.get("event").and_then(Value::as_str),
        Some(events::NETWORK_CONTEXT_CHANGED)
    );
    assert_eq!(
        ev2.pointer("/payload/transport").and_then(Value::as_str),
        Some("wifi")
    );
    assert_eq!(
        ev2.pointer("/payload/ssid").and_then(Value::as_str),
        Some("HomeWifi")
    );
    assert_eq!(
        ev3.get("event").and_then(Value::as_str),
        Some(events::PLUGINS_UPDATED)
    );

    let _ = fs::remove_dir_all(&dir);
}
