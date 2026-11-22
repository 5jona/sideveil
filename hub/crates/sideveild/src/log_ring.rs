//! Structured in-memory ring buffer and formatted diagnostic logging for `sideveild`.

use std::collections::VecDeque;
use std::sync::{Mutex, PoisonError};
use std::time::{SystemTime, UNIX_EPOCH};

use sideveil_protocol::rpc::DaemonLogsResult;

const MAX_LOG_LINES: usize = 300;
const DEFAULT_SOURCE: &str = "hub";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LogLevel {
    Info,
    Warn,
    Error,
}

impl LogLevel {
    #[must_use]
    pub const fn tag(self) -> char {
        match self {
            Self::Info => 'I',
            Self::Warn => 'W',
            Self::Error => 'E',
        }
    }

    #[must_use]
    pub fn parse_filter(raw: &str) -> Option<Self> {
        match raw.trim().to_ascii_lowercase().as_str() {
            "info" | "i" => Some(Self::Info),
            "warn" | "warning" | "w" => Some(Self::Warn),
            "error" | "err" | "e" => Some(Self::Error),
            _ => None,
        }
    }
}

#[derive(Debug, Clone)]
struct LogEntry {
    seq: u64,
    level: LogLevel,
    source: String,
    line: String,
}

#[derive(Debug)]
struct LogRingInner {
    entries: VecDeque<LogEntry>,
    next_seq: u64,
    total_dropped: u64,
}

#[derive(Debug)]
pub struct LogRing {
    inner: Mutex<LogRingInner>,
}

impl Default for LogRing {
    fn default() -> Self {
        Self::new()
    }
}

impl LogRing {
    #[must_use]
    pub fn new() -> Self {
        Self {
            inner: Mutex::new(LogRingInner {
                entries: VecDeque::with_capacity(MAX_LOG_LINES),
                next_seq: 1,
                total_dropped: 0,
            }),
        }
    }

    pub fn info(&self, message: impl AsRef<str>) {
        self.record(LogLevel::Info, DEFAULT_SOURCE, message.as_ref());
    }

    pub fn warn(&self, message: impl AsRef<str>) {
        self.record(LogLevel::Warn, DEFAULT_SOURCE, message.as_ref());
    }

    pub fn error(&self, message: impl AsRef<str>) {
        self.record(LogLevel::Error, DEFAULT_SOURCE, message.as_ref());
    }

    pub fn log_source(&self, level: LogLevel, source: impl Into<String>, message: impl AsRef<str>) {
        self.record(level, source.into(), message.as_ref());
    }

    #[must_use]
    pub fn recent(&self) -> Vec<String> {
        let guard = self.inner.lock().unwrap_or_else(PoisonError::into_inner);
        guard.entries.iter().map(|e| e.line.clone()).collect()
    }

    /// Clears buffered entries while preserving monotonic sequence numbers and drop counts.
    #[must_use]
    pub fn clear(&self) -> u64 {
        let mut guard = self.inner.lock().unwrap_or_else(PoisonError::into_inner);
        guard.entries.clear();
        guard.next_seq
    }

    /// Queries buffered log lines with optional monotonic cursor, tail limit, level, and source filters.
    #[must_use]
    pub fn query(
        &self,
        since_seq: Option<u64>,
        limit: Option<usize>,
        level: Option<&str>,
        source: Option<&str>,
    ) -> DaemonLogsResult {
        let level_filter = level.and_then(|raw| {
            let trimmed = raw.trim();
            if trimmed.is_empty() {
                None
            } else {
                Some(LogLevel::parse_filter(trimmed))
            }
        });
        let source_filter = source
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_ascii_lowercase);

        let guard = self.inner.lock().unwrap_or_else(PoisonError::into_inner);
        let next_seq = guard.next_seq;
        let total_dropped = guard.total_dropped;

        // An unrecognized explicit level filter matches nothing instead of silently ignoring the filter.
        if matches!(level_filter, Some(None)) {
            return DaemonLogsResult {
                lines: Vec::new(),
                next_seq,
                total_dropped,
            };
        }
        let expected_level = level_filter.flatten();

        let mut matched: Vec<String> = guard
            .entries
            .iter()
            .filter(|entry| since_seq.is_none_or(|seq| entry.seq >= seq))
            .filter(|entry| expected_level.is_none_or(|lvl| entry.level == lvl))
            .filter(|entry| {
                source_filter
                    .as_deref()
                    .is_none_or(|needle| entry.source.to_ascii_lowercase().contains(needle))
            })
            .map(|entry| entry.line.clone())
            .collect();

