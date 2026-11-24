//! Safe pure-Rust ZIP reader for `.svp` archives with Zip Slip, symlink, CRC, and bomb guards.

use std::collections::BTreeMap;
use std::fs;
use std::io::Read;
use std::path::Path;

use flate2::read::DeflateDecoder;

use super::{
    MAX_ARCHIVE_BYTES, MAX_ARCHIVE_ENTRIES, MAX_COMPRESSION_RATIO, MAX_ENTRY_UNCOMPRESSED_BYTES,
    MAX_TOTAL_UNCOMPRESSED_BYTES, SvpError, normalize_archive_entry_path,
    validate_package_contents,
};
use crate::plugin::PluginManifest;

const EOCD_SIG: u32 = 0x0605_4b50;
const CD_SIG: u32 = 0x0201_4b50;
const LFH_SIG: u32 = 0x0403_4b50;
const S_IFMT: u32 = 0o17_0000;
const S_IFLNK: u32 = 0o12_0000;

/// Single validated regular file extracted in memory from a `.svp` archive.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SvpFileEntry {
    pub bytes: Vec<u8>,
    pub unix_mode: u32,
}

/// Validated `.svp` archive containing its parsed manifest, files, and non-fatal warnings.
#[derive(Debug, Clone)]
pub struct SvpArchive {
    pub manifest: PluginManifest,
    pub files: BTreeMap<String, SvpFileEntry>,
    pub warnings: Vec<String>,
}

/// Reads and validates a `.svp` archive file from disk.
pub fn read_svp_file(path: &Path) -> Result<SvpArchive, SvpError> {
    let meta = fs::metadata(path)?;
    if meta.len() as usize > MAX_ARCHIVE_BYTES {
        return Err(SvpError::LimitExceeded(format!(
            "archive '{}' exceeds {} MiB limit",
            path.display(),
            MAX_ARCHIVE_BYTES / (1024 * 1024)
        )));
    }
    let bytes = fs::read(path)?;
    inspect_svp_bytes(&bytes)
}

