//! Zero-subprocess Linux `/proc` and `/sys/class/thermal` telemetry samplers.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

const THERMAL_SYSFS_ROOT: &str = "/sys/class/thermal";
static THERMAL_PATHS: Mutex<Option<Vec<PathBuf>>> = Mutex::new(None);

#[derive(Default)]
pub(crate) struct CpuPrev {
    idle: u64,
    total: u64,
}

pub(crate) fn read_cpu() -> Option<CpuPrev> {
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

pub(crate) fn sample_cpu(prev: &mut CpuPrev) -> f32 {
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

pub(crate) fn sample_mem_mb() -> (u64, u64) {
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

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
enum ThermalTier {
    Fallback = 1,
    SecondarySoc = 2,
    PrimaryCpu = 3,
}

fn classify_thermal_zone(kind: &str, mode: Option<&str>) -> Option<ThermalTier> {
    if mode.is_some_and(|m| m.trim().eq_ignore_ascii_case("disabled")) {
        return None;
    }
    let k = kind.trim().to_ascii_lowercase();
    if k.is_empty() || is_non_soc_or_virtual_zone(&k) {
        return None;
    }
    if k.starts_with("cpu")
        || k.contains("tsens")
        || k.contains("mtktscpu")
        || k.contains("soc_therm")
        || k.contains("soc-therm")
        || k.contains("soc_max")
        || k.contains("soc-max")
        || matches!(k.as_str(), "big" | "mid" | "little")
        || k.starts_with("tmu")
        || k.starts_with("cluster")
    {
        return Some(ThermalTier::PrimaryCpu);
    }
    if k.contains("gpu")
        || k.contains("npu")
        || k.contains("nsp")
        || k.contains("ddr")
        || k == "x86_pkg_temp"
        || k.contains("coretemp")
        || k.contains("k10temp")
        || k.contains("ap_ntc")
    {
        return Some(ThermalTier::SecondarySoc);
    }
    if k.contains("board") || k.contains("therm") || k.contains("tz") {
        return Some(ThermalTier::Fallback);
    }
    None
}

fn is_non_soc_or_virtual_zone(k: &str) -> bool {
    k.contains("trip")
        || k.contains("bcl")
        || k.contains("ibat")
        || k.contains("vbat")
        || k.contains("ichg")
        || k.contains("vchg")
        || k == "socd"
        || k.starts_with("socd")
        || k.ends_with("-socd")
        || k.ends_with("_socd")
        || k.contains("limit")
        || k.contains("step")
        || k.contains("virtual")
        || k.contains("quiet")
        || k == "usb"
        || k.contains("usb_")
        || k.contains("usb-")
        || k.contains("chg")
        || k.contains("flash")
        || k.contains("battery")
        || k.contains("batt")
        || k.starts_with("sys-therm")
        || k.starts_with("pm")
        || k.starts_with("shell_")
        || k.starts_with("shell-")
        || k.contains("skin")
        || k.contains("camera")
        || k.contains("mdm")
        || k.contains("modem")
}

fn parse_temp_c(raw: &str) -> Option<f32> {
    let val = raw.trim().parse::<f32>().ok()?;
    let c = if val.abs() > 200.0 { val / 1000.0 } else { val };
    (5.0..=115.0).contains(&c).then_some(c)
}

fn discover_soc_temp_paths(root: &Path) -> Vec<PathBuf> {
    let Ok(entries) = fs::read_dir(root) else {
        return Vec::new();
    };
    let mut best_tier: Option<ThermalTier> = None;
    let mut paths: Vec<PathBuf> = Vec::new();

    for entry in entries.flatten() {
        if !entry
            .file_name()
            .to_string_lossy()
            .starts_with("thermal_zone")
        {
            continue;
        }
        let zone_dir = entry.path();
        let Ok(kind) = fs::read_to_string(zone_dir.join("type")) else {
            continue;
        };
        let mode = fs::read_to_string(zone_dir.join("mode")).ok();
        let Some(tier) = classify_thermal_zone(&kind, mode.as_deref()) else {
            continue;
        };
        let temp_path = zone_dir.join("temp");
        let Some(raw) = fs::read_to_string(&temp_path).ok() else {
            continue;
        };
        if parse_temp_c(&raw).is_none() {
            continue;
        }
        match best_tier {
            Some(current) if tier < current => continue,
            Some(current) if tier > current => {
                best_tier = Some(tier);
                paths.clear();
                paths.push(temp_path);
            }
            None => {
                best_tier = Some(tier);
                paths.push(temp_path);
            }
            Some(_) => paths.push(temp_path),
        }
    }
    paths.sort();
    paths
}

fn read_max_temp_c(paths: &[PathBuf]) -> Option<f32> {
    let mut peak: Option<f32> = None;
    for path in paths {
        if let Some(c) = fs::read_to_string(path).ok().and_then(|s| parse_temp_c(&s)) {
            peak = Some(peak.map_or(c, |p| p.max(c)));
        }
    }
    peak.map(|c| (c * 10.0).round() / 10.0)
}

pub(crate) fn sample_temp_c() -> Option<f32> {
    let mut guard = THERMAL_PATHS
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Some(cached) = guard.as_ref()
        && let Some(temp) = read_max_temp_c(cached)
    {
        return Some(temp);
    }
    let discovered = discover_soc_temp_paths(Path::new(THERMAL_SYSFS_ROOT));
    let temp = read_max_temp_c(&discovered);
    *guard = (!discovered.is_empty()).then_some(discovered);
    temp
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_hw_trip_and_socd_zones() {
        assert_eq!(
            classify_thermal_zone("cpu-hw-trip-0", Some("disabled")),
            None
        );
        assert_eq!(
            classify_thermal_zone("cpu-hw-trip-1", Some("enabled")),
            None
        );
        assert_eq!(classify_thermal_zone("socd", Some("enabled")), None);
        assert_eq!(classify_thermal_zone("sys-therm-12", Some("enabled")), None);
        assert_eq!(
            classify_thermal_zone("cpu-0-2-0", Some("enabled")),
            Some(ThermalTier::PrimaryCpu)
        );
        assert_eq!(
            classify_thermal_zone("gpuss-0", Some("enabled")),
            Some(ThermalTier::SecondarySoc)
        );
    }
}