        if let Some(max_lines) = limit {
            let bounded = max_lines.min(MAX_LOG_LINES);
            if matched.len() > bounded {
                let keep_from = matched.len() - bounded;
                matched.drain(..keep_from);
            }
        }

        DaemonLogsResult {
            lines: matched,
            next_seq,
            total_dropped,
        }
    }

    fn record(&self, level: LogLevel, source: impl Into<String>, message: &str) {
        let formatted = format!("{} [{}] {}", format_local_time(), level.tag(), message);
        eprintln!("[SideVeilHub] {formatted}");
        let source = source.into();
        let mut guard = self.inner.lock().unwrap_or_else(PoisonError::into_inner);
        while guard.entries.len() >= MAX_LOG_LINES {
            guard.entries.pop_front();
            guard.total_dropped = guard.total_dropped.saturating_add(1);
        }
        let seq = guard.next_seq;
        guard.next_seq = guard.next_seq.saturating_add(1);
        guard.entries.push_back(LogEntry {
            seq,
            level,
            source,
            line: formatted,
        });
    }
}

fn format_local_time() -> String {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let secs = now.as_secs() as libc::time_t;
    let millis = now.subsec_millis();
    let mut tm: libc::tm = unsafe {
        // SAFETY: `libc::tm` is a plain C struct where all-zero is a valid initial state
        // before being populated by `localtime_r`.
        std::mem::zeroed()
    };
    let res = unsafe {
        // SAFETY: Both `secs` and `tm` are valid non-null pointers for the duration of the call.
        libc::localtime_r(&secs, &mut tm)
    };
    if res.is_null() {
        let day_secs = now.as_secs() % 86_400;
        let h = day_secs / 3_600;
        let m = (day_secs % 3_600) / 60;
        let s = day_secs % 60;
        return format!("{h:02}:{m:02}:{s:02}.{millis:03}");
    }
    format!(
        "{:02}:{:02}:{:02}.{:03}",
        tm.tm_hour, tm.tm_min, tm.tm_sec, millis
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ring_buffer_bounds_capacity_tracks_dropped_and_formats_entries() {
        let ring = LogRing::new();
        for i in 0..(MAX_LOG_LINES + 25) {
            ring.info(format!("entry-{i}"));
        }
        let lines = ring.recent();
        assert_eq!(lines.len(), MAX_LOG_LINES);
        assert!(lines[0].contains("[I] entry-25"));
        assert!(lines[MAX_LOG_LINES - 1].contains(&format!("[I] entry-{}", MAX_LOG_LINES + 24)));

        let queried = ring.query(None, None, None, None);
        assert_eq!(queried.total_dropped, 25);
        assert_eq!(queried.next_seq, (MAX_LOG_LINES + 25 + 1) as u64);
    }

    #[test]
    fn query_supports_since_seq_level_source_limit_and_clear() {
        let ring = LogRing::new();
        ring.info("boot ok");
        ring.log_source(LogLevel::Warn, "webview:diag", "slow frame");
        ring.log_source(LogLevel::Error, "webview:demo", "fetch 502");
        ring.log_source(LogLevel::Error, "webview:diag", "uncaught error");

        let all = ring.query(None, None, None, None);
        assert_eq!(all.lines.len(), 4);
        assert_eq!(all.next_seq, 5);
        assert_eq!(all.total_dropped, 0);

        let since = ring.query(Some(3), None, None, None);
        assert_eq!(since.lines.len(), 2);
        assert!(since.lines[0].contains("fetch 502"));
        assert!(since.lines[1].contains("uncaught error"));

        let errors_only = ring.query(None, None, Some("error"), None);
        assert_eq!(errors_only.lines.len(), 2);

        let diag_only = ring.query(None, None, None, Some("webview:diag"));
        assert_eq!(diag_only.lines.len(), 2);
        assert!(diag_only.lines[0].contains("slow frame"));
        assert!(diag_only.lines[1].contains("uncaught error"));

        let limited = ring.query(None, Some(2), None, None);
        assert_eq!(limited.lines.len(), 2);
        assert!(limited.lines[0].contains("fetch 502"));
        assert!(limited.lines[1].contains("uncaught error"));

        let next_after_clear = ring.clear();
        assert_eq!(next_after_clear, 5);
        let empty = ring.query(None, None, None, None);
        assert!(empty.lines.is_empty());
        assert_eq!(empty.next_seq, 5);

        ring.warn("after clear");
        let after = ring.query(Some(5), None, None, None);
        assert_eq!(after.lines.len(), 1);
        assert!(after.lines[0].contains("[W] after clear"));
        assert_eq!(after.next_seq, 6);
    }
}
