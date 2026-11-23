//! Unit tests for inbound `:sensor` message routing (`events::sensor`).

use std::fs;
use std::path::PathBuf;
use std::sync::{Arc, PoisonError};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use sideveil_protocol::config::PanelState;
use sideveil_protocol::rpc::{HostEventEnvelope, PanelCommand, PanelCommandPayload, events};
use sideveil_protocol::sensor::SensorToHub;
use tokio::sync::mpsc;

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
        "sideveil-sensor-events-{label}-{}-{nanos}",
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
async fn status_bar_pull_down_closes_panel_only_when_open() {
    let (dir, state) = make_test_state("status-bar-pull-down");
    let (tx, mut rx) = outbound_queue::channel();
    state.register_host_session(tx);
    while tokio::time::timeout(Duration::from_millis(10), rx.recv())
        .await
        .is_ok()
    {}

    // When panel is closed, StatusBarPullDown is a no-op.
    state.on_sensor_message(SensorToHub::StatusBarPullDown);
    assert!(
        tokio::time::timeout(Duration::from_millis(20), rx.recv())
            .await
            .is_err()
    );

    // When panel is open, StatusBarPullDown dispatches PanelCommand::Close.
    {
        let mut rt = state
            .runtime
            .write()
            .unwrap_or_else(PoisonError::into_inner);
        rt.panel_state = PanelState::Open;
    }
    state.on_sensor_message(SensorToHub::StatusBarPullDown);
    let raw = rx.recv().await.expect("expected PanelCommand::Close frame");
    let env: HostEventEnvelope = serde_json::from_str(&raw).expect("decode HostEventEnvelope");
    assert_eq!(env.event, events::PANEL_COMMAND);
    let payload: PanelCommandPayload =
        serde_json::from_value(env.payload.expect("payload")).expect("decode payload");
    assert_eq!(payload.command, PanelCommand::Close);

    let _ = fs::remove_dir_all(&dir);
}
