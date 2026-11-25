//! Async JSON-RPC 2.0 request dispatcher shared by `hub.sock` CLI and `SideVeilHost` WS.

mod control;
mod daemon;
mod debug;
mod plugins;
mod sandbox;

use std::sync::Arc;

use serde_json::{Value, json};
use sideveil_protocol::plugin::BUILTIN_DIAG_PLUGIN_ID;
use sideveil_protocol::rpc::{
    PanelCommand, PingResult, RpcRequest, RpcResponse, error_codes, methods,
};

use crate::state::HubState;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RpcCaller {
    /// Privileged root CLI (`sideveil-ctl` / Control Plane) connected over `hub.sock`.
    Ctl,
    /// Sandboxed WebView panel forwarded by `SideVeilHost` WS (carries trusted `pluginId`).
    HostWebView,
}

pub async fn dispatch_rpc_text(
    state: &Arc<HubState>,
    body: &str,
    caller: RpcCaller,
) -> RpcResponse {
    match serde_json::from_str::<RpcRequest>(body) {
        Ok(req) => dispatch_rpc_request(state, req, caller).await,
        Err(err) => RpcResponse::err("0", error_codes::PARSE_ERROR, format!("Parse error: {err}")),
    }
}

pub async fn dispatch_rpc_request(
    state: &Arc<HubState>,
    req: RpcRequest,
    caller: RpcCaller,
) -> RpcResponse {
    let id = req.id.clone();
    match dispatch_inner(state, req, caller).await {
        Ok(val) => RpcResponse::ok(id, val),
        Err((code, msg)) => RpcResponse::err(id, code, msg),
    }
}

