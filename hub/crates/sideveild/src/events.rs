//! Bidirectional Host WebSocket event routing for `HubState`.

mod host_inbound;
mod sensor;

use std::sync::PoisonError;

use serde::Serialize;
use serde_json::Value;
use sideveil_gfx::palette::synthesize_glass_palette;
use sideveil_protocol::config::SideVeilConfig;
use sideveil_protocol::hud::{HudDismissPayload, NativeHudScene};
use sideveil_protocol::plugin::{
    PluginActiveChangedPayload, PluginBackendState, PluginBackendStatePayload,
    PluginBusEventPayload, PluginScopedEventPayload,
};
use sideveil_protocol::rpc::{
    ConfigUpdatedPayload, HostEventEnvelope, PanelCommand, PanelCommandPayload, events,
};
use sideveil_protocol::sensor::HubToSensor;

use crate::state::HubState;

impl HubState {
    pub fn on_config_changed(&self, next: &SideVeilConfig) {
        self.sync_sensor_state();
        self.push_config_snapshot_to_host();
        self.push_plugins_snapshot_to_host();
        self.push_dynamic_palette_if_available();
        if let Ok(val) = serde_json::to_value(&next.panel) {
            self.plugin_backends()
                .broadcast_system_event(events::APPEARANCE_CHANGED, val);
        }
    }

    pub fn on_plugins_changed(&self) {
        self.sync_sensor_state();
        self.push_plugins_snapshot_to_host();
    }

    pub fn push_plugins_snapshot_to_host(&self) {
        let snapshot = self.plugins_snapshot();
        self.emit_host_event_critical(events::PLUGINS_UPDATED, &snapshot);
    }

    pub fn emit_plugin_backend_state(&self, plugin_id: &str, state: PluginBackendState) {
        self.emit_host_event_critical(
            events::PLUGIN_BACKEND_STATE,
            &PluginBackendStatePayload {
                plugin_id: plugin_id.to_owned(),
                state,
            },
        );
        self.push_plugins_snapshot_to_host();
    }

    pub fn emit_plugin_scoped_event(&self, plugin_id: &str, event: String, payload: Option<Value>) {
        self.emit_host_event_critical(
            events::PLUGIN_EVENT,
            &PluginScopedEventPayload {
                plugin_id: plugin_id.to_owned(),
                event,
                payload,
            },
        );
    }

    pub fn emit_plugin_scoped_event_ephemeral(
        &self,
        plugin_id: &str,
        event: String,
        payload: Option<Value>,
    ) {
        self.emit_host_event_ephemeral(
            events::PLUGIN_EVENT,
            &PluginScopedEventPayload {
                plugin_id: plugin_id.to_owned(),
                event,
                payload,
            },
        );
    }

    pub fn emit_plugin_bus_message(
        &self,
        from_plugin_id: &str,
        topic: String,
        payload: Option<Value>,
    ) {
        self.emit_host_event_critical(
            events::PLUGIN_BUS_MESSAGE,
            &PluginBusEventPayload {
                from_plugin_id: from_plugin_id.to_owned(),
                topic,
                payload,
            },
        );
    }

    pub fn select_active_plugin(
        &self,
        plugin_id: &str,
        package_override: Option<&str>,
    ) -> Result<PluginActiveChangedPayload, String> {
        let fg = match package_override {
            Some(p) if !p.trim().is_empty() => p.trim().to_owned(),
            _ => self
                .runtime
                .read()
                .unwrap_or_else(PoisonError::into_inner)
                .foreground_package
                .clone(),
        };
        self.plugin_registry().select_plugin(plugin_id, &fg)?;
        let snapshot = self.plugins_snapshot();
        let payload = PluginActiveChangedPayload {
            plugin_id: plugin_id.to_owned(),
            foreground_package: fg,
            matched_plugin_ids: snapshot.matched_plugin_ids,
        };
        self.emit_host_event_critical(events::PLUGINS_ACTIVE_CHANGED, &payload);
        Ok(payload)
    }

    pub fn send_panel_command(&self, command: PanelCommand) {
        self.emit_host_event_critical(events::PANEL_COMMAND, &PanelCommandPayload { command });
    }

