//! Initial role handshake and backend outbound frame types for `hub.sock` and `app.sock`.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::rpc::RpcRequest;
use crate::{PLUGIN_API_VERSION, PROTOCOL_VERSION};

fn default_protocol_version() -> u32 {
    PROTOCOL_VERSION
}

/// First newline-delimited JSON frame sent by any UDS client after connecting.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "role", rename_all = "camelCase")]
pub enum UdsClientHello {
    /// Native CLI (`sideveil-ctl`), restricted to UID 0 on `hub.sock`.
    Ctl,
    /// Thin UID 1000 Kotlin System Sensor on `hub.sock`.
    #[serde(rename_all = "camelCase")]
    Sensor {
        /// Process ID of the connecting `:sensor` daemon.
        pid: u32,
        /// Semantic version string reported by `:sensor`.
        version: String,
        /// Wire protocol version spoken by `:sensor` (defaults to 1 when omitted).
        #[serde(default = "default_protocol_version")]
        protocol_version: u32,
    },
    /// External plugin backend (KernelSU Native Daemon on `hub.sock`, or LSPosed app on `app.sock`).
    #[serde(rename_all = "camelCase")]
    PluginBackend {
        /// Registered plugin identifier claimed by the backend.
        plugin_id: String,
        /// Optional Android package name (required on `app.sock` for `uds-app` backends).
        #[serde(default)]
        package_name: Option<String>,
        /// Wire protocol version spoken by the plugin backend (defaults to 1 when omitted).
        #[serde(default = "default_protocol_version")]
        protocol_version: u32,
    },
}

/// Immediate acknowledgement sent by `sideveild` after verifying `SO_PEERCRED` and `UdsClientHello`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UdsHandshakeAck {
    /// `true` when the peer is authorized and the session may proceed.
    pub ok: bool,
    /// Human-readable rejection reason when `ok` is `false`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    /// Negotiated Hub wire protocol version.
    #[serde(default = "default_protocol_version")]
    pub protocol_version: u32,
    /// Negotiated SideVeil plugin API version.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub api_version: Option<u32>,
}

impl UdsHandshakeAck {
    /// Constructs an accepted handshake acknowledgement.
    #[must_use]
    pub fn accepted() -> Self {
        Self {
            ok: true,
            error: None,
            protocol_version: PROTOCOL_VERSION,
            api_version: Some(PLUGIN_API_VERSION),
        }
    }

    /// Constructs a rejected handshake acknowledgement with a diagnostic reason.
    #[must_use]
    pub fn rejected(reason: impl Into<String>) -> Self {
        Self {
            ok: false,
            error: Some(reason.into()),
            protocol_version: PROTOCOL_VERSION,
            api_version: Some(PLUGIN_API_VERSION),
        }
    }
}

/// Outbound NDJSON frame pushed from `sideveild` to a connected plugin backend.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum BackendOutboundFrame {
    /// Multiplexed JSON-RPC 2.0 invocation forwarded to the backend.
    Rpc(RpcRequest),
    /// Broadcast Hub system notification pushed to `uds-native` backends.
    SystemEvent {
        /// Canonical event name (e.g. `appearance.changed`, `network.contextChanged`).
        event: String,
        /// JSON event payload.
        payload: Value,
    },
}
