//! Shared pure-Rust vector graphics and perceptual color core for Sideveil.
//!
//! Built on the Linebender ecosystem (`vello_cpu`, `kurbo`, `peniko`, `color`) + `usvg` with
//! zero GPU runtime dependencies and automatic AArch64 NEON SIMD acceleration.

pub mod svg_icon;

use thiserror::Error;
pub use vello_cpu::{color, kurbo, peniko};

/// Structured errors produced by Sideveil vector rasterization and graphics helpers.
#[derive(Debug, Error)]
pub enum GfxError {
    #[error("SVG input is empty")]
    EmptyInput,
    #[error("SVG input exceeds maximum allowed size ({max_bytes} bytes)")]
    InputTooLarge { max_bytes: usize },
    #[error("failed to parse SVG document: {0}")]
    SvgParse(String),
    #[error("SVG document has degenerate or non-positive viewport dimensions")]
    InvalidViewport,
    #[error("failed to encode PNG output: {0}")]
    PngEncode(String),
}
