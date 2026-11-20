//! Async JSON-RPC 2.0 request dispatcher shared by `hub.sock` CLI and `SideveilHost` WS.

use std::sync::Arc;

use serde_json::{Value, json};
use sideveil_protocol::config::AppearancePatchParams;
use sideveil_protocol::plugin::{
    BUILTIN_DIAG_PLUGIN_ID, PluginInvokeParams, PluginSelectParams, PluginSetEnabledParams,
    PluginSetPriorityParams, PluginSetScopeOverrideParams,
};
use sideveil_protocol::rpc::{
    PanelCommand, PingResult, RpcRequest, RpcResponse, error_codes, methods,
};

use crate::plugin_acl;
use crate::state::HubState;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RpcCaller {
    /// Privileged root CLI (`sideveil-ctl`) connected over `hub.sock`.
    Ctl,
    /// Sandboxed WebView panel forwarded by `SideveilHost` WS (carries trusted `pluginId`).
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
    let is_privileged =
        caller == RpcCaller::Ctl || req.plugin_id.as_deref() == Some(BUILTIN_DIAG_PLUGIN_ID);

    match req.method.as_str() {
        methods::DAEMON_PING => serialize_result(&PingResult {
            pong: true,
            daemon_uptime_ms: state.uptime_ms(),
            timestamp_ms: HubState::now_unix_ms(),
        }),
        methods::DAEMON_STATUS => serialize_result(&state.build_status()),
        methods::DAEMON_HEALTH => serialize_result(&state.build_health()),
        methods::CONFIG_GET => serialize_result(&state.config_store().current()),
        methods::PLUGINS_LIST => serialize_result(&state.plugins_snapshot()),
        methods::DAEMON_LOGS => {
            require_privileged(is_privileged, &req.method)?;
            Ok(json!({ "lines": state.logs().recent() }))
        }
        methods::CONFIG_RELOAD => {
            require_privileged(is_privileged, &req.method)?;
            let cfg = state.config_store().reload();
            state.on_config_changed(&cfg);
            serialize_result(&cfg)
        }
        methods::CONFIG_PATCH_APPEARANCE => {
            require_privileged(is_privileged, &req.method)?;
            handle_patch_appearance(state, req.params)
        }
        methods::PANEL_OPEN => {
            require_privileged(is_privileged, &req.method)?;
            state.send_panel_command(PanelCommand::Open);
            Ok(json!({ "ok": true }))
        }
        methods::PANEL_CLOSE => {
            require_privileged(is_privileged, &req.method)?;
            state.send_panel_command(PanelCommand::Close);
            Ok(json!({ "ok": true }))
        }
        methods::PANEL_TOGGLE => {
            require_privileged(is_privileged, &req.method)?;
            state.send_panel_command(PanelCommand::Toggle);
            Ok(json!({ "ok": true }))
        }
        methods::HOST_RESTART => {
            require_privileged(is_privileged, &req.method)?;
            state.request_host_restart();
            Ok(json!({ "ok": true }))
        }
        methods::PLUGINS_RELOAD => {
            require_privileged(is_privileged, &req.method)?;
            let count = state.plugin_registry().rescan();
            state.on_plugins_changed();
            Ok(json!({ "ok": true, "count": count, "snapshot": state.plugins_snapshot() }))
        }
        methods::PLUGINS_SET_ENABLED => {
            require_privileged(is_privileged, &req.method)?;
            handle_set_enabled(state, req.params)
        }
        methods::PLUGINS_SET_PRIORITY => {
            require_privileged(is_privileged, &req.method)?;
            handle_set_priority(state, req.params)
        }
        methods::PLUGINS_SET_SCOPE_OVERRIDE => {
            require_privileged(is_privileged, &req.method)?;
            handle_set_scope_override(state, req.params)
        }
        methods::PLUGINS_SELECT => {
            require_privileged(is_privileged, &req.method)?;
            handle_select_plugin(state, req.params)
        }
        methods::PLUGIN_INVOKE => {
            handle_plugin_invoke(state, req.plugin_id.as_deref(), req.params).await
        }
        methods::FS_READ => {
            let pid = require_plugin_id(req.plugin_id.as_deref())?;
            plugin_acl::handle_fs_read(state.plugin_registry(), state.data_dir(), pid, req.params)
        }
        methods::FS_WRITE_ATOMIC => {
            let pid = require_plugin_id(req.plugin_id.as_deref())?;
            plugin_acl::handle_fs_write_atomic(
                state.plugin_registry(),
                state.data_dir(),
                pid,
                req.params,
            )
        }
        methods::SYSTEM_EXEC => {
            let pid = require_plugin_id(req.plugin_id.as_deref())?;
            plugin_acl::handle_system_exec(state.plugin_registry(), pid, req.params).await
        }
        other => Err((
            error_codes::METHOD_NOT_FOUND,
            format!("Method not found: {other}"),
        )),
    }
}

