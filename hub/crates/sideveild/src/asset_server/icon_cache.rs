//! On-disk SVG-to-PNG icon rasterization cache for `/plugins/<id>/__icon`.
//!
//! Resolves `manifest.icon` strictly inside `<plugin_root>/web/`, passes raster formats
//! (PNG/WebP/JPEG) through verbatim, and rasterizes SVG icons via `sideveil-gfx` (`usvg` +
//! `vello_cpu` NEON) with `(plugin_id, size, theme, mtime)` disk caching.

use std::path::Path;
use std::sync::Arc;
use std::time::UNIX_EPOCH;

use sideveil_gfx::svg_icon::{
    IconRasterOptions, IconTheme, MAX_SVG_ICON_BYTES, rasterize_svg_icon,
};
use tokio::fs;

use crate::asset_server::{ResolveAssetError, mime_type_for, resolve_asset_file_checked};
use crate::fs_util::write_file_atomic;
use crate::state::HubState;

const MAX_RASTER_ICON_BYTES: u64 = 2 * 1024 * 1024;

#[derive(Debug, Clone)]
pub(crate) struct ResolvedIconResponse {
    pub(crate) mime: &'static str,
    pub(crate) bytes: Vec<u8>,
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct IconQuery {
    pub(crate) size_px: u16,
    pub(crate) theme: IconTheme,
}

impl IconQuery {
    #[must_use]
    pub(crate) fn parse(query: Option<&str>) -> Self {
        let mut size_px: u16 = 96;
        let mut theme = IconTheme::Dark;

        if let Some(raw) = query {
            for pair in raw.split('&') {
                if let Some((key, value)) = pair.split_once('=') {
                    match key {
                        "size" => {
                            if let Ok(parsed) = value.parse::<u16>() {
                                size_px = parsed.clamp(16, 256);
                            }
                        }
                        "theme" => {
                            theme = match value {
                                "light" => IconTheme::Light,
                                _ => IconTheme::Dark,
                            };
                        }
                        _ => {}
                    }
                }
            }
        }

        Self { size_px, theme }
    }
}

pub(crate) async fn load_or_rasterize_icon(
    state: &Arc<HubState>,
    plugin_id: &str,
    plugin_root: &Path,
    query: IconQuery,
) -> Result<ResolvedIconResponse, (u16, &'static str)> {
    let Some(raw_icon) = state.plugin_registry().plugin_icon(plugin_id) else {
        return Err((404, "Icon Not Declared"));
    };

    let normalized_rel = normalize_icon_rel(&raw_icon);
    let file_path = resolve_asset_file_checked(plugin_root, &normalized_rel)
        .await
        .map_err(|err| match err {
            ResolveAssetError::NotFound => (404, "Icon Not Found"),
            ResolveAssetError::Forbidden(_) => (403, "Forbidden"),
        })?;

    let meta = fs::metadata(&file_path)
        .await
        .map_err(|_| (404, "Icon Not Found"))?;
    if !meta.is_file() {
        return Err((404, "Icon Not Found"));
    }

    let ext = file_path
        .extension()
        .and_then(|e| e.to_str())
        .map(str::to_ascii_lowercase)
        .unwrap_or_default();

    if ext != "svg" {
        if meta.len() > MAX_RASTER_ICON_BYTES {
            return Err((413, "Icon Too Large"));
        }
        let mime = match ext.as_str() {
            "png" | "webp" | "jpg" | "jpeg" => mime_type_for(&file_path),
            _ => return Err((415, "Unsupported Icon Format")),
        };
        let bytes = fs::read(&file_path)
            .await
            .map_err(|_| (500, "Failed To Read Icon"))?;
        return Ok(ResolvedIconResponse { mime, bytes });
    }

    if meta.len() as usize > MAX_SVG_ICON_BYTES {
        return Err((413, "SVG Icon Too Large"));
    }

    let (mtime_secs, mtime_nanos) = meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map_or((0, 0), |d| (d.as_secs(), d.subsec_nanos()));

    let cache_dir = state.cache_dir().join("icons");
    let prefix = format!("{plugin_id}_{}_{}_", query.size_px, query.theme.as_str());
    let cache_path = cache_dir.join(format!("{prefix}{mtime_secs}_{mtime_nanos}.png"));

    if let Ok(cached) = fs::read(&cache_path).await
        && !cached.is_empty()
    {
        return Ok(ResolvedIconResponse {
            mime: "image/png",
            bytes: cached,
        });
    }

    let svg_bytes = fs::read(&file_path)
        .await
        .map_err(|_| (500, "Failed To Read SVG"))?;
    let opts = IconRasterOptions {
        size_px: query.size_px,
        theme: query.theme,
        padding_ratio: 0.08,
    };

    let png_bytes = tokio::task::spawn_blocking(move || rasterize_svg_icon(&svg_bytes, opts))
        .await
        .map_err(|_| (500, "Icon Raster Task Failed"))?
        .map_err(|_| (422, "Invalid SVG Icon"))?;

    let _ = write_cache_atomic(&cache_dir, &prefix, &cache_path, &png_bytes).await;

    Ok(ResolvedIconResponse {
        mime: "image/png",
        bytes: png_bytes,
    })
}

fn normalize_icon_rel(raw_icon: &str) -> String {
    let trimmed = raw_icon.trim().trim_start_matches('/');
    if trimmed.starts_with("web/") {
        trimmed.to_owned()
    } else {
        format!("web/{trimmed}")
    }
}

async fn write_cache_atomic(
    cache_dir: &Path,
    stale_prefix: &str,
    cache_path: &Path,
    png_bytes: &[u8],
) -> std::io::Result<()> {
    fs::create_dir_all(cache_dir).await?;
    let file_name = cache_path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("icon.png");

    // Prune older mtime cache files for the same (plugin, size, theme) tuple.
    if let Ok(mut rd) = fs::read_dir(cache_dir).await {
        while let Ok(Some(entry)) = rd.next_entry().await {
            if let Some(name) = entry.file_name().to_str()
                && name.starts_with(stale_prefix)
                && name != file_name
            {
                let _ = fs::remove_file(entry.path()).await;
            }
        }
    }

    let target = cache_path.to_path_buf();
    let payload = png_bytes.to_vec();
    tokio::task::spawn_blocking(move || write_file_atomic(&target, &payload, 0o644, None, false))
        .await
        .map_err(std::io::Error::other)??;
    Ok(())
}
