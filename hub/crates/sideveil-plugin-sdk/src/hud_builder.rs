//! Ergonomic builder for constructing [`NativeHudScene`] overlays in Rust plugin daemons.

use sideveil_protocol::hud::{
    HudBackdropKind, HudPrimitive, HudTextAlign, HudTextOverlay, NativeHudScene,
};

/// Fluent builder for [`NativeHudScene`] payloads submitted via [`crate::PluginBackendHandle::push_hud`].
#[derive(Debug, Clone)]
pub struct HudSceneBuilder {
    scene: NativeHudScene,
}

impl HudSceneBuilder {
    /// Starts a new HUD scene with dimensions in density-independent pixels (`dp`).
    #[must_use]
    pub fn new(plugin_id: impl Into<String>, width_dp: f32, height_dp: f32) -> Self {
        Self {
            scene: NativeHudScene {
                plugin_id: plugin_id.into(),
                width_dp,
                height_dp,
                ..NativeHudScene::default()
            },
        }
    }

    /// Sets window anchor coordinates as screen percentages (`0.0..=1.0`).
    #[must_use]
    pub fn anchor(mut self, x_percent: f32, y_percent: f32) -> Self {
        self.scene.anchor_x_percent = x_percent;
        self.scene.anchor_y_percent = y_percent;
        self
    }

    /// Enables or disables touch pass-through (`FLAG_NOT_TOUCHABLE`).
    #[must_use]
    pub fn click_through(mut self, enabled: bool) -> Self {
        self.scene.click_through = enabled;
        self
    }

    /// Sets the native window backdrop style and corner radius.
    #[must_use]
    pub fn backdrop(mut self, backdrop: HudBackdropKind, corner_radius_dp: f32) -> Self {
        self.scene.backdrop = backdrop;
        self.scene.corner_radius_dp = corner_radius_dp;
        self
    }

    /// Appends a vector primitive (rounded pill, progress bar, sparkline, gauge, crosshair, or path).
    #[must_use]
    pub fn primitive(mut self, primitive: HudPrimitive) -> Self {
        self.scene.primitives.push(primitive);
        self
    }

    /// Appends a crisp HWUI text overlay label.
    #[must_use]
    #[allow(clippy::too_many_arguments)]
    pub fn text(
        mut self,
        x: f32,
        y: f32,
        text: impl Into<String>,
        size_sp: f32,
        color_argb: u32,
        bold: bool,
        align: HudTextAlign,
    ) -> Self {
        self.scene.texts.push(HudTextOverlay {
            x,
            y,
            text: text.into(),
            size_sp,
            color_argb,
            align,
            bold,
            tabular_nums: true,
            max_width_dp: None,
        });
        self
    }

    /// Finalizes and returns the sanitized [`NativeHudScene`].
    #[must_use]
    pub fn build(self) -> NativeHudScene {
        self.scene.sanitized()
    }
}
