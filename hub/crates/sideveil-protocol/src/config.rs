//! Authoritative SideVeil host configuration schema and sanitization rules.

mod patches;
mod sections;

use serde::{Deserialize, Serialize};

pub use self::patches::{AppearancePatchParams, EdgePatchParams};
pub use self::sections::{DebugConfig, EdgeConfig, EdgeSide, PanelConfig, ScopeConfig, ScopeMode};
use crate::generated::config_bounds::{bounds, defaults};
pub use crate::generated::protocol_constants::themes::{
    COLOROS_GLASS as THEME_COLOROS_GLASS, MODERN_SAAS_DARK as THEME_MODERN_SAAS_DARK,
};
pub use crate::generated::protocol_wire::PanelState;

#[allow(clippy::derivable_impls)]
impl Default for PanelState {
    fn default() -> Self {
        Self::Closed
    }
}

/// Top-level authoritative configuration persisted at `/data/system/sideveil/config.json`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SideVeilConfig {
    /// Schema revision number (`>= 1`).
    pub version: i32,
    /// Master enable toggle for edge gesture interception and overlay rendering.
    pub enabled: bool,
    /// Edge trigger handle and gesture threshold settings.
    pub edge: EdgeConfig,
    /// Panel sizing, theme, and blur appearance settings.
    pub panel: PanelConfig,
    /// Foreground application scope filtering rules.
    pub scope: ScopeConfig,
    /// Developer debugging switches.
    #[serde(default)]
    pub debug: DebugConfig,
}

impl Default for SideVeilConfig {
    fn default() -> Self {
        Self {
            version: defaults::VERSION,
            enabled: defaults::ENABLED,
            edge: EdgeConfig::default(),
            panel: PanelConfig::default(),
            scope: ScopeConfig::default(),
            debug: DebugConfig::default(),
        }
    }
}

impl SideVeilConfig {
    /// Returns a fully sanitized copy with all subsections clamped to safe bounds.
    #[must_use]
    pub fn sanitized(&self) -> Self {
        Self {
            version: self.version.max(bounds::MIN_VERSION),
            enabled: self.enabled,
            edge: self.edge.sanitized(),
            panel: self.panel.sanitized(),
            scope: self.scope.sanitized(),
            debug: self.debug.clone(),
        }
    }

    /// Evaluates whether the built-in diagnostic scope matches `package_name`.
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

    /// Applies an incremental appearance patch and returns the sanitized configuration.
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

    /// Applies an incremental edge handle reposition patch and returns the sanitized configuration.
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
