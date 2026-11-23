//! Unit tests for `plugin_registry::store` loopback dev-URL validation and built-in manifest.

use sideveil_protocol::plugin::{BUILTIN_DIAG_PLUGIN_ID, PluginCapability};

use super::dev_url::validate_loopback_dev_url;
use super::store::builtin_diag_manifest;

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
