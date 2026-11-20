//! Initial role handshake types for `hub.sock` and `app.sock` Unix Domain Socket peers.

use serde::{Deserialize, Serialize};

/// First newline-delimited JSON frame sent by any UDS client after connecting.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "role", rename_all = "camelCase")]
pub enum UdsClientHello {
    /// Native CLI (`sideveil-ctl`), restricted to UID 0 on `hub.sock`.
    Ctl,
    /// Thin UID 1000 Kotlin System Sensor on `hub.sock`.
    #[serde(rename_all = "camelCase")]
    Sensor { pid: u32, version: String },
    /// External plugin backend (KernelSU Native Daemon on `hub.sock`, or LSPosed app on `app.sock`).
    #[serde(rename_all = "camelCase")]
    PluginBackend {
        plugin_id: String,
        #[serde(default)]
        package_name: Option<String>,
    },
}

/// Immediate acknowledgement sent by `sideveild` after verifying `SO_PEERCRED` and `UdsClientHello`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UdsHandshakeAck {
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

impl UdsHandshakeAck {
    #[must_use]
    pub fn accepted() -> Self {
        Self {
            ok: true,
            error: None,
        }
    }

    #[must_use]
    pub fn rejected(reason: impl Into<String>) -> Self {
        Self {
            ok: false,
            error: Some(reason.into()),
        }
    }
}
