//! Unit tests for `SideVeilConfig` serialization, sanitization, and backward compatibility.

use super::*;

#[test]
fn default_config_roundtrips_json_and_matches_bounds() {
    let cfg = SideVeilConfig::default();
    let json = serde_json::to_string(&cfg).expect("serialize default config");
    let parsed: SideVeilConfig = serde_json::from_str(&json).expect("deserialize config");
    assert_eq!(parsed, cfg);
    assert_eq!(parsed.sanitized(), cfg);
    assert!(!parsed.debug.webview_debugging_enabled);
    assert!(cfg.matches_package("me.weishu.kernelsu"));
    assert!(!cfg.matches_package("com.android.systemui"));
    assert!(!cfg.matches_package("com.example.other"));
}

#[test]
fn config_without_debug_field_deserializes_with_default_debug() {
    let legacy_json = r#"{
        "version": 1,
        "enabled": true,
        "edge": { "side": "left", "topPercent": 40 },
        "panel": { "themeId": "coloros-glass", "colorMode": "dark" },
        "scope": { "mode": "allowlist", "allowlist": ["me.weishu.kernelsu"], "denylist": [] }
    }"#;
    let parsed: SideVeilConfig =
        serde_json::from_str(legacy_json).expect("deserialize legacy config without debug field");
    assert_eq!(parsed.edge.side, EdgeSide::Left);
    assert_eq!(parsed.edge.top_percent, 40);
    assert!(!parsed.debug.webview_debugging_enabled);
    assert!(!parsed.sanitized().debug.webview_debugging_enabled);
}

#[test]
fn sanitized_clamps_out_of_bounds_values_and_preserves_debug() {
    let raw = SideVeilConfig {
        version: -5,
        enabled: true,
        edge: EdgeConfig {
            side: EdgeSide::Left,
            top_percent: 999,
            height_dp: 10,
            hotzone_width_dp: 99,
            trigger_distance_dp: 1,
            edge_offset_dp: -3,
            handle_visible: false,
            handle_opacity: f32::NAN,
            dim_in_fullscreen: false,
            double_swipe_in_fullscreen: true,
            idle_auto_dim_ms: 99_999,
        },
        panel: PanelConfig {
            width_dp: 100,
            height_percent: 200,
            corner_radius_dp: 1,
            theme_id: "invalid".to_owned(),
            color_mode: "invalid".to_owned(),
            native_blur_enabled: false,
            blur_radius_px: 500,
            scrim_alpha: 2.0,
        },
        scope: ScopeConfig {
            mode: ScopeMode::Global,
            allowlist: vec![" a.b ".to_owned(), "".to_owned(), "a.b".to_owned()],
            denylist: vec!["com.android.systemui".to_owned()],
        },
        debug: DebugConfig {
            webview_debugging_enabled: true,
        },
    };
    let clean = raw.sanitized();
    assert_eq!(clean.version, 1);
    assert_eq!(clean.edge.top_percent, 85);
    assert_eq!(clean.edge.height_dp, 64);
    assert_eq!(clean.edge.hotzone_width_dp, 48);
    assert_eq!(clean.edge.trigger_distance_dp, 18);
    assert_eq!(clean.edge.edge_offset_dp, 0);
    assert!((clean.edge.handle_opacity - 0.62).abs() < 1e-6);
    assert!(clean.edge.double_swipe_in_fullscreen);
    assert_eq!(clean.edge.idle_auto_dim_ms, 30_000);
    assert_eq!(clean.panel.width_dp, 260);
    assert_eq!(clean.panel.height_percent, 92);
    assert_eq!(clean.panel.corner_radius_dp, 8);
    assert_eq!(clean.panel.theme_id, THEME_COLOROS_GLASS);
    assert_eq!(clean.panel.color_mode, "system");
    assert_eq!(clean.panel.blur_radius_px, 300);
    assert!((clean.panel.scrim_alpha - 0.75).abs() < 1e-6);
    assert_eq!(clean.scope.allowlist, vec!["a.b"]);
    assert!(clean.matches_package("com.example.anything"));
    assert!(!clean.matches_package("com.android.systemui"));
    assert!(clean.debug.webview_debugging_enabled);
}

#[test]
fn partial_appearance_patch_preserves_unspecified_fields() {
    let base = SideVeilConfig::default();
    let patched = base.apply_appearance_patch(&AppearancePatchParams {
        color_mode: Some("dark".to_owned()),
        corner_radius_dp: Some(36),
        ..Default::default()
    });
    assert_eq!(patched.panel.theme_id, THEME_COLOROS_GLASS);
    assert_eq!(patched.panel.color_mode, "dark");
    assert_eq!(patched.panel.corner_radius_dp, 36);
    assert!(patched.panel.native_blur_enabled);
}
