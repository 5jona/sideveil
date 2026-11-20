//! Bidirectional NDJSON messages exchanged between `sideveild` and the thin UID 1000 `:sensor`.

use serde::{Deserialize, Serialize};

use crate::config::{EdgeSide, PanelState, SideveilConfig};

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DisplayGeometry {
    pub width_px: i32,
    pub height_px: i32,
    pub density: f32,
}

impl Default for DisplayGeometry {
    fn default() -> Self {
        Self {
            width_px: 1080,
            height_px: 2400,
            density: 2.625,
        }
    }
}

/// Messages pushed from the UID 1000 Kotlin `:sensor` process to `sideveild`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum SensorToHub {
    #[serde(rename_all = "camelCase")]
    Hello {
        pid: u32,
        version: String,
        display: DisplayGeometry,
        task_listener_active: bool,
        gesture_monitor_active: bool,
        overlay_permission_granted: bool,
        foreground_package: String,
    },
    #[serde(rename_all = "camelCase")]
    ForegroundChanged { package_name: String },
    #[serde(rename_all = "camelCase")]
    DisplayChanged { display: DisplayGeometry },
    #[serde(rename_all = "camelCase")]
    PermissionsStatus {
        installed: bool,
        uid: Option<u32>,
        overlay_granted: bool,
    },
    #[serde(rename_all = "camelCase")]
    GestureDragStart {
        side: EdgeSide,
        start_y_fraction: f32,
    },
    #[serde(rename_all = "camelCase")]
    GestureDragMove {
        side: EdgeSide,
        progress: f32,
        raw_x: f32,
        raw_y: f32,
        delta_x: f32,
    },
    #[serde(rename_all = "camelCase")]
    GestureDragEnd {
        should_open: bool,
        velocity_px_per_sec: f32,
    },
    #[serde(rename_all = "camelCase")]
    Pong { seq: u64 },
}

/// Messages pushed from `sideveild` to the UID 1000 Kotlin `:sensor` process.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum HubToSensor {
    #[serde(rename_all = "camelCase")]
    SyncState {
        config: SideveilConfig,
        scope_active: bool,
        panel_state: PanelState,
    },
    RefreshPermissions,
    #[serde(rename_all = "camelCase")]
    Ping {
        seq: u64,
    },
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sensor_messages_serialize_with_camel_case_type_tag() {
        let msg = SensorToHub::GestureDragStart {
            side: EdgeSide::Right,
            start_y_fraction: 0.42,
        };
        let encoded = serde_json::to_string(&msg).expect("encode SensorToHub");
        assert!(encoded.contains(r#""type":"gestureDragStart""#));
        assert!(encoded.contains(r#""startYFraction":0.42"#));
        let decoded: SensorToHub = serde_json::from_str(&encoded).expect("decode SensorToHub");
        assert_eq!(decoded, msg);

        let sync = HubToSensor::SyncState {
            config: SideveilConfig::default(),
            scope_active: true,
            panel_state: PanelState::Closed,
        };
        let encoded_sync = serde_json::to_string(&sync).expect("encode HubToSensor");
        assert!(encoded_sync.contains(r#""type":"syncState""#));
        let decoded_sync: HubToSensor =
            serde_json::from_str(&encoded_sync).expect("decode HubToSensor");
        assert_eq!(decoded_sync, sync);
    }
}
