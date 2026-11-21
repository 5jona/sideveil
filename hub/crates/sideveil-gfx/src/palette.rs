//! Perceptual `Oklch` Material You glass & WebView CSS token synthesis via Linebender `color`.
//!
//! Acts as the single source of truth deriving both native Android AGSL frosted-glass tints
//! (`GlassTintOverride`) and WebView CSS custom properties (`--accent`, `--accent-soft`,
//! `--accent-fg`, `--border-hairline`, `--glass-divider`) with guaranteed WCAG contrast.

use std::collections::BTreeMap;

use sideveil_protocol::config::THEME_COLOROS_GLASS;
use sideveil_protocol::theme::{DynamicThemePalette, GlassTintOverride};
use vello_cpu::color::{LinearSrgb, Oklch, OpaqueColor, Srgb};

/// Synthesizes a harmonized native-glass + WebView CSS palette from an Android Material You
/// seed color (`0xAARRGGBB`) and wallpaper luminance hint (`0.0..=1.0`).
#[must_use]
pub fn synthesize_glass_palette(
    seed_argb: u32,
    luma_hint: f32,
    is_dark: bool,
    theme_id: &str,
) -> DynamicThemePalette {
    let luma = if luma_hint.is_finite() {
        luma_hint.clamp(0.0, 1.0)
    } else {
        0.5
    };
    let is_coloros = theme_id == THEME_COLOROS_GLASS;

    let seed_srgb = srgb_from_argb(seed_argb);
    let seed_oklch: OpaqueColor<Oklch> = seed_srgb.convert();
    let [_, raw_c, raw_h] = seed_oklch.components;
    // Neutral/achromatic seeds default to cool slate-blue hue with restrained chroma.
    let (hue, chroma_factor) = if raw_c < 0.025 || !raw_h.is_finite() {
        (250.0_f32, 0.35_f32)
    } else {
        (raw_h.rem_euclid(360.0), (raw_c / 0.16).clamp(0.45, 1.0))
    };

    // Derive WCAG-safe accent against active surface reference.
    let surface_ref = if is_dark {
        oklch_to_srgb(0.19, 0.015 * chroma_factor, hue)
    } else {
        oklch_to_srgb(0.95, 0.012 * chroma_factor, hue)
    };
    let accent_srgb = derive_accessible_accent(hue, chroma_factor, is_dark, surface_ref);
    let accent_fg_srgb = pick_contrasting_fg(accent_srgb);

    // Subtle frosted-glass veil tints modulated by backdrop luma hint.
    let glass_chroma = (if is_coloros { 0.024 } else { 0.014 }) * chroma_factor;
    let (top_l, bot_l, top_a, bot_a) = if is_dark {
        let luma_boost = luma * 0.08;
        if is_coloros {
            (
                0.27,
                0.19,
                (0.21 + luma_boost).clamp(0.18, 0.34),
                (0.28 + luma_boost).clamp(0.24, 0.40),
            )
        } else {
            (
                0.17,
                0.14,
                (0.54 + luma_boost).clamp(0.50, 0.68),
                (0.62 + luma_boost).clamp(0.56, 0.74),
            )
        }
    } else {
        let dark_bg_boost = (1.0 - luma) * 0.10;
        if is_coloros {
            (
                0.985,
                0.96,
                (0.28 + dark_bg_boost).clamp(0.24, 0.42),
                (0.23 + dark_bg_boost).clamp(0.20, 0.36),
            )
        } else {
            (
                0.99,
                0.97,
                (0.64 + dark_bg_boost).clamp(0.58, 0.76),
                (0.58 + dark_bg_boost).clamp(0.52, 0.70),
            )
        }
    };

    let tint_top = oklch_to_srgb(top_l, glass_chroma, hue);
    let tint_bottom = oklch_to_srgb(bot_l, glass_chroma * 1.15, hue);
    let rim_srgb = if is_dark {
        oklch_to_srgb(0.96, 0.02 * chroma_factor, hue)
    } else {
        oklch_to_srgb(
            if is_coloros { 0.99 } else { 0.30 },
            0.02 * chroma_factor,
            hue,
        )
    };
    let fallback_fill = oklch_to_srgb(if is_dark { 0.20 } else { 0.96 }, glass_chroma, hue);

    let glass = GlassTintOverride {
        tint_top_argb: argb_from_srgb(tint_top, top_a),
        tint_bottom_argb: argb_from_srgb(tint_bottom, bot_a),
        rim_argb: argb_from_srgb(rim_srgb, 1.0),
        accent_argb: argb_from_srgb(accent_srgb, 1.0),
        fallback_fill_argb: argb_from_srgb(fallback_fill, (top_a + bot_a) * 0.5),
        fallback_stroke_argb: argb_from_srgb(rim_srgb, if is_dark { 0.30 } else { 0.55 }),
    };

    let mut css_tokens = BTreeMap::new();
    css_tokens.insert("--accent".to_owned(), format_hex(accent_srgb));
    css_tokens.insert(
        "--accent-soft".to_owned(),
        format_rgba(accent_srgb, if is_dark { 0.22 } else { 0.14 }),
    );
    css_tokens.insert("--accent-fg".to_owned(), format_hex(accent_fg_srgb));
    let border_srgb = if is_dark {
        oklch_to_srgb(0.88, 0.02 * chroma_factor, hue)
    } else {
        oklch_to_srgb(0.28, 0.02 * chroma_factor, hue)
    };
    css_tokens.insert(
        "--border-hairline".to_owned(),
        format_rgba(border_srgb, if is_dark { 0.16 } else { 0.12 }),
    );
    css_tokens.insert(
        "--glass-divider".to_owned(),
        format_rgba(border_srgb, if is_dark { 0.13 } else { 0.10 }),
    );

    DynamicThemePalette {
        theme_id: theme_id.to_owned(),
        is_dark,
        seed_argb,
        luma_hint: luma,
        glass,
        css_tokens,
    }
}

