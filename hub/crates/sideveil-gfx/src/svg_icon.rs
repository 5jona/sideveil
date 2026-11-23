//! SVG icon rasterization powered by `usvg` and `vello_cpu` (NEON SIMD).
//!
//! Parses standard SVG icons safely (external file/data `<image>` hrefs strictly disabled),
//! resolves `currentColor` against the active dark/light glass surface, fits the vector artwork
//! inside a square target canvas with safe padding, and encodes anti-aliased PNG bytes.

mod paint;

use vello_cpu::kurbo::Affine;
use vello_cpu::peniko::Fill;
use vello_cpu::{
    Level, Pixmap, RasterizerSettings, RenderContext, RenderMode, RenderSettings, Resources,
};

use self::paint::{apply_fill, apply_stroke, collect_clip_path, to_affine, to_bez_path};
use crate::GfxError;

/// Maximum raw SVG payload size accepted for icon rasterization (512 KiB).
pub const MAX_SVG_ICON_BYTES: usize = 512 * 1024;
const MIN_ICON_SIZE_PX: u16 = 16;
const MAX_ICON_SIZE_PX: u16 = 256;
const MAX_SVG_DEPTH: u32 = 64;

/// Target surface polarity used to resolve SVG `currentColor`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum IconTheme {
    #[default]
    Dark,
    Light,
}

impl IconTheme {
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Dark => "dark",
            Self::Light => "light",
        }
    }

    #[must_use]
    pub fn current_color_hex(self) -> &'static str {
        match self {
            Self::Dark => "#F1F5F9",
            Self::Light => "#1E293B",
        }
    }
}

/// Configuration for rasterizing a plugin SVG icon to an anti-aliased PNG.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct IconRasterOptions {
    pub size_px: u16,
    pub theme: IconTheme,
    /// Relative inset padding on each side (`0.0..=0.25`).
    pub padding_ratio: f32,
}

impl Default for IconRasterOptions {
    fn default() -> Self {
        Self {
            size_px: 96,
            theme: IconTheme::Dark,
            padding_ratio: 0.08,
        }
    }
}

/// Rasterizes raw SVG bytes into an anti-aliased RGBA PNG buffer of `size_px x size_px`.
pub fn rasterize_svg_icon(
    svg_bytes: &[u8],
    options: IconRasterOptions,
) -> Result<Vec<u8>, GfxError> {
    if svg_bytes.is_empty() {
        return Err(GfxError::EmptyInput);
    }
    if svg_bytes.len() > MAX_SVG_ICON_BYTES {
        return Err(GfxError::InputTooLarge {
            max_bytes: MAX_SVG_ICON_BYTES,
        });
    }

    let size_px = options.size_px.clamp(MIN_ICON_SIZE_PX, MAX_ICON_SIZE_PX);
    let padding_ratio = if options.padding_ratio.is_finite() {
        options.padding_ratio.clamp(0.0, 0.25)
    } else {
        0.08
    };

    let parse_opts = usvg::Options {
        resources_dir: None,
        max_depth: MAX_SVG_DEPTH,
        style_sheet: Some(format!(
            "svg {{ color: {}; }}",
            options.theme.current_color_hex()
        )),
        // Strictly block external file reads and embedded data URLs inside untrusted plugin SVGs.
        image_href_resolver: usvg::ImageHrefResolver {
            resolve_data: Box::new(|_, _, _| None),
            resolve_string: Box::new(|_, _| None),
        },
        ..usvg::Options::default()
    };

    let tree = usvg::Tree::from_data(svg_bytes, &parse_opts)
        .map_err(|e| GfxError::SvgParse(e.to_string()))?;

    let src = tree.size();
    let src_w = f64::from(src.width());
    let src_h = f64::from(src.height());
    if !(src_w.is_finite() && src_h.is_finite() && src_w > 0.0 && src_h > 0.0) {
        return Err(GfxError::InvalidViewport);
    }

    let canvas = f64::from(size_px);
    let pad = canvas * f64::from(padding_ratio);
    let inner = (canvas - pad * 2.0).max(1.0);
    let scale = (inner / src_w).min(inner / src_h);
    let offset_x = (canvas - src_w * scale) * 0.5;
    let offset_y = (canvas - src_h * scale) * 0.5;
    let fit_transform = Affine::translate((offset_x, offset_y)) * Affine::scale(scale);

    let settings = RenderSettings {
        level: Level::new(),
        num_threads: 0,
    };
    let mut ctx = RenderContext::new_with(size_px, size_px, settings);
    render_group(&mut ctx, tree.root(), fit_transform);
    ctx.flush();

    let mut resources = Resources::new();
    let mut pixmap = Pixmap::new(size_px, size_px);
    let rasterizer = RasterizerSettings {
        render_mode: RenderMode::OptimizeSpeed,
        ..Default::default()
    };
    ctx.render_with(&mut pixmap, &mut resources, rasterizer);

    pixmap
        .into_png()
        .map_err(|err| GfxError::PngEncode(err.to_string()))
}

fn render_group(ctx: &mut RenderContext, group: &usvg::Group, fit_transform: Affine) {
    let opacity = group.opacity().get();
    let clip_path = group.clip_path().and_then(collect_clip_path);
    let needs_layer = opacity < 0.999 || clip_path.is_some();

    if needs_layer {
        let group_abs = fit_transform * to_affine(group.abs_transform());
        ctx.set_transform(group_abs);
        ctx.set_fill_rule(Fill::NonZero);
        ctx.push_layer(
            clip_path.as_ref(),
            None,
            (opacity < 0.999).then_some(opacity),
            None,
            None,
        );
    }

    for node in group.children() {
        match node {
            usvg::Node::Group(child) => render_group(ctx, child, fit_transform),
            usvg::Node::Path(path) => render_path(ctx, path, fit_transform),
            usvg::Node::Image(_) | usvg::Node::Text(_) => {}
        }
    }

    if needs_layer {
        ctx.pop_layer();
    }
}

fn render_path(ctx: &mut RenderContext, path: &usvg::Path, fit_transform: Affine) {
    if !path.is_visible() {
        return;
    }
    let bez = to_bez_path(path.data());
    if bez.elements().is_empty() {
        return;
    }

    let abs_transform = fit_transform * to_affine(path.abs_transform());
    ctx.set_transform(abs_transform);

    match path.paint_order() {
        usvg::PaintOrder::FillAndStroke => {
            if let Some(fill) = path.fill() {
                apply_fill(ctx, &bez, fill);
            }
            if let Some(stroke) = path.stroke() {
                apply_stroke(ctx, &bez, stroke);
            }
        }
        usvg::PaintOrder::StrokeAndFill => {
            if let Some(stroke) = path.stroke() {
                apply_stroke(ctx, &bez, stroke);
            }
            if let Some(fill) = path.fill() {
                apply_fill(ctx, &bez, fill);
            }
        }
    }
}

#[cfg(test)]
mod tests;
