//! JSON-RPC 2.0 wire types, method/event constants, and host/control payloads.

mod constants;
mod debug;
mod payloads;

use serde::{Deserialize, Serialize};
use serde_json::Value;

pub use self::constants::{error_codes, events, methods};
pub use self::debug::*;
pub use self::payloads::*;
pub use crate::sensor::DebugGestureStats;

/// JSON-RPC 2.0 error object returned in a failed [`RpcResponse`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RpcError {
    /// Numeric JSON-RPC or SideVeil-specific error code.
    pub code: i32,
    /// Human-readable diagnostic message.
    pub message: String,
}

/// JSON-RPC 2.0 request frame exchanged across `hub.sock`, `app.sock`, and Host WebSocket.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RpcRequest {
    #[serde(default = "default_jsonrpc")]
    pub jsonrpc: String,
    pub id: String,
    pub method: String,
    #[serde(default)]
    pub params: Option<Value>,
    /// Server-stamped plugin caller identity when forwarded from a `SideVeilHost` WebView.
    #[serde(default, skip_serializing_if = "Option::is_none", rename = "pluginId")]
    pub plugin_id: Option<String>,
}

impl RpcRequest {
    /// Constructs a JSON-RPC 2.0 request frame with the given correlation ID, method, and params.
    #[must_use]
    pub fn new(id: impl Into<String>, method: impl Into<String>, params: Option<Value>) -> Self {
        Self {
            jsonrpc: default_jsonrpc(),
            id: id.into(),
            method: method.into(),
            params,
            plugin_id: None,
        }
    }
}

/// JSON-RPC 2.0 response frame carrying either a `result` value or an [`RpcError`].
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct RpcResponse {
    #[serde(default = "default_jsonrpc")]
    pub jsonrpc: String,
    pub id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<RpcError>,
}

impl RpcResponse {
    /// Constructs a successful JSON-RPC 2.0 response for `id`.
    #[must_use]
    pub fn ok(id: impl Into<String>, result: Value) -> Self {
        Self {
            jsonrpc: default_jsonrpc(),
            id: id.into(),
            result: Some(result),
            error: None,
        }
    }

    /// Constructs a JSON-RPC 2.0 error response for `id`.
    #[must_use]
    pub fn err(id: impl Into<String>, code: i32, message: impl Into<String>) -> Self {
        Self {
            jsonrpc: default_jsonrpc(),
            id: id.into(),
            result: None,
            error: Some(RpcError {
                code,
                message: message.into(),
            }),
        }
    }
}

/// Server-pushed event envelope transmitted between `sideveild` and `SideVeilHost`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HostEventEnvelope {
    pub event: String,
    #[serde(default)]
    pub payload: Option<Value>,
}

fn default_jsonrpc() -> String {
    "2.0".to_owned()
}
