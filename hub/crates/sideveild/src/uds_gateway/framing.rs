//! Cancellation-safe bounded NDJSON framing and Unix socket binding helpers for `uds_gateway`.

use std::fs;
use std::io;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWriteExt, BufReader};
use tokio::net::UnixListener;

use crate::fs_util::chown_path;

pub(super) const DEFAULT_MAX_LINE_BYTES: usize = 1024 * 1024;
pub(super) const CTL_MAX_LINE_BYTES: usize = 24 * 1024 * 1024;
const SHRINK_RETAIN_CAPACITY: usize = 64 * 1024;

/// Cancellation-safe reusable frame buffer that retains allocated capacity across reads.
#[derive(Debug, Default)]
pub(super) struct LineFrameBuf {
    bytes: Vec<u8>,
    completed: bool,
}

impl LineFrameBuf {
    pub(super) fn with_capacity(capacity: usize) -> Self {
        Self {
            bytes: Vec::with_capacity(capacity),
            completed: false,
        }
    }

    fn prepare_for_next_frame(&mut self) {
        if self.completed {
            self.bytes.clear();
            if self.bytes.capacity() > DEFAULT_MAX_LINE_BYTES {
                self.bytes.shrink_to(SHRINK_RETAIN_CAPACITY);
            }
            self.completed = false;
        }
    }

    fn clear_all(&mut self) {
        self.bytes.clear();
        self.completed = false;
    }
}

/// Reads a single newline-delimited UTF-8 frame up to `max_line_bytes` into the caller-owned
/// `buf`, preserving partial bytes across `tokio::select!` cancellations and retaining `Vec`
/// capacity across completed frames.
pub(super) async fn read_bounded_line<'a, R: AsyncRead + Unpin>(
    reader: &mut BufReader<R>,
    buf: &'a mut LineFrameBuf,
    max_line_bytes: usize,
) -> io::Result<Option<&'a str>> {
    buf.prepare_for_next_frame();
    loop {
        let available = reader.fill_buf().await?;
        if available.is_empty() {
            if buf.bytes.is_empty() {
                return Ok(None);
            }
            break;
        }
        if let Some(pos) = available.iter().position(|&b| b == b'\n') {
            if buf.bytes.len() + pos > max_line_bytes {
                buf.clear_all();
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "NDJSON line exceeds maximum frame size",
                ));
            }
            buf.bytes.extend_from_slice(&available[..pos]);
            reader.consume(pos + 1);
            break;
        }
        if buf.bytes.len() + available.len() > max_line_bytes {
            buf.clear_all();
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "NDJSON line exceeds maximum frame size",
            ));
        }
        let len = available.len();
        buf.bytes.extend_from_slice(available);
        reader.consume(len);
    }
    buf.completed = true;
    std::str::from_utf8(&buf.bytes)
        .map(Some)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))
}

pub(super) async fn write_json_line<T: serde::Serialize>(
    writer: &mut tokio::net::unix::OwnedWriteHalf,
    value: &T,
) -> io::Result<()> {
    let mut buf = Vec::with_capacity(128);
    write_json_line_with_buf(writer, value, &mut buf).await
}

pub(super) async fn write_json_line_with_buf<T: serde::Serialize>(
    writer: &mut tokio::net::unix::OwnedWriteHalf,
    value: &T,
    buf: &mut Vec<u8>,
) -> io::Result<()> {
    buf.clear();
    serde_json::to_writer(&mut *buf, value)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))?;
    buf.push(b'\n');
    writer.write_all(buf).await?;
    writer.flush().await
}

