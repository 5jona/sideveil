//! Plugin-scoped sandboxed RPC handlers (`plugin.invoke`, `fs.*`, `system.exec`).

use std::sync::Arc;

use serde_json::{Value, json};
use sideveil_protocol::hud::{HudDismissPayload, NativeHudScene};
use sideveil_protocol::plugin::{PluginCapability, PluginInvokeParams};
use sideveil_protocol::rpc::error_codes;

use super::{decode_params, require_plugin_id};
use crate::plugin_acl;
use crate::state::HubState;

pub(super) async fn handle_plugin_invoke(
    state: &Arc<HubState>,
    caller_plugin_id: Option<&str>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let plugin_id = require_plugin_id(caller_plugin_id)?;
    let parsed: PluginInvokeParams = decode_params(params)?;
    if parsed.method.trim().is_empty() {
        return Err((
            error_codes::INVALID_PARAMS,
            "plugin.invoke method must not be blank".to_owned(),
        ));
    }
    state
        .plugin_backends()
        .invoke(plugin_id, parsed.method, parsed.params)
        .await
}

pub(super) fn handle_fs_read(
    state: &Arc<HubState>,
    caller_plugin_id: Option<&str>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let pid = require_plugin_id(caller_plugin_id)?;
    plugin_acl::handle_fs_read(state.plugin_registry(), state.data_dir(), pid, params)
}

pub(super) fn handle_fs_write_atomic(
    state: &Arc<HubState>,
    caller_plugin_id: Option<&str>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let pid = require_plugin_id(caller_plugin_id)?;
    plugin_acl::handle_fs_write_atomic(state.plugin_registry(), state.data_dir(), pid, params)
}

pub(super) async fn handle_system_exec(
    state: &Arc<HubState>,
    caller_plugin_id: Option<&str>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let pid = require_plugin_id(caller_plugin_id)?;
    plugin_acl::handle_system_exec(state.plugin_registry(), pid, params).await
}

pub(super) fn handle_hud_update(
    state: &Arc<HubState>,
    is_privileged: bool,
    caller_plugin_id: Option<&str>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let mut scene: NativeHudScene = decode_params(params)?;
    if !is_privileged {
        let pid = require_plugin_id(caller_plugin_id)?;
        if !state
            .plugin_registry()
            .has_capability(pid, PluginCapability::NativeHud)
        {
            return Err((
                error_codes::PERMISSION_DENIED,
                format!("Plugin '{pid}' lacks 'native-hud' capability"),
            ));
        }
        scene.plugin_id = pid.to_owned();
    } else if scene.plugin_id.trim().is_empty() {
        scene.plugin_id = caller_plugin_id.unwrap_or("sideveil.host").to_owned();
    }
    state.push_hud_scene(scene);
    Ok(json!({ "ok": true }))
}

pub(super) fn handle_hud_dismiss(
    state: &Arc<HubState>,
    is_privileged: bool,
    caller_plugin_id: Option<&str>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let target = if is_privileged {
        params
            .and_then(|v| serde_json::from_value::<HudDismissPayload>(v).ok())
            .and_then(|p| p.plugin_id)
    } else {
        Some(require_plugin_id(caller_plugin_id)?.to_owned())
    };
    state.dismiss_hud(target);
    Ok(json!({ "ok": true }))
}
