//! Configuration builder for SideVeil plugin backend UDS sessions.

use std::env;
use std::path::PathBuf;
use std::time::Duration;

use sideveil_protocol::paths;

use crate::codec::DEFAULT_MAX_FRAME_BYTES;
use crate::error::SdkError;

const DEFAULT_CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const DEFAULT_HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);
const DEFAULT_MIN_BACKOFF: Duration = Duration::from_millis(250);
const DEFAULT_MAX_BACKOFF: Duration = Duration::from_secs(10);
const DEFAULT_HEALTHY_SESSION_THRESHOLD: Duration = Duration::from_secs(5);
const DEFAULT_RELIABLE_QUEUE_CAPACITY: usize = 128;
const DEFAULT_EPHEMERAL_QUEUE_CAPACITY: usize = 32;
const MIN_FRAME_BYTES: usize = 128;

/// Connection, framing, queue, and reconnect settings for a SideVeil plugin daemon.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PluginBackendConfig {
    /// Registered plugin identifier claimed during `UdsClientHello::PluginBackend`.
    pub plugin_id: String,
    /// Target Hub Unix domain socket path (defaults to [`paths::HUB_SOCKET`]).
    pub socket_path: PathBuf,
    /// Optional isolated plugin data directory (`SIDEVEIL_DATA_DIR`).
    pub data_dir: Option<PathBuf>,
    /// Optional Android package name (required only for `uds-app` peers on `app.sock`).
    pub package_name: Option<String>,
    /// Maximum wait time when establishing a Unix socket connection.
    pub connect_timeout: Duration,
    /// Maximum wait time for sending `UdsClientHello` and receiving `UdsHandshakeAck`.
    pub handshake_timeout: Duration,
    /// Initial reconnect delay after a failed connection or unhealthy disconnect.
    pub min_backoff: Duration,
    /// Maximum exponential reconnect delay cap.
    pub max_backoff: Duration,
    /// Minimum duration an established session must remain healthy before resetting backoff.
    pub healthy_session_threshold: Duration,
    /// Maximum length in bytes of a single NDJSON wire frame (excluding trailing newline).
    pub max_frame_bytes: usize,
    /// Capacity of the reliable control/state outbound event queue.
    pub reliable_queue_capacity: usize,
    /// Capacity of the non-blocking ephemeral telemetry/HUD outbound event queue.
    pub ephemeral_queue_capacity: usize,
}

impl PluginBackendConfig {
    /// Creates a default `hub.sock` configuration for `plugin_id`.
    #[must_use]
    pub fn new(plugin_id: impl Into<String>) -> Self {
        Self {
            plugin_id: plugin_id.into(),
            socket_path: PathBuf::from(paths::HUB_SOCKET),
            data_dir: None,
            package_name: None,
            connect_timeout: DEFAULT_CONNECT_TIMEOUT,
            handshake_timeout: DEFAULT_HANDSHAKE_TIMEOUT,
            min_backoff: DEFAULT_MIN_BACKOFF,
            max_backoff: DEFAULT_MAX_BACKOFF,
            healthy_session_threshold: DEFAULT_HEALTHY_SESSION_THRESHOLD,
            max_frame_bytes: DEFAULT_MAX_FRAME_BYTES,
            reliable_queue_capacity: DEFAULT_RELIABLE_QUEUE_CAPACITY,
            ephemeral_queue_capacity: DEFAULT_EPHEMERAL_QUEUE_CAPACITY,
        }
    }

