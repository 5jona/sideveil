//! Conversion helpers mapping `usvg` paths, paints, strokes, and transforms into `vello_cpu`.

use usvg::tiny_skia_path::PathSegment;
use vello_cpu::RenderContext;
use vello_cpu::color::{AlphaColor, Srgb};
use vello_cpu::kurbo::{Affine, BezPath, Cap, Dashes, Join, Point, Stroke};
use vello_cpu::peniko::{
    ColorStop, ColorStops, Extend, Fill, Gradient, LinearGradientPosition, RadialGradientPosition,
};

pub(super) fn apply_fill(ctx: &mut RenderContext, bez: &BezPath, fill: &usvg::Fill) {
    if !configure_paint(ctx, fill.paint(), fill.opacity().get()) {
        return;
    }
    let rule = match fill.rule() {
        usvg::FillRule::NonZero => Fill::NonZero,
        usvg::FillRule::EvenOdd => Fill::EvenOdd,
    };
    ctx.set_fill_rule(rule);
    ctx.fill_path(bez);
    ctx.reset_paint_transform();
}

pub(super) fn apply_stroke(ctx: &mut RenderContext, bez: &BezPath, stroke: &usvg::Stroke) {
    if !configure_paint(ctx, stroke.paint(), stroke.opacity().get()) {
        return;
    }
    let mut k_stroke = Stroke::new(f64::from(stroke.width().get()))
        .with_miter_limit(f64::from(stroke.miterlimit().get()))
        .with_caps(match stroke.linecap() {
            usvg::LineCap::Butt => Cap::Butt,
            usvg::LineCap::Round => Cap::Round,
            usvg::LineCap::Square => Cap::Square,
        })
        .with_join(match stroke.linejoin() {
            usvg::LineJoin::Miter | usvg::LineJoin::MiterClip => Join::Miter,
            usvg::LineJoin::Round => Join::Round,
            usvg::LineJoin::Bevel => Join::Bevel,
        });
    if let Some(dash_list) = stroke.dasharray()
        && !dash_list.is_empty()
    {
        let dashes: Dashes = dash_list.iter().map(|&d| f64::from(d)).collect();
        k_stroke = k_stroke.with_dashes(f64::from(stroke.dashoffset()), dashes);
    }
    ctx.set_stroke(k_stroke);
    ctx.stroke_path(bez);
    ctx.reset_paint_transform();
}

pub(super) fn collect_clip_path(clip: &usvg::ClipPath) -> Option<BezPath> {
    let clip_xform = to_affine(clip.transform());
    let mut combined = BezPath::new();
    collect_clip_group(clip.root(), clip_xform, &mut combined);
    (!combined.elements().is_empty()).then_some(combined)
}

pub(super) fn to_affine(t: usvg::Transform) -> Affine {
    Affine::new([
        f64::from(t.sx),
        f64::from(t.ky),
        f64::from(t.kx),
        f64::from(t.sy),
        f64::from(t.tx),
        f64::from(t.ty),
    ])
}

pub(super) fn to_bez_path(path: &usvg::tiny_skia_path::Path) -> BezPath {
    let mut out = BezPath::new();
    for seg in path.segments() {
        match seg {
            PathSegment::MoveTo(p) => out.move_to((f64::from(p.x), f64::from(p.y))),
            PathSegment::LineTo(p) => out.line_to((f64::from(p.x), f64::from(p.y))),
            PathSegment::QuadTo(p1, p2) => out.quad_to(
                (f64::from(p1.x), f64::from(p1.y)),
                (f64::from(p2.x), f64::from(p2.y)),
            ),
            PathSegment::CubicTo(p1, p2, p3) => out.curve_to(
                (f64::from(p1.x), f64::from(p1.y)),
                (f64::from(p2.x), f64::from(p2.y)),
                (f64::from(p3.x), f64::from(p3.y)),
            ),
            PathSegment::Close => out.close_path(),
        }
    }
    out
}

fn configure_paint(ctx: &mut RenderContext, paint: &usvg::Paint, opacity: f32) -> bool {
    let alpha = opacity.clamp(0.0, 1.0);
    if alpha <= 0.0 {
        return false;
    }
    match paint {
        usvg::Paint::Color(c) => {
            ctx.reset_paint_transform();
            ctx.set_paint(to_alpha_color(*c, alpha));
            true
        }
        usvg::Paint::LinearGradient(lg) => {
            let stops = convert_stops(lg.stops(), alpha);
            if stops.is_empty() {
                return false;
            }
            let gradient = Gradient {
                kind: LinearGradientPosition {
                    start: Point::new(f64::from(lg.x1()), f64::from(lg.y1())),
                    end: Point::new(f64::from(lg.x2()), f64::from(lg.y2())),
                }
                .into(),
                stops,
                extend: convert_spread(lg.spread_method()),
                ..Default::default()
            };
            ctx.set_paint_transform(to_affine(lg.transform()));
            ctx.set_paint(gradient);
            true
        }
        usvg::Paint::RadialGradient(rg) => {
            let stops = convert_stops(rg.stops(), alpha);
            if stops.is_empty() {
                return false;
            }
            let gradient = Gradient {
                kind: RadialGradientPosition {
                    start_center: Point::new(f64::from(rg.fx()), f64::from(rg.fy())),
                    start_radius: rg.fr().get(),
                    end_center: Point::new(f64::from(rg.cx()), f64::from(rg.cy())),
                    end_radius: rg.r().get(),
                }
                .into(),
                stops,
                extend: convert_spread(rg.spread_method()),
                ..Default::default()
            };
            ctx.set_paint_transform(to_affine(rg.transform()));
            ctx.set_paint(gradient);
            true
        }
        usvg::Paint::Pattern(_) => false,
    }
}

fn collect_clip_group(group: &usvg::Group, clip_xform: Affine, out: &mut BezPath) {
    for node in group.children() {
        match node {
            usvg::Node::Path(p) => {
                let mut bez = to_bez_path(p.data());
                bez.apply_affine(clip_xform * to_affine(p.abs_transform()));
                for el in bez.elements() {
                    out.push(*el);
                }
            }
            usvg::Node::Group(g) => collect_clip_group(g, clip_xform, out),
            usvg::Node::Image(_) | usvg::Node::Text(_) => {}
        }
    }
}

fn convert_stops(stops: &[usvg::Stop], parent_alpha: f32) -> ColorStops {
    let converted: Vec<ColorStop> = stops
        .iter()
        .map(|s| {
            let stop_alpha = (s.opacity().get() * parent_alpha).clamp(0.0, 1.0);
            ColorStop::from((s.offset().get(), to_alpha_color(s.color(), stop_alpha)))
        })
        .collect();
    ColorStops::from(converted.as_slice())
}

fn convert_spread(spread: usvg::SpreadMethod) -> Extend {
    match spread {
        usvg::SpreadMethod::Pad => Extend::Pad,
        usvg::SpreadMethod::Reflect => Extend::Reflect,
        usvg::SpreadMethod::Repeat => Extend::Repeat,
    }
}

fn to_alpha_color(c: usvg::Color, alpha: f32) -> AlphaColor<Srgb> {
    AlphaColor::<Srgb>::from_rgba8(
        c.red,
        c.green,
        c.blue,
        (alpha.clamp(0.0, 1.0) * 255.0).round() as u8,
    )
}
