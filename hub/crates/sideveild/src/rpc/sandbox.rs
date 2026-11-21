//! Plugin-scoped sandboxed RPC handlers (`plugin.invoke`, `fs.*`, `system.exec`).

use std::sync::Arc;

use serde_json::Value;
use sideveil_protocol::plugin::PluginInvokeParams;
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
