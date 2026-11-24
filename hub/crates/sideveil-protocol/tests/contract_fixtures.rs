//! Cross-stack golden wire fixture round-trip tests for `sideveil-protocol`.
#![allow(clippy::unwrap_used, clippy::expect_used)]

use std::fs;
use std::path::PathBuf;

use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::Value;
use sideveil_protocol::config::{PanelState, SideVeilConfig, THEME_COLOROS_GLASS};
use sideveil_protocol::generated::protocol_constants::{
    builtin_diagnostic, error_codes, events, hud_defaults, methods, protocol_versions, themes,
};
use sideveil_protocol::hud::{HudDismissPayload, HudTextAlign, HudTextOverlay};
use sideveil_protocol::plugin::{
    HostPluginDiagnosticsPayload, PluginApprovalStatus, PluginDaemonStatus, PluginManifest,
    PluginOrigin, PluginRuntimeDescriptor,
};
use sideveil_protocol::rpc::{
    DaemonHealth, DaemonStatus, HostLocalAuditPayload, HostReadyPayload, PanelStateChangedPayload,
    PanelVisibilityPayload, PingResult, ScopeChangedPayload,
};
use sideveil_protocol::theme::{DynamicThemePalette, HostDynamicColorsPayload};

fn fixture_path(rel: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../../contracts/fixtures")
        .join(rel)
}

fn read_fixture(rel: &str) -> String {
    let path = fixture_path(rel);
    fs::read_to_string(&path)
        .unwrap_or_else(|err| panic!("failed to read fixture {}: {err}", path.display()))
}

fn read_invalid_data(rel: &str) -> String {
    let raw: Value = serde_json::from_str(&read_fixture(rel)).expect("parse wrapper JSON");
    assert_eq!(raw.get("invalid").and_then(Value::as_bool), Some(true));
    serde_json::to_string(raw.get("data").expect("fixture wrapper missing data"))
        .expect("encode invalid fixture data")
}

fn assert_roundtrip<T: DeserializeOwned + Serialize + PartialEq + std::fmt::Debug>(rel: &str) -> T {
    let raw = read_fixture(rel);
    let parsed: T = serde_json::from_str(&raw)
        .unwrap_or_else(|err| panic!("failed to deserialize {rel}: {err}"));
    let reencoded = serde_json::to_string(&parsed).expect("re-encode fixture");
    let reparsed: T = serde_json::from_str(&reencoded).expect("re-parse fixture");
    assert_eq!(parsed, reparsed, "round-trip mismatch for {rel}");
    parsed
}

#[test]
fn config_fixtures_match_defaults_and_reject_invalid() {
    let default_cfg: SideVeilConfig = assert_roundtrip("config/SideVeilConfig.default.json");
    assert_eq!(default_cfg, SideVeilConfig::default());
    assert_eq!(default_cfg.sanitized(), default_cfg);

    let with_debug: SideVeilConfig = assert_roundtrip("config/SideVeilConfig.with-debug.json");
    assert!(with_debug.debug.webview_debugging_enabled);
    assert_eq!(with_debug.panel.theme_id, themes::MODERN_SAAS_DARK);

    let invalid_raw = read_invalid_data("config/SideVeilConfig.invalid-mode.json");
    assert!(serde_json::from_str::<SideVeilConfig>(&invalid_raw).is_err());
}

