//! Cancellation-safe bounded NDJSON framing and Unix socket binding helpers for `uds_gateway`.

use std::fs;
use std::io;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;

use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWriteExt, BufReader};
use tokio::net::UnixListener;

use crate::fs_util::chown_path;

const MAX_LINE_BYTES: usize = 256 * 1024;

/// Reads a single newline-delimited UTF-8 frame up to `MAX_LINE_BYTES` into the caller-owned
/// `buf`, preserving partial bytes across `tokio::select!` cancellations.
///
/// Because `buf` lives across polls in the caller, dropping this future between `fill_buf()`
/// completions never loses already-consumed bytes; subsequent calls seamlessly resume reading
/// until `\n` or EOF.
pub(super) async fn read_bounded_line<R: AsyncRead + Unpin>(
    reader: &mut BufReader<R>,
    buf: &mut Vec<u8>,
) -> io::Result<Option<String>> {
    loop {
        let available = reader.fill_buf().await?;
        if available.is_empty() {
            if buf.is_empty() {
                return Ok(None);
            }
            break;
        }
        if let Some(pos) = available.iter().position(|&b| b == b'\n') {
            if buf.len() + pos > MAX_LINE_BYTES {
                buf.clear();
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "NDJSON line exceeds maximum frame size",
                ));
            }
            buf.extend_from_slice(&available[..pos]);
            reader.consume(pos + 1);
            break;
        }
        if buf.len() + available.len() > MAX_LINE_BYTES {
            buf.clear();
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "NDJSON line exceeds maximum frame size",
            ));
        }
        let len = available.len();
        buf.extend_from_slice(available);
        reader.consume(len);
    }
    let completed = std::mem::take(buf);
    String::from_utf8(completed)
        .map(Some)
        .map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))
}

pub(super) async fn write_json_line<T: serde::Serialize>(
    writer: &mut tokio::net::unix::OwnedWriteHalf,
    value: &T,
) -> io::Result<()> {
    let mut bytes =
        serde_json::to_vec(value).map_err(|err| io::Error::new(io::ErrorKind::InvalidData, err))?;
    bytes.push(b'\n');
    writer.write_all(&bytes).await?;
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
        let mut line_buf = Vec::new();

        writer
            .write_all(br#"{"jsonrpc":"2.0","meth"#)
            .await
            .expect("write first chunk");

        let cancelled = tokio::select! {
            _ = read_bounded_line(&mut reader, &mut line_buf) => false,
            () = tokio::time::sleep(Duration::from_millis(20)) => true,
        };
        assert!(cancelled, "select branch should cancel mid-frame");
        assert_eq!(line_buf, br#"{"jsonrpc":"2.0","meth"#);

        writer
            .write_all(b"od\":\"ping\"}\n{\"next\":true}\n")
            .await
            .expect("write remaining chunks");
        drop(writer);

        let first = read_bounded_line(&mut reader, &mut line_buf)
            .await
            .expect("resume line")
            .expect("first line");
        assert_eq!(first, r#"{"jsonrpc":"2.0","method":"ping"}"#);

        let second = read_bounded_line(&mut reader, &mut line_buf)
            .await
            .expect("second line")
            .expect("second line");
        assert_eq!(second, r#"{"next":true}"#);

        assert_eq!(
            read_bounded_line(&mut reader, &mut line_buf)
                .await
                .expect("eof"),
            None
        );
    }

    #[tokio::test]
    async fn read_bounded_line_rejects_oversized_frame() {
        let (mut writer, reader_half) = duplex(MAX_LINE_BYTES + 1024);
        let mut reader = BufReader::new(reader_half);
        let mut line_buf = Vec::new();

        let oversized = vec![b'a'; MAX_LINE_BYTES + 16];
        let write_task = tokio::spawn(async move {
            let _ = writer.write_all(&oversized).await;
            let _ = writer.write_all(b"\n").await;
        });

        let err = read_bounded_line(&mut reader, &mut line_buf)
            .await
            .expect_err("oversized frame should fail");
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
        assert!(line_buf.is_empty());
        let _ = write_task.await;
    }
}
