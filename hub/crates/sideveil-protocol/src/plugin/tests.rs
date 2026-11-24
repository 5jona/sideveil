//! Unit tests for plugin manifest schema, capability risk tiers, daemon spec, and network rules.

use std::collections::BTreeMap;

use super::*;

fn base_manifest() -> PluginManifest {
    PluginManifest {
        schema_version: Some(1),
        api_version: Some(1),
        min_host_version: None,
        id: "sideveil.diagnostic".to_owned(),
        name: "Diagnostic Panel".to_owned(),
        version: "0.1.0".to_owned(),
        description: None,
        author: None,
        homepage: None,
        icon: None,
        default_enabled: Some(true),
        triggers: None,
        entry: Some("web/index.html".to_owned()),
        backend: Some(PluginBackendKind::None),
        backend_packages: None,
        daemon: None,
        capabilities: Some(vec![PluginCapability::Toast]),
        surface: None,
        embed: None,
        network: None,
    }
}

#[test]
fn validates_plugin_ids_versions_and_entries() {
    assert!(validate_plugin_id("sideveil.diagnostic").is_ok());
    assert!(validate_plugin_id("com.example.my-plugin-1").is_ok());
    assert!(validate_plugin_id("../escape").is_err());
    assert!(validate_plugin_id("UpperCase").is_err());
    assert!(validate_plugin_id("a..b").is_err());

    let valid = base_manifest();
    assert!(valid.validate().is_ok());

    let bad_schema = PluginManifest {
        schema_version: Some(99),
        ..valid.clone()
    };
    assert!(matches!(
        bad_schema.validate(),
        Err(ManifestValidationError::UnsupportedSchemaVersion { declared: 99, .. })
    ));

    let bad_api = PluginManifest {
        api_version: Some(2),
        ..valid.clone()
    };
    assert!(matches!(
        bad_api.validate(),
        Err(ManifestValidationError::UnsupportedApiVersion { declared: 2, .. })
    ));

    let bad_entry = PluginManifest {
        entry: Some("../secret/index.html".to_owned()),
        ..valid
    };
    assert!(matches!(
        bad_entry.validate(),
        Err(ManifestValidationError::InvalidEntry(_))
    ));
}

#[test]
fn classifies_capability_risk_tiers_and_explicit_grant_policy() {
    for low in [
        PluginCapability::Toast,
        PluginCapability::Haptics,
        PluginCapability::Telemetry,
    ] {
        assert_eq!(low.risk_tier(), CapabilityRiskTier::Low);
        assert!(!low.requires_explicit_grant());
    }
    for med in [
        PluginCapability::Storage,
        PluginCapability::Clipboard,
        PluginCapability::NetworkLoopback,
        PluginCapability::NetworkLan,
        PluginCapability::NetworkInternet,
        PluginCapability::NetworkContext,
        PluginCapability::NativeHud,
        PluginCapability::InterPluginBus,
    ] {
        assert_eq!(med.risk_tier(), CapabilityRiskTier::Medium);
        assert!(med.requires_explicit_grant());
    }
    assert_eq!(
        PluginCapability::SystemExec.risk_tier(),
        CapabilityRiskTier::High
    );
    assert!(PluginCapability::SystemExec.requires_explicit_grant());
}

#[test]
fn validates_daemon_spec_env_allowlist_and_backend_packages() {
    let mut env = BTreeMap::new();
    env.insert("RUST_LOG".to_owned(), "info".to_owned());
    let valid_native = PluginManifest {
        id: "com.example.daemon".to_owned(),
        backend: Some(PluginBackendKind::UdsNative),
        daemon: Some(PluginDaemonSpec {
            binary: "bin/my-daemon".to_owned(),
            args: Some(vec!["--fast".to_owned()]),
            env: Some(env),
            restart_policy: Some(PluginDaemonRestartPolicy::OnFailure),
            max_restarts: Some(5),
            backoff_ms: Some(1000),
        }),
        ..base_manifest()
    };
    assert!(valid_native.validate().is_ok());

    let bad_bin = PluginManifest {
        daemon: Some(PluginDaemonSpec {
            binary: "../bin/evil".to_owned(),
            args: None,
            env: None,
            restart_policy: None,
            max_restarts: None,
            backoff_ms: None,
        }),
        ..valid_native.clone()
    };
    assert!(matches!(
        bad_bin.validate(),
        Err(ManifestValidationError::InvalidDaemonBinary(_))
    ));

    for forbidden in [
        "LD_PRELOAD",
        "LD_LIBRARY_PATH",
        "PATH",
        "HOME",
        "ANDROID_ROOT",
        "SIDEVEIL_HUB_SOCK",
    ] {
        assert!(is_forbidden_daemon_env_key(forbidden));
        let mut bad_env = BTreeMap::new();
        bad_env.insert(forbidden.to_owned(), "/tmp/evil".to_owned());
        let bad = PluginManifest {
            daemon: Some(PluginDaemonSpec {
                binary: "bin/my-daemon".to_owned(),
                args: None,
                env: Some(bad_env),
                restart_policy: None,
                max_restarts: None,
                backoff_ms: None,
            }),
            ..valid_native.clone()
        };
        assert!(matches!(
            bad.validate(),
            Err(ManifestValidationError::ForbiddenDaemonEnvVar(_))
        ));
    }

    let valid_app = PluginManifest {
        id: "com.example.app-plugin".to_owned(),
        backend: Some(PluginBackendKind::UdsApp),
        backend_packages: Some(vec!["com.example.target_app".to_owned()]),
        ..base_manifest()
    };
    assert!(valid_app.validate().is_ok());

    let bad_app = PluginManifest {
        backend_packages: Some(vec!["not_a_package".to_owned()]),
        ..valid_app
    };
    assert!(matches!(
        bad_app.validate(),
        Err(ManifestValidationError::InvalidBackendPackage(_))
    ));
}

