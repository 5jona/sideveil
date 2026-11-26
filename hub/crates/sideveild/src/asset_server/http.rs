//! Bounded HTTP/1.1 header parsing, error responses, and MIME mapping for the plugin asset server.

use std::fs::Metadata;
use std::io;
use std::path::Path;
use std::time::UNIX_EPOCH;

use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWriteExt, BufReader};

const MAX_HEADER_LINES: usize = 64;
const MAX_REQUEST_LINE_BYTES: usize = 8 * 1024;
const MAX_HEADER_LINE_BYTES: usize = 8 * 1024;
const MAX_TOTAL_HEADER_BYTES: usize = 64 * 1024;

pub(super) struct ParsedHttpRequest {
    pub(super) method: String,
    pub(super) target: String,
    pub(super) authorization: Option<String>,
    pub(super) origin: Option<String>,
    pub(super) host: Option<String>,
    pub(super) if_none_match: Option<String>,
    pub(super) keep_alive: bool,
}

/// Reads a single `\n`-terminated HTTP line while enforcing `max_bytes` before allocating.
async fn read_bounded_http_line<R: AsyncRead + Unpin>(
    reader: &mut BufReader<R>,
    max_bytes: usize,
) -> io::Result<Option<String>> {
    let mut buf = Vec::new();
    loop {
        let available = reader.fill_buf().await?;
        if available.is_empty() {
            if buf.is_empty() {
                return Ok(None);
            }
            break;
        }
        if let Some(pos) = available.iter().position(|&b| b == b'\n') {
            if buf.len() + pos > max_bytes {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "HTTP line exceeds maximum length",
                ));
            }
            buf.extend_from_slice(&available[..pos]);
            reader.consume(pos + 1);
            break;
        }
        if buf.len() + available.len() > max_bytes {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "HTTP line exceeds maximum length",
            ));
        }
        let len = available.len();
        buf.extend_from_slice(available);
        reader.consume(len);
    }
    if buf.last() == Some(&b'\r') {
        buf.pop();
    }
    String::from_utf8(buf)
        .map(Some)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))
}

