//! Unit and integration tests for `RuntimeMode` (`release`/`debug`) plugin gating
//! and the `debug.setRuntimeMode` RPC handler.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, PoisonError};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::{Value, json};
use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use sideveil_protocol::config::{RuntimeMode, SideVeilConfig};
use sideveil_protocol::generated::protocol_constants::builtin_test_plugins;
use sideveil_protocol::rpc::{RpcRequest, error_codes, events, methods};
use tokio::sync::mpsc;

use super::{RpcCaller, dispatch_rpc_request};
use crate::config_store::ConfigStore;
use crate::log_ring::LogRing;
use crate::outbound_queue;
use crate::plugin_registry::PluginRegistry;
use crate::state::HubState;

fn write_plugin(plugins_dir: &Path, id: &str, global: bool, priority: i32) {
    let dir = plugins_dir.join(id);
    fs::create_dir_all(dir.join("web")).expect("mkdir plugin web");
    fs::write(dir.join("web/index.html"), "<html></html>").expect("write html");
    let manifest = json!({
        "id": id,
        "name": id,
        "version": "1.0.0",
        "entry": "web/index.html",
        "triggers": { "packages": [], "global": global, "priority": priority }
    });
    fs::write(dir.join("manifest.json"), manifest.to_string()).expect("write manifest");
}

fn make_state(label: &str) -> (PathBuf, Arc<HubState>) {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let dir = std::env::temp_dir().join(format!(
        "sideveil-runtime-mode-{label}-{}-{nanos}",
        std::process::id()
    ));
    let plugins_dir = dir.join("plugins");
    fs::create_dir_all(&plugins_dir).expect("mkdir plugins");
    write_plugin(&plugins_dir, builtin_test_plugins::VELLO_SCOPE, true, 50);
    write_plugin(
        &plugins_dir,
        builtin_test_plugins::NATIVE_PERF_HUD,
        true,
        40,
    );
    write_plugin(&plugins_dir, "sideveil.proxy", true, 15);
    write_plugin(&plugins_dir, "strata.manager", true, 10);

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
    {
        let mut rt = state
            .runtime
            .write()
            .unwrap_or_else(PoisonError::into_inner);
        rt.foreground_package = "me.weishu.kernelsu".to_owned();
    }
    (dir, state)
}

