//! Dynamic Material You / `Oklch` synthesized palette wire types shared by Hub, Host, and WebViews.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

/// Upstream dynamic color seed reported by `SideVeilHost` from Android Material You / WallpaperColors.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HostDynamicColorsPayload {
    /// Material You primary accent or wallpaper seed color (`0xAARRGGBB`).
    pub seed_argb: u32,
    /// Estimated wallpaper/backdrop luminance hint in `[0.0, 1.0]` (`0` = dark, `1` = bright).
    #[serde(default = "default_luma")]
    pub luma_hint: f32,
    pub is_dark: bool,
}

/// Native AGSL glass tint override synthesized from the seed color in perceptual `Oklch` space.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GlassTintOverride {
    pub tint_top_argb: u32,
    pub tint_bottom_argb: u32,
    pub rim_argb: u32,
    pub accent_argb: u32,
    pub fallback_fill_argb: u32,
    pub fallback_stroke_argb: u32,
}

/// Single-source synthesized palette broadcast from `sideveild` to `SideVeilHost` and plugin WebViews.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DynamicThemePalette {
    pub theme_id: String,
    pub is_dark: bool,
    pub seed_argb: u32,
    pub luma_hint: f32,
    pub glass: GlassTintOverride,
    pub css_tokens: BTreeMap<String, String>,
}

fn default_luma() -> f32 {
    0.5
}
