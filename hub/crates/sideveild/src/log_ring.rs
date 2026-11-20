//! Structured in-memory ring buffer and formatted diagnostic logging for `sideveild`.

use std::collections::VecDeque;
use std::sync::{Mutex, PoisonError};
use std::time::{SystemTime, UNIX_EPOCH};

const MAX_LOG_LINES: usize = 200;

#[derive(Debug)]
pub struct LogRing {
    lines: Mutex<VecDeque<String>>,
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
            lines: Mutex::new(VecDeque::with_capacity(MAX_LOG_LINES)),
        }
    }

    pub fn info(&self, message: impl AsRef<str>) {
        self.record('I', message.as_ref());
    }

    pub fn warn(&self, message: impl AsRef<str>) {
        self.record('W', message.as_ref());
    }

    pub fn error(&self, message: impl AsRef<str>) {
        self.record('E', message.as_ref());
    }

    #[must_use]
    pub fn recent(&self) -> Vec<String> {
        let guard = self.lines.lock().unwrap_or_else(PoisonError::into_inner);
        guard.iter().cloned().collect()
    }

    fn record(&self, level: char, message: &str) {
        let formatted = format!("{} [{}] {}", format_local_time(), level, message);
        eprintln!("[SideveilHub] {formatted}");
        let mut guard = self.lines.lock().unwrap_or_else(PoisonError::into_inner);
        while guard.len() >= MAX_LOG_LINES {
            guard.pop_front();
        }
        guard.push_back(formatted);
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
    fn ring_buffer_bounds_capacity_and_formats_entries() {
        let ring = LogRing::new();
        for i in 0..(MAX_LOG_LINES + 25) {
            ring.info(format!("entry-{i}"));
        }
        let lines = ring.recent();
        assert_eq!(lines.len(), MAX_LOG_LINES);
        assert!(lines[0].contains("[I] entry-25"));
        assert!(lines[MAX_LOG_LINES - 1].contains(&format!("[I] entry-{}", MAX_LOG_LINES + 24)));
    }
}