#[tokio::test]
async fn release_mode_hides_builtin_test_plugins_and_debug_mode_exposes_them() {
    let (dir, state) = make_state("gating");

    // Default runtimeMode is release: all 3 builtin test plugins are excluded from matchedPluginIds,
    // while third-party plugins remain active and selectable.
    let initial = state.plugins_snapshot();
    assert_eq!(
        state.config_store().current().debug.runtime_mode,
        RuntimeMode::Release
    );
    assert_eq!(
        initial.matched_plugin_ids,
        vec!["sideveil.proxy", "strata.manager"]
    );
    assert_eq!(initial.active_plugin_id.as_deref(), Some("sideveil.proxy"));
    // Full plugin list metadata remains intact for Control Plane visibility.
    assert!(
        initial
            .plugins
            .iter()
            .any(|p| p.id == builtin_test_plugins::DIAGNOSTIC)
    );
    assert!(
        initial
            .plugins
            .iter()
            .any(|p| p.id == builtin_test_plugins::VELLO_SCOPE)
    );
    assert!(
        initial
            .plugins
            .iter()
            .any(|p| p.id == builtin_test_plugins::NATIVE_PERF_HUD)
    );

    // Switch to debug mode via RPC and verify all 3 builtin test plugins become matchable.
    let to_debug = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "m1",
            methods::DEBUG_SET_RUNTIME_MODE,
            Some(json!({ "runtimeMode": "debug" })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    assert!(to_debug.error.is_none());
    let debug_res = to_debug.result.expect("debug result");
    assert_eq!(debug_res.get("runtimeMode"), Some(&json!("debug")));
    assert_eq!(debug_res.get("previousMode"), Some(&json!("release")));

    let debug_snap = state.plugins_snapshot();
    assert_eq!(
        debug_snap.matched_plugin_ids,
        vec![
            builtin_test_plugins::VELLO_SCOPE,
            builtin_test_plugins::NATIVE_PERF_HUD,
            builtin_test_plugins::DIAGNOSTIC,
            "sideveil.proxy",
            "strata.manager",
        ]
    );
    assert_eq!(
        debug_snap.active_plugin_id.as_deref(),
        Some(builtin_test_plugins::VELLO_SCOPE)
    );

    let _ = fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn switching_debug_to_release_re_evaluates_active_plugin_persists_and_broadcasts() {
    let (dir, state) = make_state("switch-broadcast");

    // Start in debug mode and pin MRU active plugin to example.vello-scope.
    let _ = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "s1",
            methods::DEBUG_SET_RUNTIME_MODE,
            Some(json!({ "runtimeMode": "debug" })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    state
        .select_active_plugin(
            builtin_test_plugins::VELLO_SCOPE,
            Some("me.weishu.kernelsu"),
        )
        .expect("select vello-scope in debug mode");
    assert_eq!(
        state.plugins_snapshot().active_plugin_id.as_deref(),
        Some(builtin_test_plugins::VELLO_SCOPE)
    );

    // Connect a host session and drain initial handshake events.
    let (tx, mut rx) = outbound_queue::channel();
    let _sid = state.register_host_session(tx);
    let _ = rx.recv().await; // initial config.updated
    let _ = rx.recv().await; // initial plugins.updated

    // Switch back to release mode via debug.setRuntimeMode without restarting.
    let to_release = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "s2",
            methods::DEBUG_SET_RUNTIME_MODE,
            Some(json!({ "runtimeMode": "release" })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    assert!(to_release.error.is_none());
    let val = to_release.result.expect("release result");
    assert_eq!(val.get("ok"), Some(&json!(true)));
    assert_eq!(val.get("runtimeMode"), Some(&json!("release")));
    assert_eq!(val.get("previousMode"), Some(&json!("debug")));
    assert_eq!(
        val.pointer("/snapshot/activePluginId"),
        Some(&json!("sideveil.proxy"))
    );
    assert_eq!(
        val.pointer("/snapshot/matchedPluginIds"),
        Some(&json!(["sideveil.proxy", "strata.manager"]))
    );

    // Persisted config.json on disk must carry runtimeMode="release".
    let persisted: SideVeilConfig =
        serde_json::from_str(&fs::read_to_string(dir.join("config.json")).expect("read config"))
            .expect("parse persisted config");
    assert_eq!(persisted.debug.runtime_mode, RuntimeMode::Release);

    // Connected Host session must receive config.updated and plugins.updated broadcasts.
    let mut seen_config = false;
    let mut seen_plugins = false;
    while let Ok(Some(raw)) = tokio::time::timeout(Duration::from_millis(50), rx.recv()).await {
        let ev: Value = serde_json::from_str(&raw).expect("valid event json");
        match ev.get("event").and_then(Value::as_str) {
            Some(events::CONFIG_UPDATED) => {
                assert_eq!(
                    ev.pointer("/payload/config/debug/runtimeMode"),
                    Some(&json!("release"))
                );
                seen_config = true;
            }
            Some(events::PLUGINS_UPDATED) => {
                assert_eq!(
                    ev.pointer("/payload/activePluginId"),
                    Some(&json!("sideveil.proxy"))
                );
                assert_eq!(
                    ev.pointer("/payload/matchedPluginIds"),
                    Some(&json!(["sideveil.proxy", "strata.manager"]))
                );
                seen_plugins = true;
            }
            _ => {}
        }
    }
    assert!(seen_config, "expected config.updated broadcast");
    assert!(seen_plugins, "expected plugins.updated broadcast");

    // When all non-test plugins are disabled in release mode, activePluginId falls back to None.
    state
        .plugin_registry()
        .set_enabled("sideveil.proxy", false)
        .expect("disable proxy");
    state
        .plugin_registry()
        .set_enabled("strata.manager", false)
        .expect("disable strata");
    let empty_snap = state.plugins_snapshot();
    assert!(empty_snap.matched_plugin_ids.is_empty());
    assert_eq!(empty_snap.active_plugin_id, None);

    let _ = fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn set_runtime_mode_enforces_privilege_and_validates_params() {
    let (dir, state) = make_state("acl");

    let mut ext = RpcRequest::new(
        "a1",
        methods::DEBUG_SET_RUNTIME_MODE,
        Some(json!({ "runtimeMode": "debug" })),
    );
    ext.plugin_id = Some("com.example.untrusted".to_owned());
    let denied = dispatch_rpc_request(&state, ext, RpcCaller::HostWebView).await;
    assert_eq!(
        denied.error.as_ref().map(|e| e.code),
        Some(error_codes::PERMISSION_DENIED)
    );

    let invalid = dispatch_rpc_request(
        &state,
        RpcRequest::new(
            "a2",
            methods::DEBUG_SET_RUNTIME_MODE,
            Some(json!({ "runtimeMode": "staging" })),
        ),
        RpcCaller::Ctl,
    )
    .await;
    assert_eq!(
        invalid.error.as_ref().map(|e| e.code),
        Some(error_codes::INVALID_PARAMS)
    );

    let _ = fs::remove_dir_all(&dir);
}
