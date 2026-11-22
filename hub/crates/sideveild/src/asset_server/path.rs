//! Canonicalized plugin web asset path resolution with traversal and symlink protection.

use std::io;
use std::path::{Component, Path, PathBuf};

use tokio::fs;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResolveAssetError {
    Forbidden(&'static str),
    NotFound,
}

/// Resolves `raw_rel` strictly inside `<plugin_root>/web/`, distinguishing security rejections
/// (`Forbidden`) from missing files (`NotFound`) so SPA fallback never masks traversal attacks.
pub async fn resolve_asset_file_checked(
    plugin_root: &Path,
    raw_rel: &str,
) -> Result<PathBuf, ResolveAssetError> {
    let decoded = percent_decode(raw_rel).map_err(ResolveAssetError::Forbidden)?;
    let trimmed = decoded.trim_start_matches('/');
    if trimmed.is_empty() || trimmed.contains('\\') {
        return Err(ResolveAssetError::Forbidden("invalid asset path"));
    }

    let mut clean_rel = PathBuf::new();
    let mut first_segment: Option<String> = None;
    for comp in Path::new(trimmed).components() {
        match comp {
            Component::Normal(seg) => {
                let s = seg.to_string_lossy();
                if s.starts_with('.') {
                    return Err(ResolveAssetError::Forbidden("hidden files are forbidden"));
                }
                if first_segment.is_none() {
                    first_segment = Some(s.into_owned());
                }
                clean_rel.push(seg);
            }
            _ => {
                return Err(ResolveAssetError::Forbidden(
                    "directory traversal forbidden",
                ));
            }
        }
    }

    if first_segment.as_deref() != Some("web") {
        return Err(ResolveAssetError::Forbidden(
            "assets must reside under web/",
        ));
    }

    let canonical_web_root = fs::canonicalize(plugin_root.join("web"))
        .await
        .map_err(|_| ResolveAssetError::NotFound)?;
    let rel_inside_web = clean_rel
        .strip_prefix("web")
        .map_err(|_| ResolveAssetError::Forbidden("invalid web asset path"))?;
    let candidate = canonical_web_root.join(rel_inside_web);
    let canonical_target = match fs::canonicalize(&candidate).await {
        Ok(p) => p,
        Err(err) if err.kind() == io::ErrorKind::NotFound => {
            return Err(ResolveAssetError::NotFound);
        }
        Err(_) => return Err(ResolveAssetError::NotFound),
    };
    if !canonical_target.starts_with(&canonical_web_root) {
        return Err(ResolveAssetError::Forbidden("symlink escape forbidden"));
    }
    Ok(canonical_target)
}

/// Resolves `raw_rel` strictly inside `<plugin_root>/web/`, rejecting `..`, hidden files,
/// sibling files outside `web/`, and symlink escapes.
pub async fn resolve_asset_file(
    plugin_root: &Path,
    raw_rel: &str,
) -> Result<PathBuf, &'static str> {
    resolve_asset_file_checked(plugin_root, raw_rel)
        .await
        .map_err(|err| match err {
            ResolveAssetError::Forbidden(reason) => reason,
            ResolveAssetError::NotFound => "asset missing",
        })
}

/// Returns `true` when `raw_rel` looks like a client-side SPA route (no file extension on the
/// final path segment) rather than a missing static asset file.
#[must_use]
pub fn is_spa_route_path(raw_rel: &str) -> bool {
    let trimmed = raw_rel.trim_matches('/');
    if trimmed.is_empty() || raw_rel.ends_with('/') {
        return true;
    }
    let last_segment = trimmed.rsplit('/').next().unwrap_or_default();
    !last_segment.contains('.')
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
