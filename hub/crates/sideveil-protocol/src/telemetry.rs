//! Shared system telemetry snapshot wire types returned to plugins holding `telemetry` ACL.

use serde::{Deserialize, Serialize};

/// Point-in-time Linux `/proc` and `/sys` telemetry snapshot.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TelemetrySnapshot {
    /// Aggregate CPU utilization percentage (`0.0..=100.0`).
    pub cpu_percent: f32,
    /// Used physical RAM in mebibytes.
    pub mem_used_mb: u64,
    /// Total physical RAM in mebibytes.
    pub mem_total_mb: u64,
    /// Optional SoC package temperature in degrees Celsius.
    pub soc_temp_c: Option<f32>,
    /// System uptime in seconds since boot.
    pub uptime_sec: f64,
}
