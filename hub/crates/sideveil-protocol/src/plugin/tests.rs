//! Unit tests for plugin manifest schema, web-embed validation, and RFC1918 cleartext rules.

use super::*;

fn base_manifest() -> PluginManifest {
    PluginManifest {
        id: "sideveil.diagnostic".to_owned(),
        name: "诊断面板".to_owned(),
        version: "0.1.0".to_owned(),
        icon: None,
        triggers: PluginTriggers::default(),
        entry: "web/index.html".to_owned(),
        backend: PluginBackendKind::None,
        capabilities: vec![PluginCapability::Toast],
        surface: PluginSurfacePrefs::default(),
        embed: None,
        network: None,
    }
}

#[test]
fn validates_plugin_ids_and_entries() {
    assert!(validate_plugin_id("sideveil.diagnostic").is_ok());
    assert!(validate_plugin_id("com.example.my-plugin-1").is_ok());
    assert!(validate_plugin_id("../escape").is_err());
    assert!(validate_plugin_id("UpperCase").is_err());
    assert!(validate_plugin_id("a..b").is_err());

    let valid = base_manifest();
    assert!(valid.validate().is_ok());

    let bad_entry = PluginManifest {
        entry: "../secret/index.html".to_owned(),
        ..valid
    };
    assert!(matches!(
        bad_entry.validate(),
        Err(ManifestValidationError::InvalidEntry(_))
    ));
}

#[test]
fn validates_web_embed_manifest_and_enforces_zero_privilege() {
    let embed_https = PluginManifest {
        id: "embed.home".to_owned(),
        name: "Home Assistant".to_owned(),
        version: "1.0.0".to_owned(),
        icon: None,
        triggers: PluginTriggers::default(),
        entry: String::new(),
        backend: PluginBackendKind::None,
        capabilities: vec![],
        surface: PluginSurfacePrefs {
            keep_alive: true,
            ..PluginSurfacePrefs::default()
        },
        embed: Some(PluginEmbedPrefs {
            url: "https://ha.example.com/lovelace".to_owned(),
            allowed_origins: vec!["https://auth.example.com".to_owned()],
            allow_cleartext: false,
            wide_viewport: true,
            allow_zoom: false,
            web_history_back: true,
        }),
        network: None,
    };
    assert!(embed_https.validate().is_ok());

    // Reject embed with non-none backend
    let with_backend = PluginManifest {
        backend: PluginBackendKind::UdsNative,
        ..embed_https.clone()
    };
    assert_eq!(
        with_backend.validate(),
        Err(ManifestValidationError::EmbedBackendForbidden)
    );

    // Reject embed with privileged capabilities
    for cap in [
        PluginCapability::SystemExec,
        PluginCapability::Storage,
        PluginCapability::Toast,
        PluginCapability::NetworkContext,
    ] {
        let priv_embed = PluginManifest {
            capabilities: vec![cap],
            ..embed_https.clone()
        };
        assert_eq!(
            priv_embed.validate(),
            Err(ManifestValidationError::EmbedPrivilegedCapabilityForbidden(
                cap
            ))
        );
    }

    // NetworkLoopback / NetworkLan declarative caps are allowed
    let net_cap_embed = PluginManifest {
        capabilities: vec![PluginCapability::NetworkLan],
        ..embed_https
    };
    assert!(net_cap_embed.validate().is_ok());
}

#[test]
fn enforces_rfc1918_and_lan_cleartext_rules() {
    // Public HTTPS is always allowed
    assert!(validate_network_url("https://example.com:8443/app", false).is_ok());

    // Public HTTP is ALWAYS rejected, even with allow_cleartext=true
    assert!(matches!(
        validate_network_url("http://example.com/app", false),
        Err(ManifestValidationError::PublicCleartextForbidden(_))
    ));
    assert!(matches!(
        validate_network_url("http://example.com/app", true),
        Err(ManifestValidationError::PublicCleartextForbidden(_))
    ));
    assert!(matches!(
        validate_network_url("http://8.8.8.8/app", true),
        Err(ManifestValidationError::PublicCleartextForbidden(_))
    ));

    // Private/LAN HTTP requires allow_cleartext=true
    for url in [
        "http://127.0.0.1:8080",
        "http://localhost:3000/ui",
        "http://[::1]:9000",
        "http://10.0.0.1:8123",
        "http://172.16.4.2",
        "http://192.168.1.1/cgi-bin/luci",
        "http://router.lan:8080",
        "http://nas.local",
    ] {
        assert!(
            matches!(
                validate_network_url(url, false),
                Err(ManifestValidationError::CleartextNotAllowed(_))
            ),
            "expected CleartextNotAllowed for {url}"
        );
        assert!(
            validate_network_url(url, true).is_ok(),
            "expected Ok for {url} with allow_cleartext=true"
        );
    }

    // Invalid schemes / malformed URLs
    assert!(matches!(
        validate_network_url("file:///etc/passwd", true),
        Err(ManifestValidationError::InvalidUrl { .. })
    ));
    assert!(matches!(
        validate_network_url("javascript:alert(1)", true),
        Err(ManifestValidationError::InvalidUrl { .. })
    ));
    assert!(matches!(
        validate_network_url("http://user:pass@192.168.1.1", true),
        Err(ManifestValidationError::InvalidUrl { .. })
    ));

    // Standard plugin network.allowed_origins also rejects public cleartext
    let bad_net = PluginManifest {
        network: Some(PluginNetworkPrefs {
            allowed_origins: vec!["http://public.example.org".to_owned()],
            allow_cleartext: true,
            spa_fallback: true,
        }),
        ..base_manifest()
    };
    assert!(matches!(
        bad_net.validate(),
        Err(ManifestValidationError::PublicCleartextForbidden(_))
    ));
}

#[test]
fn embed_prefs_defaults_serialize_camel_case() {
    let raw = r#"{"url":"https://router.local"}"#;
    let parsed: PluginEmbedPrefs = serde_json::from_str(raw).expect("parse embed prefs");
    assert_eq!(parsed.url, "https://router.local");
    assert!(!parsed.allow_cleartext);
    assert!(parsed.wide_viewport);
    assert!(!parsed.allow_zoom);
    assert!(parsed.web_history_back);
    assert!(parsed.allowed_origins.is_empty());

    let cap_json = serde_json::to_string(&PluginCapability::NetworkContext).expect("encode cap");
    assert_eq!(cap_json, r#""network-context""#);
}
