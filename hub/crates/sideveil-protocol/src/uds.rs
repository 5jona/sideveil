//! Initial role handshake and backend outbound frame types for `hub.sock` and `app.sock`.

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::rpc::RpcRequest;

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
    },
    /// External plugin backend (KernelSU Native Daemon on `hub.sock`, or LSPosed app on `app.sock`).
    #[serde(rename_all = "camelCase")]
    PluginBackend {
        /// Registered plugin identifier claimed by the backend.
        plugin_id: String,
        /// Optional Android package name (required on `app.sock` for `uds-app` backends).
        #[serde(default)]
        package_name: Option<String>,
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
}

impl UdsHandshakeAck {
    /// Constructs an accepted handshake acknowledgement.
    #[must_use]
    pub fn accepted() -> Self {
        Self {
            ok: true,
            error: None,
        }
    }

    /// Constructs a rejected handshake acknowledgement with a diagnostic reason.
    #[must_use]
    pub fn rejected(reason: impl Into<String>) -> Self {
        Self {
            ok: false,
            error: Some(reason.into()),
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
