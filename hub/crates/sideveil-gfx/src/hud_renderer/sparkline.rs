//! Smooth Bezier sparkline renderer with optional vertical gradient area fill.

use vello_cpu::RenderContext;
use vello_cpu::kurbo::{BezPath, Cap, Join, Point, Stroke};

use super::primitives::{argb_to_color, linear_gradient};

pub(super) fn draw_sparkline(
    ctx: &mut RenderContext,
    rect: (f32, f32, f32, f32),
    values: &[f32],
    bounds: (Option<f32>, Option<f32>),
    stroke: (u32, f32),
    area_fill: (Option<u32>, Option<u32>),
    smooth: bool,
) {
    let (x, y, w, h) = rect;
    if w <= 1.0 || h <= 1.0 || values.len() < 2 {
        return;
    }
    let finite: Vec<f32> = values
        .iter()
        .copied()
        .filter(|v| v.is_finite())
        .take(256)
        .collect();
    if finite.len() < 2 {
        return;
    }
    let min_v = bounds
        .0
        .unwrap_or_else(|| finite.iter().copied().fold(f32::INFINITY, f32::min));
    let max_v = bounds
        .1
        .unwrap_or_else(|| finite.iter().copied().fold(f32::NEG_INFINITY, f32::max));
    let span = (max_v - min_v).abs().max(1e-4);

    let step_x = f64::from(w) / (finite.len() - 1) as f64;
    let pts: Vec<Point> = finite
        .iter()
        .enumerate()
        .map(|(idx, &v)| {
            let norm = f64::from(((v - min_v) / span).clamp(0.0, 1.0));
            Point::new(
                f64::from(x) + step_x * idx as f64,
                f64::from(y + h) - norm * f64::from(h),
            )
        })
        .collect();

    let mut line_path = BezPath::new();
    line_path.move_to(pts[0]);
    if smooth && pts.len() >= 3 {
        for i in 1..pts.len() - 1 {
            let mid = Point::new(
                (pts[i].x + pts[i + 1].x) * 0.5,
                (pts[i].y + pts[i + 1].y) * 0.5,
            );
            line_path.quad_to(pts[i], mid);
        }
        line_path.line_to(*pts.last().unwrap_or(&pts[0]));
    } else {
        for &pt in &pts[1..] {
            line_path.line_to(pt);
        }
    }

    if let Some(top_col) = area_fill.0 {
        let mut area = line_path.clone();
        if let Some(last) = pts.last() {
            area.line_to((last.x, f64::from(y + h)));
        }
        area.line_to((pts[0].x, f64::from(y + h)));
        area.close_path();
        let bottom_col = area_fill.1.unwrap_or(top_col & 0x00FF_FFFF);
        ctx.set_paint(linear_gradient(
            Point::new(f64::from(x), f64::from(y)),
            Point::new(f64::from(x), f64::from(y + h)),
            top_col,
            bottom_col,
        ));
        ctx.fill_path(&area);
    }

    if stroke.1 > 0.0 {
        ctx.set_paint(argb_to_color(stroke.0));
        ctx.set_stroke(
            Stroke::new(f64::from(stroke.1))
                .with_caps(Cap::Round)
                .with_join(Join::Round),
        );
        ctx.stroke_path(&line_path);
    }
}
