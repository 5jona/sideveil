//! Zero-WebView `vello_cpu` NEON vector HUD renderer for `NativeHudScene`.
//!
//! Rasterizes declarative vector primitives (`RoundedPill`, `ProgressBar`, `Sparkline`,
//! `RadialGauge`, `Crosshair`, `Path`) directly into caller-owned premultiplied RGBA8 buffers
//! matching Android `Bitmap.Config.ARGB_8888` with reusable `RenderContext`/`Pixmap` buffers.

mod path_parser;
mod primitives;
mod sparkline;

use sideveil_protocol::hud::NativeHudScene;
use vello_cpu::kurbo::Affine;
use vello_cpu::peniko::Fill;
use vello_cpu::{
    Level, Pixmap, RasterizerSettings, RenderContext, RenderMode, RenderSettings, Resources,
};

use self::primitives::draw_primitive;
use crate::GfxError;

const MAX_HUD_PIXEL_DIM: u16 = 2048;

/// Reusable `vello_cpu` HUD rasterization scratchpad avoiding per-frame allocations at 60Hz.
pub struct HudCanvas {
    ctx: RenderContext,
    resources: Resources,
    pixmap: Pixmap,
    width: u16,
    height: u16,
}

impl std::fmt::Debug for HudCanvas {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("HudCanvas")
            .field("width", &self.width)
            .field("height", &self.height)
            .finish()
    }
}

impl Default for HudCanvas {
    fn default() -> Self {
        Self::new(1, 1)
    }
}

impl HudCanvas {
    #[must_use]
    pub fn new(width: u16, height: u16) -> Self {
        let w = width.clamp(1, MAX_HUD_PIXEL_DIM);
        let h = height.clamp(1, MAX_HUD_PIXEL_DIM);
        let settings = RenderSettings {
            level: Level::new(),
            num_threads: 0,
        };
        Self {
            ctx: RenderContext::new_with(w, h, settings),
            resources: Resources::new(),
            pixmap: Pixmap::new(w, h),
            width: w,
            height: h,
        }
    }

    /// Renders `scene` into caller-supplied premultiplied RGBA8 slice (`width * height * 4` bytes).
    pub fn render_into(
        &mut self,
        scene: &NativeHudScene,
        density: f32,
        width_px: u16,
        height_px: u16,
        out_rgba: &mut [u8],
    ) -> Result<(), GfxError> {
        let expected_len = usize::from(width_px) * usize::from(height_px) * 4;
        if width_px == 0
            || height_px == 0
            || width_px > MAX_HUD_PIXEL_DIM
            || height_px > MAX_HUD_PIXEL_DIM
            || out_rgba.len() < expected_len
        {
            return Err(GfxError::InvalidCanvas {
                width: width_px,
                height: height_px,
            });
        }

        if self.width != width_px || self.height != height_px {
            self.width = width_px;
            self.height = height_px;
            self.ctx.reset_and_resize(width_px, height_px);
            self.pixmap = Pixmap::new(width_px, height_px);
        } else {
            self.ctx.reset();
        }

        let safe_density = if density.is_finite() && density > 0.25 {
            f64::from(density.clamp(0.5, 8.0))
        } else {
            1.0
        };
        let sanitized = scene.sanitized();
        self.ctx.set_transform(Affine::scale(safe_density));
        self.ctx.set_fill_rule(Fill::NonZero);

        for prim in &sanitized.primitives {
            draw_primitive(&mut self.ctx, prim);
        }

        self.ctx.flush();
        let raster = RasterizerSettings {
            render_mode: RenderMode::OptimizeSpeed,
            ..Default::default()
        };
        self.ctx
            .render_with(&mut self.pixmap, &mut self.resources, raster);

        let (chunks, _) = out_rgba[..expected_len].as_chunks_mut::<4>();
        for (pixel, chunk) in self.pixmap.data().iter().zip(chunks) {
            *chunk = [pixel.r, pixel.g, pixel.b, pixel.a];
        }
        Ok(())
    }
}