/// Computes WCAG 2.1 contrast ratio (`1.0..=21.0`) between two opaque sRGB colors.
#[must_use]
pub fn wcag_contrast_ratio(a: OpaqueColor<Srgb>, b: OpaqueColor<Srgb>) -> f32 {
    let la = relative_luminance(a);
    let lb = relative_luminance(b);
    let (hi, lo) = if la >= lb { (la, lb) } else { (lb, la) };
    (hi + 0.05) / (lo + 0.05)
}

fn derive_accessible_accent(
    hue: f32,
    chroma_factor: f32,
    is_dark: bool,
    surface_ref: OpaqueColor<Srgb>,
) -> OpaqueColor<Srgb> {
    let target_chroma = (if is_dark { 0.155 } else { 0.165 }) * chroma_factor;
    let mut lightness = if is_dark { 0.72 } else { 0.50 };
    let mut candidate = oklch_to_srgb(lightness, target_chroma, hue);

    // Adjust Oklch lightness monotonically until >= 4.5:1 contrast against the active surface.
    for _ in 0..16 {
        if wcag_contrast_ratio(candidate, surface_ref) >= 4.5 {
            break;
        }
        lightness = if is_dark {
            (lightness + 0.025).min(0.92)
        } else {
            (lightness - 0.025).max(0.24)
        };
        candidate = oklch_to_srgb(lightness, target_chroma * 0.92, hue);
    }
    candidate
}

fn pick_contrasting_fg(bg: OpaqueColor<Srgb>) -> OpaqueColor<Srgb> {
    let white = OpaqueColor::<Srgb>::from_rgb8(255, 255, 255);
    let dark = OpaqueColor::<Srgb>::from_rgb8(15, 23, 42);
    if wcag_contrast_ratio(white, bg) >= wcag_contrast_ratio(dark, bg) {
        white
    } else {
        dark
    }
}

fn relative_luminance(c: OpaqueColor<Srgb>) -> f32 {
    let lin: OpaqueColor<LinearSrgb> = c.convert();
    let [r, g, b] = lin.components;
    (0.2126 * r.clamp(0.0, 1.0) + 0.7152 * g.clamp(0.0, 1.0) + 0.0722 * b.clamp(0.0, 1.0))
        .clamp(0.0, 1.0)
}

