//! Plugin registry administration JSON-RPC handlers (`plugins.*`).

use std::sync::Arc;

use serde_json::Value;
use sideveil_protocol::plugin::{
    PluginSelectParams, PluginSetEnabledParams, PluginSetPriorityParams,
    PluginSetScopeOverrideParams,
};
use sideveil_protocol::rpc::error_codes;

use super::{decode_params, serialize_result};
use crate::state::HubState;

pub(super) fn handle_set_enabled(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: PluginSetEnabledParams = decode_params(params)?;
    state
        .plugin_registry()
        .set_enabled(&parsed.plugin_id, parsed.enabled)
        .map_err(|msg| (error_codes::INVALID_PARAMS, msg))?;
    state.on_plugins_changed();
    serialize_result(&state.plugins_snapshot())
}

pub(super) fn handle_set_priority(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: PluginSetPriorityParams = decode_params(params)?;
    state
        .plugin_registry()
        .set_priority(&parsed.plugin_id, parsed.priority)
        .map_err(|msg| (error_codes::INVALID_PARAMS, msg))?;
    state.on_plugins_changed();
    serialize_result(&state.plugins_snapshot())
}

pub(super) fn handle_set_scope_override(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: PluginSetScopeOverrideParams = decode_params(params)?;
    state
        .plugin_registry()
        .set_scope_override(
            &parsed.plugin_id,
            parsed.packages,
            parsed.global,
            parsed.clear,
        )
        .map_err(|msg| (error_codes::INVALID_PARAMS, msg))?;
    state.on_plugins_changed();
    serialize_result(&state.plugins_snapshot())
}

pub(super) fn handle_select_plugin(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: PluginSelectParams = decode_params(params)?;
    let active = state
        .select_active_plugin(&parsed.plugin_id, parsed.package_name.as_deref())
        .map_err(|msg| (error_codes::INVALID_PARAMS, msg))?;
    serialize_result(&active)
}