/// Inspects and validates raw `.svp` ZIP bytes in memory without touching the filesystem.
pub fn inspect_svp_bytes(data: &[u8]) -> Result<SvpArchive, SvpError> {
    if data.len() > MAX_ARCHIVE_BYTES {
        return Err(SvpError::LimitExceeded(format!(
            "archive exceeds {} MiB limit",
            MAX_ARCHIVE_BYTES / (1024 * 1024)
        )));
    }
    let (cd_offset, cd_size, entry_count) = parse_eocd(data)?;
    if entry_count > MAX_ARCHIVE_ENTRIES {
        return Err(SvpError::LimitExceeded(format!(
            "archive has {entry_count} entries (max {MAX_ARCHIVE_ENTRIES})"
        )));
    }
    let cd_end = cd_offset
        .checked_add(cd_size)
        .filter(|&end| end <= data.len())
        .ok_or_else(|| SvpError::MalformedZip("central directory out of bounds".to_owned()))?;

    let mut raw_entries: Vec<(String, SvpFileEntry)> = Vec::new();
    let mut total_uncompressed: usize = 0;
    let mut cursor = cd_offset;

    for _ in 0..entry_count {
        if cursor + 46 > cd_end || read_u32(data, cursor)? != CD_SIG {
            return Err(SvpError::MalformedZip(
                "invalid central directory header".to_owned(),
            ));
        }
        let method = read_u16(data, cursor + 10)?;
        let expected_crc = read_u32(data, cursor + 16)?;
        let comp_size = read_u32(data, cursor + 20)? as usize;
        let uncomp_size = read_u32(data, cursor + 24)? as usize;
        let name_len = read_u16(data, cursor + 28)? as usize;
        let extra_len = read_u16(data, cursor + 30)? as usize;
        let comment_len = read_u16(data, cursor + 32)? as usize;
        let external_attr = read_u32(data, cursor + 38)?;
        let lfh_offset = read_u32(data, cursor + 42)? as usize;

        let name_start = cursor + 46;
        let name_end = name_start + name_len;
        let next_cursor = name_end + extra_len + comment_len;
        if next_cursor > cd_end {
            return Err(SvpError::MalformedZip(
                "central directory entry truncated".to_owned(),
            ));
        }
        let raw_name = std::str::from_utf8(&data[name_start..name_end])
            .map_err(|_| SvpError::MalformedZip("non-UTF-8 entry filename".to_owned()))?;
        cursor = next_cursor;

        let unix_mode = external_attr >> 16;
        if (unix_mode & S_IFMT) == S_IFLNK {
            return Err(SvpError::UnsafeEntry(format!(
                "symlinks are disallowed in .svp archives: '{raw_name}'"
            )));
        }

        let is_dir = raw_name.ends_with('/');
        let normalized = normalize_archive_entry_path(raw_name)?;
        if is_dir {
            continue;
        }

        if uncomp_size > MAX_ENTRY_UNCOMPRESSED_BYTES {
            return Err(SvpError::LimitExceeded(format!(
                "entry '{normalized}' exceeds {} MiB uncompressed limit",
                MAX_ENTRY_UNCOMPRESSED_BYTES / (1024 * 1024)
            )));
        }
        if method != 0 && uncomp_size > 1024 {
            let max_allowed = comp_size.max(1).saturating_mul(MAX_COMPRESSION_RATIO);
            if uncomp_size > max_allowed {
                return Err(SvpError::LimitExceeded(format!(
                    "entry '{normalized}' exceeds {MAX_COMPRESSION_RATIO}x compression ratio limit"
                )));
            }
        }

        let remaining_budget = MAX_TOTAL_UNCOMPRESSED_BYTES.saturating_sub(total_uncompressed);
        let entry_cap = MAX_ENTRY_UNCOMPRESSED_BYTES.min(remaining_budget);
        let payload = extract_local_entry(data, lfh_offset, method, comp_size, entry_cap)?;

        if method != 0 && payload.len() > 1024 {
            let max_allowed = comp_size.max(1).saturating_mul(MAX_COMPRESSION_RATIO);
            if payload.len() > max_allowed {
                return Err(SvpError::LimitExceeded(format!(
                    "entry '{normalized}' exceeded {MAX_COMPRESSION_RATIO}x ratio during inflate"
                )));
            }
        }
        if crc32fast::hash(&payload) != expected_crc {
            return Err(SvpError::CrcMismatch(normalized));
        }
        total_uncompressed = total_uncompressed.saturating_add(payload.len());
        raw_entries.push((
            normalized,
            SvpFileEntry {
                bytes: payload,
                unix_mode: unix_mode & 0o777,
            },
        ));
    }

    let files = unwrap_single_root_dir(raw_entries)?;
    let (manifest, warnings) = validate_package_contents(&files)?;
    Ok(SvpArchive {
        manifest,
        files,
        warnings,
    })
}

fn unwrap_single_root_dir(
    entries: Vec<(String, SvpFileEntry)>,
) -> Result<BTreeMap<String, SvpFileEntry>, SvpError> {
    let has_root_manifest = entries.iter().any(|(k, _)| k == "manifest.json");
    let strip_prefix: Option<String> = if has_root_manifest {
        None
    } else {
        let mut first_dirs = entries
            .iter()
            .filter_map(|(k, _)| k.split_once('/').map(|(d, _)| d));
        let candidate = first_dirs.next().map(str::to_owned);
        if let Some(ref prefix) = candidate
            && entries
                .iter()
                .all(|(k, _)| k.starts_with(&format!("{prefix}/")))
        {
            Some(format!("{prefix}/"))
        } else {
            None
        }
    };

    let mut map = BTreeMap::new();
    for (path, entry) in entries {
        let key = match &strip_prefix {
            Some(p) => path.strip_prefix(p).unwrap_or(&path).to_owned(),
            None => path,
        };
        if map.insert(key.clone(), entry).is_some() {
            return Err(SvpError::UnsafeEntry(format!(
                "duplicate entry '{key}' in archive"
            )));
        }
    }
    Ok(map)
}