pub(super) async fn parse_http_headers<R: AsyncRead + Unpin>(
    reader: &mut BufReader<R>,
) -> io::Result<ParsedHttpRequest> {
    let Some(request_line) = read_bounded_http_line(reader, MAX_REQUEST_LINE_BYTES).await? else {
        return Err(io::Error::new(
            io::ErrorKind::UnexpectedEof,
            "empty HTTP request",
        ));
    };
    let mut total_bytes = request_line.len();
    let mut parts = request_line.split_whitespace();
    let method = parts
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing HTTP method"))?
        .to_owned();
    let target = parts
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing HTTP target"))?
        .to_owned();
    let version = parts.next().unwrap_or("HTTP/1.1");

    let mut authorization = None;
    let mut origin: Option<String> = None;
    let mut origin_count = 0usize;
    let mut host: Option<String> = None;
    let mut host_count = 0usize;
    let mut if_none_match: Option<String> = None;
    let mut conn_close = false;
    let mut conn_keep_alive = false;
    let mut has_body_framing = false;
    let mut saw_terminator = false;
    for _ in 0..=MAX_HEADER_LINES {
        let Some(line) = read_bounded_http_line(reader, MAX_HEADER_LINE_BYTES).await? else {
            saw_terminator = true;
            break;
        };
        if line.is_empty() {
            saw_terminator = true;
            break;
        }
        total_bytes = total_bytes.saturating_add(line.len());
        if total_bytes > MAX_TOTAL_HEADER_BYTES {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "HTTP headers exceed maximum aggregate size",
            ));
        }
        if let Some((name, value)) = line.split_once(':') {
            let trimmed_name = name.trim();
            let val = value.trim();
            if trimmed_name.eq_ignore_ascii_case("authorization") {
                authorization = Some(val.to_owned());
            } else if trimmed_name.eq_ignore_ascii_case("origin") {
                origin_count += 1;
                if origin.is_none() || !val.is_empty() {
                    origin = Some(val.to_owned());
                }
            } else if trimmed_name.eq_ignore_ascii_case("host") {
                host_count += 1;
                host = Some(val.to_owned());
            } else if trimmed_name.eq_ignore_ascii_case("if-none-match") {
                if_none_match = Some(val.to_owned());
            } else if trimmed_name.eq_ignore_ascii_case("connection") {
                for token in val.split(',').map(str::trim) {
                    if token.eq_ignore_ascii_case("close") {
                        conn_close = true;
                    } else if token.eq_ignore_ascii_case("keep-alive") {
                        conn_keep_alive = true;
                    }
                }
            } else if trimmed_name.eq_ignore_ascii_case("content-length")
                || trimmed_name.eq_ignore_ascii_case("transfer-encoding")
            {
                has_body_framing = true;
            }
        }
    }

    if !saw_terminator {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "too many HTTP headers",
        ));
    }

    if origin_count > 1 {
        origin = Some("duplicate-origin".to_owned());
    }
    if host_count > 1 {
        host = Some("duplicate-host".to_owned());
    }

    let keep_alive = !has_body_framing
        && !conn_close
        && (version.eq_ignore_ascii_case("HTTP/1.1") || conn_keep_alive);

    Ok(ParsedHttpRequest {
        method,
        target,
        authorization,
        origin,
        host,
        if_none_match,
        keep_alive,
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

/// Computes the weak ETag for hashed plugin assets under `/assets/`, or `None` for non-hashed/HTML files.
pub(super) fn etag_for(raw_rel: &str, path: &Path, meta: &Metadata) -> Option<String> {
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
    if !is_hashed_asset {
        return None;
    }
    let (secs, nanos) = meta
        .modified()
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map_or((0, 0), |d| (d.as_secs(), d.subsec_nanos()));
    Some(format!("W/\"{:x}-{:x}-{:x}\"", meta.len(), secs, nanos))
}

/// Evaluates an `If-None-Match` header value against `etag` using RFC 9110 weak comparison.
pub(super) fn etag_matches(if_none_match: &str, etag: &str) -> bool {
    let target_opaque = etag.strip_prefix("W/").unwrap_or(etag);
    if_none_match.split(',').map(str::trim).any(|candidate| {
        candidate == "*" || candidate.strip_prefix("W/").unwrap_or(candidate) == target_opaque
    })
}

/// Returns `Cache-Control` (and weak `ETag` for hashed assets under `/assets/`), keeping HTML `no-store`.
pub(crate) fn cache_headers_for(raw_rel: &str, path: &Path, meta: &Metadata) -> String {
    match etag_for(raw_rel, path, meta) {
        Some(etag) => format!("Cache-Control: private, max-age=300, immutable\r\nETag: {etag}\r\n"),
        None => "Cache-Control: no-store\r\n".to_owned(),
    }
}

pub(super) async fn write_not_modified(
    writer: &mut tokio::net::tcp::OwnedWriteHalf,
    cache_headers: &str,
    coi_headers: &str,
    keep_alive: bool,
) -> io::Result<()> {
    let conn = if keep_alive { "keep-alive" } else { "close" };
    let resp = format!(
        "HTTP/1.1 304 Not Modified\r\n{cache_headers}X-Content-Type-Options: nosniff\r\n{coi_headers}Connection: {conn}\r\n\r\n"
    );
    writer.write_all(resp.as_bytes()).await?;
    writer.flush().await
}

pub(super) async fn write_no_content(
    writer: &mut tokio::net::tcp::OwnedWriteHalf,
    keep_alive: bool,
) -> io::Result<()> {
    let conn = if keep_alive { "keep-alive" } else { "close" };
    let resp = format!(
        "HTTP/1.1 204 No Content\r\nCache-Control: private, max-age=300\r\nX-Content-Type-Options: nosniff\r\nConnection: {conn}\r\n\r\n"
    );
    writer.write_all(resp.as_bytes()).await?;
    writer.flush().await
}

pub(super) async fn write_error(
    writer: &mut tokio::net::tcp::OwnedWriteHalf,
    status: u16,
    reason: &str,
) -> io::Result<()> {
    let resp = format!(
        "HTTP/1.1 {status} {reason}\r\nContent-Type: text/plain\r\nContent-Length: {}\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n{reason}",
        reason.len()
    );
    writer.write_all(resp.as_bytes()).await?;
    writer.flush().await
}

#[cfg(test)]
mod tests {
    use tokio::io::BufReader;

    use super::*;

    #[tokio::test]
    async fn rejects_oversized_request_line_and_header_line() {
        let oversized_req = format!(
            "GET /{} HTTP/1.1\r\n\r\n",
            "a".repeat(MAX_REQUEST_LINE_BYTES)
        );
        let mut reader = BufReader::new(oversized_req.as_bytes());
        let err = parse_http_headers(&mut reader)
            .await
            .err()
            .expect("oversized request line must fail");
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);

        let oversized_hdr = format!(
            "GET /plugins/demo/index.html HTTP/1.1\r\nX-Pad: {}\r\n\r\n",
            "b".repeat(MAX_HEADER_LINE_BYTES)
        );
        let mut reader2 = BufReader::new(oversized_hdr.as_bytes());
        let err2 = parse_http_headers(&mut reader2)
            .await
            .err()
            .expect("oversized header line must fail");
        assert_eq!(err2.kind(), io::ErrorKind::InvalidData);
    }

    #[tokio::test]
    async fn parses_valid_bounded_request_headers() {
        let raw = b"GET /plugins/demo/web/index.html HTTP/1.1\r\nAuthorization: Bearer secret\r\nOrigin: https://example.test\r\n\r\n";
        let mut reader = BufReader::new(&raw[..]);
        let parsed = parse_http_headers(&mut reader)
            .await
            .expect("parse headers");
        assert_eq!(parsed.method, "GET");
        assert_eq!(parsed.target, "/plugins/demo/web/index.html");
        assert_eq!(parsed.authorization.as_deref(), Some("Bearer secret"));
        assert_eq!(parsed.origin.as_deref(), Some("https://example.test"));
    }

    #[tokio::test]
    async fn rejects_requests_exceeding_max_header_lines() {
        let mut raw = String::from("GET /plugins/demo/web/index.html HTTP/1.1\r\n");
        for i in 0..=MAX_HEADER_LINES {
            raw.push_str(&format!("X-Pad-{i}: v\r\n"));
        }
        raw.push_str("Origin: https://evil.test\r\n\r\n");
        let mut reader = BufReader::new(raw.as_bytes());
        let err = parse_http_headers(&mut reader)
            .await
            .err()
            .expect("65+ headers must fail");
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
    }
}
