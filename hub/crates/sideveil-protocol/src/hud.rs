//! Declarative vector HUD scene schema for zero-WebView `vello_cpu` NEON rendering + HWUI text.

use serde::{Deserialize, Serialize};

use crate::generated::protocol_constants::hud_defaults;
pub use crate::generated::theme_hud::{HudDismissPayload, HudTextAlign};

#[allow(clippy::derivable_impls)]
impl Default for HudTextAlign {
    fn default() -> Self {
        Self::Start
    }
}

/// Background presentation style for a native HUD overlay window.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum HudBackdropKind {
    #[default]
    NativeGlass,
    Transparent,
    Opaque,
}

/// Vector drawing primitive rasterized by `sideveil-gfx` (`vello_cpu` NEON SIMD).
/// All coordinates and radii are expressed in logical Android `dp`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum HudPrimitive {
    #[serde(rename_all = "camelCase")]
    RoundedPill {
        x: f32,
        y: f32,
        w: f32,
        h: f32,
        #[serde(default)]
        radius: f32,
        fill_argb: u32,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        stroke_argb: Option<u32>,
        #[serde(default)]
        stroke_width: f32,
    },
    #[serde(rename_all = "camelCase")]
    ProgressBar {
        x: f32,
        y: f32,
        w: f32,
        h: f32,
        #[serde(default)]
        radius: f32,
        value: f32,
        track_argb: u32,
        fill_start_argb: u32,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        fill_end_argb: Option<u32>,
    },
    #[serde(rename_all = "camelCase")]
    Sparkline {
        x: f32,
        y: f32,
        w: f32,
        h: f32,
        values: Vec<f32>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        min: Option<f32>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        max: Option<f32>,
        stroke_argb: u32,
        #[serde(default = "default_stroke_width")]
        stroke_width: f32,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        fill_top_argb: Option<u32>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        fill_bottom_argb: Option<u32>,
        #[serde(default = "default_true")]
        smooth: bool,
    },
    #[serde(rename_all = "camelCase")]
    RadialGauge {
        cx: f32,
        cy: f32,
        radius: f32,
        thickness: f32,
        value: f32,
        #[serde(default = "default_gauge_start")]
        start_angle_deg: f32,
        #[serde(default = "default_gauge_sweep")]
        sweep_angle_deg: f32,
        track_argb: u32,
        fill_start_argb: u32,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        fill_end_argb: Option<u32>,
    },
    #[serde(rename_all = "camelCase")]
    Crosshair {
        cx: f32,
        cy: f32,
        size: f32,
        #[serde(default)]
        gap: f32,
        #[serde(default = "default_stroke_width")]
        thickness: f32,
        color_argb: u32,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        outline_argb: Option<u32>,
    },
    #[serde(rename_all = "camelCase")]
    Path {
        d: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        fill_argb: Option<u32>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        stroke_argb: Option<u32>,
        #[serde(default = "default_stroke_width")]
        stroke_width: f32,
    },
}

/// Crisp native text overlay drawn by Android HWUI `Canvas.drawText` on top of the vector bitmap.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HudTextOverlay {
    pub x: f32,
    pub y: f32,
    pub text: String,
    #[serde(default = "default_text_sp")]
    pub size_sp: f32,
    #[serde(default = "default_text_color_argb")]
    pub color_argb: u32,
    #[serde(default)]
    pub align: HudTextAlign,
    #[serde(default)]
    pub bold: bool,
    #[serde(default = "default_true")]
    pub tabular_nums: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_width_dp: Option<f32>,
}

/// Complete declarative scene pushed by a plugin backend or CLI for zero-WebView native HUD display.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct NativeHudScene {
    pub plugin_id: String,
    pub width_dp: f32,
    pub height_dp: f32,
    pub corner_radius_dp: f32,
    pub backdrop: HudBackdropKind,
    pub click_through: bool,
    pub anchor_x_percent: f32,
    pub anchor_y_percent: f32,
    pub primitives: Vec<HudPrimitive>,
    pub texts: Vec<HudTextOverlay>,
}

