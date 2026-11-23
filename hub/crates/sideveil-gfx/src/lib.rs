//! Shared pure-Rust vector graphics and perceptual color core for SideVeil.
//!
//! Built on the Linebender ecosystem (`vello_cpu`, `kurbo`, `peniko`, `color`) + optional `usvg`
//! with zero GPU runtime dependencies and automatic AArch64 NEON SIMD acceleration.

pub mod hud_renderer;
pub mod palette;
#[cfg(feature = "svg-icon")]
pub mod svg_icon;

use thiserror::Error;
pub use vello_cpu::{color, kurbo, peniko};

/// Structured errors produced by SideVeil vector rasterization and graphics helpers.
#[derive(Debug, Error)]
pub enum GfxError {
    /// Provided SVG byte slice was empty.
    #[error("SVG input is empty")]
    EmptyInput,
    /// Provided SVG byte slice exceeded the configured safety ceiling.
    #[error("SVG input exceeds maximum allowed size ({max_bytes} bytes)")]
    InputTooLarge {
        /// Maximum permitted input byte length.
        max_bytes: usize,
    },
    /// `usvg` failed to parse the SVG XML document.
    #[error("failed to parse SVG document: {0}")]
    SvgParse(String),
    /// Parsed SVG reported zero, negative, or non-finite viewport dimensions.
    #[error("SVG document has degenerate or non-positive viewport dimensions")]
    InvalidViewport,
    /// PNG encoder failed while serializing the rasterized pixmap.
    #[error("failed to encode PNG output: {0}")]
    PngEncode(String),
    /// Target HUD canvas dimensions or caller RGBA buffer length were invalid.
    #[error("invalid HUD canvas dimensions ({width}x{height}) or output buffer size")]
    InvalidCanvas {
        /// Requested canvas width in pixels.
        width: u16,
        /// Requested canvas height in pixels.
        height: u16,
    },
}
