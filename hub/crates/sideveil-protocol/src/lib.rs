//! Shared wire protocol types for SideVeil Hub, System Sensor, Render Host, and Plugins.

pub mod config;
pub mod generated;
pub mod hud;
pub mod plugin;
pub mod rpc;
pub mod sensor;
pub mod svp;
pub mod telemetry;
pub mod theme;
pub mod uds;

/// Current SideVeil wire protocol version.
pub const PROTOCOL_VERSION: u32 =
    generated::protocol_constants::protocol_versions::PROTOCOL_VERSION;

/// Current SideVeil plugin API version.
pub const PLUGIN_API_VERSION: u32 =
    generated::protocol_constants::protocol_versions::PLUGIN_API_VERSION;

/// Current SideVeil plugin `manifest.json` schema version.
pub const MANIFEST_SCHEMA_VERSION: u32 =
    generated::protocol_constants::protocol_versions::MANIFEST_SCHEMA_VERSION;

/// Canonical Android package name for the headless render host `priv-app`.
pub const DEFAULT_HOST_PACKAGE: &str = "pochita.sideveil.host";

/// Default runtime paths on Android devices.
pub mod paths {
    pub const STATE_DIR: &str = "/data/system/sideveil";
    pub const CONFIG_PATH: &str = "/data/system/sideveil/config.json";
    pub const PLUGINS_OVERRIDES_PATH: &str = "/data/system/sideveil/plugins.json";
    pub const RUN_DIR: &str = "/data/system/sideveil/run";
    pub const HUB_SOCKET: &str = "/data/system/sideveil/run/hub.sock";
    pub const APP_SOCKET: &str = "/data/system/sideveil/run/app.sock";
    pub const PLUGINS_DIR: &str = "/data/system/sideveil/plugins";
    pub const DATA_DIR: &str = "/data/system/sideveil/data";
    pub const SENSOR_DEX_LIVE: &str = "/data/system/sideveil/sideveil-sensor.dex";
    pub const ROOT_RUN_DIR: &str = "/data/adb/sideveil/run";
    pub const MODULE_DIR: &str = "/data/adb/modules/sideveil";
}

#[cfg(test)]
mod tests {
    use super::PROTOCOL_VERSION;

    #[test]
    fn protocol_version_is_nonzero() {
        assert_eq!(PROTOCOL_VERSION, 1);
    }
}