fn parse_eocd(data: &[u8]) -> Result<(usize, usize, usize), SvpError> {
    if data.len() < 22 {
        return Err(SvpError::MalformedZip("archive too small".to_owned()));
    }
    let search_start = data.len().saturating_sub(65_557);
    let mut pos = data.len() - 22;
    loop {
        if read_u32(data, pos)? == EOCD_SIG {
            let comment_len = read_u16(data, pos + 20)? as usize;
            if pos + 22 + comment_len == data.len() {
                let count = read_u16(data, pos + 10)? as usize;
                let cd_size = read_u32(data, pos + 12)? as usize;
                let cd_offset = read_u32(data, pos + 16)? as usize;
                return Ok((cd_offset, cd_size, count));
            }
        }
        if pos == search_start {
            break;
        }
        pos -= 1;
    }
    Err(SvpError::MalformedZip(
        "missing ZIP end-of-central-directory record".to_owned(),
    ))
}

fn extract_local_entry(
    data: &[u8],
    lfh_offset: usize,
    method: u16,
    comp_size: usize,
    max_uncompressed: usize,
) -> Result<Vec<u8>, SvpError> {
    if lfh_offset + 30 > data.len() || read_u32(data, lfh_offset)? != LFH_SIG {
        return Err(SvpError::MalformedZip(
            "invalid local file header".to_owned(),
        ));
    }
    let name_len = read_u16(data, lfh_offset + 26)? as usize;
    let extra_len = read_u16(data, lfh_offset + 28)? as usize;
    let data_start = lfh_offset + 30 + name_len + extra_len;
    let data_end = data_start
        .checked_add(comp_size)
        .filter(|&end| end <= data.len())
        .ok_or_else(|| SvpError::MalformedZip("entry compressed data out of bounds".to_owned()))?;
    let comp_slice = &data[data_start..data_end];

    match method {
        0 => {
            if comp_slice.len() > max_uncompressed {
                return Err(SvpError::LimitExceeded(
                    "stored entry exceeds uncompressed size budget".to_owned(),
                ));
            }
            Ok(comp_slice.to_vec())
        }
        8 => {
            let mut decoder = DeflateDecoder::new(comp_slice).take((max_uncompressed + 1) as u64);
            let mut out = Vec::new();
            decoder
                .read_to_end(&mut out)
                .map_err(|err| SvpError::MalformedZip(format!("deflate error: {err}")))?;
            if out.len() > max_uncompressed {
                return Err(SvpError::LimitExceeded(
                    "deflated entry exceeds uncompressed size budget".to_owned(),
                ));
            }
            Ok(out)
        }
        other => Err(SvpError::MalformedZip(format!(
            "unsupported ZIP compression method {other} (only Stored=0 and Deflate=8 supported)"
        ))),
    }
}

fn read_u16(data: &[u8], offset: usize) -> Result<u16, SvpError> {
    let bytes: [u8; 2] = data
        .get(offset..offset + 2)
        .and_then(|s| s.try_into().ok())
        .ok_or_else(|| SvpError::MalformedZip("unexpected EOF".to_owned()))?;
    Ok(u16::from_le_bytes(bytes))
}

fn read_u32(data: &[u8], offset: usize) -> Result<u32, SvpError> {
    let bytes: [u8; 4] = data
        .get(offset..offset + 4)
        .and_then(|s| s.try_into().ok())
        .ok_or_else(|| SvpError::MalformedZip("unexpected EOF".to_owned()))?;
    Ok(u32::from_le_bytes(bytes))
}
