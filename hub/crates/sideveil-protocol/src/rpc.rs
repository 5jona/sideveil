//! JSON-RPC 2.0 wire types, method/event constants, and host/control payloads.

mod constants;
mod payloads;

use serde::{Deserialize, Serialize};
use serde_json::Value;

pub use self::constants::{error_codes, events, methods};
pub use self::payloads::*;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RpcError {
    pub code: i32,
    pub message: String,
}

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
    #[must_use]
    pub fn ok(id: impl Into<String>, result: Value) -> Self {
        Self {
            jsonrpc: default_jsonrpc(),
            id: id.into(),
            result: Some(result),
            error: None,
        }
    }

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

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HostEventEnvelope {
    pub event: String,
    #[serde(default)]
    pub payload: Option<Value>,
}

fn default_jsonrpc() -> String {
    "2.0".to_owned()
}