#[test]
fn validates_network_origins_against_loopback_lan_and_internet_capabilities() {
    assert!(
        validate_origin_for_capabilities(
            "http://127.0.0.1:8080",
            true,
            &[PluginCapability::NetworkLoopback]
        )
        .is_ok()
    );
    assert!(matches!(
        validate_origin_for_capabilities(
            "http://192.168.1.50:8080",
            true,
            &[PluginCapability::NetworkLoopback]
        ),
        Err(ManifestValidationError::OriginCapabilityMismatch { .. })
    ));
    assert!(
        validate_origin_for_capabilities(
            "http://192.168.1.50:8080",
            true,
            &[PluginCapability::NetworkLan]
        )
        .is_ok()
    );
    assert!(matches!(
        validate_origin_for_capabilities(
            "https://api.example.com",
            false,
            &[PluginCapability::NetworkLan]
        ),
        Err(ManifestValidationError::OriginCapabilityMismatch { .. })
    ));
    assert!(
        validate_origin_for_capabilities(
            "https://api.example.com",
            false,
            &[PluginCapability::NetworkInternet]
        )
        .is_ok()
    );
    assert!(
        validate_origin_for_capabilities(
            "wss://stream.example.com/ws",
            false,
            &[PluginCapability::NetworkInternet]
        )
        .is_ok()
    );
    assert!(matches!(
        validate_origin_for_capabilities(
            "http://api.example.com",
            true,
            &[PluginCapability::NetworkInternet]
        ),
        Err(ManifestValidationError::PublicCleartextForbidden(_))
    ));
    // P0-2: Unspecified & IPv4-mapped IPv6 bypasses must be rejected.
    for unspec in [
        "https://0.0.0.0:443",
        "https://[::]:443",
        "https://[::ffff:0.0.0.0]:443",
    ] {
        assert!(
            matches!(
                validate_origin_for_capabilities(
                    unspec,
                    true,
                    &[PluginCapability::NetworkInternet]
                ),
                Err(ManifestValidationError::InvalidUrl { .. })
            ),
            "expected unspecified IP rejection for {unspec}"
        );
    }
    for mapped in [
        "https://[::ffff:7f00:1]:8443",
        "https://[::ffff:c0a8:101]:8443",
    ] {
        assert!(
            matches!(
                validate_origin_for_capabilities(
                    mapped,
                    false,
                    &[PluginCapability::NetworkInternet]
                ),
                Err(ManifestValidationError::OriginCapabilityMismatch { .. })
            ),
            "IPv4-mapped IPv6 {mapped} must not pass under network-internet"
        );
    }
    assert!(
        validate_origin_for_capabilities(
            "https://[::ffff:7f00:1]:8443",
            false,
            &[PluginCapability::NetworkLoopback]
        )
        .is_ok()
    );
    // P2-3: network.allowedOrigins with zero network capabilities must fail validation.
    let no_net_cap_manifest: PluginManifest = serde_json::from_str(
        r#"{
            "id": "com.example.nonet",
            "name": "NoNet",
            "version": "1.0.0",
            "entry": "web/index.html",
            "capabilities": ["toast"],
            "network": { "allowedOrigins": ["https://api.example.com"] }
        }"#,
    )
    .expect("parse");
    assert!(matches!(
        no_net_cap_manifest.validate(),
        Err(ManifestValidationError::OriginCapabilityMismatch { .. })
    ));
}

#[test]
fn validates_web_embed_manifest_and_enforces_zero_privilege() {
    let embed_https = PluginManifest {
        id: "embed.home".to_owned(),
        name: "Home Assistant".to_owned(),
        version: "1.0.0".to_owned(),
        entry: None,
        backend: Some(PluginBackendKind::None),
        capabilities: Some(vec![]),
        embed: Some(PluginManifestEmbedPrefs {
            url: "https://ha.example.com/lovelace".to_owned(),
            remote_url: Some("https://ha.example.com:8123".to_owned()),
            lan_ssids: Some(vec!["HomeIoT".to_owned()]),
            allowed_origins: Some(vec!["https://auth.example.com".to_owned()]),
            allow_cleartext: Some(false),
            wide_viewport: Some(true),
            allow_zoom: Some(false),
            web_history_back: Some(true),
            text_zoom: Some(100),
            show_toolbar: Some(true),
            keep_alive_grace_sec: Some(300),
        }),
        ..base_manifest()
    };
    assert!(embed_https.validate().is_ok());

    let with_backend = PluginManifest {
        backend: Some(PluginBackendKind::UdsNative),
        ..embed_https.clone()
    };
    assert_eq!(
        with_backend.validate(),
        Err(ManifestValidationError::EmbedBackendForbidden)
    );

    for cap in [
        PluginCapability::SystemExec,
        PluginCapability::Storage,
        PluginCapability::Toast,
        PluginCapability::NetworkContext,
    ] {
        let priv_embed = PluginManifest {
            capabilities: Some(vec![cap]),
            ..embed_https.clone()
        };
        assert_eq!(
            priv_embed.validate(),
            Err(ManifestValidationError::EmbedPrivilegedCapabilityForbidden(
                cap
            ))
        );
    }

    let net_cap_embed = PluginManifest {
        capabilities: Some(vec![PluginCapability::NetworkLan]),
        ..embed_https
    };
    assert!(net_cap_embed.validate().is_ok());
}