#[test]
fn status_health_and_ping_fixtures_roundtrip() {
    let full: DaemonStatus = assert_roundtrip("status/DaemonStatus.full.json");
    assert_eq!(full.panel_state, PanelState::Open);
    assert_eq!(
        full.active_plugin_id.as_deref(),
        Some(builtin_diagnostic::ID)
    );
    assert_eq!(full.matched_plugins_count, 2);
    assert_eq!(full.protocol_version, protocol_versions::PROTOCOL_VERSION);
    assert_eq!(
        full.plugin_api_version,
        protocol_versions::PLUGIN_API_VERSION
    );
    assert!(full.gesture_stats.is_some());

    let minimal: DaemonStatus = assert_roundtrip("status/DaemonStatus.minimal-nulls.json");
    assert_eq!(minimal.panel_state, PanelState::Closed);
    assert_eq!(minimal.host_pid, None);
    assert_eq!(minimal.active_plugin_id, None);
    assert_eq!(minimal.matched_plugins_count, 0);

    let invalid_status = read_invalid_data("status/DaemonStatus.invalid-panel-state.json");
    assert!(serde_json::from_str::<DaemonStatus>(&invalid_status).is_err());

    let health: DaemonHealth = assert_roundtrip("status/DaemonHealth.ok.json");
    assert!(health.ok && health.sensor_connected && health.host_connected);

    let ping: PingResult = assert_roundtrip("status/PingResult.ok.json");
    assert!(ping.pong);
}

#[test]
fn scope_panel_diagnostics_and_host_event_fixtures_roundtrip() {
    let scope: ScopeChangedPayload = assert_roundtrip("events/ScopeChangedPayload.active.json");
    assert!(scope.active);
    assert_eq!(scope.foreground_activity, ".ui.MainActivity");

    let bad_scope = read_invalid_data("events/ScopeChangedPayload.invalid-missing-active.json");
    assert!(serde_json::from_str::<ScopeChangedPayload>(&bad_scope).is_err());

    let vis: PanelVisibilityPayload = assert_roundtrip("events/PanelVisibilityPayload.open.json");
    assert!(vis.visible);
    assert_eq!(vis.state, PanelState::Open);

    let bad_vis = read_invalid_data("events/PanelVisibilityPayload.invalid-state.json");
    assert!(serde_json::from_str::<PanelVisibilityPayload>(&bad_vis).is_err());

    let state: PanelStateChangedPayload =
        assert_roundtrip("events/PanelStateChangedPayload.dragging.json");
    assert_eq!(state.state, PanelState::Dragging);

    let diag: HostPluginDiagnosticsPayload =
        assert_roundtrip("events/HostPluginDiagnosticsPayload.full.json");
    assert_eq!(diag.webview_crash_count, 2);
    assert_eq!(diag.warm_plugin_ids.len(), 2);

    let bad_diag = read_invalid_data("events/HostPluginDiagnosticsPayload.invalid-crash-type.json");
    assert!(serde_json::from_str::<HostPluginDiagnosticsPayload>(&bad_diag).is_err());

    let ready: HostReadyPayload = assert_roundtrip("events/HostReadyPayload.full.json");
    assert_eq!(ready.protocol_version, protocol_versions::PROTOCOL_VERSION);
    assert_eq!(ready.api_version, protocol_versions::PLUGIN_API_VERSION);
    assert_eq!(ready.plugin_id, "example.vello-scope");

    let bad_ready = read_invalid_data("events/HostReadyPayload.invalid-missing-plugin.json");
    assert!(serde_json::from_str::<HostReadyPayload>(&bad_ready).is_err());

    let audit: HostLocalAuditPayload =
        assert_roundtrip("events/HostLocalAuditPayload.allowed.json");
    assert!(audit.allowed);
    assert_eq!(audit.method, "clipboard.get");

    let bad_audit = read_invalid_data("events/HostLocalAuditPayload.invalid-missing-allowed.json");
    assert!(serde_json::from_str::<HostLocalAuditPayload>(&bad_audit).is_err());
}