    pub fn push_hud_scene(&self, scene: NativeHudScene) {
        let clean = scene.sanitized();
        {
            let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
            rt.active_hud_plugin = Some(clean.plugin_id.clone());
        }
        // High-frequency HUD updates ride the coalescing ephemeral channel so control frames never stall.
        self.emit_host_event_ephemeral(events::HUD_SCENE_UPDATED, &clean);
    }

    pub fn dismiss_hud(&self, plugin_id: Option<String>) {
        let should_emit = {
            let mut rt = self.runtime.write().unwrap_or_else(PoisonError::into_inner);
            match (&plugin_id, &rt.active_hud_plugin) {
                (None, _) => {
                    rt.active_hud_plugin = None;
                    true
                }
                (Some(req), Some(active)) if req == active => {
                    rt.active_hud_plugin = None;
                    true
                }
                (Some(_), None) => true,
                _ => false,
            }
        };
        if should_emit {
            self.emit_host_event_critical(events::HUD_DISMISSED, &HudDismissPayload { plugin_id });
        }
    }

    pub fn push_dynamic_palette_if_available(&self) {
        let colors = self
            .runtime
            .read()
            .unwrap_or_else(PoisonError::into_inner)
            .dynamic_colors;
        let Some(colors) = colors else {
            return;
        };
        let cfg = self.config_store().current();
        let is_dark = match cfg.panel.color_mode.as_str() {
            "light" => false,
            "dark" => true,
            _ => colors.is_dark,
        };
        let palette = synthesize_glass_palette(
            colors.seed_argb,
            colors.luma_hint,
            is_dark,
            &cfg.panel.theme_id,
        );
        self.emit_host_event_critical(events::THEME_PALETTE_UPDATED, &palette);
    }

    pub fn request_sensor_permission_refresh(&self) {
        let guard = self
            .sensor_session
            .read()
            .unwrap_or_else(PoisonError::into_inner);
        if let Some(handle) = guard.as_ref() {
            let _ = handle.sender.send(HubToSensor::RefreshPermissions);
        }
    }

    pub fn sync_sensor_state(&self) {
        let cfg = self.config_store().current();
        let snapshot = self.plugins_snapshot();
        let scope_active = !snapshot.matched_plugin_ids.is_empty();
        let (panel_state, fullscreen_active) = {
            let rt = self.runtime.read().unwrap_or_else(PoisonError::into_inner);
            (rt.panel_state, rt.fullscreen_active)
        };
        let guard = self
            .sensor_session
            .read()
            .unwrap_or_else(PoisonError::into_inner);
        if let Some(handle) = guard.as_ref() {
            let _ = handle.sender.send(HubToSensor::SyncState {
                config: cfg,
                scope_active,
                panel_state,
                fullscreen_active,
            });
        }
    }

    pub fn push_config_snapshot_to_host(&self) {
        let cfg = self.config_store().current();
        let snapshot = self.plugins_snapshot();
        let scope_active = !snapshot.matched_plugin_ids.is_empty();
        self.emit_host_event_critical(
            events::CONFIG_UPDATED,
            &ConfigUpdatedPayload {
                config: cfg,
                scope_active,
                foreground_package: snapshot.foreground_package,
            },
        );
    }

    pub fn emit_host_event_critical<T: Serialize>(&self, event: &str, payload: &T) {
        let Some(wire) = encode_event(event, payload) else {
            return;
        };
        let guard = self
            .host_session
            .read()
            .unwrap_or_else(PoisonError::into_inner);
        if let Some(handle) = guard.as_ref() {
            let _ = handle.sender.send_critical(wire);
        }
    }

    pub fn emit_host_event_ephemeral<T: Serialize>(&self, event: &str, payload: &T) {
        let Some(wire) = encode_event(event, payload) else {
            return;
        };
        let guard = self
            .host_session
            .read()
            .unwrap_or_else(PoisonError::into_inner);
        if let Some(handle) = guard.as_ref() {
            let _ = handle.sender.send_ephemeral(wire);
        }
    }
}

fn encode_event<T: Serialize>(event: &str, payload: &T) -> Option<String> {
    let val: Value = serde_json::to_value(payload).ok()?;
    serde_json::to_string(&HostEventEnvelope {
        event: event.to_owned(),
        payload: Some(val),
    })
    .ok()
}
