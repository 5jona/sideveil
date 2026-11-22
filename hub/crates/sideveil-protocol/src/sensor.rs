//! Bidirectional NDJSON messages exchanged between `sideveild` and the thin UID 1000 `:sensor`.

use serde::{Deserialize, Serialize};

use crate::config::{EdgeSide, PanelState, SideVeilConfig};

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

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum NetworkTransportKind {
    Wifi,
    Cellular,
    Ethernet,
    Other,
    #[default]
    None,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct NetworkContextSnapshot {
    pub transport: NetworkTransportKind,
    pub vpn_active: bool,
    pub validated: bool,
    pub metered: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub interface_name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ssid: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub gateway: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub local_ipv4: Option<String>,
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
        #[serde(default)]
        foreground_activity: String,
    },
    #[serde(rename_all = "camelCase")]
    ForegroundChanged {
        package_name: String,
        #[serde(default)]
        activity_name: String,
    },
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
        #[serde(default)]
        canceled: bool,
    },
    #[serde(rename_all = "camelCase")]
    GestureFullscreenHint { side: EdgeSide },
    #[serde(rename_all = "camelCase")]
    EdgeRepositionStart { side: EdgeSide, top_percent: i32 },
    #[serde(rename_all = "camelCase")]
    EdgeRepositionMove { side: EdgeSide, top_percent: i32 },
    #[serde(rename_all = "camelCase")]
    EdgeRepositionEnd {
        side: EdgeSide,
        top_percent: i32,
        committed: bool,
    },
    #[serde(rename_all = "camelCase")]
    NetworkContextChanged { context: NetworkContextSnapshot },
    #[serde(rename_all = "camelCase")]
    Pong { seq: u64 },
}

/// Messages pushed from `sideveild` to the UID 1000 Kotlin `:sensor` process.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum HubToSensor {
    #[serde(rename_all = "camelCase")]
    SyncState {
        config: SideVeilConfig,
        scope_active: bool,
        panel_state: PanelState,
        #[serde(default)]
        fullscreen_active: bool,
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
            config: SideVeilConfig::default(),
            scope_active: true,
            panel_state: PanelState::Closed,
            fullscreen_active: false,
        };
        let encoded_sync = serde_json::to_string(&sync).expect("encode HubToSensor");
        assert!(encoded_sync.contains(r#""type":"syncState""#));
        let decoded_sync: HubToSensor =
            serde_json::from_str(&encoded_sync).expect("decode HubToSensor");
        assert_eq!(decoded_sync, sync);
    }

    #[test]
    fn network_context_snapshot_roundtrips_camel_case() {
        let ctx = NetworkContextSnapshot {
            transport: NetworkTransportKind::Wifi,
            vpn_active: true,
            validated: true,
            metered: false,
            interface_name: Some("wlan0".to_owned()),
            ssid: Some("LabNet".to_owned()),
            gateway: Some("192.168.1.1".to_owned()),
            local_ipv4: Some("192.168.1.42".to_owned()),
        };
        let msg = SensorToHub::NetworkContextChanged {
            context: ctx.clone(),
        };
        let encoded = serde_json::to_string(&msg).expect("encode NetworkContextChanged");
        assert!(encoded.contains(r#""type":"networkContextChanged""#));
        assert!(encoded.contains(r#""transport":"wifi""#));
        assert!(encoded.contains(r#""vpnActive":true"#));
        assert!(encoded.contains(r#""localIpv4":"192.168.1.42""#));
        let decoded: SensorToHub = serde_json::from_str(&encoded).expect("decode network msg");
        assert_eq!(decoded, SensorToHub::NetworkContextChanged { context: ctx });

        let default_ctx: NetworkContextSnapshot =
            serde_json::from_str("{}").expect("default network ctx");
        assert_eq!(default_ctx.transport, NetworkTransportKind::None);
        assert!(!default_ctx.vpn_active);
    }
}
