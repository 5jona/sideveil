//! Deterministic `.svp` ZIP packager validating plugin directories before archiving.

use std::collections::BTreeMap;
use std::fs;
use std::io::Write;
use std::path::Path;

use flate2::Compression;
use flate2::write::DeflateEncoder;

use super::{
    MAX_ARCHIVE_ENTRIES, MAX_ENTRY_UNCOMPRESSED_BYTES, MAX_TOTAL_UNCOMPRESSED_BYTES, SvpError,
    SvpFileEntry, validate_package_contents,
};
use crate::plugin::PluginManifest;

const EXCLUDED_DIRS: &[&str] = &["node_modules", "target", "dist-tmp", "__pycache__"];

/// Validates `source_dir` and writes a deterministic `.svp` ZIP archive to `output_path`.
pub fn pack_plugin_dir_to_svp(
    source_dir: &Path,
    output_path: &Path,
) -> Result<PluginManifest, SvpError> {
    if !source_dir.is_dir() {
        return Err(SvpError::InvalidPackage(format!(
            "source '{}' is not a directory",
            source_dir.display()
        )));
    }
    let mut files = BTreeMap::new();
    let mut total_bytes: usize = 0;
    collect_dir_files(source_dir, source_dir, &mut files, &mut total_bytes)?;

    let (manifest, _warnings) = validate_package_contents(&files)?;
    let daemon_bin = manifest.daemon.as_ref().map(|d| d.binary.as_str());

    let mut entries_for_zip: Vec<(&str, &[u8], u32, u16)> = Vec::with_capacity(files.len());
    for (rel, entry) in &files {
        let mode = if daemon_bin == Some(rel.as_str()) {
            0o10_0755
        } else {
            0o10_0644
        };
        entries_for_zip.push((rel.as_str(), &entry.bytes, mode, 8));
    }

    let zip_bytes = build_zip_bytes(&entries_for_zip)?;
    if let Some(parent) = output_path.parent()
        && !parent.as_os_str().is_empty()
    {
        fs::create_dir_all(parent)?;
    }
    fs::write(output_path, zip_bytes)?;
    Ok(manifest)
}

fn collect_dir_files(
    root: &Path,
    dir: &Path,
    out: &mut BTreeMap<String, SvpFileEntry>,
    total_bytes: &mut usize,
) -> Result<(), SvpError> {
    let mut entries: Vec<_> = fs::read_dir(dir)?.collect::<Result<Vec<_>, _>>()?;
    entries.sort_by_key(std::fs::DirEntry::file_name);

    for entry in entries {
        let name = entry.file_name();
        let Some(name_str) = name.to_str() else {
            return Err(SvpError::UnsafeEntry("non-UTF-8 filename".to_owned()));
        };
        if name_str.starts_with('.') || name_str.ends_with(".tmp") || name_str.ends_with(".swp") {
            continue;
        }
        let path = entry.path();
        let meta = fs::symlink_metadata(&path)?;
        let ftype = meta.file_type();
        if ftype.is_symlink() {
            return Err(SvpError::UnsafeEntry(format!(
                "symlinks are disallowed in plugin packages: {}",
                path.display()
            )));
        }
        if ftype.is_dir() {
            if EXCLUDED_DIRS.contains(&name_str) {
                continue;
            }
            collect_dir_files(root, &path, out, total_bytes)?;
        } else if ftype.is_file() {
            if out.len() >= MAX_ARCHIVE_ENTRIES {
                return Err(SvpError::LimitExceeded(format!(
                    "plugin exceeds maximum of {MAX_ARCHIVE_ENTRIES} files"
                )));
            }
            let rel = path
                .strip_prefix(root)
                .map_err(|_| SvpError::UnsafeEntry("path escape".to_owned()))?;
            let rel_str = rel
                .to_str()
                .ok_or_else(|| SvpError::UnsafeEntry("non-UTF-8 relative path".to_owned()))?
                .replace('\\', "/");
            if meta.len() as usize > MAX_ENTRY_UNCOMPRESSED_BYTES {
                return Err(SvpError::LimitExceeded(format!(
                    "file '{rel_str}' exceeds {} MiB limit",
                    MAX_ENTRY_UNCOMPRESSED_BYTES / (1024 * 1024)
                )));
            }
            let bytes = fs::read(&path)?;
            *total_bytes = total_bytes.saturating_add(bytes.len());
            if *total_bytes > MAX_TOTAL_UNCOMPRESSED_BYTES {
                return Err(SvpError::LimitExceeded(format!(
                    "package exceeds {} MiB uncompressed limit",
                    MAX_TOTAL_UNCOMPRESSED_BYTES / (1024 * 1024)
                )));
            }
            out.insert(
                rel_str,
                SvpFileEntry {
                    bytes,
                    unix_mode: 0o644,
                },
            );
        }
    }
    Ok(())
}

