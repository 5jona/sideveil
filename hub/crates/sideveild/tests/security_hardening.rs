//! Security hardening tests: caller role ACLs, diagnostic dev-URL demotion, tiered capability
//! grants, `.svp` upgrade diffs, and daemon re-approval resets.
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::{Value, json};
use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use sideveil_protocol::plugin::{
    BUILTIN_DIAG_PLUGIN_ID, PluginApprovalStatus, PluginCapability, PluginInstallParams,
    PluginOrigin,
};
use sideveil_protocol::rpc::{RpcRequest, error_codes, methods};
use sideveil_protocol::svp::pack_plugin_dir_to_svp;
use sideveild::config_store::ConfigStore;
use sideveild::log_ring::LogRing;
use sideveild::plugin_registry::PluginRegistry;
use sideveild::rpc::{self, RpcCaller};
use sideveild::state::HubState;
use tokio::sync::mpsc;

fn make_state(tag: &str) -> (PathBuf, Arc<HubState>) {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let dir =
        std::env::temp_dir().join(format!("sideveil-sec-{tag}-{}-{nanos}", std::process::id()));
    let logs = Arc::new(LogRing::new());
    let store = ConfigStore::open(dir.join("config.json"), Arc::clone(&logs));
    let registry = PluginRegistry::open_with_bundled(
        dir.join("plugins"),
        Some(dir.join("bundled")),
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

fn write_plugin_dir(root: &Path, id: &str, manifest: Value) -> PathBuf {
    let pdir = root.join(id);
    fs::create_dir_all(pdir.join("web")).expect("mkdir");
    fs::write(pdir.join("web/index.html"), "<h1>ok</h1>").expect("html");
    fs::write(pdir.join("manifest.json"), manifest.to_string()).expect("manifest");
    pdir
}

#[tokio::test]
async fn enforces_caller_role_acl_and_demotes_diagnostic_when_dev_url_active() {
    let (dir, state) = make_state("caller-acl");

    for method in [
        methods::DAEMON_STATUS,
        methods::DAEMON_HEALTH,
        methods::CONFIG_GET,
        methods::PLUGINS_LIST,
        methods::PLUGINS_GRANT_CAPABILITIES,
        methods::PLUGINS_RELOAD_WEBVIEW,
    ] {
        let mut req = RpcRequest::new("t1", method, None);
        req.plugin_id = Some("com.example.thirdparty".to_owned());
        let resp = rpc::dispatch_rpc_request(&state, req, RpcCaller::HostWebView).await;
        assert_eq!(
            resp.error.as_ref().map(|e| e.code),
            Some(error_codes::PERMISSION_DENIED),
            "third-party WebView must be denied {method}"
        );
    }

    let mut diag_status = RpcRequest::new("d1", methods::DAEMON_STATUS, None);
    diag_status.plugin_id = Some(BUILTIN_DIAG_PLUGIN_ID.to_owned());
    assert!(
        rpc::dispatch_rpc_request(&state, diag_status, RpcCaller::HostWebView)
            .await
            .error
            .is_none()
    );
    let mut diag_admin = RpcRequest::new("d2", methods::PLUGINS_SET_ENABLED, None);
    diag_admin.plugin_id = Some(BUILTIN_DIAG_PLUGIN_ID.to_owned());
    assert_eq!(
        rpc::dispatch_rpc_request(&state, diag_admin, RpcCaller::HostWebView)
            .await
            .error
            .map(|e| e.code),
        Some(error_codes::PERMISSION_DENIED)
    );

    // Setting devServerUrl on sideveil.diagnostic demotes it to sandboxed WebView.
    state
        .plugin_registry()
        .set_dev_url(BUILTIN_DIAG_PLUGIN_ID, Some("http://127.0.0.1:5175"), false)
        .expect("set dev url");
    let mut demoted = RpcRequest::new("d3", methods::CONFIG_GET, None);
    demoted.plugin_id = Some(BUILTIN_DIAG_PLUGIN_ID.to_owned());
    assert_eq!(
        rpc::dispatch_rpc_request(&state, demoted, RpcCaller::HostWebView)
            .await
            .error
            .map(|e| e.code),
        Some(error_codes::PERMISSION_DENIED)
    );

    let _ = fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn enforces_tiered_grants_svp_upgrade_diff_and_daemon_reapproval() {
    let (dir, state) = make_state("grants-upgrade");
    let src1 = dir.join("src1");
    let p1 = write_plugin_dir(
        &src1,
        "com.example.evo",
        json!({
            "schemaVersion": 1,
            "apiVersion": 1,
            "id": "com.example.evo",
            "name": "Evo",
            "version": "1.0.0",
            "entry": "web/index.html",
            "backend": "uds-native",
            "daemon": { "binary": "bin/run" },
            "capabilities": ["toast", "storage"]
        }),
    );
    fs::create_dir_all(p1.join("bin")).unwrap();
    fs::write(p1.join("bin/run"), b"#!/bin/sh\nexit 0\n").unwrap();
    let svp1 = dir.join("evo-1.0.0.svp");
    pack_plugin_dir_to_svp(&p1, &svp1).expect("pack v1");

    let install1 = PluginInstallParams {
        source_path: Some(svp1.to_string_lossy().into_owned()),
        ..PluginInstallParams::default()
    };
    state
        .plugin_registry()
        .install_from_params(&install1)
        .expect("install v1");

    assert!(
        state
            .plugin_registry()
            .has_capability("com.example.evo", PluginCapability::Toast)
    );
    assert!(
        !state
            .plugin_registry()
            .has_capability("com.example.evo", PluginCapability::Storage)
    );
    let snap1 = state.plugins_snapshot();
    let desc1 = snap1
        .plugins
        .iter()
        .find(|p| p.id == "com.example.evo")
        .unwrap();
    assert_eq!(desc1.origin, PluginOrigin::Installed);
    assert_eq!(desc1.approval_status, PluginApprovalStatus::PendingReview);
    assert!(!desc1.daemon_approved);

    let g = RpcRequest::new(
        "g1",
        methods::PLUGINS_GRANT_CAPABILITIES,
        Some(json!({
            "pluginId": "com.example.evo",
            "grantAllPending": true,
            "approveDaemon": true
        })),
    );
    assert!(
        rpc::dispatch_rpc_request(&state, g, RpcCaller::Ctl)
            .await
            .error
            .is_none()
    );
    assert!(
        state
            .plugin_registry()
            .has_capability("com.example.evo", PluginCapability::Storage)
    );

    // Upgrade to v2 adding system-exec and changing daemon binary -> preserves storage,
    // leaves system-exec pending, and resets daemon_approved.
    let src2 = dir.join("src2");
    let p2 = write_plugin_dir(
        &src2,
        "com.example.evo",
        json!({
            "schemaVersion": 1,
            "apiVersion": 1,
            "id": "com.example.evo",
            "name": "Evo",
            "version": "2.0.0",
            "entry": "web/index.html",
            "backend": "uds-native",
            "daemon": { "binary": "bin/run" },
            "capabilities": ["toast", "storage", "system-exec"]
        }),
    );
    fs::create_dir_all(p2.join("bin")).unwrap();
    fs::write(p2.join("bin/run"), b"#!/bin/sh\necho changed\n").unwrap();
    let svp2 = dir.join("evo-2.0.0.svp");
    pack_plugin_dir_to_svp(&p2, &svp2).expect("pack v2");

    let install2 = PluginInstallParams {
        source_path: Some(svp2.to_string_lossy().into_owned()),
        ..PluginInstallParams::default()
    };
    state
        .plugin_registry()
        .install_from_params(&install2)
        .expect("install v2");

    assert!(
        state
            .plugin_registry()
            .has_capability("com.example.evo", PluginCapability::Storage)
    );
    assert!(
        !state
            .plugin_registry()
            .has_capability("com.example.evo", PluginCapability::SystemExec)
    );
    let snap2 = state.plugins_snapshot();
    let desc2 = snap2
        .plugins
        .iter()
        .find(|p| p.id == "com.example.evo")
        .unwrap();
    assert_eq!(desc2.approval_status, PluginApprovalStatus::NeedsReapproval);
    assert!(!desc2.daemon_approved);

    let _ = fs::remove_dir_all(&dir);
}
