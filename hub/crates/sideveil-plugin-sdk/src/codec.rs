//! Cancellation-safe bounded NDJSON framing built on [`tokio_util::codec::LinesCodec`].

use futures_util::{Sink, SinkExt, Stream, StreamExt};
use serde::Serialize;
use serde::de::DeserializeOwned;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio_util::codec::{Framed, LinesCodec, LinesCodecError};

use crate::error::SdkError;

/// Default maximum NDJSON line length (256 KiB), matching `sideveild`'s UDS gateway limit.
pub const DEFAULT_MAX_FRAME_BYTES: usize = 256 * 1024;

/// Wraps an async transport in a bounded, cancellation-safe [`LinesCodec`] [`Framed`] adapter.
pub fn ndjson_framed<T>(io: T, max_frame_bytes: usize) -> Framed<T, LinesCodec>
where
    T: AsyncRead + AsyncWrite,
{
    Framed::new(io, LinesCodec::new_with_max_length(max_frame_bytes))
}

/// Serializes `value` as a single-line JSON string enforcing `max_frame_bytes`.
pub fn encode_json_line<T: Serialize>(
    value: &T,
    max_frame_bytes: usize,
) -> Result<String, SdkError> {
    let encoded = serde_json::to_string(value)?;
    if encoded.len() > max_frame_bytes {
        return Err(SdkError::FrameTooLarge {
            len: encoded.len(),
            max: max_frame_bytes,
        });
    }
    Ok(encoded)
}

/// Deserializes a single NDJSON frame line into `T`.
pub fn decode_json_line<T: DeserializeOwned>(line: &str) -> Result<T, SdkError> {
    Ok(serde_json::from_str(line.trim())?)
}

/// Encodes and flushes a JSON frame over a [`LinesCodec`] sink while enforcing `max_frame_bytes`.
pub async fn send_json_frame<S, T>(
    sink: &mut S,
    value: &T,
    max_frame_bytes: usize,
) -> Result<(), SdkError>
where
    S: Sink<String, Error = LinesCodecError> + Unpin,
    T: Serialize,
{
    let line = encode_json_line(value, max_frame_bytes)?;
    sink.send(line)
        .await
        .map_err(|err| map_codec_error(err, max_frame_bytes))
}

/// Receives the next non-empty NDJSON frame from a [`LinesCodec`] stream and deserializes it.
///
/// Returns `Ok(None)` on clean EOF. Because `Framed` retains partially buffered bytes inside its
/// internal `BytesMut`, dropping the returned future inside `tokio::select!` never loses data.
pub async fn recv_json_frame<S, T>(
    stream: &mut S,
    max_frame_bytes: usize,
) -> Result<Option<T>, SdkError>
where
    S: Stream<Item = Result<String, LinesCodecError>> + Unpin,
    T: DeserializeOwned,
{
    while let Some(item) = stream.next().await {
        let line = item.map_err(|err| map_codec_error(err, max_frame_bytes))?;
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        return decode_json_line(trimmed).map(Some);
    }
    Ok(None)
}

pub(crate) fn map_codec_error(err: LinesCodecError, max_frame_bytes: usize) -> SdkError {
    match err {
        LinesCodecError::MaxLineLengthExceeded => SdkError::FrameTooLarge {
            len: max_frame_bytes.saturating_add(1),
            max: max_frame_bytes,
        },
        other => SdkError::Codec(other),
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use serde::{Deserialize, Serialize};
    use tokio::io::{AsyncWriteExt, duplex};

    use super::*;

    #[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
    struct SampleFrame {
        id: u32,
        text: String,
    }

    #[tokio::test]
    async fn encode_and_decode_roundtrip_over_framed_transport() {
        let (client_io, server_io) = duplex(1024);
        let mut client = ndjson_framed(client_io, 1024);
        let mut server = ndjson_framed(server_io, 1024);

        let sent = SampleFrame {
            id: 7,
            text: "hello\nworld".to_owned(),
        };
        send_json_frame(&mut client, &sent, 1024)
            .await
            .expect("send frame");
        drop(client);

        let received: SampleFrame = recv_json_frame(&mut server, 1024)
            .await
            .expect("recv frame")
            .expect("some frame");
        assert_eq!(received, sent);

        let eof: Option<SampleFrame> = recv_json_frame(&mut server, 1024).await.expect("clean eof");
        assert_eq!(eof, None);
    }

    #[tokio::test]
    async fn rejects_oversized_frame_on_encode_and_decode() {
        let big = SampleFrame {
            id: 1,
            text: "x".repeat(200),
        };
        let encode_err = encode_json_line(&big, 64).expect_err("oversized encode must fail");
        assert!(matches!(
            encode_err,
            SdkError::FrameTooLarge { max: 64, .. }
        ));

        let (mut writer, reader_io) = duplex(1024);
        let mut reader = ndjson_framed(reader_io, 64);
        writer
            .write_all(format!("{}\n", "a".repeat(120)).as_bytes())
            .await
            .expect("write oversized line");

        let decode_err = recv_json_frame::<_, SampleFrame>(&mut reader, 64)
            .await
            .expect_err("oversized decode must fail");
        assert!(matches!(
            decode_err,
            SdkError::FrameTooLarge { max: 64, .. }
        ));
    }

    #[tokio::test]
    async fn framed_lines_codec_preserves_partial_bytes_across_select_cancellation() {
        let (mut writer, reader_io) = duplex(1024);
        let mut reader = ndjson_framed(reader_io, 1024);

        writer
            .write_all(br#"{"id":42,"te"#)
            .await
            .expect("write first half");

        let cancelled = tokio::select! {
            _ = recv_json_frame::<_, SampleFrame>(&mut reader, 1024) => false,
            () = tokio::time::sleep(Duration::from_millis(20)) => true,
        };
        assert!(cancelled, "select branch must cancel mid-frame");

        writer
            .write_all(br#"xt":"survived"}"#)
            .await
            .expect("write second half");
        writer.write_all(b"\n").await.expect("write newline");
        drop(writer);

        let frame: SampleFrame = recv_json_frame(&mut reader, 1024)
            .await
            .expect("resume frame cleanly")
            .expect("complete frame");
        assert_eq!(
            frame,
            SampleFrame {
                id: 42,
                text: "survived".to_owned(),
            }
        );
    }
}
