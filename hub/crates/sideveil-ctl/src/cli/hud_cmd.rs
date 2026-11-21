//! `sideveil-ctl hud` subcommand builder (demo vector HUD & dismiss).

use serde_json::{Value, json};
use sideveil_protocol::hud::{
    HudBackdropKind, HudPrimitive, HudTextAlign, HudTextOverlay, NativeHudScene,
};
use sideveil_protocol::rpc::methods;

use super::OutputMode;

pub(super) fn parse_hud_subcommand(
    args: &[String],
) -> Result<(String, Option<Value>, OutputMode), (u8, String)> {
    let sub = args.first().map_or("demo", String::as_str);
    match sub {
        "demo" => {
            let scene = build_demo_hud_scene();
            let val = serde_json::to_value(scene)
                .map_err(|e| (2, format!("Failed to serialize demo HUD scene: {e}")))?;
            Ok((
                methods::HUD_UPDATE_SCENE.to_owned(),
                Some(val),
                OutputMode::FullEnvelope,
            ))
        }
        "dismiss" => {
            let plugin_id = args.get(1).cloned();
            Ok((
                methods::HUD_DISMISS.to_owned(),
                Some(json!({ "pluginId": plugin_id })),
                OutputMode::FullEnvelope,
            ))
        }
        other => Err((
            2,
            format!(
                "Unknown hud subcommand '{other}'. Usage: sideveil-ctl hud [demo|dismiss [plugin-id]]"
            ),
        )),
    }
}

fn build_demo_hud_scene() -> NativeHudScene {
    NativeHudScene {
        plugin_id: "sideveil.hud.demo".to_owned(),
        width_dp: 212.0,
        height_dp: 88.0,
        corner_radius_dp: 18.0,
        backdrop: HudBackdropKind::NativeGlass,
        click_through: false,
        anchor_x_percent: 4.0,
        anchor_y_percent: 12.0,
        primitives: vec![
            HudPrimitive::Sparkline {
                x: 12.0,
                y: 34.0,
                w: 118.0,
                h: 40.0,
                values: vec![
                    118.0, 120.0, 119.0, 117.0, 120.0, 120.0, 116.0, 119.0, 120.0, 120.0, 118.0,
                    120.0,
                ],
                min: Some(60.0),
                max: Some(125.0),
                stroke_argb: 0xFF38_BDF8,
                stroke_width: 2.0,
                fill_top_argb: Some(0x5538_BDF8),
                fill_bottom_argb: Some(0x0038_BDF8),
                smooth: true,
            },
            HudPrimitive::RadialGauge {
                cx: 170.0,
                cy: 46.0,
                radius: 22.0,
                thickness: 5.0,
                value: 0.64,
                start_angle_deg: 135.0,
                sweep_angle_deg: 270.0,
                track_argb: 0x33FF_FFFF,
                fill_start_argb: 0xFF34_D399,
                fill_end_argb: Some(0xFFF5_9E0B),
            },
        ],
        texts: vec![
            HudTextOverlay {
                x: 12.0,
                y: 22.0,
                text: "120 FPS · Vello HUD".to_owned(),
                size_sp: 11.5,
                color_argb: 0xFFF1_F5F9,
                align: HudTextAlign::Start,
                bold: true,
                tabular_nums: true,
                max_width_dp: Some(128.0),
            },
            HudTextOverlay {
                x: 170.0,
                y: 50.0,
                text: "41°C".to_owned(),
                size_sp: 11.0,
                color_argb: 0xFFF8_FAFC,
                align: HudTextAlign::Center,
                bold: true,
                tabular_nums: true,
                max_width_dp: None,
            },
        ],
    }
}
