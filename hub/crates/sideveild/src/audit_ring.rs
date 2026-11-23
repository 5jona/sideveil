//! Bounded ring buffer recording structured plugin ACL and lifecycle audit events.

use std::collections::VecDeque;
use std::sync::{Mutex, PoisonError};
use std::time::{SystemTime, UNIX_EPOCH};

use sideveil_protocol::plugin::PluginAuditEntry;

const MAX_AUDIT_ENTRIES: usize = 120;

/// Thread-safe bounded ring buffer retaining recent plugin ACL and lifecycle audit entries.
#[derive(Debug, Default)]
pub struct AuditRing {
    entries: Mutex<VecDeque<PluginAuditEntry>>,
}

impl AuditRing {
    /// Creates an empty audit ring buffer with default bounded capacity.
    #[must_use]
    pub fn new() -> Self {
        Self {
            entries: Mutex::new(VecDeque::with_capacity(MAX_AUDIT_ENTRIES)),
        }
    }

    /// Appends a timestamped audit entry, evicting the oldest entry if full.
    pub fn record(
        &self,
        plugin_id: impl Into<String>,
        action: impl Into<String>,
        allowed: bool,
        detail: impl Into<String>,
    ) {
        let entry = PluginAuditEntry {
            timestamp_ms: now_unix_ms(),
            plugin_id: plugin_id.into(),
            action: action.into(),
            allowed,
            detail: detail.into(),
        };
        let mut guard = self.entries.lock().unwrap_or_else(PoisonError::into_inner);
        if guard.len() >= MAX_AUDIT_ENTRIES {
            guard.pop_front();
        }
        guard.push_back(entry);
    }

    /// Returns a chronological snapshot of retained audit entries.
    #[must_use]
    pub fn recent(&self) -> Vec<PluginAuditEntry> {
        self.entries
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .iter()
            .cloned()
            .collect()
    }
}

fn now_unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| u64::try_from(d.as_millis()).unwrap_or(u64::MAX))
}
