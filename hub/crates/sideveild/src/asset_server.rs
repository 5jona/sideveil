//! Authenticated loopback HTTP static asset server for isolated plugin bundles (`GET /plugins/<id>/<path>`).

mod http;
mod icon_cache;
mod path;

use std::sync::Arc;
use std::time::Duration;

use sideveil_protocol::plugin::validate_plugin_id;
use tokio::fs;
use tokio::io::{AsyncWriteExt, BufReader};
use tokio::net::TcpStream;

pub(crate) use self::http::{cache_headers_for, mime_type_for};
use self::http::{parse_http_headers, write_error};
pub use self::path::{
    ResolveAssetError, is_spa_route_path, resolve_asset_file, resolve_asset_file_checked,
};
use crate::host_ws::constant_time_eq;
use crate::state::HubState;

const HEADER_TIMEOUT: Duration = Duration::from_secs(4);
const MAX_ASSET_BYTES: u64 = 16 * 1024 * 1024;

/// Serves a single authenticated HTTP/1.1 GET request for `/plugins/<plugin_id>/<relative_path>`.
pub async fn handle_http_asset(stream: TcpStream, state: Arc<HubState>) {
    let (read_half, mut write_half) = stream.into_split();
    let mut reader = BufReader::new(read_half);

    let Ok(Ok(req)) = tokio::time::timeout(HEADER_TIMEOUT, parse_http_headers(&mut reader)).await
    else {
        let _ = write_error(&mut write_half, 400, "Bad Request").await;
        return;
    };

    if req.method != "GET" && req.method != "HEAD" {
        let _ = write_error(&mut write_half, 405, "Method Not Allowed").await;
        return;
    }

    if let Some(origin) = req.origin.as_deref()
        && !origin.is_empty()
    {
        let _ = write_error(&mut write_half, 403, "Forbidden").await;
        return;
    }

    let expected_auth = format!("Bearer {}", state.ws_token());
    let provided = req.authorization.as_deref().unwrap_or_default();
    if !constant_time_eq(provided.as_bytes(), expected_auth.as_bytes()) {
        let _ = write_error(&mut write_half, 401, "Unauthorized").await;
        return;
    }

    let (path_only, query_str) = match req.target.split_once('?') {
        Some((p, q)) => (p, Some(q)),
        None => (req.target.as_str(), None),
    };
    let Some(rest) = path_only.strip_prefix("/plugins/") else {
        let _ = write_error(&mut write_half, 404, "Not Found").await;
        return;
    };
    let Some((plugin_id, raw_rel)) = rest.split_once('/') else {
        let _ = write_error(&mut write_half, 404, "Not Found").await;
        return;
    };
    if validate_plugin_id(plugin_id).is_err() {
        let _ = write_error(&mut write_half, 400, "Invalid Plugin ID").await;
        return;
    }

    let Some(plugin_root) = state.plugin_registry().plugin_root(plugin_id) else {
        let _ = write_error(&mut write_half, 404, "Plugin Not Found").await;
        return;
    };

    if raw_rel == "__icon" {
        let query = icon_cache::IconQuery::parse(query_str);
        match icon_cache::load_or_rasterize_icon(&state, plugin_id, &plugin_root, query).await {
            Ok(resolved) => {
                let header = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: {}\r\nContent-Length: {}\r\nCache-Control: private, max-age=60\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n",
                    resolved.mime,
                    resolved.bytes.len()
                );
                if write_half.write_all(header.as_bytes()).await.is_ok() {
                    if req.method == "GET" {
                        let _ = write_half.write_all(&resolved.bytes).await;
                    }
                    let _ = write_half.flush().await;
                }
            }
            Err((status, reason)) => {
                let _ = write_error(&mut write_half, status, reason).await;
            }
        }
        return;
    }

    let (file_path, effective_rel) = match resolve_asset_file_checked(&plugin_root, raw_rel).await {
        Ok(path) => (path, raw_rel.to_owned()),
        Err(ResolveAssetError::Forbidden(_)) => {
            let _ = write_error(&mut write_half, 403, "Forbidden").await;
            return;
        }
        Err(ResolveAssetError::NotFound) => {
            if is_spa_route_path(raw_rel)
                && let Some(spa_entry) = state.plugin_registry().plugin_spa_entry(plugin_id)
                && let Ok(fallback) = resolve_asset_file(&plugin_root, &spa_entry).await
            {
                (fallback, spa_entry)
            } else {
                let _ = write_error(&mut write_half, 404, "Not Found").await;
                return;
            }
        }
    };

    let Ok(meta) = fs::metadata(&file_path).await else {
        let _ = write_error(&mut write_half, 404, "Not Found").await;
        return;
    };
    if !meta.is_file() || meta.len() > MAX_ASSET_BYTES {
        let _ = write_error(&mut write_half, 404, "Not Found").await;
        return;
    }

    let Ok(bytes) = fs::read(&file_path).await else {
        let _ = write_error(&mut write_half, 500, "Internal Server Error").await;
        return;
    };

    let mime = mime_type_for(&file_path);
    let cache_headers = cache_headers_for(&effective_rel, &file_path, &meta);
    let coi_headers = if state
        .plugin_registry()
        .plugin_cross_origin_isolated(plugin_id)
    {
        "Cross-Origin-Opener-Policy: same-origin\r\nCross-Origin-Embedder-Policy: require-corp\r\nCross-Origin-Resource-Policy: same-origin\r\n"
    } else {
        ""
    };
    let header = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: {mime}\r\nContent-Length: {}\r\n{cache_headers}X-Content-Type-Options: nosniff\r\n{coi_headers}Connection: close\r\n\r\n",
        bytes.len()
    );
    if write_half.write_all(header.as_bytes()).await.is_err() {
        return;
    }
    if req.method == "GET" {
        let _ = write_half.write_all(&bytes).await;
    }
    let _ = write_half.flush().await;
}

#[cfg(test)]
mod tests;
