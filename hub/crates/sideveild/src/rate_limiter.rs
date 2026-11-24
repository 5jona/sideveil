//! Per-plugin sliding-window rate limiter protecting Hub RPCs and high-frequency HUD/bus lanes.

use std::collections::{HashMap, VecDeque};
use std::sync::{Mutex, PoisonError};
use std::time::{Duration, Instant};

/// Rate-limited action classes enforced per plugin identifier.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum RateLimitBucket {
    /// `system.exec` invocations (max 20 per 10s).
    SystemExec,
    /// `plugin.busPublish` broadcasts (max 60 per 10s).
    BusPublish,
    /// `hud.updateScene` pushes (max 240 per 1s).
    HudUpdate,
}

impl RateLimitBucket {
    const fn policy(self) -> (usize, Duration) {
        match self {
            Self::SystemExec => (20, Duration::from_secs(10)),
            Self::BusPublish => (60, Duration::from_secs(10)),
            Self::HudUpdate => (240, Duration::from_secs(1)),
        }
    }
}

/// Thread-safe per-plugin sliding-window rate limiter.
#[derive(Debug, Default)]
pub struct PluginRateLimiter {
    windows: Mutex<HashMap<(String, RateLimitBucket), VecDeque<Instant>>>,
}

impl PluginRateLimiter {
    /// Creates an empty rate limiter.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Checks and records one event for `(plugin_id, bucket)`, returning `false` when over limit.
    pub fn check_and_record(&self, plugin_id: &str, bucket: RateLimitBucket) -> bool {
        let (max_events, window) = bucket.policy();
        self.check_with_policy(plugin_id, bucket, max_events, window, Instant::now())
    }

    /// Checks with explicit policy and timestamp (exposed for deterministic unit testing).
    pub fn check_with_policy(
        &self,
        plugin_id: &str,
        bucket: RateLimitBucket,
        max_events: usize,
        window: Duration,
        now: Instant,
    ) -> bool {
        let mut guard = self.windows.lock().unwrap_or_else(PoisonError::into_inner);
        let q = guard.entry((plugin_id.to_owned(), bucket)).or_default();
        while q
            .front()
            .is_some_and(|&ts| now.saturating_duration_since(ts) >= window)
        {
            q.pop_front();
        }
        if q.len() >= max_events {
            return false;
        }
        q.push_back(now);
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sliding_window_blocks_bursts_and_recovers_after_window() {
        let limiter = PluginRateLimiter::new();
        let t0 = Instant::now();
        let win = Duration::from_secs(1);

        assert!(limiter.check_with_policy("demo", RateLimitBucket::SystemExec, 2, win, t0));
        assert!(limiter.check_with_policy("demo", RateLimitBucket::SystemExec, 2, win, t0));
        assert!(!limiter.check_with_policy("demo", RateLimitBucket::SystemExec, 2, win, t0));
        // Independent plugin is unaffected.
        assert!(limiter.check_with_policy("other", RateLimitBucket::SystemExec, 2, win, t0));

        let t1 = t0 + Duration::from_millis(1050);
        assert!(limiter.check_with_policy("demo", RateLimitBucket::SystemExec, 2, win, t1));
    }
}