fn oklch_to_srgb(l: f32, c: f32, h: f32) -> OpaqueColor<Srgb> {
    // Binary-shrink chroma if out of sRGB gamut so hue & lightness stay perceptually accurate.
    let mut chroma = c.max(0.0);
    for _ in 0..8 {
        let converted: OpaqueColor<Srgb> =
            OpaqueColor::<Oklch>::new([l.clamp(0.0, 1.0), chroma, h]).convert();
        let [r, g, b] = converted.components;
        if (-0.005..=1.005).contains(&r)
            && (-0.005..=1.005).contains(&g)
            && (-0.005..=1.005).contains(&b)
        {
            return OpaqueColor::<Srgb>::new([
                r.clamp(0.0, 1.0),
                g.clamp(0.0, 1.0),
                b.clamp(0.0, 1.0),
            ]);
        }
        chroma *= 0.78;
    }
    let fallback: OpaqueColor<Srgb> =
        OpaqueColor::<Oklch>::new([l.clamp(0.0, 1.0), 0.0, h]).convert();
    let [r, g, b] = fallback.components;
    OpaqueColor::<Srgb>::new([r.clamp(0.0, 1.0), g.clamp(0.0, 1.0), b.clamp(0.0, 1.0)])
}

fn srgb_from_argb(argb: u32) -> OpaqueColor<Srgb> {
    let r = ((argb >> 16) & 0xFF) as u8;
    let g = ((argb >> 8) & 0xFF) as u8;
    let b = (argb & 0xFF) as u8;
    OpaqueColor::<Srgb>::from_rgb8(r, g, b)
}

fn argb_from_srgb(c: OpaqueColor<Srgb>, alpha: f32) -> u32 {
    let a = (alpha.clamp(0.0, 1.0) * 255.0).round() as u32;
    let r = (c.components[0].clamp(0.0, 1.0) * 255.0).round() as u32;
    let g = (c.components[1].clamp(0.0, 1.0) * 255.0).round() as u32;
    let b = (c.components[2].clamp(0.0, 1.0) * 255.0).round() as u32;
    (a << 24) | (r << 16) | (g << 8) | b
}

fn format_hex(c: OpaqueColor<Srgb>) -> String {
    let r = (c.components[0].clamp(0.0, 1.0) * 255.0).round() as u8;
    let g = (c.components[1].clamp(0.0, 1.0) * 255.0).round() as u8;
    let b = (c.components[2].clamp(0.0, 1.0) * 255.0).round() as u8;
    format!("#{r:02x}{g:02x}{b:02x}")
}

fn format_rgba(c: OpaqueColor<Srgb>, alpha: f32) -> String {
    let r = (c.components[0].clamp(0.0, 1.0) * 255.0).round() as u8;
    let g = (c.components[1].clamp(0.0, 1.0) * 255.0).round() as u8;
    let b = (c.components[2].clamp(0.0, 1.0) * 255.0).round() as u8;
    format!("rgba({r}, {g}, {b}, {:.2})", alpha.clamp(0.0, 1.0))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maintains_wcag_contrast_across_extreme_seed_colors() {
        let extreme_seeds = [
            0xFF000000_u32,
            0xFFFFFFFF,
            0xFFFFFF00,
            0xFF2563EB,
            0xFFEF4444,
        ];
        for seed in extreme_seeds {
            for is_dark in [true, false] {
                let pal = synthesize_glass_palette(seed, 0.5, is_dark, THEME_COLOROS_GLASS);
                let accent = srgb_from_argb(pal.glass.accent_argb);
                let fg_hex = pal.css_tokens.get("--accent-fg").expect("--accent-fg");
                let fg = if fg_hex == "#ffffff" {
                    OpaqueColor::<Srgb>::from_rgb8(255, 255, 255)
                } else {
                    OpaqueColor::<Srgb>::from_rgb8(15, 23, 42)
                };
                let ratio = wcag_contrast_ratio(accent, fg);
                assert!(
                    ratio >= 4.5,
                    "seed={seed:#010x} dark={is_dark} accent={accent:?} fg={fg_hex} contrast={ratio:.2}"
                );
                assert!(pal.css_tokens.contains_key("--accent"));
                assert!(pal.css_tokens.contains_key("--accent-soft"));
                assert!(pal.css_tokens.contains_key("--border-hairline"));
            }
        }
    }

    #[test]
    fn adapts_veil_alpha_to_backdrop_luma_hint() {
        let on_dark_bg = synthesize_glass_palette(0xFF38BDF8, 0.1, true, THEME_COLOROS_GLASS);
        let on_bright_bg = synthesize_glass_palette(0xFF38BDF8, 0.95, true, THEME_COLOROS_GLASS);
        let alpha_dark = (on_dark_bg.glass.tint_top_argb >> 24) & 0xFF;
        let alpha_bright = (on_bright_bg.glass.tint_top_argb >> 24) & 0xFF;
        assert!(alpha_bright > alpha_dark);
    }
}
