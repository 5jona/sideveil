//! Targeted regression tests for Phase 6 adversarial security audit findings (P0-4, P1-2, P1-3,
//! P1-6, P2-2, P2-3).
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::{Value, json};
use sideveil_protocol::DEFAULT_HOST_PACKAGE;
use sideveil_protocol::plugin::{
    PluginCapability, PluginCreateWebEmbedParams, PluginInspectArchiveParams, PluginInstallParams,
    PluginOrigin, PluginSetEmbedConfigParams,
};
use sideveil_protocol::rpc::{RpcRequest, methods};
use sideveil_protocol::svp::pack_plugin_dir_to_svp;
use sideveil_protocol::uds::{UdsClientHello, UdsHandshakeAck};
use sideveild::config_store::ConfigStore;
use sideveild::log_ring::LogRing;
use sideveild::peercred::PeerAuthConfig;
use sideveild::plugin_registry::PluginRegistry;
use sideveild::rpc::{self, RpcCaller};
use sideveild::state::HubState;
use sideveild::uds_gateway::UdsGateway;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;
use tokio::sync::mpsc;

fn make_state(tag: &str) -> (PathBuf, Arc<HubState>) {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let dir =
        std::env::temp_dir().join(format!("sideveil-aud-{tag}-{}-{nanos}", std::process::id()));
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
async fn p0_4_inspect_upgrade_reports_prior_grants_and_explicit_install_revokes_unchecked() {
    let (dir, state) = make_state("p0-4");
    let src = dir.join("src");
    let p1 = write_plugin_dir(
        &src,
        "com.example.up",
        json!({
            "schemaVersion": 1,
            "apiVersion": 1,
            "id": "com.example.up",
            "name": "Up",
            "version": "1.0.0",
            "entry": "web/index.html",
            "backend": "uds-native",
            "daemon": { "binary": "bin/d" },
            "capabilities": ["storage", "system-exec"]
        }),
    );
    fs::create_dir_all(p1.join("bin")).unwrap();
    fs::write(p1.join("bin/d"), b"#!/bin/sh\nexit 0\n").unwrap();
    let svp = dir.join("up.svp");
    pack_plugin_dir_to_svp(&p1, &svp).unwrap();

    // Fresh install granting storage + daemon approval.
    state
        .plugin_registry()
        .install_from_params(&PluginInstallParams {
            source_path: Some(svp.to_string_lossy().into_owned()),
            granted_capabilities: Some(vec![PluginCapability::Storage]),
            approve_daemon: true,
            ..PluginInstallParams::default()
        })
        .unwrap();

    let report = state
        .plugin_registry()
        .inspect_archive(&PluginInspectArchiveParams {
            path: Some(svp.to_string_lossy().into_owned()),
            ..PluginInspectArchiveParams::default()
        })
        .unwrap();
    assert!(report.is_upgrade);
    assert!(report.daemon_already_approved);
    let storage_item = report
        .capabilities
        .iter()
        .find(|c| c.capability == PluginCapability::Storage)
        .unwrap();
    let exec_item = report
        .capabilities
        .iter()
        .find(|c| c.capability == PluginCapability::SystemExec)
        .unwrap();
    assert!(storage_item.already_granted);
    assert!(!exec_item.already_granted);

    // Upgrade with explicit empty grantedCapabilities -> authoritatively revokes Storage!
    state
        .plugin_registry()
        .install_from_params(&PluginInstallParams {
            source_path: Some(svp.to_string_lossy().into_owned()),
            granted_capabilities: Some(Vec::new()),
            approve_daemon: false,
            ..PluginInstallParams::default()
        })
        .unwrap();
    assert!(
        !state
            .plugin_registry()
            .has_capability("com.example.up", PluginCapability::Storage)
    );

    let _ = fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn p1_2_blocks_shadowing_bundled_plugins_via_install_embed_or_disk_drop() {
    let (dir, state) = make_state("p1-2");
    write_plugin_dir(
        &dir.join("bundled"),
        "example.bundled",
        json!({
            "id": "example.bundled",
            "name": "Bundled",
            "version": "1.0.0",
            "entry": "web/index.html"
        }),
    );
    state.plugin_registry().rescan();
    assert_eq!(
        state.plugin_registry().plugin_origin("example.bundled"),
        Some(PluginOrigin::Bundled)
    );

    // 1. Installing a directory with the bundled ID must fail.
    let rogue_src = write_plugin_dir(
        &dir.join("rogue"),
        "example.bundled",
        json!({
            "id": "example.bundled",
            "name": "Hijack",
            "version": "9.9.9",
            "entry": "web/index.html"
        }),
    );
    assert!(
        state
            .plugin_registry()
            .install_from_dir(&rogue_src)
            .is_err()
    );

    // 2. Creating a web-embed with the bundled ID must fail.
    assert!(
        state
            .plugin_registry()
            .create_web_embed(&PluginCreateWebEmbedParams {
                id: "example.bundled".to_owned(),
                name: "Hijack".to_owned(),
                url: "https://example.com".to_owned(),
                remote_url: None,
                lan_ssids: Vec::new(),
                packages: Vec::new(),
                global: true,
                priority: None,
                allowed_origins: Vec::new(),
                allow_cleartext: false,
                keep_alive: false,
                wide_viewport: None,
                allow_zoom: None,
                web_history_back: None,
                text_zoom: None,
                show_toolbar: None,
                keep_alive_grace_sec: None,
                width_dp: None,
                height_percent: None,
            })
            .is_err()
    );

    // 3. Even a direct drop into plugins_dir cannot shadow Bundled during rescan.
    write_plugin_dir(
        &dir.join("plugins"),
        "example.bundled",
        json!({
            "id": "example.bundled",
            "name": "DiskHijack",
            "version": "9.9.9",
            "entry": "web/index.html"
        }),
    );
    state.plugin_registry().rescan();
    assert_eq!(
        state.plugin_registry().plugin_origin("example.bundled"),
        Some(PluginOrigin::Bundled)
    );

    let _ = fs::remove_dir_all(&dir);
}

#[tokio::test]
async fn p1_3_exec_reaps_background_descendants_and_p1_6_terminates_disabled_backend() {
    let (dir, state) = make_state("p1-3-p1-6");
    let pdir = write_plugin_dir(
        &dir.join("plugins"),
        "com.example.bg",
        json!({
            "id": "com.example.bg",
            "name": "Bg",
            "version": "1.0.0",
            "entry": "web/index.html",
            "backend": "uds-native",
            "capabilities": ["system-exec"]
        }),
    );
    let _ = pdir;
    state.plugin_registry().rescan();
    let grant = RpcRequest::new(
        "g",
        methods::PLUGINS_GRANT_CAPABILITIES,
        Some(json!({ "pluginId": "com.example.bg", "grantAllPending": true })),
    );
    assert!(
        rpc::dispatch_rpc_request(&state, grant, RpcCaller::Ctl)
            .await
            .error
            .is_none()
    );

    // P1-3: Background descendant holding stdout open must NOT hang past timeoutMs.
    let mut exec_req = RpcRequest::new(
        "bg1",
        methods::SYSTEM_EXEC,
        Some(json!({ "cmd": "echo ready; /bin/sleep 60 &", "options": { "timeoutMs": 600 } })),
    );
    exec_req.plugin_id = Some("com.example.bg".to_owned());
    let started = tokio::time::Instant::now();
    let resp = rpc::dispatch_rpc_request(&state, exec_req, RpcCaller::HostWebView).await;
    assert!(started.elapsed() < Duration::from_secs(2));
    assert!(resp.error.is_none());
    assert!(
        resp.result.unwrap()["stdout"]
            .as_str()
            .unwrap()
            .contains("ready")
    );

    // P1-6: Disabling plugin immediately disconnects active backend UDS session.
    let hub_sock = dir.join("hub.sock");
    let app_sock = dir.join("app.sock");
    let uid = unsafe {
        // SAFETY: `getuid` has no preconditions.
        libc::getuid()
    };
    let gw = UdsGateway::bind(
        hub_sock.clone(),
        app_sock,
        PeerAuthConfig {
            extra_privileged_uids: vec![uid],
            packages_list_path: None,
        },
    )
    .unwrap();
    let gw_task = tokio::spawn(gw.serve(Arc::clone(&state)));

    let stream = UnixStream::connect(&hub_sock).await.unwrap();
    let (r, mut w) = stream.into_split();
    let mut reader = BufReader::new(r);
    let hello = serde_json::to_string(&UdsClientHello::PluginBackend {
        plugin_id: "com.example.bg".to_owned(),
        package_name: None,
        protocol_version: 1,
    })
    .unwrap();
    w.write_all(format!("{hello}\n").as_bytes()).await.unwrap();
    let mut ack = String::new();
    reader.read_line(&mut ack).await.unwrap();
    assert!(
        serde_json::from_str::<UdsHandshakeAck>(ack.trim())
            .unwrap()
            .ok
    );

    // Disable plugin -> session closes immediately (peer sees EOF).
    let dis = RpcRequest::new(
        "d",
        methods::PLUGINS_SET_ENABLED,
        Some(json!({ "pluginId": "com.example.bg", "enabled": false })),
    );
    assert!(
        rpc::dispatch_rpc_request(&state, dis, RpcCaller::Ctl)
            .await
            .error
            .is_none()
    );
    let mut eof_line = String::new();
    let n = tokio::time::timeout(Duration::from_secs(1), reader.read_line(&mut eof_line))
        .await
        .expect("peer should receive immediate EOF on disable")
        .unwrap();
    assert_eq!(n, 0);

    // P2-2 & P2-3: Supervised sensor PID check + hub.sock daemon approval + set_embed_config guard.
    state.set_supervised_sensor_pid(Some(12345));
    assert!(state.verify_sensor_peer_pid(Some(12345)).is_ok());
    assert!(state.verify_sensor_peer_pid(Some(99999)).is_err());
    let embed_params: PluginSetEmbedConfigParams = serde_json::from_value(json!({
        "pluginId": "com.example.bg",
        "embedUrl": "https://example.com"
    }))
    .unwrap();
    assert!(
        state
            .plugin_registry()
            .set_embed_config(&embed_params)
            .is_err()
    );

    gw_task.abort();
    let _ = fs::remove_dir_all(&dir);
}
