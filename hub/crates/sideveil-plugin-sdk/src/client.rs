//! Reconnecting `hub.sock` client API, RPC handler trait, signal helper, and backoff loop.

use std::future::Future;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Instant;

use serde_json::Value;
use sideveil_protocol::rpc::RpcRequest;
use tokio::signal::unix::{SignalKind, signal};
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

use crate::config::PluginBackendConfig;
use crate::error::{PluginRpcError, SdkError};
use crate::handle::PluginBackendHandle;
use crate::session::{OutboundReceivers, connect_and_handshake, run_active_session};

/// Per-invocation context supplied to [`PluginRpcHandler`] callbacks.
#[derive(Debug, Clone)]
pub struct PluginRpcContext {
    plugin_id: Arc<str>,
    handle: PluginBackendHandle,
    cancel: CancellationToken,
    data_dir: Option<PathBuf>,
    protocol_version: u32,
    api_version: u32,
}

impl PluginRpcContext {
    pub(crate) fn new(
        plugin_id: Arc<str>,
        handle: PluginBackendHandle,
        cancel: CancellationToken,
        data_dir: Option<PathBuf>,
        protocol_version: u32,
        api_version: u32,
    ) -> Self {
        Self {
            plugin_id,
            handle,
            cancel,
            data_dir,
            protocol_version,
            api_version,
        }
    }

    /// Returns the plugin identifier for the active session.
    #[must_use]
    pub fn plugin_id(&self) -> &str {
        &self.plugin_id
    }

    /// Returns the cloneable backend handle for emitting events or inspecting connection status.
    #[must_use]
    pub fn handle(&self) -> &PluginBackendHandle {
        &self.handle
    }

    /// Returns the session-scoped [`CancellationToken`], cancelled on disconnect or shutdown.
    #[must_use]
    pub fn cancel_token(&self) -> &CancellationToken {
        &self.cancel
    }

    /// Returns the isolated plugin data directory (`SIDEVEIL_DATA_DIR`) when configured.
    #[must_use]
    pub fn data_dir(&self) -> Option<&Path> {
        self.data_dir.as_deref()
    }

    /// Returns the wire `protocolVersion` negotiated with `sideveild`.
    #[must_use]
    pub fn protocol_version(&self) -> u32 {
        self.protocol_version
    }

    /// Returns the `apiVersion` advertised by `sideveild`.
    #[must_use]
    pub fn api_version(&self) -> u32 {
        self.api_version
    }
}

/// Async handler trait implemented by SideVeil Rust plugin daemons.
pub trait PluginRpcHandler: Send + Sync + 'static {
    /// Handles a multiplexed JSON-RPC 2.0 request routed from the Hub.
    fn handle_rpc(
        &self,
        req: RpcRequest,
        ctx: PluginRpcContext,
    ) -> impl Future<Output = Result<Value, PluginRpcError>> + Send;

    /// Receives broadcast Hub system events (e.g. `appearance.changed`, `panel.visibility`, `scope.changed`).
    fn on_system_event(
        &self,
        _event: String,
        _payload: Value,
        _ctx: PluginRpcContext,
    ) -> impl Future<Output = ()> + Send {
        std::future::ready(())
    }

    /// Invoked after a Hub UDS session completes role handshake.
    fn on_connected(&self, _ctx: PluginRpcContext) -> impl Future<Output = ()> + Send {
        std::future::ready(())
    }

    /// Invoked after an active Hub UDS session disconnects and drains in-flight tasks.
    fn on_disconnected(&self, _plugin_id: String) -> impl Future<Output = ()> + Send {
        std::future::ready(())
    }

    /// Invoked when connecting or handshaking with `sideveild` fails before backing off.
    fn on_error(&self, _err: &SdkError) -> impl Future<Output = ()> + Send {
        std::future::ready(())
    }
}

/// Owns the reconnecting UDS lifecycle paired with a [`PluginBackendHandle`].
#[derive(Debug)]
pub struct PluginBackendRunner {
    config: PluginBackendConfig,
    plugin_id: Arc<str>,
    handle: PluginBackendHandle,
    receivers: Option<OutboundReceivers>,
    cancel: CancellationToken,
}

