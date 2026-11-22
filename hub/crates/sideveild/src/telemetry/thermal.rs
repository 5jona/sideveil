//! Linux `/sys/class/thermal` SoC die temperature discovery and cached sampling.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

const THERMAL_SYSFS_ROOT: &str = "/sys/class/thermal";
static THERMAL_PATHS: Mutex<Option<Vec<PathBuf>>> = Mutex::new(None);

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

pub(super) fn sample_soc_temp_c() -> Option<f32> {
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
    use std::time::{SystemTime, UNIX_EPOCH};

    fn write_zone(root: &Path, idx: u32, kind: &str, temp: &str, mode: Option<&str>) {
        let dir = root.join(format!("thermal_zone{idx}"));
        fs::create_dir_all(&dir).expect("create zone dir");
        fs::write(dir.join("type"), format!("{kind}\n")).expect("write type");
        fs::write(dir.join("temp"), format!("{temp}\n")).expect("write temp");
        if let Some(m) = mode {
            fs::write(dir.join("mode"), format!("{m}\n")).expect("write mode");
        }
    }

    #[test]
    fn ignores_disabled_hw_trip_bcl_and_socd_and_picks_peak_cpu_die_temp() {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("time")
            .as_nanos();
        let root = std::env::temp_dir().join(format!("sideveil-thermal-test-{nanos}"));
        fs::create_dir_all(&root).expect("create root");

        write_zone(&root, 59, "cpu-hw-trip-0", "95000", Some("disabled"));
        write_zone(&root, 64, "cpu-hw-trip-1", "95000", Some("enabled"));
        write_zone(&root, 92, "socd", "20", Some("enabled"));
        write_zone(&root, 77, "sys-therm-12", "82262", Some("enabled"));
        write_zone(&root, 87, "pmih010x_lite_tz", "64632", Some("enabled"));
        write_zone(&root, 56, "pmh0101-bcl-lvl0", "0", Some("enabled"));
        write_zone(&root, 5, "cpu-0-0-0", "32900", Some("enabled"));
        write_zone(&root, 9, "cpu-0-2-0", "34100", Some("enabled"));
        write_zone(&root, 36, "gpuss-0", "36500", Some("enabled"));

        let paths = discover_soc_temp_paths(&root);
        assert_eq!(paths.len(), 2);
        assert_eq!(read_max_temp_c(&paths), Some(34.1));

        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn falls_back_to_gpu_when_no_cpu_zones_exist() {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("time")
            .as_nanos();
        let root = std::env::temp_dir().join(format!("sideveil-thermal-gpu-{nanos}"));
        fs::create_dir_all(&root).expect("create root");

        write_zone(&root, 1, "cpu-hw-trip-0", "95000", Some("disabled"));
        write_zone(&root, 2, "gpuss-0", "37400", Some("enabled"));
        write_zone(&root, 3, "board_temp", "28000", Some("enabled"));

        let paths = discover_soc_temp_paths(&root);
        assert_eq!(read_max_temp_c(&paths), Some(37.4));

        let _ = fs::remove_dir_all(&root);
    }
}
