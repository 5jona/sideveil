//! Plugin registry administration JSON-RPC handlers (`plugins.*`).

use std::sync::Arc;

use serde_json::{Value, json};
use sideveil_protocol::plugin::{
    PluginCapability, PluginCreateWebEmbedParams, PluginDaemonLogsParams, PluginDaemonLogsResult,
    PluginDaemonStatus, PluginGrantCapabilitiesParams, PluginInspectArchiveParams,
    PluginInstallParams, PluginReloadWebViewParams, PluginSelectParams, PluginSetDevUrlParams,
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
    let source_desc = if parsed.archive_base64.is_some() {
        parsed
            .filename
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .unwrap_or("archiveBase64")
            .to_owned()
    } else {
        parsed
            .source_path
            .as_deref()
            .or(parsed.source_dir.as_deref())
            .map(str::trim)
            .unwrap_or_default()
            .to_owned()
    };
    let manifest = state
        .plugin_registry()
        .install_from_params(&parsed)
        .map_err(|err| (error_codes::INVALID_PARAMS, err.to_string()))?;
    state.audit().record(
        manifest.id.clone(),
        "plugins.install",
        true,
        format!("v{} from {source_desc}", manifest.version),
    );
    state.daemon_manager().notify_reconcile();
    state.on_plugins_changed();
    Ok(json!({
        "ok": true,
        "pluginId": manifest.id,
        "version": manifest.version,
        "snapshot": state.plugins_snapshot(),
    }))
}

pub(super) fn handle_inspect_archive(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: PluginInspectArchiveParams = decode_params(params)?;
    let report = state
        .plugin_registry()
        .inspect_archive(&parsed)
        .map_err(|err| (error_codes::INVALID_PARAMS, err.to_string()))?;
    serialize_result(&report)
}

pub(super) fn handle_grant_capabilities(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: PluginGrantCapabilitiesParams = decode_params(params)?;
    state
        .plugin_registry()
        .grant_capabilities(&parsed)
        .map_err(|err| (error_codes::INVALID_PARAMS, err.to_string()))?;

    if parsed.revoke_all || parsed.approve_daemon == Some(false) {
        state.plugin_backends().disconnect_plugin(&parsed.plugin_id);
    }
    if !state
        .plugin_registry()
        .has_capability(&parsed.plugin_id, PluginCapability::NativeHud)
    {
        state.dismiss_hud(Some(parsed.plugin_id.clone()));
    }
    state.daemon_manager().notify_reconcile();
    state.audit().record(
        parsed.plugin_id.clone(),
        "plugins.grantCapabilities",
        true,
        format!(
            "allPending={} revokeAll={} approveDaemon={:?}",
            parsed.grant_all_pending, parsed.revoke_all, parsed.approve_daemon
        ),
    );
    state.on_plugins_changed();
    serialize_result(&state.plugins_snapshot())
}

pub(super) fn handle_daemon_logs(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: PluginDaemonLogsParams = decode_params(params)?;
    if !state.plugin_registry().contains_plugin(&parsed.plugin_id) {
        return Err((
            error_codes::INVALID_PARAMS,
            format!("plugin '{}' is not registered", parsed.plugin_id),
        ));
    }
    let limit = parsed.limit.unwrap_or(100);
    let lines = state.daemon_manager().daemon_logs(&parsed.plugin_id, limit);
    let info = state.daemon_manager().status_of(&parsed.plugin_id);
    let (status, pid, restart_count, last_error) = info
        .map_or((PluginDaemonStatus::NotConfigured, None, 0, None), |i| {
            (i.status, i.pid, i.restart_count, i.last_error)
        });
    serialize_result(&PluginDaemonLogsResult {
        plugin_id: parsed.plugin_id,
        status,
        pid,
        restart_count,
        last_error,
        lines,
    })
}

pub(super) fn handle_reload_webview(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: PluginReloadWebViewParams = decode_params(params)?;
    if !state.plugin_registry().contains_plugin(&parsed.plugin_id) {
        return Err((
            error_codes::INVALID_PARAMS,
            format!("plugin '{}' is not registered", parsed.plugin_id),
        ));
    }
    state.send_panel_reload_plugin(parsed.plugin_id.clone());
    state
        .audit()
        .record(parsed.plugin_id.clone(), "plugins.reloadWebView", true, "");
    Ok(json!({
        "ok": true,
        "pluginId": parsed.plugin_id,
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
    if !parsed.enabled {
        state.plugin_backends().disconnect_plugin(&parsed.plugin_id);
        state.dismiss_hud(Some(parsed.plugin_id));
    }
    state.daemon_manager().notify_reconcile();
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
    state.plugin_backends().disconnect_plugin(&parsed.plugin_id);
    state.dismiss_hud(Some(parsed.plugin_id.clone()));
    state
        .plugin_registry()
        .uninstall_plugin(&parsed.plugin_id, state.cache_dir(), Some(state.data_dir()))
        .map_err(|err| (error_codes::INVALID_PARAMS, err.to_string()))?;
    state.daemon_manager().notify_reconcile();
    state.daemon_manager().remove_plugin(&parsed.plugin_id);
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