impl PluginBackendRunner {
    /// Creates a `(PluginBackendHandle, PluginBackendRunner)` pair validated against `config`.
    pub fn new(
        config: PluginBackendConfig,
        cancel: CancellationToken,
    ) -> Result<(PluginBackendHandle, Self), SdkError> {
        config.validate()?;
        let plugin_id: Arc<str> = Arc::from(config.plugin_id.as_str());
        let (reliable_tx, reliable_rx) = mpsc::channel(config.reliable_queue_capacity);
        let (ephemeral_tx, ephemeral_rx) = mpsc::channel(config.ephemeral_queue_capacity);
        let handle = PluginBackendHandle::new(
            Arc::clone(&plugin_id),
            reliable_tx,
            ephemeral_tx,
            cancel.clone(),
        );
        let runner = Self {
            config,
            plugin_id,
            handle: handle.clone(),
            receivers: Some(OutboundReceivers {
                reliable_rx,
                ephemeral_rx,
            }),
            cancel,
        };
        Ok((handle, runner))
    }

    /// Creates a `(PluginBackendHandle, PluginBackendRunner)` pair from `SIDEVEIL_*` env vars.
    pub fn from_env(cancel: CancellationToken) -> Result<(PluginBackendHandle, Self), SdkError> {
        Self::new(PluginBackendConfig::from_env()?, cancel)
    }

    /// Drives the reconnecting Hub session loop until `cancel` is triggered.
    pub async fn run<H: PluginRpcHandler>(mut self, handler: H) -> Result<(), SdkError> {
        let handler = Arc::new(handler);
        let mut backoff = self.config.min_backoff;

        while !self.cancel.is_cancelled() {
            match connect_and_handshake(&self.config, &self.cancel).await {
                Ok(session) => {
                    let Some(receivers) = self.receivers.take() else {
                        break;
                    };
                    let started = Instant::now();
                    self.receivers = run_active_session(
                        &self.config,
                        &self.plugin_id,
                        &self.handle,
                        &self.cancel,
                        session,
                        receivers,
                        &handler,
                    )
                    .await;
                    if self.cancel.is_cancelled() {
                        break;
                    }
                    if started.elapsed() >= self.config.healthy_session_threshold {
                        backoff = self.config.min_backoff;
                    }
                }
                Err(SdkError::Cancelled) => break,
                Err(err) => {
                    handler.on_error(&err).await;
                }
            }

            if self.cancel.is_cancelled() {
                break;
            }
            tokio::select! {
                biased;
                () = self.cancel.cancelled() => break,
                () = tokio::time::sleep(backoff) => {}
            }
            backoff = backoff.saturating_mul(2).min(self.config.max_backoff);
        }

        self.handle.set_connected(false);
        Ok(())
    }

    /// Runs the backend loop and cancels cleanly on either `SIGTERM` or `SIGINT`.
    pub async fn run_with_signals<H: PluginRpcHandler>(self, handler: H) -> Result<(), SdkError> {
        let signal_cancel = self.cancel.clone();
        let watcher = tokio::spawn(async move {
            wait_shutdown_signal().await;
            signal_cancel.cancel();
        });
        let res = self.run(handler).await;
        watcher.abort();
        res
    }
}

/// Convenience entrypoint validating `config` and running a plugin backend until `cancel` fires.
pub async fn run_plugin_backend<H: PluginRpcHandler>(
    config: PluginBackendConfig,
    handler: H,
    cancel: CancellationToken,
) -> Result<(), SdkError> {
    let (_handle, runner) = PluginBackendRunner::new(config, cancel)?;
    runner.run(handler).await
}

/// Waits asynchronously for either Unix `SIGTERM` or `SIGINT` (falling back to `ctrl_c`).
pub async fn wait_shutdown_signal() {
    let mut sigterm = signal(SignalKind::terminate()).ok();
    let mut sigint = signal(SignalKind::interrupt()).ok();

    match (sigterm.as_mut(), sigint.as_mut()) {
        (Some(t), Some(i)) => {
            tokio::select! {
                _ = t.recv() => {}
                _ = i.recv() => {}
            }
        }
        (Some(t), None) => {
            let _ = t.recv().await;
        }
        (None, Some(i)) => {
            let _ = i.recv().await;
        }
        (None, None) => {
            let _ = tokio::signal::ctrl_c().await;
        }
    }
}
