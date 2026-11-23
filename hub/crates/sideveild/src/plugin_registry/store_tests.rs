//! Unit tests for `plugin_registry::store` loopback dev-URL validation, asset revision, and built-in manifest.

use std::collections::HashSet;
use std::fs;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use sideveil_protocol::config::SideVeilConfig;
use sideveil_protocol::plugin::{BUILTIN_DIAG_PLUGIN_ID, PluginCapability};

use super::PluginRegistry;
use super::dev_url::validate_loopback_dev_url;
use super::store::{builtin_diag_manifest, compute_asset_revision};
use crate::log_ring::LogRing;

#[test]
fn validate_loopback_dev_url_accepts_valid_loopback_and_rejects_ssrf() {
    assert_eq!(
        validate_loopback_dev_url("http://127.0.0.1:5173/")
            .ok()
            .as_deref(),
        Some("http://127.0.0.1:5173")
    );
    assert_eq!(
        validate_loopback_dev_url("https://localhost:8443/app")
            .ok()
            .as_deref(),
        Some("https://localhost:8443/app")
    );
    assert_eq!(
        validate_loopback_dev_url("http://[::1]:3000")
            .ok()
            .as_deref(),
        Some("http://[::1]:3000")
    );
    assert_eq!(
        validate_loopback_dev_url("http://localhost")
            .ok()
            .as_deref(),
        Some("http://localhost")
    );

    for bad in [
        "",
        "   ",
        "http://127.0.0.1:80@attacker.example",
        "http://localhost@evil.example:5173",
        "http://user:pass@127.0.0.1:5173",
        "http://127.0.0.1:0",
        "http://127.0.0.1:99999",
        "http://127.0.0.1:abc",
        "http://127.0.0.1?@attacker.example",
        "http://127.0.0.1#frag",
        "http://127.0.0.1\\attacker.example",
        "http://127.0.0.1:5173/\r\nX-Inject: 1",
        "http://127.0.0.1:5173/with space",
        "http://127.0.0.1:5173/\x00",
        "ftp://127.0.0.1:5173",
        "javascript:alert(1)",
        "http://192.168.1.10:5173",
        "http://[::2]:5173",
        "http://[::1",
    ] {
        assert!(
            validate_loopback_dev_url(bad).is_err(),
            "expected rejection for {bad:?}"
        );
    }
}

#[test]
fn builtin_diag_manifest_declares_storage_and_toast() {
    let m = builtin_diag_manifest();
    assert_eq!(m.id, BUILTIN_DIAG_PLUGIN_ID);
    assert_eq!(m.name, "内置诊断");
    assert_eq!(m.version, "0.1.0");
    assert!(!m.triggers.global);
    assert_eq!(m.triggers.priority, 20);
    assert_eq!(
        m.capabilities,
        vec![PluginCapability::Storage, PluginCapability::Toast]
    );
}

#[test]
fn compute_asset_revision_detects_entry_changes_and_rejects_traversal() {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let dir =
        std::env::temp_dir().join(format!("sideveil-asset-rev-{}-{nanos}", std::process::id()));
    let plugin_dir = dir.join("plugins/strata.manager");
    fs::create_dir_all(plugin_dir.join("web")).expect("mkdir");
    fs::write(
        plugin_dir.join("manifest.json"),
        r#"{
            "id": "strata.manager",
            "name": "Strata Manager",
            "version": "1.0.0",
            "entry": "web/index.html"
        }"#,
    )
    .expect("write manifest");
    fs::write(
        plugin_dir.join("web/index.html"),
        "<html><script src=\"./assets/index-AAA.js\"></script></html>",
    )
    .expect("write html v1");

    let rev1 = compute_asset_revision(&plugin_dir, "web/index.html").expect("rev1");
    assert_eq!(
        compute_asset_revision(&plugin_dir, "web/index.html").as_deref(),
        Some(rev1.as_str())
    );
    assert!(compute_asset_revision(&plugin_dir, "../manifest.json").is_none());
    assert!(compute_asset_revision(&plugin_dir, "").is_none());

    let logs = Arc::new(LogRing::new());
    let registry = PluginRegistry::open(dir.join("plugins"), dir.join("plugins.json"), logs);
    let mut rx = registry.subscribe();
    let cfg = SideVeilConfig::default();
    let snap1 = registry.snapshot("", "", &cfg, &HashSet::new());
    let desc1 = snap1
        .plugins
        .iter()
        .find(|p| p.id == "strata.manager")
        .expect("strata descriptor");
    assert_eq!(desc1.asset_revision.as_deref(), Some(rev1.as_str()));

    // Re-scanning with unchanged files must not emit a spurious update.
    let _ = registry.rescan();
    assert!(rx.try_recv().is_err());

    // Updating only web/index.html (manifest.json untouched) MUST change asset_revision and fire updates_tx!
    fs::write(
        plugin_dir.join("web/index.html"),
        "<html><script src=\"./assets/index-DXWqVSzR.js\"></script></html>",
    )
    .expect("write html v2");
    let _ = registry.rescan();
    assert!(
        rx.try_recv().is_ok(),
        "rescan must broadcast update when entry HTML changes"
    );

    let snap2 = registry.snapshot("", "", &cfg, &HashSet::new());
    let desc2 = snap2
        .plugins
        .iter()
        .find(|p| p.id == "strata.manager")
        .expect("strata descriptor v2");
    assert_ne!(desc1.asset_revision, desc2.asset_revision);
    assert!(desc2.asset_revision.is_some());

    // Built-in diagnostic plugin omits asset_revision on the wire.
    let diag = snap2
        .plugins
        .iter()
        .find(|p| p.id == BUILTIN_DIAG_PLUGIN_ID)
        .expect("builtin diagnostic");
    assert!(diag.asset_revision.is_none());
    let diag_json = serde_json::to_value(diag).expect("serialize builtin");
    assert!(diag_json.get("assetRevision").is_none());

    let _ = fs::remove_dir_all(&dir);
}