    /// Reads standard supervisor-injected environment variables (`SIDEVEIL_PLUGIN_ID`,
    /// `SIDEVEIL_HUB_SOCK`, `SIDEVEIL_DATA_DIR`) to build a [`PluginBackendConfig`].
    pub fn from_env() -> Result<Self, SdkError> {
        let plugin_id = env::var("SIDEVEIL_PLUGIN_ID")
            .ok()
            .map(|s| s.trim().to_owned())
            .filter(|s| !s.is_empty())
            .ok_or_else(|| {
                SdkError::InvalidConfig(
                    "SIDEVEIL_PLUGIN_ID environment variable is missing or empty".to_owned(),
                )
            })?;
        let mut cfg = Self::new(plugin_id);
        if let Ok(sock) = env::var("SIDEVEIL_HUB_SOCK")
            && !sock.trim().is_empty()
        {
            cfg.socket_path = PathBuf::from(sock.trim());
        }
        if let Ok(data_dir) = env::var("SIDEVEIL_DATA_DIR")
            && !data_dir.trim().is_empty()
        {
            cfg.data_dir = Some(PathBuf::from(data_dir.trim()));
        }
        Ok(cfg)
    }

    /// Overrides the target Unix domain socket path.
    #[must_use]
    pub fn with_socket_path(mut self, socket_path: impl Into<PathBuf>) -> Self {
        self.socket_path = socket_path.into();
        self
    }

    /// Overrides the plugin's isolated data directory path.
    #[must_use]
    pub fn with_data_dir(mut self, data_dir: impl Into<PathBuf>) -> Self {
        self.data_dir = Some(data_dir.into());
        self
    }

    /// Sets the Android package name claimed on `app.sock`.
    #[must_use]
    pub fn with_package_name(mut self, package_name: impl Into<String>) -> Self {
        self.package_name = Some(package_name.into());
        self
    }

    /// Overrides the socket connect timeout.
    #[must_use]
    pub fn with_connect_timeout(mut self, connect_timeout: Duration) -> Self {
        self.connect_timeout = connect_timeout;
        self
    }

    /// Overrides the UDS role handshake timeout.
    #[must_use]
    pub fn with_handshake_timeout(mut self, handshake_timeout: Duration) -> Self {
        self.handshake_timeout = handshake_timeout;
        self
    }

    /// Sets the exponential reconnect backoff range (`min_backoff..=max_backoff`).
    #[must_use]
    pub fn with_backoff(mut self, min_backoff: Duration, max_backoff: Duration) -> Self {
        self.min_backoff = min_backoff;
        self.max_backoff = max_backoff;
        self
    }

    /// Overrides the healthy-session duration required to reset reconnect backoff.
    #[must_use]
    pub fn with_healthy_session_threshold(mut self, threshold: Duration) -> Self {
        self.healthy_session_threshold = threshold;
        self
    }

    /// Overrides the maximum NDJSON frame size in bytes.
    #[must_use]
    pub fn with_max_frame_bytes(mut self, max_frame_bytes: usize) -> Self {
        self.max_frame_bytes = max_frame_bytes;
        self
    }

    /// Overrides the reliable and ephemeral outbound channel capacities.
    #[must_use]
    pub fn with_queue_capacities(mut self, reliable: usize, ephemeral: usize) -> Self {
        self.reliable_queue_capacity = reliable;
        self.ephemeral_queue_capacity = ephemeral;
        self
    }

    pub(crate) fn validate(&self) -> Result<(), SdkError> {
        if self.plugin_id.trim().is_empty() {
            return Err(SdkError::InvalidConfig(
                "plugin_id must not be empty".to_owned(),
            ));
        }
        if self.connect_timeout.is_zero() || self.handshake_timeout.is_zero() {
            return Err(SdkError::InvalidConfig(
                "connect_timeout and handshake_timeout must be non-zero".to_owned(),
            ));
        }
        if self.min_backoff.is_zero() || self.max_backoff < self.min_backoff {
            return Err(SdkError::InvalidConfig(
                "min_backoff must be non-zero and <= max_backoff".to_owned(),
            ));
        }
        if self.max_frame_bytes < MIN_FRAME_BYTES {
            return Err(SdkError::InvalidConfig(format!(
                "max_frame_bytes must be at least {MIN_FRAME_BYTES}"
            )));
        }
        if self.reliable_queue_capacity == 0 || self.ephemeral_queue_capacity == 0 {
            return Err(SdkError::InvalidConfig(
                "queue capacities must be non-zero".to_owned(),
            ));
        }
        Ok(())
    }
}