fn build_zip_bytes(entries: &[(&str, &[u8], u32, u16)]) -> Result<Vec<u8>, SvpError> {
    let mut out = Vec::new();
    let mut central = Vec::new();
    // Fixed deterministic DOS timestamp (2025-01-01 00:00:00).
    let dos_time: u16 = 0;
    let dos_date: u16 = (45 << 9) | (1 << 5) | 1;

    for &(name, raw, unix_mode, req_method) in entries {
        let crc = crc32fast::hash(raw);
        let (method, payload): (u16, Vec<u8>) = if req_method == 8 {
            let mut enc = DeflateEncoder::new(Vec::new(), Compression::default());
            enc.write_all(raw)?;
            let deflated = enc.finish()?;
            if deflated.len() < raw.len() {
                (8, deflated)
            } else {
                (0, raw.to_vec())
            }
        } else {
            (0, raw.to_vec())
        };

        let lfh_offset = u32::try_from(out.len())
            .map_err(|_| SvpError::LimitExceeded("ZIP offset overflow".to_owned()))?;
        let name_bytes = name.as_bytes();
        let name_len = u16::try_from(name_bytes.len())
            .map_err(|_| SvpError::UnsafeEntry("entry filename too long".to_owned()))?;
        let comp_len = u32::try_from(payload.len())
            .map_err(|_| SvpError::LimitExceeded("compressed size overflow".to_owned()))?;
        let uncomp_len = u32::try_from(raw.len())
            .map_err(|_| SvpError::LimitExceeded("uncompressed size overflow".to_owned()))?;

        // Local file header
        out.extend_from_slice(&0x0403_4b50_u32.to_le_bytes());
        out.extend_from_slice(&20_u16.to_le_bytes());
        out.extend_from_slice(&0_u16.to_le_bytes());
        out.extend_from_slice(&method.to_le_bytes());
        out.extend_from_slice(&dos_time.to_le_bytes());
        out.extend_from_slice(&dos_date.to_le_bytes());
        out.extend_from_slice(&crc.to_le_bytes());
        out.extend_from_slice(&comp_len.to_le_bytes());
        out.extend_from_slice(&uncomp_len.to_le_bytes());
        out.extend_from_slice(&name_len.to_le_bytes());
        out.extend_from_slice(&0_u16.to_le_bytes());
        out.extend_from_slice(name_bytes);
        out.extend_from_slice(&payload);

        // Central directory header (version made by Unix = 3)
        central.extend_from_slice(&0x0201_4b50_u32.to_le_bytes());
        central.extend_from_slice(&((3_u16 << 8) | 20).to_le_bytes());
        central.extend_from_slice(&20_u16.to_le_bytes());
        central.extend_from_slice(&0_u16.to_le_bytes());
        central.extend_from_slice(&method.to_le_bytes());
        central.extend_from_slice(&dos_time.to_le_bytes());
        central.extend_from_slice(&dos_date.to_le_bytes());
        central.extend_from_slice(&crc.to_le_bytes());
        central.extend_from_slice(&comp_len.to_le_bytes());
        central.extend_from_slice(&uncomp_len.to_le_bytes());
        central.extend_from_slice(&name_len.to_le_bytes());
        central.extend_from_slice(&0_u16.to_le_bytes());
        central.extend_from_slice(&0_u16.to_le_bytes());
        central.extend_from_slice(&0_u16.to_le_bytes());
        central.extend_from_slice(&0_u16.to_le_bytes());
        central.extend_from_slice(&(unix_mode << 16).to_le_bytes());
        central.extend_from_slice(&lfh_offset.to_le_bytes());
        central.extend_from_slice(name_bytes);
    }

    let cd_offset = u32::try_from(out.len())
        .map_err(|_| SvpError::LimitExceeded("CD offset overflow".to_owned()))?;
    let cd_size = u32::try_from(central.len())
        .map_err(|_| SvpError::LimitExceeded("CD size overflow".to_owned()))?;
    let count = u16::try_from(entries.len())
        .map_err(|_| SvpError::LimitExceeded("entry count overflow".to_owned()))?;

    out.extend_from_slice(&central);
    out.extend_from_slice(&0x0605_4b50_u32.to_le_bytes());
    out.extend_from_slice(&0_u16.to_le_bytes());
    out.extend_from_slice(&0_u16.to_le_bytes());
    out.extend_from_slice(&count.to_le_bytes());
    out.extend_from_slice(&count.to_le_bytes());
    out.extend_from_slice(&cd_size.to_le_bytes());
    out.extend_from_slice(&cd_offset.to_le_bytes());
    out.extend_from_slice(&0_u16.to_le_bytes());
    Ok(out)
}

#[cfg(test)]
pub(super) fn build_raw_zip_for_test(entries: &[(&str, &[u8], u32, u16)]) -> Vec<u8> {
    build_zip_bytes(entries).unwrap_or_default()
}
