//! Cloneable plugin backend handle exposing dual-priority event emission, HUD helpers, and connection state.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use serde_json::Value;
use sideveil_protocol::hud::NativeHudScene;
use sideveil_protocol::plugin::PluginBackendEvent;
use sideveil_protocol::rpc::methods;
use tokio::sync::mpsc;
use tokio::sync::mpsc::error::TrySendError;
use tokio_util::sync::CancellationToken;

use crate::error::SdkError;

/// Cloneable handle to a running SideVeil plugin backend session.
///
/// Survives transparent Hub reconnects and routes outbound events across two isolated queues:
/// - **Reliable** (`ephemeral = false`): bounded backpressured channel for state/control events.
/// - **Ephemeral** (`ephemeral = true`): non-blocking bounded channel for high-frequency telemetry
///   or Native HUD scenes; drops immediately with [`SdkError::EphemeralQueueFull`] under saturation.
#[derive(Debug, Clone)]
pub struct PluginBackendHandle {
    plugin_id: Arc<str>,
    connected: Arc<AtomicBool>,
    reliable_tx: mpsc::Sender<PluginBackendEvent>,
    ephemeral_tx: mpsc::Sender<PluginBackendEvent>,
    cancel: CancellationToken,
}

impl PluginBackendHandle {
    pub(crate) fn new(
        plugin_id: Arc<str>,
        reliable_tx: mpsc::Sender<PluginBackendEvent>,
        ephemeral_tx: mpsc::Sender<PluginBackendEvent>,
        cancel: CancellationToken,
    ) -> Self {
        Self {
            plugin_id,
            connected: Arc::new(AtomicBool::new(false)),
            reliable_tx,
            ephemeral_tx,
            cancel,
        }
    }

    /// Returns the plugin identifier bound to this backend handle.
    #[must_use]
    pub fn plugin_id(&self) -> &str {
        &self.plugin_id
    }

    /// Returns `true` when the backend currently holds an active authenticated Hub session.
    #[must_use]
    pub fn is_connected(&self) -> bool {
        self.connected.load(Ordering::Acquire)
    }

    /// Returns a clone of the root [`CancellationToken`] controlling the backend lifecycle.
    #[must_use]
    pub fn cancellation_token(&self) -> CancellationToken {
        self.cancel.clone()
    }

    /// Triggers graceful shutdown of the backend runner and all in-flight session tasks.
    pub fn shutdown(&self) {
        self.cancel.cancel();
    }

    /// Pushes a typed [`NativeHudScene`] frame over the non-blocking ephemeral queue.
    pub async fn push_hud(&self, scene: &NativeHudScene) -> Result<(), SdkError> {
        let payload = serde_json::to_value(scene)?;
        self.emit_event(methods::HUD_UPDATE_SCENE, Some(payload), true)
            .await
    }

    /// Dismisses this plugin's active native HUD overlay reliably.
    pub async fn dismiss_hud(&self) -> Result<(), SdkError> {
        self.emit_event(methods::HUD_DISMISS, None, false).await
    }

    /// Emits a plugin event toward `sideveild`.
    ///
    /// When `ephemeral` is `true`, uses a non-blocking send on the bounded ephemeral queue so
    /// high-frequency frames never block the caller or starve reliable events.
    /// When `ephemeral` is `false`, awaits space on the reliable queue while observing shutdown
    /// cancellation.
    pub async fn emit_event(
        &self,
        event: impl Into<String>,
        payload: Option<Value>,
        ephemeral: bool,
    ) -> Result<(), SdkError> {
        let frame = PluginBackendEvent {
            event: event.into(),
            payload,
            ephemeral,
        };
        if ephemeral {
            return map_try_send_ephemeral(self.ephemeral_tx.try_send(frame));
        }
        tokio::select! {
            biased;
            () = self.cancel.cancelled() => Err(SdkError::Cancelled),
            res = self.reliable_tx.send(frame) => res.map_err(|_| SdkError::ChannelClosed),
        }
    }

    /// Non-blocking variant of [`Self::emit_event`] for synchronous call sites.
    pub fn try_emit_event(
        &self,
        event: impl Into<String>,
        payload: Option<Value>,
        ephemeral: bool,
    ) -> Result<(), SdkError> {
        let frame = PluginBackendEvent {
            event: event.into(),
            payload,
            ephemeral,
        };
        if ephemeral {
            map_try_send_ephemeral(self.ephemeral_tx.try_send(frame))
        } else {
            self.reliable_tx.try_send(frame).map_err(|err| match err {
                TrySendError::Full(_) => SdkError::ReliableQueueFull,
                TrySendError::Closed(_) => SdkError::ChannelClosed,
            })
        }
    }

    pub(crate) fn set_connected(&self, connected: bool) {
        self.connected.store(connected, Ordering::Release);
    }
}

fn map_try_send_ephemeral<T>(res: Result<(), TrySendError<T>>) -> Result<(), SdkError> {
    res.map_err(|err| match err {
        TrySendError::Full(_) => SdkError::EphemeralQueueFull,
        TrySendError::Closed(_) => SdkError::ChannelClosed,
    })
}
