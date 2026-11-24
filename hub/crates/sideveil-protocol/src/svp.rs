//! Safe, deterministic `.svp` (SideVeil Plugin ZIP) archive inspection, unpacking, and packing.

mod reader;
mod writer;

use std::collections::BTreeMap;
use std::io;
use std::path::Path;

use thiserror::Error;

pub use self::reader::{SvpArchive, SvpFileEntry, inspect_svp_bytes, read_svp_file};
pub use self::writer::pack_plugin_dir_to_svp;
use crate::plugin::{ManifestValidationError, PluginManifest};

/// Maximum decoded `.svp` archive size (16 MiB).
pub const MAX_ARCHIVE_BYTES: usize = 16 * 1024 * 1024;
/// Maximum number of entries permitted inside a `.svp` archive.
pub const MAX_ARCHIVE_ENTRIES: usize = 512;
/// Maximum uncompressed size of any single file entry (16 MiB).
pub const MAX_ENTRY_UNCOMPRESSED_BYTES: usize = 16 * 1024 * 1024;
/// Maximum aggregate uncompressed size across all entries (32 MiB).
pub const MAX_TOTAL_UNCOMPRESSED_BYTES: usize = 32 * 1024 * 1024;
/// Maximum compression ratio permitted for compressed files larger than 1 KiB.
pub const MAX_COMPRESSION_RATIO: usize = 100;

