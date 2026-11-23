//! Configuration subsections (`EdgeConfig`, `PanelConfig`, `ScopeConfig`, `DebugConfig`)
//! and their bounds-clamping sanitization helpers.

use std::collections::HashSet;

use serde::{Deserialize, Serialize};

use super::{THEME_COLOROS_GLASS, THEME_MODERN_SAAS_DARK};
use crate::generated::config_bounds::{bounds, defaults};

/// Screen edge where the sidebar trigger handle and panel attach.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum EdgeSide {
    /// Left screen edge.
    Left,
    /// Right screen edge (default).
    #[default]
    Right,
}

/// Foreground-application matching mode for the built-in diagnostic scope.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ScopeMode {
    /// Enable only on packages listed in `allowlist`.
    #[default]
    Allowlist,
    /// Enable on all foreground packages except `denylist`.
    Global,
}

/// Edge trigger geometry, gesture thresholds, and idle dimming configuration.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct EdgeConfig {
    /// Screen edge hosting the gesture hotzone and indicator handle.
    pub side: EdgeSide,
    /// Vertical anchor position as percentage of screen height.
    pub top_percent: i32,
    /// Vertical hotzone height in density-independent pixels.
    pub height_dp: i32,
    /// Edge hotzone touch width in dp.
    pub hotzone_width_dp: i32,
    /// Horizontal swipe distance required to commit open in dp.
    pub trigger_distance_dp: i32,
    /// Horizontal inset offset from the physical edge in dp.
    pub edge_offset_dp: i32,
    /// Whether the translucent edge pill indicator is rendered.
    pub handle_visible: bool,
    /// Resting opacity of the edge indicator pill.
    pub handle_opacity: f32,
    /// Whether to dim the indicator pill during immersive fullscreen apps.
    pub dim_in_fullscreen: bool,
    /// Whether fullscreen apps require two consecutive swipes to open.
    pub double_swipe_in_fullscreen: bool,
    /// Idle delay in milliseconds before auto-dimming the indicator pill.
    pub idle_auto_dim_ms: i32,
}

impl Default for EdgeConfig {
    fn default() -> Self {
        Self {
            side: EdgeSide::Right,
            top_percent: defaults::EDGE_TOP_PERCENT,
            height_dp: defaults::EDGE_HEIGHT_DP,
            hotzone_width_dp: defaults::EDGE_HOTZONE_WIDTH_DP,
            trigger_distance_dp: defaults::EDGE_TRIGGER_DISTANCE_DP,
            edge_offset_dp: defaults::EDGE_OFFSET_DP,
            handle_visible: defaults::EDGE_HANDLE_VISIBLE,
            handle_opacity: defaults::EDGE_HANDLE_OPACITY,
            dim_in_fullscreen: defaults::EDGE_DIM_IN_FULLSCREEN,
            double_swipe_in_fullscreen: defaults::EDGE_DOUBLE_SWIPE_IN_FULLSCREEN,
            idle_auto_dim_ms: defaults::EDGE_IDLE_AUTO_DIM_MS,
        }
    }
}

impl EdgeConfig {
    /// Returns a copy with all numeric fields clamped to canonical contract bounds.
    #[must_use]
    pub fn sanitized(&self) -> Self {
        let opacity = if self.handle_opacity.is_finite() {
            self.handle_opacity.clamp(
                bounds::EDGE_HANDLE_OPACITY_MIN,
                bounds::EDGE_HANDLE_OPACITY_MAX,
            )
        } else {
            defaults::EDGE_HANDLE_OPACITY
        };
        Self {
            side: self.side,
            top_percent: self
                .top_percent
                .clamp(bounds::EDGE_TOP_PERCENT_MIN, bounds::EDGE_TOP_PERCENT_MAX),
            height_dp: self
                .height_dp
                .clamp(bounds::EDGE_HEIGHT_DP_MIN, bounds::EDGE_HEIGHT_DP_MAX),
            hotzone_width_dp: self.hotzone_width_dp.clamp(
                bounds::EDGE_HOTZONE_WIDTH_DP_MIN,
                bounds::EDGE_HOTZONE_WIDTH_DP_MAX,
            ),
            trigger_distance_dp: self.trigger_distance_dp.clamp(
                bounds::EDGE_TRIGGER_DISTANCE_DP_MIN,
                bounds::EDGE_TRIGGER_DISTANCE_DP_MAX,
            ),
            edge_offset_dp: self
                .edge_offset_dp
                .clamp(bounds::EDGE_OFFSET_DP_MIN, bounds::EDGE_OFFSET_DP_MAX),
            handle_visible: self.handle_visible,
            handle_opacity: opacity,
            dim_in_fullscreen: self.dim_in_fullscreen,
            double_swipe_in_fullscreen: self.double_swipe_in_fullscreen,
            idle_auto_dim_ms: self.idle_auto_dim_ms.clamp(
                bounds::EDGE_IDLE_AUTO_DIM_MS_MIN,
                bounds::EDGE_IDLE_AUTO_DIM_MS_MAX,
            ),
        }
    }
}

