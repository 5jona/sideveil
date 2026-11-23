//! Theme-aware `NativeHudScene` synthesis for the reference performance HUD plugin.

use std::collections::VecDeque;

use sideveil_protocol::hud::{
    HudBackdropKind, HudPrimitive, HudTextAlign, HudTextOverlay, NativeHudScene,
};

pub(crate) const PLUGIN_ID: &str = "example.native-perf-hud";

pub(crate) fn build_scene(
    history: &VecDeque<f32>,
    cpu_pct: f32,
    mem_used: u64,
    mem_total: u64,
    temp_c: f32,
    click_through: bool,
    is_dark: bool,
) -> NativeHudScene {
    let mem_ratio = if mem_total > 0 {
        (mem_used as f32 / mem_total as f32).clamp(0.0, 1.0)
    } else {
        0.0
    };
    let temp_ratio = ((temp_c - 20.0) / 65.0).clamp(0.0, 1.0);
    let (
        primary_text,
        gauge_text,
        track_argb,
        wave_stroke,
        wave_fill_top,
        bar_start,
        bar_end,
        gauge_end,
    ) = if is_dark {
        (
            0xFFF1_F5F9,
            0xFFF8_FAFC,
            0x33FF_FFFF,
            0xFF38_BDF8,
            0x5538_BDF8,
            0xFF34_D399,
            0xFF38_BDF8,
            0xFFEF_4444,
        )
    } else {
        (
            0xFF0F_172A,
            0xFF0F_172A,
            0x280F_172A,
            0xFF02_84C7,
            0x4402_84C7,
            0xFF05_9669,
            0xFF02_84C7,
            0xFFDC_2626,
        )
    };
    NativeHudScene {
        plugin_id: PLUGIN_ID.to_owned(),
        width_dp: 216.0,
        height_dp: 94.0,
        corner_radius_dp: 18.0,
        backdrop: HudBackdropKind::NativeGlass,
        click_through,
        anchor_x_percent: 4.0,
        anchor_y_percent: 12.0,
        primitives: vec![
            HudPrimitive::Sparkline {
                x: 12.0,
                y: 28.0,
                w: 122.0,
                h: 36.0,
                values: history.iter().copied().collect(),
                min: Some(0.0),
                max: Some(100.0),
                stroke_argb: wave_stroke,
                stroke_width: 2.0,
                fill_top_argb: Some(wave_fill_top),
                fill_bottom_argb: Some(wave_stroke & 0x00FF_FFFF),
                smooth: true,
            },
            HudPrimitive::ProgressBar {
                x: 12.0,
                y: 74.0,
                w: 122.0,
                h: 6.0,
                radius: 3.0,
                value: mem_ratio,
                track_argb,
                fill_start_argb: bar_start,
                fill_end_argb: Some(bar_end),
            },
            HudPrimitive::RadialGauge {
                cx: 174.0,
                cy: 48.0,
                radius: 22.0,
                thickness: 5.0,
                value: temp_ratio,
                start_angle_deg: 135.0,
                sweep_angle_deg: 270.0,
                track_argb,
                fill_start_argb: bar_start,
                fill_end_argb: Some(gauge_end),
            },
        ],
        texts: vec![
            HudTextOverlay {
                x: 12.0,
                y: 20.0,
                text: format!("CPU {cpu_pct:4.1}%  RAM {:.1}G", mem_used as f32 / 1024.0),
                size_sp: 11.0,
                color_argb: primary_text,
                align: HudTextAlign::Start,
                bold: true,
                tabular_nums: true,
                max_width_dp: Some(130.0),
            },
            HudTextOverlay {
                x: 174.0,
                y: 52.0,
                text: format!("{temp_c:.0}°C"),
                size_sp: 11.0,
                color_argb: gauge_text,
                align: HudTextAlign::Center,
                bold: true,
                tabular_nums: true,
                max_width_dp: None,
            },
        ],
    }
}
