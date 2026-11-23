//! Shared atomic file persistence and POSIX ownership helpers.

use std::ffi::CString;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Write};
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

static TMP_SEQ: AtomicU64 = AtomicU64::new(1);

/// Generates a process- and concurrency-unique dot-prefixed temporary path next to `target`.
#[must_use]
pub fn unique_tmp_path(target: &Path) -> PathBuf {
    let file_name = target
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("file");
    let pid = std::process::id();
    let seq = TMP_SEQ.fetch_add(1, Ordering::Relaxed);
    let tmp_name = format!(".{file_name}.tmp.{pid}.{seq}");
    match target.parent() {
        Some(parent) if !parent.as_os_str().is_empty() => parent.join(tmp_name),
        _ => PathBuf::from(tmp_name),
    }
}

/// Atomically writes `bytes` to `target` via unique temp file -> `sync_all` -> mode/chown -> rename.
pub fn write_file_atomic(
    target: &Path,
    bytes: &[u8],
    mode: u32,
    owner: Option<(u32, u32)>,
    sync_parent: bool,
) -> io::Result<()> {
    let parent = target
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "target path has no parent"))?;
    fs::create_dir_all(parent)?;

    let tmp_path = unique_tmp_path(target);
    let write_res = (|| -> io::Result<()> {
        let mut file = OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&tmp_path)?;
        file.write_all(bytes)?;
        file.sync_all()?;
        fs::set_permissions(&tmp_path, fs::Permissions::from_mode(mode))?;
        if let Some((uid, gid)) = owner {
            let _ = chown_path(&tmp_path, uid, gid);
        }
        drop(file);
        fs::rename(&tmp_path, target)?;
        if sync_parent && let Ok(dir) = File::open(parent) {
            let _ = dir.sync_all();
        }
        Ok(())
    })();

    if write_res.is_err() {
        let _ = fs::remove_file(&tmp_path);
    }
    write_res
}

/// Sets POSIX `(uid, gid)` ownership on `path` via `libc::chown`.
pub fn chown_path(path: &Path, uid: u32, gid: u32) -> io::Result<()> {
    let c_path = CString::new(path.as_os_str().as_bytes())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "path contains interior NUL"))?;
    let rc = unsafe {
        // SAFETY: `c_path` is a valid NUL-terminated C string.
        libc::chown(c_path.as_ptr(), uid, gid)
    };
    if rc == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unique_tmp_path_is_distinct_across_calls() {
        let target = Path::new("/tmp/sideveil-example/config.json");
        let first = unique_tmp_path(target);
        let second = unique_tmp_path(target);
        assert_ne!(first, second);
        assert_eq!(first.parent(), target.parent());
        assert!(
            first
                .file_name()
                .and_then(|n| n.to_str())
                .is_some_and(|n| n.starts_with(".config.json.tmp."))
        );
    }

    #[test]
    fn write_file_atomic_writes_and_replaces_cleanly() {
        let dir = std::env::temp_dir().join(format!(
            "sideveil-fs-util-{}-{}",
            std::process::id(),
            TMP_SEQ.fetch_add(1, Ordering::Relaxed)
        ));
        let target = dir.join("state.json");
        write_file_atomic(&target, b"{\"v\":1}\n", 0o600, None, true).expect("first write");
        assert_eq!(fs::read_to_string(&target).expect("read v1"), "{\"v\":1}\n");

        write_file_atomic(&target, b"{\"v\":2}\n", 0o644, None, true).expect("second write");
        assert_eq!(fs::read_to_string(&target).expect("read v2"), "{\"v\":2}\n");

        let mode = fs::metadata(&target).expect("meta").permissions().mode() & 0o777;
        assert_eq!(mode, 0o644);
        let _ = fs::remove_dir_all(&dir);
    }
}