/// Errors returned while decoding, validating, unpacking, or packing `.svp` archives.
#[derive(Debug, Error)]
pub enum SvpError {
    /// Archive payload exceeds [`MAX_ARCHIVE_BYTES`] or is invalid Base64.
    #[error("invalid archive payload: {0}")]
    InvalidPayload(String),
    /// ZIP structure is malformed, truncated, or uses an unsupported compression method.
    #[error("malformed .svp ZIP archive: {0}")]
    MalformedZip(String),
    /// Entry path violates Zip Slip, symlink, or hidden-file safety constraints.
    #[error("unsafe entry in .svp archive: {0}")]
    UnsafeEntry(String),
    /// Entry count, uncompressed size, or compression ratio exceeded safety limits.
    #[error("zip safety limit exceeded: {0}")]
    LimitExceeded(String),
    /// Decompressed entry CRC-32 did not match ZIP header checksum.
    #[error("CRC-32 mismatch for entry '{0}'")]
    CrcMismatch(String),
    /// Archive did not contain a valid `manifest.json` or declared asset/binary.
    #[error("invalid plugin package: {0}")]
    InvalidPackage(String),
    /// Manifest JSON failed deserialization.
    #[error("invalid manifest.json: {0}")]
    InvalidManifestJson(#[from] serde_json::Error),
    /// Manifest failed schema/security validation.
    #[error(transparent)]
    InvalidManifest(#[from] ManifestValidationError),
    /// Underlying filesystem I/O failure.
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
}

/// Decodes a Base64-encoded `.svp` archive while enforcing [`MAX_ARCHIVE_BYTES`] before allocating.
pub fn decode_archive_base64(encoded: &str) -> Result<Vec<u8>, SvpError> {
    // Base64 expands 3 bytes into 4 chars; count non-whitespace bytes before allocating.
    let max_b64_len = (MAX_ARCHIVE_BYTES / 3 + 2) * 4;
    let mut non_ws_len = 0usize;
    for b in encoded.bytes() {
        if !b.is_ascii_whitespace() {
            non_ws_len += 1;
            if non_ws_len > max_b64_len {
                return Err(SvpError::LimitExceeded(format!(
                    "archive exceeds {} MiB maximum decoded size",
                    MAX_ARCHIVE_BYTES / (1024 * 1024)
                )));
            }
        }
    }
    if non_ws_len == 0 {
        return Err(SvpError::InvalidPayload(
            "archiveBase64 must not be empty".to_owned(),
        ));
    }
    let mut cleaned = Vec::with_capacity(non_ws_len);
    for b in encoded.bytes() {
        if !b.is_ascii_whitespace() {
            cleaned.push(b);
        }
    }
    let bytes = data_encoding::BASE64
        .decode(&cleaned)
        .map_err(|err| SvpError::InvalidPayload(format!("base64 decode error: {err}")))?;
    if bytes.len() > MAX_ARCHIVE_BYTES {
        return Err(SvpError::LimitExceeded(format!(
            "archive exceeds {} MiB maximum decoded size",
            MAX_ARCHIVE_BYTES / (1024 * 1024)
        )));
    }
    Ok(bytes)
}

/// Validates a normalized archive entry path against Zip Slip, absolute paths, and hidden files.
pub(crate) fn normalize_archive_entry_path(raw: &str) -> Result<String, SvpError> {
    if raw.is_empty()
        || raw.contains('\0')
        || raw.contains('\\')
        || raw.starts_with('/')
        || raw.as_bytes().get(1) == Some(&b':')
    {
        return Err(SvpError::UnsafeEntry(format!(
            "disallowed entry path '{raw}'"
        )));
    }
    let trimmed = raw.trim_end_matches('/');
    if trimmed.is_empty() {
        return Err(SvpError::UnsafeEntry("empty entry path".to_owned()));
    }
    let mut parts = Vec::new();
    for seg in trimmed.split('/') {
        if seg.is_empty() || seg == "." || seg == ".." || seg.starts_with('.') {
            return Err(SvpError::UnsafeEntry(format!(
                "disallowed path segment '{seg}' in '{raw}'"
            )));
        }
        parts.push(seg);
    }
    Ok(parts.join("/"))
}

/// Validates a parsed plugin directory or archive's manifest and declared entry/daemon files.
pub fn validate_package_contents(
    files: &BTreeMap<String, SvpFileEntry>,
) -> Result<(PluginManifest, Vec<String>), SvpError> {
    let manifest_entry = files.get("manifest.json").ok_or_else(|| {
        SvpError::InvalidPackage("manifest.json missing at archive root".to_owned())
    })?;
    let manifest_str = std::str::from_utf8(&manifest_entry.bytes)
        .map_err(|err| SvpError::InvalidPackage(format!("manifest.json is not UTF-8: {err}")))?;
    let manifest: PluginManifest = serde_json::from_str(manifest_str)?;
    manifest.validate()?;

    let mut warnings = Vec::new();
    if manifest.embed.is_none() {
        let entry = manifest.effective_entry();
        if !files.contains_key(entry) {
            return Err(SvpError::InvalidPackage(format!(
                "declared entry '{entry}' is missing from plugin package"
            )));
        }
        if !entry.starts_with("web/") {
            warnings.push(format!(
                "entry '{entry}' does not reside under 'web/'; asset server restricts web assets to web/"
            ));
        }
    }
    if let Some(icon) = &manifest.icon {
        let normalized_icon = if icon.starts_with("web/") {
            icon.clone()
        } else {
            format!("web/{icon}")
        };
        if !files.contains_key(icon) && !files.contains_key(&normalized_icon) {
            warnings.push(format!("declared icon '{icon}' not found in package"));
        }
    }
    if let Some(daemon) = &manifest.daemon
        && !files.contains_key(&daemon.binary)
    {
        return Err(SvpError::InvalidPackage(format!(
            "declared daemon binary '{}' is missing from plugin package",
            daemon.binary
        )));
    }
    Ok((manifest, warnings))
}

/// Returns `true` when `path` appears to reference a `.svp` or `.zip` archive file.
#[must_use]
pub fn is_archive_path(path: &Path) -> bool {
    path.is_file()
        && path
            .extension()
            .and_then(|e| e.to_str())
            .is_some_and(|ext| ext.eq_ignore_ascii_case("svp") || ext.eq_ignore_ascii_case("zip"))
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    use super::*;

    fn temp_dir(label: &str) -> std::path::PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        std::env::temp_dir().join(format!(
            "sideveil-svp-{label}-{}-{nanos}",
            std::process::id()
        ))
    }

    #[test]
    fn pack_and_inspect_roundtrip_and_base64_decode() {
        let root = temp_dir("roundtrip");
        let pkg = root.join("src");
        fs::create_dir_all(pkg.join("web")).expect("mkdir web");
        fs::create_dir_all(pkg.join("node_modules/pkg")).expect("mkdir node_modules");
        fs::write(pkg.join("node_modules/pkg/junk.js"), "ignored").expect("write junk");
        fs::write(
            pkg.join("manifest.json"),
            r#"{"schemaVersion":1,"apiVersion":1,"id":"com.example.pack","name":"Pack","version":"1.0.0","entry":"web/index.html"}"#,
        )
        .expect("write manifest");
        fs::write(pkg.join("web/index.html"), "<html>ok</html>").expect("write html");

        let out_svp = root.join("pack.svp");
        let manifest = pack_plugin_dir_to_svp(&pkg, &out_svp).expect("pack svp");
        assert_eq!(manifest.id, "com.example.pack");

        let archive = read_svp_file(&out_svp).expect("read svp");
        assert_eq!(archive.manifest.id, "com.example.pack");
        assert!(archive.files.contains_key("web/index.html"));
        assert!(!archive.files.keys().any(|k| k.contains("node_modules")));

        let raw = fs::read(&out_svp).expect("read bytes");
        let b64 = data_encoding::BASE64.encode(&raw);
        let decoded = decode_archive_base64(&b64).expect("decode b64");
        let via_b64 = inspect_svp_bytes(&decoded).expect("inspect b64");
        assert_eq!(via_b64.manifest.id, "com.example.pack");

        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn rejects_zip_slip_and_symlink_and_zip_bomb() {
        // Zip Slip entry crafted manually
        let evil_zip = writer::build_raw_zip_for_test(&[("../escape.txt", b"boom", 0o100644, 0)]);
        assert!(matches!(
            inspect_svp_bytes(&evil_zip),
            Err(SvpError::UnsafeEntry(_))
        ));

        // Symlink entry crafted manually (S_IFLNK = 0o120000)
        let sym_zip = writer::build_raw_zip_for_test(&[("web/link", b"/etc/passwd", 0o120777, 0)]);
        assert!(matches!(
            inspect_svp_bytes(&sym_zip),
            Err(SvpError::UnsafeEntry(_))
        ));

        // High compression ratio zip-bomb (500 KiB zeros deflated to < 1 KiB)
        let zeros = vec![0u8; 512 * 1024];
        let bomb_zip = writer::build_raw_zip_for_test(&[("web/bomb.bin", &zeros, 0o100644, 8)]);
        assert!(matches!(
            inspect_svp_bytes(&bomb_zip),
            Err(SvpError::LimitExceeded(_))
        ));
    }
}