/// One-shot helper for tests and offline callers.
pub fn render_hud_scene_into(
    scene: &NativeHudScene,
    density: f32,
    width_px: u16,
    height_px: u16,
    out_rgba: &mut [u8],
) -> Result<(), GfxError> {
    let mut canvas = HudCanvas::new(width_px, height_px);
    canvas.render_into(scene, density, width_px, height_px, out_rgba)
}

#[cfg(test)]
mod tests {
    use sideveil_protocol::hud::HudPrimitive;

    use super::*;

    #[test]
    fn renders_sparkline_gauge_and_pill_into_rgba_buffer() {
        let scene = NativeHudScene {
            plugin_id: "test.hud".to_owned(),
            width_dp: 200.0,
            height_dp: 88.0,
            primitives: vec![
                HudPrimitive::RoundedPill {
                    x: 4.0,
                    y: 4.0,
                    w: 192.0,
                    h: 80.0,
                    radius: 14.0,
                    fill_argb: 0x331E293B,
                    stroke_argb: Some(0x66FFFFFF),
                    stroke_width: 1.0,
                },
                HudPrimitive::Sparkline {
                    x: 12.0,
                    y: 28.0,
                    w: 112.0,
                    h: 44.0,
                    values: vec![45.0, 58.0, 60.0, 59.0, 61.0, 54.0, 60.0],
                    min: Some(0.0),
                    max: Some(65.0),
                    stroke_argb: 0xFF38BDF8,
                    stroke_width: 2.0,
                    fill_top_argb: Some(0x5538BDF8),
                    fill_bottom_argb: Some(0x0038BDF8),
                    smooth: true,
                },
                HudPrimitive::RadialGauge {
                    cx: 162.0,
                    cy: 48.0,
                    radius: 22.0,
                    thickness: 5.0,
                    value: 0.72,
                    start_angle_deg: 135.0,
                    sweep_angle_deg: 270.0,
                    track_argb: 0x33FFFFFF,
                    fill_start_argb: 0xFF34D399,
                    fill_end_argb: Some(0xFFF59E0B),
                },
            ],
            ..Default::default()
        };

        let mut buf = vec![0u8; 400 * 176 * 4];
        render_hud_scene_into(&scene, 2.0, 400, 176, &mut buf).expect("render hud");
        let (pixels, _) = buf.as_chunks::<4>();
        let non_empty_pixels = pixels.iter().filter(|px| px[3] > 10).count();
        assert!(
            non_empty_pixels > 5000,
            "expected substantial rasterized HUD pixels, got {non_empty_pixels}"
        );
    }

    #[test]
    fn handles_malformed_paths_nan_values_and_tiny_buffers_without_panicking() {
        let weird = NativeHudScene {
            width_dp: f32::NAN,
            height_dp: -10.0,
            primitives: vec![
                HudPrimitive::Sparkline {
                    x: 0.0,
                    y: 0.0,
                    w: 50.0,
                    h: 20.0,
                    values: vec![f32::NAN, f32::INFINITY],
                    min: None,
                    max: None,
                    stroke_argb: 0xFFFFFFFF,
                    stroke_width: 1.5,
                    fill_top_argb: None,
                    fill_bottom_argb: None,
                    smooth: true,
                },
                HudPrimitive::Path {
                    d: "M 2 2 L 10 10 Z @@@ garbage".to_owned(),
                    fill_argb: None,
                    stroke_argb: Some(0xFFFFFFFF),
                    stroke_width: 1.0,
                },
            ],
            ..Default::default()
        };
        let mut ok_buf = vec![0u8; 64 * 32 * 4];
        assert!(render_hud_scene_into(&weird, 1.0, 64, 32, &mut ok_buf).is_ok());

        let mut too_small = vec![0u8; 4];
        assert!(matches!(
            render_hud_scene_into(&weird, 1.0, 64, 32, &mut too_small),
            Err(GfxError::InvalidCanvas { .. })
        ));
    }
}
