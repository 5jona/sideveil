//! Async Linux `inotify` directory watcher shared by `ConfigStore` and `PluginRegistry`.

use std::collections::{HashMap, HashSet};
use std::ffi::CString;
use std::fs;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};

use tokio::io::unix::AsyncFd;

/// Default mask covering atomic renames, writes, creations, and deletions.
pub const DIR_CHANGE_MASK: u32 = libc::IN_CLOSE_WRITE
    | libc::IN_MOVED_TO
    | libc::IN_MOVED_FROM
    | libc::IN_CREATE
    | libc::IN_DELETE
    | libc::IN_MODIFY;

#[derive(Debug)]
pub struct InotifyDirWatcher {
    async_fd: AsyncFd<OwnedFd>,
}

impl InotifyDirWatcher {
    pub fn new(dir: &Path, mask: u32) -> io::Result<Self> {
        let fd = unsafe {
            // SAFETY: `inotify_init1` takes valid flag bits and returns a new fd or -1.
            libc::inotify_init1(libc::IN_NONBLOCK | libc::IN_CLOEXEC)
        };
        if fd < 0 {
            return Err(io::Error::last_os_error());
        }
        let owned = unsafe {
            // SAFETY: `fd` is newly created and owned solely by this struct.
            OwnedFd::from_raw_fd(fd as RawFd)
        };
        let c_dir = CString::new(dir.as_os_str().as_bytes())
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "dir contains NUL"))?;
        let wd = unsafe {
            // SAFETY: `owned` is a valid inotify fd and `c_dir` is NUL-terminated.
            libc::inotify_add_watch(owned.as_raw_fd(), c_dir.as_ptr(), mask)
        };
        if wd < 0 {
            return Err(io::Error::last_os_error());
        }
        let async_fd = unsafe {
            // SAFETY: `owned` is a valid non-blocking inotify fd moved exclusively into `AsyncFd`,
            // guaranteeing it stays open for the lifetime of the registration.
            AsyncFd::register(owned)?
        };
        Ok(Self { async_fd })
    }

    /// Waits until an event fires matching `target_name` (exact filename match).
    pub async fn wait_for_name(&self, target_name: &[u8]) -> io::Result<()> {
        let mut buf = [0u8; 4096];
        loop {
            let mut guard = self.async_fd.readable().await?;
            match guard.try_io(|inner| read_inotify(inner.as_raw_fd(), &mut buf)) {
                Ok(Ok(bytes)) => {
                    if inotify_buffer_matches(&buf[..bytes], Some(target_name)) {
                        return Ok(());
                    }
                }
                Ok(Err(err)) => return Err(err),
                Err(_would_block) => continue,
            }
        }
    }

    /// Waits until any event fires inside the watched directory (or synced subdirectories).
    pub async fn wait_any(&self) -> io::Result<()> {
        let mut buf = [0u8; 4096];
        loop {
            let mut guard = self.async_fd.readable().await?;
            match guard.try_io(|inner| read_inotify(inner.as_raw_fd(), &mut buf)) {
                Ok(Ok(bytes)) => {
                    if inotify_buffer_matches(&buf[..bytes], None) {
                        return Ok(());
                    }
                }
                Ok(Err(err)) => return Err(err),
                Err(_would_block) => continue,
            }
        }
    }

    /// Drains any queued inotify events non-blockingly (used after debounce sleeps).
    pub fn drain_pending(&self) {
        let mut buf = [0u8; 4096];
        while read_inotify(self.async_fd.get_ref().as_raw_fd(), &mut buf).is_ok() {}
    }

    /// Synchronizes watch descriptors for all immediate non-dot subdirectories under `root`,
    /// ensuring in-place edits inside `<root>/<plugin_id>/manifest.json` trigger `wait_any()`.
    pub fn sync_subdirectories(
        &self,
        root: &Path,
        mask: u32,
        watched_children: &mut HashMap<PathBuf, libc::c_int>,
    ) {
        let mut live_dirs = HashSet::new();
        if let Ok(entries) = fs::read_dir(root) {
            for entry in entries.flatten() {
                let path = entry.path();
                if !path.is_dir() {
                    continue;
                }
                if entry
                    .file_name()
                    .to_str()
                    .is_some_and(|name| name.starts_with('.'))
                {
                    continue;
                }
                live_dirs.insert(path);
            }
        }

        let raw_fd = self.async_fd.get_ref().as_raw_fd();
        watched_children.retain(|path, wd| {
            if live_dirs.contains(path) {
                true
            } else {
                remove_watch(raw_fd, *wd);
                false
            }
        });

        for dir in live_dirs {
            if watched_children.contains_key(&dir) {
                continue;
            }
            let Ok(c_dir) = CString::new(dir.as_os_str().as_bytes()) else {
                continue;
            };
            // SAFETY: `raw_fd` is a valid inotify fd and `c_dir` is NUL-terminated.
            let wd = unsafe { libc::inotify_add_watch(raw_fd, c_dir.as_ptr(), mask) };
            if wd >= 0 {
                watched_children.insert(dir, wd);
            }
        }
    }
}

fn remove_watch(raw_fd: RawFd, wd: libc::c_int) {
    #[cfg(target_os = "android")]
    if let Ok(wd_u32) = u32::try_from(wd) {
        // SAFETY: `raw_fd` is a valid open inotify descriptor and `wd_u32` was registered on it.
        let _ = unsafe { libc::inotify_rm_watch(raw_fd, wd_u32) };
    }
    #[cfg(not(target_os = "android"))]
    {
        // SAFETY: `raw_fd` is a valid open inotify descriptor and `wd` was registered on it.
        let _ = unsafe { libc::inotify_rm_watch(raw_fd, wd) };
    }
}

fn read_inotify(fd: RawFd, buf: &mut [u8]) -> io::Result<usize> {
    let n = unsafe {
        // SAFETY: `buf` is a valid mutable byte slice of length `buf.len()`.
        libc::read(fd, buf.as_mut_ptr().cast::<libc::c_void>(), buf.len())
    };
    if n < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(n as usize)
    }
}

fn inotify_buffer_matches(buf: &[u8], target_name: Option<&[u8]>) -> bool {
    let header_len = std::mem::size_of::<libc::inotify_event>();
    let mut offset = 0usize;
    let mut saw_any = false;
    while offset + header_len <= buf.len() {
        let event = unsafe {
            // SAFETY: `buf[offset..offset + header_len]` is in bounds; `read_unaligned` handles arbitrary alignment.
            std::ptr::read_unaligned(buf.as_ptr().add(offset).cast::<libc::inotify_event>())
        };
        saw_any = true;
        let name_len = event.len as usize;
        let name_start = offset + header_len;
        let next_offset = name_start.saturating_add(name_len);
        if next_offset > buf.len() {
            break;
        }
        if let Some(expected) = target_name {
            let raw_name = &buf[name_start..next_offset];
            let trimmed = raw_name.split(|&b| b == 0).next().unwrap_or_default();
            if trimmed == expected {
                return true;
            }
        }
        offset = next_offset;
    }
    target_name.is_none() && saw_any
}
