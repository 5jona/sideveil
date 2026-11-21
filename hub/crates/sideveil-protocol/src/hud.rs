//! Declarative vector HUD scene schema for zero-WebView `vello_cpu` NEON rendering + HWUI text.

use serde::{Deserialize, Serialize};

/// Background presentation style for a native HUD overlay window.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum HudBackdropKind {
    #[default]
    NativeGlass,
    Transparent,
    Opaque,
}

/// Horizontal text alignment for HWUI text overlays composited above `vello_cpu` vector layers.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum HudTextAlign {
    #[default]
    Start,
    Center,
    End,
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
            width_dp: 200.0,
            height_dp: 88.0,
            corner_radius_dp: 18.0,
            backdrop: HudBackdropKind::NativeGlass,
            click_through: false,
            anchor_x_percent: 4.0,
            anchor_y_percent: 12.0,
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
        out.width_dp = clamp_finite(out.width_dp, 48.0, 420.0, 200.0);
        out.height_dp = clamp_finite(out.height_dp, 28.0, 320.0, 88.0);
        out.corner_radius_dp = clamp_finite(out.corner_radius_dp, 0.0, 40.0, 18.0);
        out.anchor_x_percent = clamp_finite(out.anchor_x_percent, 0.0, 100.0, 4.0);
        out.anchor_y_percent = clamp_finite(out.anchor_y_percent, 0.0, 100.0, 12.0);
        out.primitives.truncate(MAX_HUD_PRIMITIVES);
        out.texts.truncate(MAX_HUD_TEXTS);
        out
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HudDismissPayload {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub plugin_id: Option<String>,
}

pub const MAX_HUD_PRIMITIVES: usize = 64;
pub const MAX_HUD_TEXTS: usize = 32;

fn default_stroke_width() -> f32 {
    1.5
}

fn default_true() -> bool {
    true
}

fn default_gauge_start() -> f32 {
    135.0
}

fn default_gauge_sweep() -> f32 {
    270.0
}

fn default_text_sp() -> f32 {
    12.0
}

fn clamp_finite(val: f32, min: f32, max: f32, fallback: f32) -> f32 {
    if val.is_finite() {
        val.clamp(min, max)
    } else {
        fallback
    }
}
