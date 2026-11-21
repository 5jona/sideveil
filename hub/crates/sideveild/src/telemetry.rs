//! Zero-subprocess Linux `/proc` & `/sys` telemetry sampler for plugins holding `telemetry` ACL.

use std::fs;
use std::sync::Mutex;

use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TelemetrySnapshot {
    pub cpu_percent: f32,
    pub mem_used_mb: u64,
    pub mem_total_mb: u64,
    pub soc_temp_c: Option<f32>,
    pub uptime_sec: f64,
}

#[derive(Debug, Default)]
struct CpuTotals {
    idle: u64,
    total: u64,
}

static PREV_CPU: Mutex<Option<CpuTotals>> = Mutex::new(None);

#[must_use]
pub fn sample_snapshot() -> TelemetrySnapshot {
    let cpu_percent = sample_cpu_percent();
    let (mem_used_mb, mem_total_mb) = sample_mem_mb();
    let soc_temp_c = sample_soc_temp_c();
    let uptime_sec = fs::read_to_string("/proc/uptime")
        .ok()
        .and_then(|s| s.split_whitespace().next()?.parse::<f64>().ok())
        .unwrap_or(0.0);

    TelemetrySnapshot {
        cpu_percent,
        mem_used_mb,
        mem_total_mb,
        soc_temp_c,
        uptime_sec,
    }
}

fn sample_cpu_percent() -> f32 {
    let Some(cur) = read_cpu_totals() else {
        return 0.0;
    };
    let mut guard = PREV_CPU
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    let pct = if let Some(prev) = guard.as_ref() {
        let d_total = cur.total.saturating_sub(prev.total);
        let d_idle = cur.idle.saturating_sub(prev.idle);
        if d_total > 0 {
            ((d_total.saturating_sub(d_idle)) as f32 / d_total as f32 * 100.0).clamp(0.0, 100.0)
        } else {
            0.0
        }
    } else {
        0.0
    };
    *guard = Some(cur);
    (pct * 10.0).round() / 10.0
}

fn read_cpu_totals() -> Option<CpuTotals> {
    let content = fs::read_to_string("/proc/stat").ok()?;
    let line = content.lines().next()?;
    let mut nums = line
        .split_whitespace()
        .skip(1)
        .filter_map(|t| t.parse::<u64>().ok());
    let user = nums.next()?;
    let nice = nums.next()?;
    let system = nums.next()?;
    let idle = nums.next()?;
    let iowait = nums.next().unwrap_or(0);
    let irq = nums.next().unwrap_or(0);
    let softirq = nums.next().unwrap_or(0);
    let steal = nums.next().unwrap_or(0);
    Some(CpuTotals {
        idle: idle.saturating_add(iowait),
        total: user + nice + system + idle + iowait + irq + softirq + steal,
    })
}

fn sample_mem_mb() -> (u64, u64) {
    let Ok(raw) = fs::read_to_string("/proc/meminfo") else {
        return (0, 0);
    };
    let mut total_kb = 0_u64;
    let mut avail_kb = 0_u64;
    for line in raw.lines() {
        if let Some(rest) = line.strip_prefix("MemTotal:") {
            total_kb = parse_kb(rest);
        } else if let Some(rest) = line.strip_prefix("MemAvailable:") {
            avail_kb = parse_kb(rest);
        }
    }
    let used_kb = total_kb.saturating_sub(avail_kb);
    (used_kb / 1024, total_kb / 1024)
}

fn parse_kb(rest: &str) -> u64 {
    rest.split_whitespace()
        .next()
        .and_then(|s| s.parse::<u64>().ok())
        .unwrap_or(0)
}

fn sample_soc_temp_c() -> Option<f32> {
    let entries = fs::read_dir("/sys/class/thermal").ok()?;
    let mut best: Option<f32> = None;
    for entry in entries.flatten().take(32) {
        let name = entry.file_name();
        if !name.to_string_lossy().starts_with("thermal_zone") {
            continue;
        }
        let path = entry.path();
        let raw_temp = fs::read_to_string(path.join("temp"))
            .ok()
            .and_then(|s| s.trim().parse::<f32>().ok());
        let Some(val) = raw_temp else {
            continue;
        };
        let c = if val > 200.0 { val / 1000.0 } else { val };
        if (15.0..=115.0).contains(&c) {
            let kind = fs::read_to_string(path.join("type"))
                .unwrap_or_default()
                .to_ascii_lowercase();
            if kind.contains("cpu") || kind.contains("soc") || kind.contains("tsens") {
                return Some((c * 10.0).round() / 10.0);
            }
            best.get_or_insert((c * 10.0).round() / 10.0);
        }
    }
    best
}
