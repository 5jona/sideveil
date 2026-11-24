//! Reusable async SDK for SideVeil native Rust plugin daemons (`uds-native` / `uds-app`).
//!
//! `sideveil-plugin-sdk` handles the `hub.sock` / `app.sock` lifecycle so plugin daemons
//! can focus strictly on domain logic:
//! - Bounded, cancellation-safe NDJSON framing via [`tokio_util::codec::LinesCodec`] (`codec`).
//! - Role handshake ([`sideveil_protocol::uds::UdsClientHello::PluginBackend`]) with version
//!   negotiation, configurable connect/handshake timeouts, and exponential reconnect backoff.
//! - Dual-priority outbound queues separating RPC replies and reliable state/control events from
//!   high-frequency, non-blocking ephemeral telemetry/HUD frames.
//! - Typed Native HUD helpers ([`PluginBackendHandle::push_hud`], [`PluginBackendHandle::dismiss_hud`],
//!   and [`HudSceneBuilder`]).
//! - Structured cancellation and task draining via [`CancellationToken`] and
//!   [`tokio_util::task::TaskTracker`], plus graceful `SIGTERM`/`SIGINT` handling.

mod client;
pub mod codec;
mod config;
mod error;
mod handle;
mod hud_builder;
mod session;

pub use self::client::{
    PluginBackendRunner, PluginRpcContext, PluginRpcHandler, run_plugin_backend,
    wait_shutdown_signal,
};
pub use self::config::PluginBackendConfig;
pub use self::error::{PluginRpcError, SdkError};
pub use self::handle::PluginBackendHandle;
pub use self::hud_builder::HudSceneBuilder;
pub use sideveil_protocol::hud::{
    HudBackdropKind, HudPrimitive, HudTextAlign, HudTextOverlay, NativeHudScene,
};
pub use tokio_util::sync::CancellationToken;
