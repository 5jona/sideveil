//! Shared runtime state, status snapshots, and bounded stderr ring buffers for supervised plugin daemons.

use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, PoisonError, RwLock};

use sideveil_protocol::plugin::PluginDaemonStatus;
use tokio::sync::{Notify, broadcast};

/// Maximum retained stderr lines per supervised `uds-native` plugin daemon.
pub const MAX_DAEMON_STDERR_LINES: usize = 200;
const MAX_STDERR_LINE_BYTES: usize = 2048;

/// Live lifecycle status snapshot for one supervised `uds-native` plugin daemon.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DaemonRuntimeInfo {
    pub status: PluginDaemonStatus,
    pub pid: Option<u32>,
    pub restart_count: u32,
    pub last_error: Option<String>,
}

impl Default for DaemonRuntimeInfo {
    fn default() -> Self {
        Self {
            status: PluginDaemonStatus::NotConfigured,
            pid: None,
            restart_count: 0,
            last_error: None,
        }
    }
}

#[derive(Debug, Default)]
struct DaemonRecord {
    info: DaemonRuntimeInfo,
    stderr_lines: VecDeque<String>,
}

/// Thread-safe registry of live daemon statuses, bounded stderr logs, and reconcile triggers.
#[derive(Debug)]
pub struct PluginDaemonManager {
    inner: RwLock<HashMap<String, DaemonRecord>>,
    reconcile_notify: Notify,
    status_tx: broadcast::Sender<()>,
}

impl PluginDaemonManager {
    /// Creates an empty daemon state manager.
    #[must_use]
    pub fn new() -> Arc<Self> {
        let (status_tx, _) = broadcast::channel(32);
        Arc::new(Self {
            inner: RwLock::new(HashMap::new()),
            reconcile_notify: Notify::new(),
            status_tx,
        })
    }

    /// Triggers an immediate asynchronous supervisor reconciliation pass.
    pub fn notify_reconcile(&self) {
        self.reconcile_notify.notify_one();
    }

    /// Waits for the next explicit reconcile trigger.
    pub async fn wait_reconcile(&self) {
        self.reconcile_notify.notified().await;
    }

    /// Subscribes to status transition events so snapshots can be pushed to the Render Host.
    #[must_use]
    pub fn subscribe_status(&self) -> broadcast::Receiver<()> {
        self.status_tx.subscribe()
    }

    /// Returns a cloned map of all currently tracked daemon runtime statuses.
    #[must_use]
    pub fn snapshot_all(&self) -> HashMap<String, DaemonRuntimeInfo> {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        guard
            .iter()
            .map(|(k, v)| (k.clone(), v.info.clone()))
            .collect()
    }

    /// Returns the runtime status for a single plugin daemon if tracked.
    #[must_use]
    pub fn status_of(&self, plugin_id: &str) -> Option<DaemonRuntimeInfo> {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        guard.get(plugin_id).map(|rec| rec.info.clone())
    }

    /// Updates a plugin daemon's live status and notifies subscribers when it changed.
    pub fn set_status(&self, plugin_id: &str, info: DaemonRuntimeInfo) {
        let changed = {
            let mut guard = self.inner.write().unwrap_or_else(PoisonError::into_inner);
            let rec = guard.entry(plugin_id.to_owned()).or_default();
            if rec.info == info {
                false
            } else {
                rec.info = info;
                true
            }
        };
        if changed {
            let _ = self.status_tx.send(());
        }
    }

    /// Appends one stderr line to `plugin_id`'s bounded ring buffer (max 200 lines).
    pub fn push_stderr_line(&self, plugin_id: &str, mut line: String) {
        if line.len() > MAX_STDERR_LINE_BYTES {
            let mut idx = MAX_STDERR_LINE_BYTES;
            while idx > 0 && !line.is_char_boundary(idx) {
                idx -= 1;
            }
            line.truncate(idx);
            line.push_str("...[truncated]");
        }
        let mut guard = self.inner.write().unwrap_or_else(PoisonError::into_inner);
        let rec = guard.entry(plugin_id.to_owned()).or_default();
        if rec.stderr_lines.len() >= MAX_DAEMON_STDERR_LINES {
            let _ = rec.stderr_lines.pop_front();
        }
        rec.stderr_lines.push_back(line);
    }

    /// Returns up to `limit` most recent stderr lines captured from `plugin_id`'s daemon.
    #[must_use]
    pub fn daemon_logs(&self, plugin_id: &str, limit: usize) -> Vec<String> {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        let Some(rec) = guard.get(plugin_id) else {
            return Vec::new();
        };
        let cap = limit.min(MAX_DAEMON_STDERR_LINES);
        let skip = rec.stderr_lines.len().saturating_sub(cap);
        rec.stderr_lines.iter().skip(skip).cloned().collect()
    }

    /// Removes all tracked daemon status and stderr logs for an uninstalled plugin.
    pub fn remove_plugin(&self, plugin_id: &str) {
        let removed = {
            let mut guard = self.inner.write().unwrap_or_else(PoisonError::into_inner);
            guard.remove(plugin_id).is_some()
        };
        if removed {
            let _ = self.status_tx.send(());
        }
    }
}
