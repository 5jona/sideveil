//! Unit tests for SVG icon rasterization (`svg_icon`).

use std::io::Cursor;

use vello_cpu::Pixmap;

use super::*;

const PNG_MAGIC: &[u8; 8] = b"\x89PNG\r\n\x1a\n";

#[test]
fn rasterizes_current_color_svg_distinctly_for_dark_and_light() {
    let svg = br#"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><circle cx="12" cy="12" r="8"/><path d="M12 8v8M8 12h8"/></svg>"#;

    let dark_png = rasterize_svg_icon(
        svg,
        IconRasterOptions {
            size_px: 64,
            theme: IconTheme::Dark,
            padding_ratio: 0.1,
        },
    )
    .expect("rasterize dark icon");
    let light_png = rasterize_svg_icon(
        svg,
        IconRasterOptions {
            size_px: 64,
            theme: IconTheme::Light,
            padding_ratio: 0.1,
        },
    )
    .expect("rasterize light icon");

    assert!(dark_png.starts_with(PNG_MAGIC));
    assert!(light_png.starts_with(PNG_MAGIC));
    assert_ne!(dark_png, light_png);

    let dark_px = Pixmap::from_png(Cursor::new(&dark_png)).expect("decode dark png");
    let light_px = Pixmap::from_png(Cursor::new(&light_png)).expect("decode light png");
    assert_eq!((dark_px.width(), dark_px.height()), (64, 64));

    let dark_luma: u64 = dark_px
        .data()
        .iter()
        .filter(|p| p.a > 128)
        .map(|p| u64::from(p.r) + u64::from(p.g) + u64::from(p.b))
        .sum();
    let light_luma: u64 = light_px
        .data()
        .iter()
        .filter(|p| p.a > 128)
        .map(|p| u64::from(p.r) + u64::from(p.g) + u64::from(p.b))
        .sum();
    assert!(
        dark_luma > light_luma * 3,
        "dark-surface icon should be brighter than light-surface icon"
    );
}

#[test]
fn rasterizes_gradient_and_clipped_svg_cleanly() {
    let svg = br##"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48">
        <defs>
            <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stop-color="#38bdf8"/>
                <stop offset="100%" stop-color="#6366f1"/>
            </linearGradient>
            <clipPath id="c"><circle cx="24" cy="24" r="20"/></clipPath>
        </defs>
        <g clip-path="url(#c)" opacity="0.9">
            <rect x="0" y="0" width="48" height="48" fill="url(#g)"/>
        </g>
    </svg>"##;

    let png =
        rasterize_svg_icon(svg, IconRasterOptions::default()).expect("rasterize gradient svg");
    assert!(png.starts_with(PNG_MAGIC));
    let pixmap = Pixmap::from_png(Cursor::new(&png)).expect("decode png");
    assert_eq!((pixmap.width(), pixmap.height()), (96, 96));
    let opaque_pixels = pixmap.data().iter().filter(|p| p.a > 100).count();
    assert!(
        opaque_pixels > 2000,
        "expected substantial visible pixels, got {opaque_pixels}"
    );
    // Corner pixel outside circular clip must remain fully transparent.
    assert_eq!(pixmap.data()[0].a, 0);
}

#[test]
fn rejects_empty_oversized_and_malformed_svg() {
    assert!(matches!(
        rasterize_svg_icon(b"", IconRasterOptions::default()),
        Err(GfxError::EmptyInput)
    ));
    let huge = vec![b' '; MAX_SVG_ICON_BYTES + 1];
    assert!(matches!(
        rasterize_svg_icon(&huge, IconRasterOptions::default()),
        Err(GfxError::InputTooLarge { .. })
    ));
    assert!(matches!(
        rasterize_svg_icon(b"<not-svg", IconRasterOptions::default()),
        Err(GfxError::SvgParse(_))
    ));
}
