//! Authenticated loopback HTTP static asset server for isolated plugin bundles (`GET /plugins/<id>/<path>`).

mod http;
mod icon_cache;
mod memory_cache;
mod path;

use std::sync::Arc;
use std::time::Duration;

use sideveil_protocol::plugin::validate_plugin_id;
use tokio::fs;
use tokio::io::{AsyncWriteExt, BufReader};
use tokio::net::TcpStream;

use self::http::{
    ParsedHttpRequest, etag_for, etag_matches, parse_http_headers, write_error, write_no_content,
    write_not_modified,
};
pub(crate) use self::http::{cache_headers_for, mime_type_for};
pub(crate) use self::memory_cache::AssetMemoryCache;
use self::memory_cache::{AssetStamp, MAX_CACHED_ASSET_BYTES};
pub use self::path::{
    ResolveAssetError, is_spa_route_path, resolve_asset_file, resolve_asset_file_checked,
};
use crate::host_ws::{constant_time_eq, is_loopback_host_header};
use crate::state::HubState;

const HEADER_TIMEOUT: Duration = Duration::from_secs(4);
const KEEP_ALIVE_IDLE_TIMEOUT: Duration = Duration::from_secs(15);
const MAX_KEEP_ALIVE_REQUESTS: usize = 64;
const MAX_ASSET_BYTES: u64 = 16 * 1024 * 1024;

/// Serves authenticated HTTP/1.1 GET/HEAD requests (with safe Keep-Alive and ETag 304)
/// for `/plugins/<plugin_id>/<relative_path>`.
pub async fn handle_http_asset(stream: TcpStream, state: Arc<HubState>) {
    let (read_half, mut write_half) = stream.into_split();
    let mut reader = BufReader::new(read_half);

    for req_idx in 0..MAX_KEEP_ALIVE_REQUESTS {
        let timeout = if req_idx == 0 {
            HEADER_TIMEOUT
        } else {
            KEEP_ALIVE_IDLE_TIMEOUT
        };
        let req = match tokio::time::timeout(timeout, parse_http_headers(&mut reader)).await {
            Ok(Ok(req)) => req,
            Ok(Err(err)) if req_idx > 0 && err.kind() == std::io::ErrorKind::UnexpectedEof => {
                return;
            }
            Err(_) if req_idx > 0 => return,
            _ => {
                let _ = write_error(&mut write_half, 400, "Bad Request").await;
                return;
            }
        };

        let keep_alive = req.keep_alive && req_idx + 1 < MAX_KEEP_ALIVE_REQUESTS;
        if !serve_single_asset_request(&req, &state, &mut write_half, keep_alive).await {
            return;
        }
    }
}