async fn dispatch_inner(
    state: &Arc<HubState>,
    req: RpcRequest,
    caller: RpcCaller,
) -> Result<Value, (i32, String)> {
    let is_ctl = caller == RpcCaller::Ctl;
    // Built-in diagnostic WebView gets diagnostic-plane access ONLY when not redirected to a dev server.
    let is_builtin_diag = caller == RpcCaller::HostWebView
        && req.plugin_id.as_deref() == Some(BUILTIN_DIAG_PLUGIN_ID)
        && !state.plugin_registry().has_dev_url(BUILTIN_DIAG_PLUGIN_ID);
    let is_diag_or_ctl = is_ctl || is_builtin_diag;

    match req.method.as_str() {
        methods::DAEMON_PING => serialize_result(&PingResult {
            pong: true,
            daemon_uptime_ms: state.uptime_ms(),
            timestamp_ms: HubState::now_unix_ms(),
        }),
        methods::DAEMON_STATUS => {
            require_role(is_diag_or_ctl, &req.method, "host diagnostic/control plane")?;
            serialize_result(&state.build_status())
        }
        methods::DAEMON_HEALTH => {
            require_role(is_diag_or_ctl, &req.method, "host diagnostic/control plane")?;
            serialize_result(&state.build_health())
        }
        methods::CONFIG_GET => {
            require_role(is_diag_or_ctl, &req.method, "host diagnostic/control plane")?;
            serialize_result(&state.config_store().current())
        }
        methods::PLUGINS_LIST => {
            require_role(is_diag_or_ctl, &req.method, "host diagnostic/control plane")?;
            serialize_result(&state.plugins_snapshot())
        }
        methods::DAEMON_LOGS => {
            require_role(is_diag_or_ctl, &req.method, "host diagnostic/control plane")?;
            daemon::handle_daemon_logs(state, req.params)
        }
        methods::DAEMON_LOGS_CLEAR => {
            require_role(is_diag_or_ctl, &req.method, "host diagnostic/control plane")?;
            daemon::handle_daemon_logs_clear(state)
        }
        methods::CONFIG_RELOAD => {
            require_role(is_ctl, &req.method, "root control plane")?;
            let cfg = state.config_store().reload();
            state.on_config_changed(&cfg);
            serialize_result(&cfg)
        }
        methods::CONFIG_PATCH_APPEARANCE => {
            require_role(is_diag_or_ctl, &req.method, "host diagnostic/control plane")?;
            control::handle_patch_appearance(state, req.params)
        }
        methods::CONFIG_PATCH_EDGE => {
            require_role(is_ctl, &req.method, "root control plane")?;
            control::handle_patch_edge(state, req.params)
        }
        methods::PANEL_OPEN => {
            require_role(is_ctl, &req.method, "root control plane")?;
            control::handle_panel_open(state, req.params)
        }
        methods::PANEL_CLOSE => {
            require_role(is_diag_or_ctl, &req.method, "host diagnostic/control plane")?;
            state.send_panel_command(PanelCommand::Close);
            Ok(json!({ "ok": true }))
        }
        methods::PANEL_TOGGLE => {
            require_role(is_ctl, &req.method, "root control plane")?;
            state.send_panel_command(PanelCommand::Toggle);
            Ok(json!({ "ok": true }))
        }
        methods::HOST_RESTART => {
            require_role(is_ctl, &req.method, "root control plane")?;
            state.request_host_restart();
            Ok(json!({ "ok": true }))
        }
        methods::PLUGINS_RELOAD => {
            require_role(is_ctl, &req.method, "root control plane")?;
            let count = state.plugin_registry().rescan();
            state.daemon_manager().notify_reconcile();
            state.on_plugins_changed();
            Ok(json!({ "ok": true, "count": count, "snapshot": state.plugins_snapshot() }))
        }
        methods::PLUGINS_INSTALL => {
            require_role(is_ctl, &req.method, "root control plane")?;
            plugins::handle_install_plugin(state, req.params)
        }
        methods::PLUGINS_INSPECT_ARCHIVE => {
            require_role(is_ctl, &req.method, "root control plane")?;
            plugins::handle_inspect_archive(state, req.params)
        }
        methods::PLUGINS_GRANT_CAPABILITIES => {
            require_role(is_ctl, &req.method, "root control plane")?;
            plugins::handle_grant_capabilities(state, req.params)
        }
        methods::PLUGINS_DAEMON_LOGS => {
            require_role(is_ctl, &req.method, "root control plane")?;
            plugins::handle_daemon_logs(state, req.params)
        }
        methods::PLUGINS_RELOAD_WEBVIEW => {
            require_role(is_ctl, &req.method, "root control plane")?;
            plugins::handle_reload_webview(state, req.params)
        }
        methods::PLUGINS_SET_ENABLED => {
            require_role(is_ctl, &req.method, "root control plane")?;
            plugins::handle_set_enabled(state, req.params)
        }
        methods::PLUGINS_SET_PRIORITY => {
            require_role(is_ctl, &req.method, "root control plane")?;
            plugins::handle_set_priority(state, req.params)
        }
        methods::PLUGINS_SET_SCOPE_OVERRIDE => {
            require_role(is_ctl, &req.method, "root control plane")?;
            plugins::handle_set_scope_override(state, req.params)
        }
        methods::PLUGINS_SET_DEV_URL => {
            require_role(is_ctl, &req.method, "root control plane")?;
            plugins::handle_set_dev_url(state, req.params)
        }
        methods::PLUGINS_SET_EMBED_CONFIG => {
            require_role(is_ctl, &req.method, "root control plane")?;
            plugins::handle_set_embed_config(state, req.params)
        }
        methods::PLUGINS_CREATE_WEB_EMBED => {
            require_role(is_ctl, &req.method, "root control plane")?;
            plugins::handle_create_web_embed(state, req.params)
        }
        methods::PLUGINS_UNINSTALL => {
            require_role(is_ctl, &req.method, "root control plane")?;
            plugins::handle_uninstall_plugin(state, req.params)
        }
        methods::PLUGINS_AUDIT => {
            require_role(is_diag_or_ctl, &req.method, "host diagnostic/control plane")?;
            plugins::handle_plugins_audit(state)
        }
        methods::PLUGINS_SELECT => {
            require_role(is_ctl, &req.method, "root control plane")?;
            plugins::handle_select_plugin(state, req.params)
        }
        methods::NETWORK_CONTEXT => {
            sandbox::handle_network_context(state, is_diag_or_ctl, req.plugin_id.as_deref())
        }
        methods::PLUGIN_INVOKE => {
            sandbox::handle_plugin_invoke(state, is_ctl, req.plugin_id.as_deref(), req.params).await
        }
        methods::PLUGIN_BUS_PUBLISH => {
            sandbox::handle_bus_publish(state, req.plugin_id.as_deref(), req.params)
        }
        methods::TELEMETRY_SNAPSHOT => {
            sandbox::handle_telemetry_snapshot(state, is_diag_or_ctl, req.plugin_id.as_deref())
        }
        methods::FS_READ => sandbox::handle_fs_read(state, req.plugin_id.as_deref(), req.params),
        methods::FS_WRITE_ATOMIC => {
            sandbox::handle_fs_write_atomic(state, req.plugin_id.as_deref(), req.params)
        }
        methods::SYSTEM_EXEC => {
            sandbox::handle_system_exec(state, req.plugin_id.as_deref(), req.params).await
        }
        methods::HUD_UPDATE_SCENE => {
            sandbox::handle_hud_update(state, is_diag_or_ctl, req.plugin_id.as_deref(), req.params)
        }
        methods::HUD_DISMISS => {
            sandbox::handle_hud_dismiss(state, is_diag_or_ctl, req.plugin_id.as_deref(), req.params)
        }
        methods::DEBUG_INSPECT_UI => {
            require_role(is_diag_or_ctl, &req.method, "host diagnostic/control plane")?;
            debug::handle_inspect_ui(state, req.params).await
        }
        methods::DEBUG_CDP_STATUS => {
            require_role(is_diag_or_ctl, &req.method, "host diagnostic/control plane")?;
            debug::handle_cdp_status(state)
        }
        methods::DEBUG_SET_WEBVIEW_DEBUGGING => {
            require_role(is_ctl, &req.method, "root control plane")?;
            debug::handle_set_webview_debugging(state, req.params)
        }
        methods::DEBUG_RESET_FRAME_METRICS => {
            require_role(is_diag_or_ctl, &req.method, "host diagnostic/control plane")?;
            debug::handle_reset_frame_metrics(state).await
        }
        other => Err((
            error_codes::METHOD_NOT_FOUND,
            format!("Method not found: {other}"),
        )),
    }
}

