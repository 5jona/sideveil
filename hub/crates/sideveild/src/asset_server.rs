//! Authenticated loopback HTTP static asset server for isolated plugin bundles (`GET /plugins/<id>/<path>`).

use std::io;
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use sideveil_protocol::plugin::validate_plugin_id;
use tokio::fs;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::TcpStream;

use crate::host_ws::constant_time_eq;
use crate::state::HubState;

const HEADER_TIMEOUT: Duration = Duration::from_secs(4);
const MAX_HEADER_LINES: usize = 64;
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

    let path_only = req.target.split('?').next().unwrap_or_default();
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

    let Ok(file_path) = resolve_asset_file(&plugin_root, raw_rel).await else {
        let _ = write_error(&mut write_half, 403, "Forbidden").await;
        return;
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
    let header = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: {mime}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n",
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

struct ParsedHttpRequest {
    method: String,
    target: String,
    authorization: Option<String>,
    origin: Option<String>,
}

async fn parse_http_headers(
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

/// Resolves `raw_rel` strictly inside `<plugin_root>/web/`, rejecting `..`, hidden files,
/// sibling files outside `web/`, and symlink escapes.
pub async fn resolve_asset_file(
    plugin_root: &Path,
    raw_rel: &str,
) -> Result<PathBuf, &'static str> {
    let decoded = percent_decode(raw_rel)?;
    let trimmed = decoded.trim_start_matches('/');
    if trimmed.is_empty() || trimmed.contains('\\') {
        return Err("invalid asset path");
    }

    let mut clean_rel = PathBuf::new();
    let mut first_segment: Option<String> = None;
    for comp in Path::new(trimmed).components() {
        match comp {
            Component::Normal(seg) => {
                let s = seg.to_string_lossy();
                if s.starts_with('.') {
                    return Err("hidden files are forbidden");
                }
                if first_segment.is_none() {
                    first_segment = Some(s.into_owned());
                }
                clean_rel.push(seg);
            }
            _ => return Err("directory traversal forbidden"),
        }
    }

    if first_segment.as_deref() != Some("web") {
        return Err("assets must reside under web/");
    }

    let canonical_web_root = fs::canonicalize(plugin_root.join("web"))
        .await
        .map_err(|_| "plugin web root missing")?;
    let rel_inside_web = clean_rel
        .strip_prefix("web")
        .map_err(|_| "invalid web asset path")?;
    let candidate = canonical_web_root.join(rel_inside_web);
    let canonical_target = fs::canonicalize(&candidate)
        .await
        .map_err(|_| "asset missing")?;
    if !canonical_target.starts_with(&canonical_web_root) {
        return Err("symlink escape forbidden");
    }
    Ok(canonical_target)
}

fn percent_decode(input: &str) -> Result<String, &'static str> {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            if i + 2 >= bytes.len() {
                return Err("incomplete percent escape");
            }
            let hi = from_hex(bytes[i + 1]).ok_or("invalid percent escape")?;
            let lo = from_hex(bytes[i + 2]).ok_or("invalid percent escape")?;
            let decoded = (hi << 4) | lo;
            if decoded == 0 {
                return Err("NUL byte forbidden");
            }
            out.push(decoded);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    String::from_utf8(out).map_err(|_| "invalid UTF-8 in path")
}

fn from_hex(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

fn mime_type_for(path: &Path) -> &'static str {
    match path.extension().and_then(|e| e.to_str()).unwrap_or("") {
        "html" | "htm" => "text/html; charset=utf-8",
        "js" | "mjs" => "application/javascript; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "json" => "application/json; charset=utf-8",
        "svg" => "image/svg+xml",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "webp" => "image/webp",
        "woff2" => "font/woff2",
        "wasm" => "application/wasm",
        _ => "application/octet-stream",
    }
}

async fn write_error(
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