async fn serve_single_asset_request(
    req: &ParsedHttpRequest,
    state: &Arc<HubState>,
    write_half: &mut tokio::net::tcp::OwnedWriteHalf,
    keep_alive: bool,
) -> bool {
    if req.method != "GET" && req.method != "HEAD" {
        let _ = write_error(write_half, 405, "Method Not Allowed").await;
        return false;
    }

    if let Some(origin) = req.origin.as_deref()
        && !origin.is_empty()
    {
        let _ = write_error(write_half, 403, "Forbidden").await;
        return false;
    }

    if let Some(host) = req.host.as_deref()
        && !is_loopback_host_header(host)
    {
        let _ = write_error(write_half, 403, "Forbidden").await;
        return false;
    }

    let provided = req.authorization.as_deref().unwrap_or_default();
    if !constant_time_eq(provided.as_bytes(), state.ws_auth_header().as_bytes()) {
        let _ = write_error(write_half, 401, "Unauthorized").await;
        return false;
    }

    let (path_only, query_str) = match req.target.split_once('?') {
        Some((p, q)) => (p, Some(q)),
        None => (req.target.as_str(), None),
    };
    let Some(rest) = path_only.strip_prefix("/plugins/") else {
        let _ = write_error(write_half, 404, "Not Found").await;
        return false;
    };
    let Some((plugin_id, raw_rel)) = rest.split_once('/') else {
        let _ = write_error(write_half, 404, "Not Found").await;
        return false;
    };
    if validate_plugin_id(plugin_id).is_err() {
        let _ = write_error(write_half, 400, "Invalid Plugin ID").await;
        return false;
    }

    let Some(plugin_root) = state.plugin_registry().plugin_root(plugin_id) else {
        let _ = write_error(write_half, 404, "Plugin Not Found").await;
        return false;
    };

    let conn_hdr = if keep_alive { "keep-alive" } else { "close" };

    if raw_rel == "__icon" {
        let query = icon_cache::IconQuery::parse(query_str);
        match icon_cache::load_or_rasterize_icon(state, plugin_id, &plugin_root, query).await {
            Ok(resolved) => {
                let header = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: {}\r\nContent-Length: {}\r\nCache-Control: private, max-age=60\r\nX-Content-Type-Options: nosniff\r\nConnection: {conn_hdr}\r\n\r\n",
                    resolved.mime,
                    resolved.bytes.len()
                );
                if write_half.write_all(header.as_bytes()).await.is_err() {
                    return false;
                }
                if req.method == "GET" && write_half.write_all(&resolved.bytes).await.is_err() {
                    return false;
                }
                return write_half.flush().await.is_ok() && keep_alive;
            }
            Err((status, reason)) => {
                let _ = write_error(write_half, status, reason).await;
                return false;
            }
        }
    }

    let (file_path, effective_rel) = if raw_rel == "favicon.ico" {
        match resolve_asset_file_checked(&plugin_root, "web/favicon.ico").await {
            Ok(path) => (path, "web/favicon.ico".to_owned()),
            Err(ResolveAssetError::NotFound) => {
                return write_no_content(write_half, keep_alive).await.is_ok() && keep_alive;
            }
            Err(ResolveAssetError::Forbidden(_)) => {
                let _ = write_error(write_half, 403, "Forbidden").await;
                return false;
            }
        }
    } else {
        match resolve_asset_file_checked(&plugin_root, raw_rel).await {
            Ok(path) => (path, raw_rel.to_owned()),
            Err(ResolveAssetError::Forbidden(_)) => {
                let _ = write_error(write_half, 403, "Forbidden").await;
                return false;
            }
            Err(ResolveAssetError::NotFound) => {
                if is_spa_route_path(raw_rel)
                    && let Some(spa_entry) = state.plugin_registry().plugin_spa_entry(plugin_id)
                    && let Ok(fallback) = resolve_asset_file(&plugin_root, &spa_entry).await
                {
                    (fallback, spa_entry)
                } else {
                    let _ = write_error(write_half, 404, "Not Found").await;
                    return false;
                }
            }
        }
    };

    let Ok(meta) = fs::metadata(&file_path).await else {
        let _ = write_error(write_half, 404, "Not Found").await;
        return false;
    };
    if !meta.is_file() || meta.len() > MAX_ASSET_BYTES {
        let _ = write_error(write_half, 404, "Not Found").await;
        return false;
    }

    let cache_headers = cache_headers_for(&effective_rel, &file_path, &meta);
    let coi_headers = if state
        .plugin_registry()
        .plugin_cross_origin_isolated(plugin_id)
    {
        "Cross-Origin-Opener-Policy: same-origin\r\nCross-Origin-Embedder-Policy: require-corp\r\nCross-Origin-Resource-Policy: same-origin\r\n"
    } else {
        ""
    };

    if let Some(etag) = etag_for(&effective_rel, &file_path, &meta)
        && let Some(if_none_match) = req.if_none_match.as_deref()
        && etag_matches(if_none_match, &etag)
    {
        return write_not_modified(write_half, &cache_headers, coi_headers, keep_alive)
            .await
            .is_ok()
            && keep_alive;
    }

    let mime = mime_type_for(&file_path);
    if req.method == "HEAD" {
        let header = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: {mime}\r\nContent-Length: {}\r\n{cache_headers}X-Content-Type-Options: nosniff\r\n{coi_headers}Connection: {conn_hdr}\r\n\r\n",
            meta.len()
        );
        if write_half.write_all(header.as_bytes()).await.is_err() {
            return false;
        }
        return write_half.flush().await.is_ok() && keep_alive;
    }

    let stamp = AssetStamp::from_metadata(&meta);
    let bytes = if let Some(s) = stamp
        && let Some(cached) = state.asset_cache().get(&file_path, s)
    {
        cached
    } else {
        let Ok(raw) = fs::read(&file_path).await else {
            let _ = write_error(write_half, 500, "Internal Server Error").await;
            return false;
        };
        let payload = Arc::new(raw);
        if let Some(s) = stamp
            && payload.len() <= MAX_CACHED_ASSET_BYTES
        {
            state
                .asset_cache()
                .insert(file_path, s, Arc::clone(&payload));
        }
        payload
    };

    let header = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: {mime}\r\nContent-Length: {}\r\n{cache_headers}X-Content-Type-Options: nosniff\r\n{coi_headers}Connection: {conn_hdr}\r\n\r\n",
        bytes.len()
    );
    if write_half.write_all(header.as_bytes()).await.is_err() {
        return false;
    }
    if write_half.write_all(&bytes).await.is_err() {
        return false;
    }
    write_half.flush().await.is_ok() && keep_alive
}

#[cfg(test)]
mod tests;
