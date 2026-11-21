//! Zero-subprocess Linux `/proc` and `/sys/class/thermal` telemetry samplers.

use std::fs;

#[derive(Default)]
pub struct CpuPrev {
    idle: u64,
    total: u64,
}

pub fn read_cpu() -> Option<CpuPrev> {
    let text = fs::read_to_string("/proc/stat").ok()?;
    let nums: Vec<u64> = text
        .lines()
        .next()?
        .split_whitespace()
        .skip(1)
        .filter_map(|s| s.parse().ok())
        .collect();
    if nums.len() < 4 {
        return None;
    }
    let idle = nums[3] + nums.get(4).copied().unwrap_or(0);
    let total: u64 = nums.iter().copied().sum();
    Some(CpuPrev { idle, total })
}

pub fn sample_cpu(prev: &mut CpuPrev) -> f32 {
    let Some(cur) = read_cpu() else {
        return 0.0;
    };
    let d_tot = cur.total.saturating_sub(prev.total);
    let d_idle = cur.idle.saturating_sub(prev.idle);
    *prev = cur;
    if d_tot == 0 {
        0.0
    } else {
        ((d_tot.saturating_sub(d_idle)) as f32 / d_tot as f32 * 100.0).clamp(0.0, 100.0)
    }
}

pub fn sample_mem_mb() -> (u64, u64) {
    let Ok(raw) = fs::read_to_string("/proc/meminfo") else {
        return (0, 0);
    };
    let mut total = 0_u64;
    let mut avail = 0_u64;
    for line in raw.lines() {
        if let Some(rest) = line.strip_prefix("MemTotal:") {
            total = rest
                .split_whitespace()
                .next()
                .and_then(|s| s.parse().ok())
                .unwrap_or(0);
        } else if let Some(rest) = line.strip_prefix("MemAvailable:") {
            avail = rest
                .split_whitespace()
                .next()
                .and_then(|s| s.parse().ok())
                .unwrap_or(0);
        }
    }
    (total.saturating_sub(avail) / 1024, total / 1024)
}

pub fn sample_temp_c() -> Option<f32> {
    let mut fallback: Option<f32> = None;
    for entry in fs::read_dir("/sys/class/thermal").ok()?.flatten().take(48) {
        if !entry
            .file_name()
            .to_string_lossy()
            .starts_with("thermal_zone")
        {
            continue;
        }
        let path = entry.path();
        let Some(raw) = fs::read_to_string(path.join("temp"))
            .ok()
            .and_then(|s| s.trim().parse::<f32>().ok())
        else {
            continue;
        };
        let c = if raw > 200.0 { raw / 1000.0 } else { raw };
        if !(15.0..=110.0).contains(&c) {
            continue;
        }
        let kind = fs::read_to_string(path.join("type"))
            .unwrap_or_default()
            .to_ascii_lowercase();
        if kind.contains("cpu") || kind.contains("soc") || kind.contains("tsens") {
            return Some(c);
        }
        if fallback.is_none() {
            fallback = Some(c);
        }
    }
    fallback
}