#[test]
fn plugin_manifest_and_descriptor_fixtures_roundtrip() {
    let minimal: PluginManifest = assert_roundtrip("plugin-manifest/PluginManifest.minimal.json");
    assert!(minimal.validate().is_ok());

    let native: PluginManifest =
        assert_roundtrip("plugin-manifest/PluginManifest.native-daemon.json");
    assert!(native.validate().is_ok());
    assert!(!native.effective_default_enabled());
    assert!(native.daemon.is_some());

    let embed: PluginManifest = assert_roundtrip("plugin-manifest/PluginManifest.web-embed.json");
    assert!(embed.validate().is_ok());

    let bad_cap = read_invalid_data("plugin-manifest/PluginManifest.invalid-capability.json");
    assert!(serde_json::from_str::<PluginManifest>(&bad_cap).is_err());

    let desc: PluginRuntimeDescriptor =
        assert_roundtrip("plugin-manifest/PluginRuntimeDescriptor.full.json");
    assert_eq!(desc.origin, PluginOrigin::Bundled);
    assert_eq!(desc.approval_status, PluginApprovalStatus::Approved);
    assert_eq!(desc.daemon_status, PluginDaemonStatus::Running);
    assert!(desc.has_daemon);

    let bad_origin =
        read_invalid_data("plugin-manifest/PluginRuntimeDescriptor.invalid-origin.json");
    assert!(serde_json::from_str::<PluginRuntimeDescriptor>(&bad_origin).is_err());
}

#[test]
fn theme_and_hud_fixtures_align_defaults_and_reject_invalid() {
    let palette: DynamicThemePalette = assert_roundtrip("theme-hud/DynamicThemePalette.dark.json");
    assert_eq!(palette.theme_id, THEME_COLOROS_GLASS);
    assert_eq!(palette.glass.accent_argb, palette.seed_argb);

    let bad_palette = read_invalid_data("theme-hud/DynamicThemePalette.invalid-missing-glass.json");
    assert!(serde_json::from_str::<DynamicThemePalette>(&bad_palette).is_err());

    let seed: HostDynamicColorsPayload =
        assert_roundtrip("theme-hud/HostDynamicColorsPayload.seed.json");
    assert!(seed.is_dark);

    let full_text: HudTextOverlay = assert_roundtrip("theme-hud/HudTextOverlay.full.json");
    assert_eq!(full_text.color_argb, hud_defaults::DEFAULT_TEXT_COLOR_ARGB);
    assert_eq!(full_text.align, HudTextAlign::Start);

    let min_text: HudTextOverlay = assert_roundtrip("theme-hud/HudTextOverlay.minimal.json");
    assert_eq!(min_text.color_argb, hud_defaults::DEFAULT_TEXT_COLOR_ARGB);
    assert!((min_text.size_sp - hud_defaults::DEFAULT_TEXT_SIZE_SP).abs() < f32::EPSILON);

    let bad_text = read_invalid_data("theme-hud/HudTextOverlay.invalid-align.json");
    assert!(serde_json::from_str::<HudTextOverlay>(&bad_text).is_err());

    let dismiss: HudDismissPayload = assert_roundtrip("theme-hud/HudDismissPayload.targeted.json");
    assert_eq!(
        dismiss.plugin_id.as_deref(),
        Some("example.native-perf-hud")
    );
}

#[test]
fn generated_constants_include_new_methods_events_versions_and_error_codes() {
    assert_eq!(protocol_versions::PROTOCOL_VERSION, 1);
    assert_eq!(protocol_versions::PLUGIN_API_VERSION, 1);
    assert_eq!(protocol_versions::MANIFEST_SCHEMA_VERSION, 1);
    assert_eq!(events::HOST_READY, "host.ready");
    assert_eq!(events::HOST_LOCAL_AUDIT, "host.localAudit");
    assert_eq!(
        methods::PLUGINS_GRANT_CAPABILITIES,
        "plugins.grantCapabilities"
    );
    assert_eq!(methods::PLUGINS_INSPECT_ARCHIVE, "plugins.inspectArchive");
    assert_eq!(methods::PLUGINS_DAEMON_LOGS, "plugins.daemonLogs");
    assert_eq!(methods::PLUGINS_RELOAD_WEBVIEW, "plugins.reloadWebView");
    assert_eq!(error_codes::DAEMON_DISCONNECTED, -32001);
    assert_eq!(error_codes::RPC_TIMEOUT, -32002);
    assert_eq!(error_codes::PERMISSION_DENIED, -32003);
    assert_eq!(error_codes::RATE_LIMITED, -32004);
    assert_eq!(error_codes::VERSION_MISMATCH, -32005);
    assert_eq!(error_codes::QUOTA_EXCEEDED, -32006);
}