pub(super) fn bind_socket(
    path: &Path,
    mode: u32,
    owner: Option<(u32, u32)>,
) -> io::Result<UnixListener> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    if path.exists() {
        let _ = fs::remove_file(path);
    }
    let listener = UnixListener::bind(path)?;
    fs::set_permissions(path, fs::Permissions::from_mode(mode))?;
    if let Some((uid, gid)) = owner {
        let _ = chown_path(path, uid, gid);
    }
    Ok(listener)
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use tokio::io::{AsyncWriteExt, BufReader, duplex};

    use super::*;

    #[tokio::test]
    async fn read_bounded_line_preserves_partial_frame_across_select_cancellation() {
        let (mut writer, reader_half) = duplex(1024);
        let mut reader = BufReader::new(reader_half);
        let mut line_buf = LineFrameBuf::with_capacity(64);

        writer
            .write_all(br#"{"jsonrpc":"2.0","meth"#)
            .await
            .expect("write first chunk");

        let cancelled = tokio::select! {
            _ = read_bounded_line(&mut reader, &mut line_buf, DEFAULT_MAX_LINE_BYTES) => false,
            () = tokio::time::sleep(Duration::from_millis(20)) => true,
        };
        assert!(cancelled, "select branch should cancel mid-frame");
        assert_eq!(line_buf.bytes, br#"{"jsonrpc":"2.0","meth"#);

        writer
            .write_all(b"od\":\"ping\"}\n{\"next\":true}\n")
            .await
            .expect("write remaining chunks");
        drop(writer);

        let first = read_bounded_line(&mut reader, &mut line_buf, DEFAULT_MAX_LINE_BYTES)
            .await
            .expect("resume line")
            .expect("first line");
        assert_eq!(first, r#"{"jsonrpc":"2.0","method":"ping"}"#);
        let cap_after_first = line_buf.bytes.capacity();
        assert!(cap_after_first >= 64);

        let second = read_bounded_line(&mut reader, &mut line_buf, DEFAULT_MAX_LINE_BYTES)
            .await
            .expect("second line")
            .expect("second line");
        assert_eq!(second, r#"{"next":true}"#);
        assert_eq!(line_buf.bytes.capacity(), cap_after_first);

        assert_eq!(
            read_bounded_line(&mut reader, &mut line_buf, DEFAULT_MAX_LINE_BYTES)
                .await
                .expect("eof"),
            None
        );
    }

    #[tokio::test]
    async fn read_bounded_line_rejects_oversized_frame() {
        let (mut writer, reader_half) = duplex(DEFAULT_MAX_LINE_BYTES + 1024);
        let mut reader = BufReader::new(reader_half);
        let mut line_buf = LineFrameBuf::default();

        let oversized = vec![b'a'; DEFAULT_MAX_LINE_BYTES + 16];
        let write_task = tokio::spawn(async move {
            let _ = writer.write_all(&oversized).await;
            let _ = writer.write_all(b"\n").await;
        });

        let err = read_bounded_line(&mut reader, &mut line_buf, DEFAULT_MAX_LINE_BYTES)
            .await
            .expect_err("oversized frame should fail");
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(line_buf.bytes.is_empty());
        let _ = write_task.await;
    }

    #[tokio::test]
    async fn read_bounded_line_allows_large_ctl_payloads_under_ctl_limit() {
        let payload_len = DEFAULT_MAX_LINE_BYTES + 4096;
        let (mut writer, reader_half) = duplex(64 * 1024);
        let mut reader = BufReader::new(reader_half);
        let mut line_buf = LineFrameBuf::default();
        let write_task = tokio::spawn(async move {
            let chunk = vec![b'x'; 32 * 1024];
            let mut rem = payload_len;
            while rem > 0 {
                let n = rem.min(chunk.len());
                writer.write_all(&chunk[..n]).await.expect("write chunk");
                rem -= n;
            }
            writer.write_all(b"\n").await.expect("write nl");
        });
        let line = read_bounded_line(&mut reader, &mut line_buf, CTL_MAX_LINE_BYTES)
            .await
            .expect("ctl large line")
            .expect("some line");
        assert_eq!(line.len(), payload_len);
        let _ = write_task.await;
    }
}
