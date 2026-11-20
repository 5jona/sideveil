//! Zero-polling Linux `pidfd_open` + Tokio `AsyncFd` process exit watcher.

use std::io;
use std::os::fd::{FromRawFd, OwnedFd, RawFd};

use tokio::io::unix::AsyncFd;

#[derive(Debug)]
pub struct PidFd {
    pid: u32,
    async_fd: AsyncFd<OwnedFd>,
}

impl PidFd {
    /// Opens a non-blocking Linux pidfd for `pid` so exits are delivered via `epoll`.
    pub fn open(pid: u32) -> io::Result<Self> {
        let pid_t = libc::pid_t::try_from(pid)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "pid out of range"))?;
        let raw_fd = unsafe {
            // SAFETY: `SYS_pidfd_open` takes a valid `pid_t` and flags (`PIDFD_NONBLOCK` == `O_NONBLOCK`),
            // returning either a new owned file descriptor or `-1` with `errno` set.
            libc::syscall(
                libc::SYS_pidfd_open,
                pid_t,
                libc::O_NONBLOCK as libc::c_uint,
            )
        };
        if raw_fd < 0 {
            return Err(io::Error::last_os_error());
        }
        let owned = unsafe {
            // SAFETY: `raw_fd` was just returned by a successful `pidfd_open` syscall and is uniquely owned here.
            OwnedFd::from_raw_fd(raw_fd as RawFd)
        };
        let async_fd = unsafe {
            // SAFETY: `owned` is a valid non-blocking pidfd whose ownership moves exclusively into `AsyncFd`,
            // ensuring the descriptor remains open and is not closed externally while registered.
            AsyncFd::register(owned)?
        };
        Ok(Self { pid, async_fd })
    }

    #[must_use]
    pub fn pid(&self) -> u32 {
        self.pid
    }

    /// Asynchronously waits until the kernel signals process exit (`EPOLLIN`) on the pidfd.
    pub async fn wait_exit(&self) -> io::Result<()> {
        let _guard = self.async_fd.readable().await?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::time::{Duration, Instant};

    use tokio::process::Command;

    use super::*;

    #[tokio::test]
    async fn pidfd_notifies_promptly_when_child_exits() {
        let mut child = Command::new("sh")
            .args(["-c", "sleep 0.05"])
            .spawn()
            .expect("spawn short-lived shell");
        let pid = child.id().expect("child pid");
        let pidfd = PidFd::open(pid).expect("open pidfd");

        let started = Instant::now();
        pidfd.wait_exit().await.expect("wait_exit");
        let elapsed = started.elapsed();

        let status = child.wait().await.expect("reap child");
        assert!(status.success());
        assert!(
            elapsed < Duration::from_secs(2),
            "pidfd wakeup took unexpectedly long: {elapsed:?}"
        );
    }
}
