//! HTTP/1.1 header parsing, error responses, and MIME mapping for the plugin asset server.

use std::fs::Metadata;
use std::io;
use std::path::Path;
use std::time::UNIX_EPOCH;

use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

const MAX_HEADER_LINES: usize = 64;

pub(super) struct ParsedHttpRequest {
    pub(super) method: String,
    pub(super) target: String,
    pub(super) authorization: Option<String>,
    pub(super) origin: Option<String>,
}

pub(super) async fn parse_http_headers(
    reader: &mut BufReader<tokio::net::tcp::OwnedReadHalf>,
) -> io::Result<ParsedHttpRequest> {
    let mut request_line = String::new();
    let n = reader.read_line(&mut request_line).await?;
    if n == 0 {
        return Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            "empty HTTP request",
        ));
    }
    let mut parts = request_line.split_whitespace();
    let method = parts
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing HTTP method"))?
        .to_owned();
    let target = parts
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing HTTP target"))?
        .to_owned();

    let mut authorization = None;
    let mut origin = None;
    for _ in 0..MAX_HEADER_LINES {
        let mut line = String::new();
        let read = reader.read_line(&mut line).await?;
        if read == 0 {
            break;
        }
        let trimmed = line.trim_end_matches(['\r', '\n']);
        if trimmed.is_empty() {
            break;
        }
        if let Some((name, value)) = trimmed.split_once(':') {
            let lower = name.trim().to_ascii_lowercase();
            let val = value.trim().to_owned();
            if lower == "authorization" {
                authorization = Some(val);
            } else if lower == "origin" {
                origin = Some(val);
            }
        }
    }

    Ok(ParsedHttpRequest {
        method,
        target,
        authorization,
        origin,
    })
}

pub(crate) fn mime_type_for(path: &Path) -> &'static str {
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .map(str::to_ascii_lowercase)
        .unwrap_or_default();
    match ext.as_str() {
        "html" | "htm" => "text/html; charset=utf-8",
        "js" | "mjs" => "application/javascript; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "json" | "map" => "application/json; charset=utf-8",
        "webmanifest" => "application/manifest+json; charset=utf-8",
        "svg" => "image/svg+xml",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "webp" => "image/webp",
        "avif" => "image/avif",
        "ico" => "image/x-icon",
        "woff2" => "font/woff2",
        "woff" => "font/woff",
        "ttf" => "font/ttf",
        "otf" => "font/otf",
        "wasm" => "application/wasm",
        "txt" => "text/plain; charset=utf-8",
        "xml" => "application/xml; charset=utf-8",
        _ => "application/octet-stream",
    }
}

/// Returns `Cache-Control` (and weak `ETag` for hashed assets under `/assets/`), keeping HTML `no-store`.
pub(crate) fn cache_headers_for(raw_rel: &str, path: &Path, meta: &Metadata) -> String {
    let ext = path
        .extension()
        .and_then(|e| e.to_str())
        .map(str::to_ascii_lowercase)
        .unwrap_or_default();
    let is_html = matches!(ext.as_str(), "html" | "htm");
    let normalized = raw_rel.trim_start_matches('/');
    let is_hashed_asset = !is_html
        && (normalized.starts_with("web/assets/")
            || normalized.starts_with("assets/")
            || normalized.contains("/assets/"));

    if is_hashed_asset {
        let (secs, nanos) = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
            .map_or((0, 0), |d| (d.as_secs(), d.subsec_nanos()));
        format!(
            "Cache-Control: private, max-age=300, immutable\r\nETag: W/\"{:x}-{:x}-{:x}\"\r\n",
            meta.len(),
            secs,
            nanos
        )
    } else {
        "Cache-Control: no-store\r\n".to_owned()
    }
}

pub(super) async fn write_error(
    writer: &mut tokio::net::tcp::OwnedWriteHalf,
    status: u16,
    reason: &str,
) -> io::Result<()> {
    let resp = format!(
        "HTTP/1.1 {status} {reason}\r\nContent-Type: text/plain\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{reason}",
        reason.len()
    );
    writer.write_all(resp.as_bytes()).await?;
    writer.flush().await
}