fn require_role(allowed: bool, method: &str, role: &str) -> Result<(), (i32, String)> {
    if allowed {
        Ok(())
    } else {
        Err((
            error_codes::PERMISSION_DENIED,
            format!("Method '{method}' is restricted to {role}"),
        ))
    }
}

pub(crate) fn require_plugin_id(plugin_id: Option<&str>) -> Result<&str, (i32, String)> {
    plugin_id
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .ok_or_else(|| {
            (
                error_codes::PERMISSION_DENIED,
                "Plugin-scoped RPC requires an authenticated pluginId".to_owned(),
            )
        })
}

pub(crate) fn decode_params<T: serde::de::DeserializeOwned>(
    params: Option<Value>,
) -> Result<T, (i32, String)> {
    let Some(val) = params else {
        return Err((
            error_codes::INVALID_PARAMS,
            "Missing required params object".to_owned(),
        ));
    };
    serde_json::from_value(val).map_err(|err| {
        (
            error_codes::INVALID_PARAMS,
            format!("Invalid params: {err}"),
        )
    })
}

pub(crate) fn serialize_result<T: serde::Serialize>(value: &T) -> Result<Value, (i32, String)> {
    serde_json::to_value(value).map_err(|err| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed to encode response: {err}"),
        )
    })
}

#[cfg(test)]
mod tests;
#[cfg(test)]
mod tests_debug;
#[cfg(test)]
mod tests_debug_logs;
#[cfg(test)]
mod tests_embed;
#[cfg(test)]
mod tests_panel;
