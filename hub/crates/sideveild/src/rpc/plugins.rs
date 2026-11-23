//! Plugin registry administration JSON-RPC handlers (`plugins.*`).

use std::path::Path;
use std::sync::Arc;

use serde_json::{Value, json};
use sideveil_protocol::plugin::{
    PluginCreateWebEmbedParams, PluginInstallParams, PluginSelectParams, PluginSetDevUrlParams,
    PluginSetEmbedConfigParams, PluginSetEnabledParams, PluginSetPriorityParams,
    PluginSetScopeOverrideParams, PluginUninstallParams,
};
use sideveil_protocol::rpc::error_codes;

use super::{decode_params, serialize_result};
use crate::state::HubState;

pub(super) fn handle_install_plugin(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: PluginInstallParams = decode_params(params)?;
    let src = Path::new(parsed.source_dir.trim());
    let manifest = state
        .plugin_registry()
        .install_from_dir(src)
        .map_err(|err| (error_codes::INVALID_PARAMS, err.to_string()))?;
    state.audit().record(
        manifest.id.clone(),
        "plugins.install",
        true,
        format!("v{} from {}", manifest.version, src.display()),
    );
    state.on_plugins_changed();
    Ok(json!({
        "ok": true,
        "pluginId": manifest.id,
        "version": manifest.version,
        "snapshot": state.plugins_snapshot(),
    }))
}

pub(super) fn handle_set_enabled(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: PluginSetEnabledParams = decode_params(params)?;
    state
        .plugin_registry()
        .set_enabled(&parsed.plugin_id, parsed.enabled)
        .map_err(|err| (error_codes::INVALID_PARAMS, err.to_string()))?;
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
        .map_err(|err| (error_codes::INVALID_PARAMS, err.to_string()))?;
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
        .map_err(|err| (error_codes::INVALID_PARAMS, err.to_string()))?;
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
        .map_err(|err| (error_codes::INVALID_PARAMS, err.to_string()))?;
    serialize_result(&active)
}

pub(super) fn handle_set_dev_url(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: PluginSetDevUrlParams = decode_params(params)?;
    state
        .plugin_registry()
        .set_dev_url(
            &parsed.plugin_id,
            parsed.dev_server_url.as_deref(),
            parsed.clear,
        )
        .map_err(|err| (error_codes::INVALID_PARAMS, err.to_string()))?;
    state.audit().record(
        parsed.plugin_id,
        "plugins.setDevUrl",
        true,
        if parsed.clear {
            "cleared dev server URL".to_owned()
        } else {
            parsed.dev_server_url.unwrap_or_default()
        },
    );
    state.on_plugins_changed();
    serialize_result(&state.plugins_snapshot())
}

pub(super) fn handle_set_embed_config(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: PluginSetEmbedConfigParams = decode_params(params)?;
    state
        .plugin_registry()
        .set_embed_config(&parsed)
        .map_err(|err| (error_codes::INVALID_PARAMS, err.to_string()))?;
    state.audit().record(
        parsed.plugin_id,
        "plugins.setEmbedConfig",
        true,
        if parsed.clear {
            "cleared embed config overrides".to_owned()
        } else {
            parsed.embed_url.unwrap_or_else(|| "updated".to_owned())
        },
    );
    state.on_plugins_changed();
    serialize_result(&state.plugins_snapshot())
}

pub(super) fn handle_create_web_embed(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: PluginCreateWebEmbedParams = decode_params(params)?;
    let manifest = state
        .plugin_registry()
        .create_web_embed(&parsed)
        .map_err(|err| (error_codes::INVALID_PARAMS, err.to_string()))?;
    state.audit().record(
        manifest.id.clone(),
        "plugins.createWebEmbed",
        true,
        parsed.url,
    );
    state.on_plugins_changed();
    Ok(json!({
        "ok": true,
        "pluginId": manifest.id,
        "snapshot": state.plugins_snapshot(),
    }))
}

pub(super) fn handle_uninstall_plugin(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: PluginUninstallParams = decode_params(params)?;
    state
        .plugin_registry()
        .uninstall_plugin(&parsed.plugin_id, state.cache_dir())
        .map_err(|err| (error_codes::INVALID_PARAMS, err.to_string()))?;
    state
        .audit()
        .record(parsed.plugin_id.clone(), "plugins.uninstall", true, "");
    state.on_plugins_changed();
    Ok(json!({
        "ok": true,
        "pluginId": parsed.plugin_id,
        "snapshot": state.plugins_snapshot(),
    }))
}

pub(super) fn handle_plugins_audit(state: &Arc<HubState>) -> Result<Value, (i32, String)> {
    Ok(json!({
        "entries": state.audit().recent(),
        "diagnostics": state.host_diagnostics(),
    }))
}
