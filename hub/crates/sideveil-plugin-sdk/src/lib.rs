//! Reusable async SDK for SideVeil native Rust plugin daemons (`uds-native` / `uds-app`).
//!
//! `sideveil-plugin-sdk` handles the `hub.sock` / `app.sock` lifecycle so plugin daemons
//! can focus strictly on domain logic:
//! - Bounded, cancellation-safe NDJSON framing via [`tokio_util::codec::LinesCodec`] (`codec`).
//! - Role handshake ([`sideveil_protocol::uds::UdsClientHello::PluginBackend`]) with configurable
//!   connect/handshake timeouts and exponential reconnect backoff that resets after healthy sessions.
//! - Dual-priority outbound queues separating RPC replies and reliable state/control events from
//!   high-frequency, non-blocking ephemeral telemetry/HUD frames.
//! - Structured cancellation and task draining via [`CancellationToken`] and
//!   [`tokio_util::task::TaskTracker`].
//! - Multiplexed JSON-RPC 2.0 request dispatch alongside Hub-pushed system event delivery
//!   (such as `appearance.changed`, `panel.visibility`, and `scope.changed`).

mod client;
pub mod codec;
mod config;
mod error;
mod handle;
mod session;

pub use self::client::{
    PluginBackendRunner, PluginRpcContext, PluginRpcHandler, run_plugin_backend,
};
pub use self::config::PluginBackendConfig;
pub use self::error::{PluginRpcError, SdkError};
pub use self::handle::PluginBackendHandle;
pub use tokio_util::sync::CancellationToken;
