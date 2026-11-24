//! Plugin-scoped sandboxed RPC handlers (`plugin.invoke`, `fs.*`, `system.exec`, `hud.*`).

use std::sync::Arc;
use std::time::Duration;

use serde_json::{Value, json};
use sideveil_protocol::hud::{HudDismissPayload, NativeHudScene};
use sideveil_protocol::plugin::{
    PluginBusPublishParams, PluginCapability, PluginInvokeParams, validate_plugin_id,
};
use sideveil_protocol::rpc::error_codes;

use super::{decode_params, require_plugin_id, serialize_result};
use crate::plugin_acl;
use crate::rate_limiter::RateLimitBucket;
use crate::state::HubState;
use crate::telemetry;

pub(super) async fn handle_plugin_invoke(
    state: &Arc<HubState>,
    allow_explicit_target: bool,
    caller_plugin_id: Option<&str>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: PluginInvokeParams = decode_params(params)?;
    let target_plugin_id = if allow_explicit_target {
        if let Some(explicit) = parsed
            .plugin_id
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
        {
            validate_plugin_id(explicit)
                .map_err(|err| (error_codes::INVALID_PARAMS, err.to_string()))?;
            explicit.to_owned()
        } else {
            require_plugin_id(caller_plugin_id)?.to_owned()
        }
    } else {
        // WebView callers are strictly bound to their server-stamped pluginId.
        require_plugin_id(caller_plugin_id)?.to_owned()
    };

    if parsed.method.trim().is_empty() {
        return Err((
            error_codes::INVALID_PARAMS,
            "plugin.invoke method must not be blank".to_owned(),
        ));
    }

    let timeout = Duration::from_millis(parsed.effective_timeout_ms());
    state
        .plugin_backends()
        .invoke(&target_plugin_id, parsed.method, parsed.params, timeout)
        .await
}

pub(super) fn handle_fs_read(
    state: &Arc<HubState>,
    caller_plugin_id: Option<&str>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let pid = require_plugin_id(caller_plugin_id)?;
    let res = plugin_acl::handle_fs_read(state.plugin_registry(), state.data_dir(), pid, params);
    let detail = res.as_ref().err().map_or("", |(_, msg)| msg.as_str());
    state.audit().record(pid, "fs.read", res.is_ok(), detail);
    res
}

pub(super) fn handle_fs_write_atomic(
    state: &Arc<HubState>,
    caller_plugin_id: Option<&str>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let pid = require_plugin_id(caller_plugin_id)?;
    let res =
        plugin_acl::handle_fs_write_atomic(state.plugin_registry(), state.data_dir(), pid, params);
    let detail = res.as_ref().err().map_or("", |(_, msg)| msg.as_str());
    state
        .audit()
        .record(pid, "fs.writeAtomic", res.is_ok(), detail);
    res
}

pub(super) async fn handle_system_exec(
    state: &Arc<HubState>,
    caller_plugin_id: Option<&str>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let pid = require_plugin_id(caller_plugin_id)?;
    if !state
        .rate_limiter()
        .check_and_record(pid, RateLimitBucket::SystemExec)
    {
        state
            .audit()
            .record(pid, "system.exec", false, "rate limited");
        return Err((
            error_codes::RATE_LIMITED,
            format!("Plugin '{pid}' exceeded system.exec rate limit"),
        ));
    }
    match plugin_acl::handle_system_exec(state.plugin_registry(), pid, params).await {
        Ok(outcome) => {
            state
                .audit()
                .record(pid, "system.exec", true, outcome.audit_detail);
            Ok(outcome.response)
        }
        Err((code, msg)) => {
            state.audit().record(pid, "system.exec", false, msg.clone());
            Err((code, msg))
        }
    }
}

pub(super) fn handle_bus_publish(
    state: &Arc<HubState>,
    caller_plugin_id: Option<&str>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let pid = require_plugin_id(caller_plugin_id)?;
    if !state
        .plugin_registry()
        .has_capability(pid, PluginCapability::InterPluginBus)
    {
        state.audit().record(
            pid,
            "plugin.busPublish",
            false,
            "missing inter-plugin-bus capability",
        );
        return Err((
            error_codes::PERMISSION_DENIED,
            format!("Plugin '{pid}' lacks 'inter-plugin-bus' capability"),
        ));
    }
    if !state
        .rate_limiter()
        .check_and_record(pid, RateLimitBucket::BusPublish)
    {
        state
            .audit()
            .record(pid, "plugin.busPublish", false, "rate limited");
        return Err((
            error_codes::RATE_LIMITED,
            format!("Plugin '{pid}' exceeded plugin.busPublish rate limit"),
        ));
    }
    let parsed: PluginBusPublishParams = decode_params(params)?;
    let topic = parsed.topic.trim().to_owned();
    if topic.is_empty() || topic.len() > 96 {
        return Err((
            error_codes::INVALID_PARAMS,
            "bus topic must be 1..=96 non-blank chars".to_owned(),
        ));
    }
    state
        .audit()
        .record(pid, "plugin.busPublish", true, topic.clone());
    state.emit_plugin_bus_message(pid, topic, parsed.payload);
    Ok(json!({ "ok": true }))
}

pub(super) fn handle_telemetry_snapshot(
    state: &Arc<HubState>,
    is_privileged: bool,
    caller_plugin_id: Option<&str>,
) -> Result<Value, (i32, String)> {
    if !is_privileged {
        let pid = require_plugin_id(caller_plugin_id)?;
        if !state
            .plugin_registry()
            .has_capability(pid, PluginCapability::Telemetry)
        {
            state.audit().record(
                pid,
                "telemetry.snapshot",
                false,
                "missing telemetry capability",
            );
            return Err((
                error_codes::PERMISSION_DENIED,
                format!("Plugin '{pid}' lacks 'telemetry' capability"),
            ));
        }
    }
    serialize_result(&telemetry::sample_snapshot())
}

pub(super) fn handle_network_context(
    state: &Arc<HubState>,
    is_privileged: bool,
    caller_plugin_id: Option<&str>,
) -> Result<Value, (i32, String)> {
    if !is_privileged {
        let pid = require_plugin_id(caller_plugin_id)?;
        if !state
            .plugin_registry()
            .has_capability(pid, PluginCapability::NetworkContext)
        {
            state.audit().record(
                pid,
                "network.context",
                false,
                "missing network-context capability",
            );
            return Err((
                error_codes::PERMISSION_DENIED,
                format!("Plugin '{pid}' lacks 'network-context' capability"),
            ));
        }
    }
    serialize_result(&state.network_context())
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
        if !state
            .rate_limiter()
            .check_and_record(pid, RateLimitBucket::HudUpdate)
        {
            return Err((
                error_codes::RATE_LIMITED,
                format!("Plugin '{pid}' exceeded hud.updateScene rate limit"),
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
        Some(pid.to_owned())
    };
    state.dismiss_hud(target);
    Ok(json!({ "ok": true }))
}
