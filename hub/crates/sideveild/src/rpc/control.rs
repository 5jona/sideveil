//! Host control-plane JSON-RPC handlers (`config.patchAppearance`, `config.patchEdge`, `panel.open`).

use std::sync::Arc;

use serde_json::{Value, json};
use sideveil_protocol::config::{AppearancePatchParams, EdgePatchParams};
use sideveil_protocol::rpc::{PanelCommand, PanelOpenParams, error_codes};

use super::serialize_result;
use crate::state::HubState;

pub(super) fn handle_panel_open(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed = match params {
        Some(val) => serde_json::from_value::<PanelOpenParams>(val).map_err(|err| {
            (
                error_codes::INVALID_PARAMS,
                format!("Invalid params: {err}"),
            )
        })?,
        None => PanelOpenParams::default(),
    };

    let mut selected_plugin_id: Option<String> = None;
    if let Some(plugin_id) = parsed
        .plugin_id
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        // Always update active plugin and push `plugins.activeChanged` even when the drawer
        // is already open so `sideveil-ctl open <plugin_id>` switches immediately.
        state
            .select_active_plugin(plugin_id, None)
            .map_err(|err| (error_codes::INVALID_PARAMS, err.to_string()))?;
        selected_plugin_id = Some(plugin_id.to_owned());
    }

    state.send_panel_command(PanelCommand::Open);
    let active_plugin_id = state
        .plugins_snapshot()
        .active_plugin_id
        .or(selected_plugin_id);
    Ok(json!({
        "ok": true,
        "activePluginId": active_plugin_id,
    }))
}

pub(super) async fn handle_patch_appearance(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let patch = match params {
        Some(val) => serde_json::from_value::<AppearancePatchParams>(val).map_err(|err| {
            (
                error_codes::INVALID_PARAMS,
                format!("Invalid params: {err}"),
            )
        })?,
        None => AppearancePatchParams::default(),
    };
    let updated = state
        .config_store()
        .patch_appearance(&patch)
        .await
        .map_err(|err| {
            (
                error_codes::INTERNAL_ERROR,
                format!("Failed to persist appearance patch: {err}"),
            )
        })?;
    state.on_config_changed(&updated);
    serialize_result(&updated)
}

pub(super) async fn handle_patch_edge(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let patch = match params {
        Some(val) => serde_json::from_value::<EdgePatchParams>(val).map_err(|err| {
            (
                error_codes::INVALID_PARAMS,
                format!("Invalid params: {err}"),
            )
        })?,
        None => EdgePatchParams::default(),
    };
    let updated = state
        .config_store()
        .patch_edge(&patch)
        .await
        .map_err(|err| {
            (
                error_codes::INTERNAL_ERROR,
                format!("Failed to persist edge patch: {err}"),
            )
        })?;
    state.on_config_changed(&updated);
    serialize_result(&updated)
}
