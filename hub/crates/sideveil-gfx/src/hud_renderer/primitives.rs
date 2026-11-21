//! Vector primitive drawing routines (`RoundedPill`, `ProgressBar`, `Sparkline`,
//! `RadialGauge`, `Crosshair`, `Path`) executed on `vello_cpu::RenderContext`.

use std::f64::consts::PI;

use sideveil_protocol::hud::HudPrimitive;
use vello_cpu::RenderContext;
use vello_cpu::color::{AlphaColor, Srgb};
use vello_cpu::kurbo::{Arc, BezPath, Cap, Join, Line, Point, RoundedRect, Shape, Stroke, Vec2};
use vello_cpu::peniko::{ColorStop, ColorStops, Extend, Gradient, LinearGradientPosition};

use super::path_parser::parse_compact_path;
use super::sparkline::draw_sparkline;

pub(super) fn draw_primitive(ctx: &mut RenderContext, prim: &HudPrimitive) {
    match prim {
        HudPrimitive::RoundedPill {
            x,
            y,
            w,
            h,
            radius,
            fill_argb,
            stroke_argb,
            stroke_width,
        } => draw_rounded_pill(
            ctx,
            (*x, *y, *w, *h, *radius),
            *fill_argb,
            *stroke_argb,
            *stroke_width,
        ),
        HudPrimitive::ProgressBar {
            x,
            y,
            w,
            h,
            radius,
            value,
            track_argb,
            fill_start_argb,
            fill_end_argb,
        } => draw_progress_bar(
            ctx,
            (*x, *y, *w, *h, *radius),
            *value,
            *track_argb,
            *fill_start_argb,
            *fill_end_argb,
        ),
        HudPrimitive::Sparkline {
            x,
            y,
            w,
            h,
            values,
            min,
            max,
            stroke_argb,
            stroke_width,
            fill_top_argb,
            fill_bottom_argb,
            smooth,
        } => draw_sparkline(
            ctx,
            (*x, *y, *w, *h),
            values,
            (*min, *max),
            (*stroke_argb, *stroke_width),
            (*fill_top_argb, *fill_bottom_argb),
            *smooth,
        ),
        HudPrimitive::RadialGauge {
            cx,
            cy,
            radius,
            thickness,
            value,
            start_angle_deg,
            sweep_angle_deg,
            track_argb,
            fill_start_argb,
            fill_end_argb,
        } => draw_radial_gauge(
            ctx,
            (*cx, *cy, *radius, *thickness),
            (*value, *start_angle_deg, *sweep_angle_deg),
            (*track_argb, *fill_start_argb, *fill_end_argb),
        ),
        HudPrimitive::Crosshair {
            cx,
            cy,
            size,
            gap,
            thickness,
            color_argb,
            outline_argb,
        } => draw_crosshair(
            ctx,
            (*cx, *cy, *size, *gap, *thickness),
            *color_argb,
            *outline_argb,
        ),
        HudPrimitive::Path {
            d,
            fill_argb,
            stroke_argb,
            stroke_width,
        } => draw_custom_path(ctx, d, *fill_argb, *stroke_argb, *stroke_width),
    }
}

fn draw_rounded_pill(
    ctx: &mut RenderContext,
    geom: (f32, f32, f32, f32, f32),
    fill_argb: u32,
    stroke_argb: Option<u32>,
    stroke_width: f32,
) {
    let (x, y, w, h, radius) = geom;
    if w <= 0.0 || h <= 0.0 {
        return;
    }
    let r = f64::from(radius.max(0.0)).min(f64::from(w.min(h)) * 0.5);
    let bez = RoundedRect::new(
        f64::from(x),
        f64::from(y),
        f64::from(x + w),
        f64::from(y + h),
        r,
    )
    .to_path(0.1);
    ctx.set_paint(argb_to_color(fill_argb));
    ctx.fill_path(&bez);
    if let Some(stroke_col) = stroke_argb
        && stroke_width > 0.0
    {
        ctx.set_paint(argb_to_color(stroke_col));
        ctx.set_stroke(Stroke::new(f64::from(stroke_width)));
        ctx.stroke_path(&bez);
    }
}

fn draw_progress_bar(
    ctx: &mut RenderContext,
    geom: (f32, f32, f32, f32, f32),
    value: f32,
    track_argb: u32,
    fill_start_argb: u32,
    fill_end_argb: Option<u32>,
) {
    let (x, y, w, h, radius) = geom;
    if w <= 0.0 || h <= 0.0 {
        return;
    }
    let r = f64::from(radius.max(0.0)).min(f64::from(h) * 0.5);
    let track = RoundedRect::new(
        f64::from(x),
        f64::from(y),
        f64::from(x + w),
        f64::from(y + h),
        r,
    );
    ctx.set_paint(argb_to_color(track_argb));
    ctx.fill_path(&track.to_path(0.1));

    let clamped = if value.is_finite() {
        value.clamp(0.0, 1.0)
    } else {
        0.0
    };
    let fill_w = (w * clamped).max(0.0);
    if fill_w <= 0.5 {
        return;
    }
    let fill_rr = RoundedRect::new(
        f64::from(x),
        f64::from(y),
        f64::from(x + fill_w),
        f64::from(y + h),
        r.min(f64::from(fill_w) * 0.5),
    );
    if let Some(end_col) = fill_end_argb {
        ctx.set_paint(linear_gradient(
            Point::new(f64::from(x), f64::from(y)),
            Point::new(f64::from(x + w), f64::from(y)),
            fill_start_argb,
            end_col,
        ));
    } else {
        ctx.set_paint(argb_to_color(fill_start_argb));
    }
    ctx.fill_path(&fill_rr.to_path(0.1));
}

fn draw_radial_gauge(
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

fn draw_crosshair(
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

fn draw_custom_path(
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

pub(super) fn linear_gradient(
    start: Point,
    end: Point,
    start_argb: u32,
    end_argb: u32,
) -> Gradient {
    let stops = [
        ColorStop::from((0.0_f32, argb_to_color(start_argb))),
        ColorStop::from((1.0_f32, argb_to_color(end_argb))),
    ];
    Gradient {
        kind: LinearGradientPosition { start, end }.into(),
        stops: ColorStops::from(stops.as_slice()),
        extend: Extend::Pad,
        ..Default::default()
    }
}

pub(super) fn argb_to_color(argb: u32) -> AlphaColor<Srgb> {
    let a = ((argb >> 24) & 0xFF) as u8;
    let r = ((argb >> 16) & 0xFF) as u8;
    let g = ((argb >> 8) & 0xFF) as u8;
    let b = (argb & 0xFF) as u8;
    AlphaColor::<Srgb>::from_rgba8(r, g, b, a)
}
