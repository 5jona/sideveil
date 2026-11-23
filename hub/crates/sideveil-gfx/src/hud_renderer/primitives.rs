//! Vector primitive dispatcher and bar/pill drawing routines executed on `vello_cpu::RenderContext`.

use sideveil_protocol::hud::HudPrimitive;
use vello_cpu::RenderContext;
use vello_cpu::color::{AlphaColor, Srgb};
use vello_cpu::kurbo::{Point, RoundedRect, Shape, Stroke};
use vello_cpu::peniko::{ColorStop, ColorStops, Extend, Gradient, LinearGradientPosition};

use super::gauges::{draw_crosshair, draw_custom_path, draw_radial_gauge};
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
