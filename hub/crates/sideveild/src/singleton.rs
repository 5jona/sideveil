//! Kernel-level single-instance `flock(2)` guard for `sideveild`.

use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::os::fd::AsRawFd;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};

/// Default lock filename placed inside `sideveild`'s runtime directory (`${run_dir}/sideveild.lock`).
pub const LOCK_FILE_NAME: &str = "sideveild.lock";

const LOCK_FILE_MODE: u32 = 0o600;
const MAX_HOLDER_PID_BYTES: u64 = 64;

/// Failure returned when acquiring the `sideveild` singleton lock.
#[derive(Debug, thiserror::Error)]
pub enum SingletonError {
    /// Another live `sideveild` process already holds the exclusive `flock(2)` lock.
    #[error(
        "another sideveild instance already holds lock {} (holder_pid={})",
        path.display(),
        format_holder_pid(*holder_pid)
    )]
    AlreadyRunning {
        path: PathBuf,
        holder_pid: Option<u32>,
    },
    /// Underlying filesystem or syscall I/O failure while opening or locking the file.
    #[error("failed to acquire singleton lock {}: {source}", path.display())]
    Io {
        path: PathBuf,
        #[source]
        source: io::Error,
    },
}

impl From<SingletonError> for io::Error {
    fn from(err: SingletonError) -> Self {
        let kind = match &err {
            SingletonError::AlreadyRunning { .. } => io::ErrorKind::WouldBlock,
            SingletonError::Io { source, .. } => source.kind(),
        };
        io::Error::new(kind, err)
    }
}

/// Holds an exclusive non-blocking kernel `flock(2)` lock on `${run_dir}/sideveild.lock`.
///
/// The kernel automatically releases the lock when this guard is dropped or when the
/// owning process exits (including abrupt `SIGKILL` termination). The lock file itself
/// is intentionally left on disk to avoid unlink-vs-flock inode races.
#[derive(Debug)]
pub struct SingletonGuard(File);

impl SingletonGuard {
    /// Opens `lock_path` (mode `0600`) and acquires `flock(fd, LOCK_EX | LOCK_NB)`.
    ///
    /// On success, truncates the lock file and records `std::process::id()`.
    /// On contention (`EWOULDBLOCK`), reads the current holder PID from the lock file
    /// and returns [`SingletonError::AlreadyRunning`].
    pub fn acquire(lock_path: impl AsRef<Path>) -> Result<Self, SingletonError> {
        let path = lock_path.as_ref().to_path_buf();
        let mut file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .mode(LOCK_FILE_MODE)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&path)
            .map_err(|source| SingletonError::Io {
                path: path.clone(),
                source,
            })?;

        let fd = file.as_raw_fd();
        loop {
            let rc = unsafe {
                // SAFETY: `fd` is a valid open file descriptor owned by `file` for the duration
                // of this syscall, and `LOCK_EX | LOCK_NB` is a valid `flock(2)` operation mask.
                libc::flock(fd, libc::LOCK_EX | libc::LOCK_NB)
            };
            if rc == 0 {
                break;
            }
            let err = io::Error::last_os_error();
            if err.kind() == io::ErrorKind::Interrupted {
                continue;
            }
            if err.kind() == io::ErrorKind::WouldBlock
                || err.raw_os_error() == Some(libc::EWOULDBLOCK)
            {
                let holder_pid = read_holder_pid(&mut file);
                return Err(SingletonError::AlreadyRunning { path, holder_pid });
            }
            return Err(SingletonError::Io { path, source: err });
        }

        file.set_permissions(fs::Permissions::from_mode(LOCK_FILE_MODE))
            .map_err(|source| SingletonError::Io {
                path: path.clone(),
                source,
            })?;
        file.set_len(0).map_err(|source| SingletonError::Io {
            path: path.clone(),
            source,
        })?;
        file.seek(SeekFrom::Start(0))
            .map_err(|source| SingletonError::Io {
                path: path.clone(),
                source,
            })?;

        let pid_text = format!("{}\n", std::process::id());
        file.write_all(pid_text.as_bytes())
            .map_err(|source| SingletonError::Io {
                path: path.clone(),
                source,
            })?;
        file.flush()
            .map_err(|source| SingletonError::Io { path, source })?;

        Ok(Self(file))
    }

    /// Returns a reference to the locked file handle.
    #[must_use]
    pub fn file(&self) -> &File {
        &self.0
    }
}

fn read_holder_pid(file: &mut File) -> Option<u32> {
    if file.seek(SeekFrom::Start(0)).is_err() {
        return None;
    }
    let mut raw = String::new();
    if Read::by_ref(file)
        .take(MAX_HOLDER_PID_BYTES)
        .read_to_string(&mut raw)
        .is_err()
    {
        return None;
    }
    raw.trim().parse::<u32>().ok()
}

fn format_holder_pid(holder_pid: Option<u32>) -> String {
    holder_pid.map_or_else(|| "unknown".to_owned(), |pid| pid.to_string())
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};

    use super::*;

    static NEXT_TEST_ID: AtomicU64 = AtomicU64::new(1);

    fn temp_lock_dir() -> PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |d| d.as_nanos());
        let seq = NEXT_TEST_ID.fetch_add(1, Ordering::Relaxed);
        let dir = std::env::temp_dir().join(format!(
            "sideveild-singleton-test-{}-{nanos}-{seq}",
            std::process::id()
        ));
        fs::create_dir_all(&dir).expect("create temp lock dir");
        dir
    }

    #[test]
    fn acquires_rejects_second_holder_with_pid_and_reacquires_after_drop() {
        let dir = temp_lock_dir();
        let lock_path = dir.join(LOCK_FILE_NAME);
        let current_pid = std::process::id();

        let first = SingletonGuard::acquire(&lock_path).expect("first acquire should succeed");
        let mode = first
            .file()
            .metadata()
            .expect("lock metadata")
            .permissions()
            .mode()
            & 0o777;
        assert_eq!(mode, LOCK_FILE_MODE);

        let recorded = fs::read_to_string(&lock_path).expect("read lock file pid");
        assert_eq!(recorded.trim(), current_pid.to_string());

        let second_err =
            SingletonGuard::acquire(&lock_path).expect_err("second acquire must fail while locked");
        match &second_err {
            SingletonError::AlreadyRunning { path, holder_pid } => {
                assert_eq!(path, &lock_path);
                assert_eq!(*holder_pid, Some(current_pid));
            }
            other => panic!("expected AlreadyRunning, got {other:?}"),
        }
        assert!(
            second_err.to_string().contains(&current_pid.to_string()),
            "error message should include holder PID: {second_err}"
        );

        drop(first);

        let third = SingletonGuard::acquire(&lock_path).expect("acquire should succeed after drop");
        let recorded_after = fs::read_to_string(&lock_path).expect("read re-acquired lock pid");
        assert_eq!(recorded_after.trim(), current_pid.to_string());
        drop(third);

        let _ = fs::remove_dir_all(&dir);
    }
}
