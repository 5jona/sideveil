//! Authoritative SideVeil host configuration schema and sanitization rules.

mod patches;

use std::collections::HashSet;

use serde::{Deserialize, Serialize};

pub use self::patches::{AppearancePatchParams, EdgePatchParams};

pub const THEME_COLOROS_GLASS: &str = "coloros-glass";
pub const THEME_MODERN_SAAS_DARK: &str = "modern-saas-dark";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum EdgeSide {
    Left,
    #[default]
    Right,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ScopeMode {
    #[default]
    Allowlist,
    Global,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PanelState {
    #[default]
    Closed,
    Dragging,
    Open,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct EdgeConfig {
    pub side: EdgeSide,
    pub top_percent: i32,
    pub height_dp: i32,
    pub hotzone_width_dp: i32,
    pub trigger_distance_dp: i32,
    pub edge_offset_dp: i32,
    pub handle_visible: bool,
    pub handle_opacity: f32,
    pub dim_in_fullscreen: bool,
    pub double_swipe_in_fullscreen: bool,
    pub idle_auto_dim_ms: i32,
}

impl Default for EdgeConfig {
    fn default() -> Self {
        Self {
            side: EdgeSide::Right,
            top_percent: 32,
            height_dp: 168,
            hotzone_width_dp: 24,
            trigger_distance_dp: 36,
            edge_offset_dp: 1,
            handle_visible: true,
            handle_opacity: 0.62,
            dim_in_fullscreen: true,
            double_swipe_in_fullscreen: false,
            idle_auto_dim_ms: 3500,
        }
    }
}

impl EdgeConfig {
    #[must_use]
    pub fn sanitized(&self) -> Self {
        let opacity = if self.handle_opacity.is_finite() {
            self.handle_opacity.clamp(0.1, 1.0)
        } else {
            Self::default().handle_opacity
        };
        Self {
            side: self.side,
            top_percent: self.top_percent.clamp(5, 85),
            height_dp: self.height_dp.clamp(64, 360),
            hotzone_width_dp: self.hotzone_width_dp.clamp(12, 48),
            trigger_distance_dp: self.trigger_distance_dp.clamp(18, 96),
            edge_offset_dp: self.edge_offset_dp.clamp(0, 24),
            handle_visible: self.handle_visible,
            handle_opacity: opacity,
            dim_in_fullscreen: self.dim_in_fullscreen,
            double_swipe_in_fullscreen: self.double_swipe_in_fullscreen,
            idle_auto_dim_ms: self.idle_auto_dim_ms.clamp(0, 30_000),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PanelConfig {
    pub width_dp: i32,
    pub height_percent: i32,
    pub corner_radius_dp: i32,
    pub theme_id: String,
    pub color_mode: String,
    pub native_blur_enabled: bool,
    pub blur_radius_px: i32,
    pub scrim_alpha: f32,
}

impl Default for PanelConfig {
    fn default() -> Self {
        Self {
            width_dp: 340,
            height_percent: 72,
            corner_radius_dp: 22,
            theme_id: THEME_COLOROS_GLASS.to_owned(),
            color_mode: "system".to_owned(),
            native_blur_enabled: true,
            blur_radius_px: 160,
            scrim_alpha: 0.0,
        }
    }
}

impl PanelConfig {
    #[must_use]
    pub fn sanitized(&self) -> Self {
        let theme_id = match self.theme_id.as_str() {
            THEME_COLOROS_GLASS | THEME_MODERN_SAAS_DARK => self.theme_id.clone(),
            _ => THEME_COLOROS_GLASS.to_owned(),
        };
        let color_mode = match self.color_mode.as_str() {
            "light" | "dark" | "system" => self.color_mode.clone(),
            _ => "system".to_owned(),
        };
        let scrim_alpha = if self.scrim_alpha.is_finite() {
            self.scrim_alpha.clamp(0.0, 0.75)
        } else {
            Self::default().scrim_alpha
        };
        Self {
            width_dp: self.width_dp.clamp(260, 480),
            height_percent: self.height_percent.clamp(40, 92),
            corner_radius_dp: self.corner_radius_dp.clamp(8, 36),
            theme_id,
            color_mode,
            native_blur_enabled: self.native_blur_enabled,
            blur_radius_px: self.blur_radius_px.clamp(0, 300),
            scrim_alpha,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ScopeConfig {
    pub mode: ScopeMode,
    pub allowlist: Vec<String>,
    pub denylist: Vec<String>,
}

impl Default for ScopeConfig {
    fn default() -> Self {
        Self {
            mode: ScopeMode::Allowlist,
            allowlist: vec!["me.weishu.kernelsu".to_owned()],
            denylist: vec!["com.android.systemui".to_owned()],
        }
    }
}

impl ScopeConfig {
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

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct DebugConfig {
    pub webview_debugging_enabled: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SideVeilConfig {
    pub version: i32,
    pub enabled: bool,
    pub edge: EdgeConfig,
    pub panel: PanelConfig,
    pub scope: ScopeConfig,
    #[serde(default)]
    pub debug: DebugConfig,
}

impl Default for SideVeilConfig {
    fn default() -> Self {
        Self {
            version: 1,
            enabled: true,
            edge: EdgeConfig::default(),
            panel: PanelConfig::default(),
            scope: ScopeConfig::default(),
            debug: DebugConfig::default(),
        }
    }
}

impl SideVeilConfig {
    #[must_use]
    pub fn sanitized(&self) -> Self {
        Self {
            version: self.version.max(1),
            enabled: self.enabled,
            edge: self.edge.sanitized(),
            panel: self.panel.sanitized(),
            scope: self.scope.sanitized(),
            debug: self.debug.clone(),
        }
    }

    #[must_use]
    pub fn matches_package(&self, package_name: &str) -> bool {
        let pkg = package_name.trim();
        if !self.enabled || pkg.is_empty() {
            return false;
        }
        let clean = self.scope.sanitized();
        if clean.denylist.iter().any(|item| item == pkg) {
            return false;
        }
        match clean.mode {
            ScopeMode::Global => true,
            ScopeMode::Allowlist => clean.allowlist.iter().any(|item| item == pkg),
        }
    }

    #[must_use]
    pub fn apply_appearance_patch(&self, patch: &AppearancePatchParams) -> Self {
        let mut next = self.clone();
        if let Some(theme_id) = &patch.theme_id {
            next.panel.theme_id = theme_id.clone();
        }
        if let Some(color_mode) = &patch.color_mode {
            next.panel.color_mode = color_mode.clone();
        }
        if let Some(native_blur_enabled) = patch.native_blur_enabled {
            next.panel.native_blur_enabled = native_blur_enabled;
        }
        if let Some(blur_radius_px) = patch.blur_radius_px {
            next.panel.blur_radius_px = blur_radius_px;
        }
        if let Some(corner_radius_dp) = patch.corner_radius_dp {
            next.panel.corner_radius_dp = corner_radius_dp;
        }
        next.sanitized()
    }

    #[must_use]
    pub fn apply_edge_patch(&self, patch: &EdgePatchParams) -> Self {
        let mut next = self.clone();
        if let Some(side) = patch.side {
            next.edge.side = side;
        }
        if let Some(top_percent) = patch.top_percent {
            next.edge.top_percent = top_percent;
        }
        next.sanitized()
    }
}

#[cfg(test)]
mod tests;
