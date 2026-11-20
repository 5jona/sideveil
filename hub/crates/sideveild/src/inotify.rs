//! Async Linux `inotify` directory watcher shared by `ConfigStore` and `PluginRegistry`.

use std::ffi::CString;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::ffi::OsStrExt;
use std::path::Path;

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

    /// Waits until any event fires inside the watched directory.
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