fn require_privileged(allowed: bool, method: &str) -> Result<(), (i32, String)> {
    if allowed {
        Ok(())
    } else {
        Err((
            error_codes::PERMISSION_DENIED,
            format!("Method '{method}' is restricted to host control plane"),
        ))
    }
}

fn require_plugin_id(plugin_id: Option<&str>) -> Result<&str, (i32, String)> {
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

async fn handle_plugin_invoke(
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

fn handle_set_enabled(
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

fn handle_set_priority(
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

fn handle_set_scope_override(
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

fn handle_select_plugin(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: PluginSelectParams = decode_params(params)?;
    let active = state
        .select_active_plugin(&parsed.plugin_id, parsed.package_name.as_deref())
        .map_err(|msg| (error_codes::INVALID_PARAMS, msg))?;
    serialize_result(&active)
}

fn handle_patch_appearance(
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
        .map_err(|err| {
            (
                error_codes::INTERNAL_ERROR,
                format!("Failed to persist appearance patch: {err}"),
            )
        })?;
    state.on_config_changed(&updated);
    serialize_result(&updated)
}

fn decode_params<T: serde::de::DeserializeOwned>(
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

fn serialize_result<T: serde::Serialize>(value: &T) -> Result<Value, (i32, String)> {
    serde_json::to_value(value).map_err(|err| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed to encode response: {err}"),
        )
    })
}

#[cfg(test)]
mod tests {
    use std::fs;

    use sideveil_protocol::DEFAULT_HOST_PACKAGE;
    use tokio::sync::mpsc;

    use super::*;
    use crate::config_store::ConfigStore;
    use crate::log_ring::LogRing;
    use crate::plugin_registry::PluginRegistry;

    #[tokio::test]
    async fn dispatches_core_rpc_methods_and_enforces_external_plugin_acl() {
        let dir = std::env::temp_dir().join(format!("sideveil-rpc-test-{}", std::process::id()));
        let _ = fs::create_dir_all(&dir);
        let logs = Arc::new(LogRing::new());
        let store = ConfigStore::open(dir.join("config.json"), Arc::clone(&logs));
        let registry = PluginRegistry::open(
            dir.join("plugins"),
            dir.join("plugins.json"),
            Arc::clone(&logs),
        );
        let (wake_tx, _wake_rx) = mpsc::unbounded_channel();
        let state = HubState::new(
            DEFAULT_HOST_PACKAGE.to_owned(),
            store,
            registry,
            dir.join("data"),
            logs,
            wake_tx,
        );

        let ping = dispatch_rpc_request(
            &state,
            RpcRequest::new("1", methods::DAEMON_PING, None),
            RpcCaller::Ctl,
        )
        .await;
        assert!(ping.error.is_none());
        assert_eq!(
            ping.result.as_ref().and_then(|v| v.get("pong")),
            Some(&json!(true))
        );

        let patched = dispatch_rpc_request(
            &state,
            RpcRequest::new(
                "2",
                methods::CONFIG_PATCH_APPEARANCE,
                Some(json!({ "colorMode": "light", "cornerRadiusDp": 30 })),
            ),
            RpcCaller::Ctl,
        )
        .await;
        assert!(patched.error.is_none());
        assert_eq!(state.config_store().current().panel.color_mode, "light");
        assert_eq!(state.config_store().current().panel.corner_radius_dp, 30);

        // External plugin cannot call host.restart.
        let mut ext_req = RpcRequest::new("3", methods::HOST_RESTART, None);
        ext_req.plugin_id = Some("com.example.external".to_owned());
        let denied = dispatch_rpc_request(&state, ext_req, RpcCaller::HostWebView).await;
        assert_eq!(
            denied.error.as_ref().map(|e| e.code),
            Some(error_codes::PERMISSION_DENIED)
        );

        // Unstamped HostWebView request must also be denied privileged methods.
        let unstamped_req = RpcRequest::new("3b", methods::HOST_RESTART, None);
        let unstamped_denied =
            dispatch_rpc_request(&state, unstamped_req, RpcCaller::HostWebView).await;
        assert_eq!(
            unstamped_denied.error.as_ref().map(|e| e.code),
            Some(error_codes::PERMISSION_DENIED)
        );

        let missing = dispatch_rpc_request(
            &state,
            RpcRequest::new("4", "unknown.method", None),
            RpcCaller::Ctl,
        )
        .await;
        assert_eq!(
            missing.error.as_ref().map(|e| e.code),
            Some(error_codes::METHOD_NOT_FOUND)
        );

        let _ = fs::remove_dir_all(&dir);
    }
}
