//! Radial gauge, crosshair, and custom compact-path primitive drawing routines.

use std::f64::consts::PI;

use vello_cpu::RenderContext;
use vello_cpu::kurbo::{Arc, BezPath, Cap, Join, Line, Point, Shape, Stroke, Vec2};

use super::path_parser::parse_compact_path;
use super::primitives::{argb_to_color, linear_gradient};

pub(super) fn draw_radial_gauge(
    ctx: &mut RenderContext,
    geom: (f32, f32, f32, f32),
    angles: (f32, f32, f32),
    colors: (u32, u32, Option<u32>),
) {
    let (cx, cy, radius, thickness) = geom;
    let (value, start_angle_deg, sweep_angle_deg) = angles;
    let (track_argb, fill_start_argb, fill_end_argb) = colors;
    if radius <= 0.5 || thickness <= 0.1 {
        return;
    }
    ctx.set_stroke(
        Stroke::new(f64::from(thickness))
            .with_caps(Cap::Round)
            .with_join(Join::Round),
    );
    let center = Point::new(f64::from(cx), f64::from(cy));
    let radii = Vec2::new(f64::from(radius), f64::from(radius));
    let start_rad = f64::from(start_angle_deg) * PI / 180.0;
    let full_sweep = f64::from(sweep_angle_deg.clamp(-360.0, 360.0)) * PI / 180.0;

    let track_arc = Arc::new(center, radii, start_rad, full_sweep, 0.0);
    ctx.set_paint(argb_to_color(track_argb));
    ctx.stroke_path(&track_arc.to_path(0.1));

    let clamped = if value.is_finite() {
        value.clamp(0.0, 1.0)
    } else {
        0.0
    };
    if clamped <= 0.005 {
        return;
    }
    let val_arc = Arc::new(
        center,
        radii,
        start_rad,
        full_sweep * f64::from(clamped),
        0.0,
    );
    if let Some(end_col) = fill_end_argb {
        ctx.set_paint(linear_gradient(
            Point::new(f64::from(cx - radius), f64::from(cy)),
            Point::new(f64::from(cx + radius), f64::from(cy)),
            fill_start_argb,
            end_col,
        ));
    } else {
        ctx.set_paint(argb_to_color(fill_start_argb));
    }
    ctx.stroke_path(&val_arc.to_path(0.1));
}

pub(super) fn draw_crosshair(
    ctx: &mut RenderContext,
    geom: (f32, f32, f32, f32, f32),
    color_argb: u32,
    outline_argb: Option<u32>,
) {
    let (cx, cy, size, gap, thickness) = geom;
    let (cx, cy) = (f64::from(cx), f64::from(cy));
    let s = f64::from(size.max(1.0));
    let g = f64::from(gap.max(0.0));
    let mut bez = BezPath::new();
    for line in [
        Line::new((cx - s, cy), (cx - g, cy)),
        Line::new((cx + g, cy), (cx + s, cy)),
        Line::new((cx, cy - s), (cx, cy - g)),
        Line::new((cx, cy + g), (cx, cy + s)),
    ] {
        bez.move_to(line.p0);
        bez.line_to(line.p1);
    }
    if let Some(out) = outline_argb {
        ctx.set_paint(argb_to_color(out));
        ctx.set_stroke(Stroke::new(f64::from(thickness) + 2.0).with_caps(Cap::Round));
        ctx.stroke_path(&bez);
    }
    ctx.set_paint(argb_to_color(color_argb));
    ctx.set_stroke(Stroke::new(f64::from(thickness)).with_caps(Cap::Round));
    ctx.stroke_path(&bez);
}

pub(super) fn draw_custom_path(
    ctx: &mut RenderContext,
    d: &str,
    fill_argb: Option<u32>,
    stroke_argb: Option<u32>,
    stroke_width: f32,
) {
    let bez = parse_compact_path(d);
    if bez.elements().is_empty() {
        return;
    }
    if let Some(fill) = fill_argb {
        ctx.set_paint(argb_to_color(fill));
        ctx.fill_path(&bez);
    }
    if let Some(stroke) = stroke_argb
        && stroke_width > 0.0
    {
        ctx.set_paint(argb_to_color(stroke));
        ctx.set_stroke(
            Stroke::new(f64::from(stroke_width))
                .with_caps(Cap::Round)
                .with_join(Join::Round),
        );
        ctx.stroke_path(&bez);
    }
}
