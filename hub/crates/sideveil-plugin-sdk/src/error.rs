//! Structured error types for SideVeil plugin daemon SDK operations and RPC handlers.

use std::path::PathBuf;
use std::time::Duration;

use serde_json::Value;
use sideveil_protocol::rpc::{RpcResponse, error_codes};
use thiserror::Error;
use tokio_util::codec::LinesCodecError;

/// Transport, framing, configuration, and lifecycle errors emitted by `sideveil-plugin-sdk`.
#[derive(Debug, Error)]
pub enum SdkError {
    /// Invalid [`crate::PluginBackendConfig`] parameter.
    #[error("invalid plugin backend configuration: {0}")]
    InvalidConfig(String),

    /// Connecting to the Hub UDS socket exceeded the configured timeout.
    #[error("timed out after {timeout:?} connecting to hub socket {}", path.display())]
    ConnectTimeout {
        /// Target Unix domain socket path.
        path: PathBuf,
        /// Configured connection timeout.
        timeout: Duration,
    },

    /// Handshake exchange exceeded the configured timeout.
    #[error("timed out after {timeout:?} waiting for hub handshake acknowledgement")]
    HandshakeTimeout {
        /// Configured handshake timeout.
        timeout: Duration,
    },

    /// Hub explicitly rejected `UdsClientHello::PluginBackend`.
    #[error("hub rejected plugin backend handshake: {0}")]
    HandshakeRejected(String),

    /// Underlying Unix domain socket closed cleanly before a frame could complete.
    #[error("hub socket closed")]
    ConnectionClosed,

    /// Outbound or inbound NDJSON line exceeded `max_frame_bytes`.
    #[error("NDJSON frame length {len} exceeds maximum {max} bytes")]
    FrameTooLarge {
        /// Observed frame length in bytes (or `max + 1` when detected during streaming decode).
        len: usize,
        /// Configured maximum frame length in bytes.
        max: usize,
    },

    /// `LinesCodec` framing or I/O error.
    #[error("NDJSON codec error: {0}")]
    Codec(#[from] LinesCodecError),

    /// JSON serialization or deserialization failure.
    #[error("JSON serialization error: {0}")]
    Json(#[from] serde_json::Error),

    /// Raw I/O error on the Unix domain socket.
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    /// Outbound event channel closed because the backend runner stopped.
    #[error("plugin backend runner channel closed")]
    ChannelClosed,

    /// Non-blocking ephemeral event queue is full; frame was dropped to preserve backpressure isolation.
    #[error("ephemeral event queue full; frame dropped")]
    EphemeralQueueFull,

    /// Operation was aborted via [`tokio_util::sync::CancellationToken`].
    #[error("plugin backend cancelled")]
    Cancelled,
}

/// Structured JSON-RPC 2.0 error returned by a [`crate::PluginRpcHandler`].
#[derive(Debug, Clone, PartialEq, Error)]
#[error("RPC error {code}: {message}")]
pub struct PluginRpcError {
    /// Numeric JSON-RPC 2.0 or SideVeil domain error code.
    pub code: i32,
    /// Human-readable error description.
    pub message: String,
    /// Optional structured diagnostic payload attached by the plugin handler.
    pub data: Option<Value>,
}

impl PluginRpcError {
    /// Creates a new structured RPC error with the given numeric `code` and `message`.
    #[must_use]
    pub fn new(code: i32, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
            data: None,
        }
    }

    /// Attaches an optional JSON `data` payload to this RPC error.
    #[must_use]
    pub fn with_data(mut self, data: Value) -> Self {
        self.data = Some(data);
        self
    }

    /// Creates a standard JSON-RPC `INVALID_PARAMS` (`-32602`) error.
    #[must_use]
    pub fn invalid_params(message: impl Into<String>) -> Self {
        Self::new(error_codes::INVALID_PARAMS, message)
    }

    /// Creates a standard JSON-RPC `METHOD_NOT_FOUND` (`-32601`) error.
    #[must_use]
    pub fn method_not_found(method: impl AsRef<str>) -> Self {
        Self::new(
            error_codes::METHOD_NOT_FOUND,
            format!("unknown plugin RPC method: {}", method.as_ref()),
        )
    }

    /// Creates a standard SideVeil `INTERNAL_ERROR` (`-32000`) error.
    #[must_use]
    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(error_codes::INTERNAL_ERROR, message)
    }

    /// Converts this error into a wire [`RpcResponse`] correlated with `id`.
    #[must_use]
    pub fn into_rpc_response(self, id: impl Into<String>) -> RpcResponse {
        RpcResponse::err(id, self.code, self.message)
    }
}