/// Sidebar panel geometry, visual theme preset, and native blur settings.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PanelConfig {
    /// Sidebar panel width in dp.
    pub width_dp: i32,
    /// Sidebar panel height as percentage of screen height.
    pub height_percent: i32,
    /// Outer corner radius in dp.
    pub corner_radius_dp: i32,
    /// Canonical theme preset identifier (`coloros-glass` or `modern-saas-dark`).
    pub theme_id: String,
    /// Surface polarity preference (`light`, `dark`, or `system`).
    pub color_mode: String,
    /// Whether Android cross-window blur is enabled on the panel window.
    pub native_blur_enabled: bool,
    /// Native cross-window blur radius in physical pixels.
    pub blur_radius_px: i32,
    /// Backdrop scrim dim opacity behind the expanded panel.
    pub scrim_alpha: f32,
}

impl Default for PanelConfig {
    fn default() -> Self {
        Self {
            width_dp: defaults::PANEL_WIDTH_DP,
            height_percent: defaults::PANEL_HEIGHT_PERCENT,
            corner_radius_dp: defaults::PANEL_CORNER_RADIUS_DP,
            theme_id: THEME_COLOROS_GLASS.to_owned(),
            color_mode: defaults::PANEL_COLOR_MODE.to_owned(),
            native_blur_enabled: defaults::PANEL_NATIVE_BLUR_ENABLED,
            blur_radius_px: defaults::PANEL_BLUR_RADIUS_PX,
            scrim_alpha: defaults::PANEL_SCRIM_ALPHA,
        }
    }
}

impl PanelConfig {
    /// Returns a copy with theme allowlist, color mode, and numeric bounds validated.
    #[must_use]
    pub fn sanitized(&self) -> Self {
        let theme_id = match self.theme_id.as_str() {
            THEME_COLOROS_GLASS | THEME_MODERN_SAAS_DARK => self.theme_id.clone(),
            _ => THEME_COLOROS_GLASS.to_owned(),
        };
        let color_mode = match self.color_mode.as_str() {
            "light" | "dark" | "system" => self.color_mode.clone(),
            _ => defaults::PANEL_COLOR_MODE.to_owned(),
        };
        let scrim_alpha = if self.scrim_alpha.is_finite() {
            self.scrim_alpha
                .clamp(bounds::PANEL_SCRIM_ALPHA_MIN, bounds::PANEL_SCRIM_ALPHA_MAX)
        } else {
            defaults::PANEL_SCRIM_ALPHA
        };
        Self {
            width_dp: self
                .width_dp
                .clamp(bounds::PANEL_WIDTH_DP_MIN, bounds::PANEL_WIDTH_DP_MAX),
            height_percent: self.height_percent.clamp(
                bounds::PANEL_HEIGHT_PERCENT_MIN,
                bounds::PANEL_HEIGHT_PERCENT_MAX,
            ),
            corner_radius_dp: self.corner_radius_dp.clamp(
                bounds::PANEL_CORNER_RADIUS_DP_MIN,
                bounds::PANEL_CORNER_RADIUS_DP_MAX,
            ),
            theme_id,
            color_mode,
            native_blur_enabled: self.native_blur_enabled,
            blur_radius_px: self.blur_radius_px.clamp(
                bounds::PANEL_BLUR_RADIUS_PX_MIN,
                bounds::PANEL_BLUR_RADIUS_PX_MAX,
            ),
            scrim_alpha,
        }
    }
}

/// Package visibility scope controlling when the built-in sidebar activates.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ScopeConfig {
    /// Allowlist or global matching mode.
    pub mode: ScopeMode,
    /// Package names permitted when `mode == ScopeMode::Allowlist`.
    pub allowlist: Vec<String>,
    /// Package names always excluded regardless of mode.
    pub denylist: Vec<String>,
}

impl Default for ScopeConfig {
    fn default() -> Self {
        Self {
            mode: ScopeMode::Allowlist,
            allowlist: vec![defaults::SCOPE_ALLOWLIST_DEFAULT_PACKAGE.to_owned()],
            denylist: vec![defaults::SCOPE_DENYLIST_DEFAULT_PACKAGE.to_owned()],
        }
    }
}

impl ScopeConfig {
    /// Returns a copy with trimmed, deduplicated package lists.
    #[must_use]
    pub fn sanitized(&self) -> Self {
        Self {
            mode: self.mode,
            allowlist: sanitize_pkg_list(&self.allowlist),
            denylist: sanitize_pkg_list(&self.denylist),
        }
    }
}

fn sanitize_pkg_list(items: &[String]) -> Vec<String> {
    let mut seen = HashSet::with_capacity(items.len());
    let mut out = Vec::with_capacity(items.len());
    for item in items {
        let trimmed = item.trim();
        if !trimmed.is_empty() && seen.insert(trimmed.to_owned()) {
            out.push(trimmed.to_owned());
        }
    }
    out
}

/// Developer diagnostics flags persisted in the authoritative configuration.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct DebugConfig {
    /// Enables Android WebView remote DevTools inspection (`WebView.setWebContentsDebuggingEnabled`).
    pub webview_debugging_enabled: bool,
}