impl Default for NativeHudScene {
    fn default() -> Self {
        Self {
            plugin_id: String::new(),
            width_dp: hud_defaults::DEFAULT_WIDTH_DP,
            height_dp: hud_defaults::DEFAULT_HEIGHT_DP,
            corner_radius_dp: hud_defaults::DEFAULT_CORNER_RADIUS_DP,
            backdrop: HudBackdropKind::NativeGlass,
            click_through: false,
            anchor_x_percent: hud_defaults::DEFAULT_ANCHOR_X_PERCENT,
            anchor_y_percent: hud_defaults::DEFAULT_ANCHOR_Y_PERCENT,
            primitives: Vec::new(),
            texts: Vec::new(),
        }
    }
}

impl NativeHudScene {
    /// Clamps dimensions and list counts to safe bounds for bounded CPU/memory HUD rendering.
    #[must_use]
    pub fn sanitized(&self) -> Self {
        let mut out = self.clone();
        out.width_dp = clamp_finite(out.width_dp, 48.0, 420.0, hud_defaults::DEFAULT_WIDTH_DP);
        out.height_dp = clamp_finite(out.height_dp, 28.0, 320.0, hud_defaults::DEFAULT_HEIGHT_DP);
        out.corner_radius_dp = clamp_finite(
            out.corner_radius_dp,
            0.0,
            40.0,
            hud_defaults::DEFAULT_CORNER_RADIUS_DP,
        );
        out.anchor_x_percent = clamp_finite(
            out.anchor_x_percent,
            0.0,
            100.0,
            hud_defaults::DEFAULT_ANCHOR_X_PERCENT,
        );
        out.anchor_y_percent = clamp_finite(
            out.anchor_y_percent,
            0.0,
            100.0,
            hud_defaults::DEFAULT_ANCHOR_Y_PERCENT,
        );
        out.primitives.truncate(MAX_HUD_PRIMITIVES);
        for primitive in &mut out.primitives {
            match primitive {
                HudPrimitive::Sparkline { values, .. } => {
                    values.truncate(MAX_SPARKLINE_POINTS);
                }
                HudPrimitive::Path { d, .. } if d.len() > MAX_HUD_PATH_BYTES => {
                    truncate_utf8(d, MAX_HUD_PATH_BYTES);
                }
                _ => {}
            }
        }
        out.texts.truncate(MAX_HUD_TEXTS);
        for text_item in &mut out.texts {
            if text_item.text.len() > MAX_HUD_TEXT_BYTES {
                truncate_utf8(&mut text_item.text, MAX_HUD_TEXT_BYTES);
            }
        }
        out
    }
}

fn truncate_utf8(s: &mut String, max_bytes: usize) {
    let mut end = max_bytes.min(s.len());
    while end > 0 && !s.is_char_boundary(end) {
        end -= 1;
    }
    s.truncate(end);
}

pub const MAX_HUD_PRIMITIVES: usize = hud_defaults::MAX_PRIMITIVES;
pub const MAX_HUD_TEXTS: usize = hud_defaults::MAX_TEXTS;
pub const MAX_SPARKLINE_POINTS: usize = 256;
pub const MAX_HUD_PATH_BYTES: usize = 4096;
pub const MAX_HUD_TEXT_BYTES: usize = 256;

fn default_stroke_width() -> f32 {
    hud_defaults::DEFAULT_STROKE_WIDTH
}

fn default_true() -> bool {
    true
}

fn default_gauge_start() -> f32 {
    hud_defaults::DEFAULT_GAUGE_START_DEG
}

fn default_gauge_sweep() -> f32 {
    hud_defaults::DEFAULT_GAUGE_SWEEP_DEG
}

fn default_text_sp() -> f32 {
    hud_defaults::DEFAULT_TEXT_SIZE_SP
}

fn default_text_color_argb() -> u32 {
    hud_defaults::DEFAULT_TEXT_COLOR_ARGB
}

fn clamp_finite(val: f32, min: f32, max: f32, fallback: f32) -> f32 {
    if val.is_finite() {
        val.clamp(min, max)
    } else {
        fallback
    }
}
