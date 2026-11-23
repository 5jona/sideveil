//! Privileged debugging JSON-RPC handlers (`debug.inspectUi`, `debug.cdpStatus`,
//! `debug.setWebViewDebugging`, `debug.resetFrameMetrics`, `debug.setRuntimeMode`).

use std::sync::{Arc, PoisonError};
use std::time::Duration;

use serde_json::{Value, json};
use sideveil_protocol::config::RuntimeMode;
use sideveil_protocol::generated::protocol_constants::builtin_test_plugins;
use sideveil_protocol::rpc::{
    DebugCdpStatusResult, DebugInspectUiParams, DebugSetRuntimeModeParams,
    DebugSetRuntimeModeResult, DebugSetWebViewDebuggingParams, error_codes, methods,
};

use super::{decode_params, serialize_result};
use crate::state::HubState;

const INSPECT_UI_WIRE_MARGIN_MS: u64 = 1_500;
const RESET_FRAME_METRICS_TIMEOUT: Duration = Duration::from_secs(5);

pub(super) async fn handle_inspect_ui(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed = match params {
        Some(Value::Null) | None => DebugInspectUiParams::default(),
        Some(val) => serde_json::from_value::<DebugInspectUiParams>(val).map_err(|err| {
            (
                error_codes::INVALID_PARAMS,
                format!("Invalid debug.inspectUi params: {err}"),
            )
        })?,
    };
    let inner_timeout_ms = parsed.effective_timeout_ms();
    let outer_timeout =
        Duration::from_millis(inner_timeout_ms.saturating_add(INSPECT_UI_WIRE_MARGIN_MS));
    let mut result = state
        .invoke_host(
            methods::DEBUG_INSPECT_UI,
            Some(json!({ "timeoutMs": inner_timeout_ms })),
            outer_timeout,
        )
        .await?;

    if let Value::Object(ref mut map) = result
        && let Some(stats) = state.gesture_stats()
        && let Ok(stats_val) = serde_json::to_value(&stats)
    {
        map.insert("gestureStats".to_owned(), stats_val);
    }
    Ok(result)
}

pub(super) async fn handle_reset_frame_metrics(
    state: &Arc<HubState>,
) -> Result<Value, (i32, String)> {
    let _ = state
        .invoke_host(
            methods::DEBUG_RESET_FRAME_METRICS,
            None,
            RESET_FRAME_METRICS_TIMEOUT,
        )
        .await?;
    Ok(json!({ "ok": true }))
}

pub(super) fn handle_cdp_status(state: &Arc<HubState>) -> Result<Value, (i32, String)> {
    let cfg = state.config_store().current();
    let status = build_cdp_status(state, cfg.debug.webview_debugging_enabled, false);
    serialize_result(&status)
}

pub(super) fn handle_set_webview_debugging(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: DebugSetWebViewDebuggingParams = decode_params(params)?;
    let (prev_enabled, updated) = state
        .config_store()
        .set_webview_debugging(parsed.enabled)
        .map_err(|err| {
            (
                error_codes::INTERNAL_ERROR,
                format!("Failed to persist webview debugging flag: {err}"),
            )
        })?;
    state.on_config_changed(&updated);

    let dev_server_active = state.plugin_registry().has_active_dev_server();
    let prev_effective = prev_enabled || dev_server_active;
    let next_effective = updated.debug.webview_debugging_enabled || dev_server_active;
    let host_pid = current_host_pid(state);
    let restart_recommended = prev_effective && !next_effective && host_pid.is_some();

    let status = build_cdp_status(
        state,
        updated.debug.webview_debugging_enabled,
        restart_recommended,
    );
    serialize_result(&status)
}

pub(super) fn handle_set_runtime_mode(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed: DebugSetRuntimeModeParams = decode_params(params)?;
    let (previous_mode, updated) = state
        .config_store()
        .set_runtime_mode(parsed.runtime_mode)
        .map_err(|err| {
            (
                error_codes::INTERNAL_ERROR,
                format!("Failed to persist runtime mode: {err}"),
            )
        })?;
    if updated.debug.runtime_mode == RuntimeMode::Release {
        state.dismiss_hud(Some(builtin_test_plugins::NATIVE_PERF_HUD.to_owned()));
    }
    state.on_config_changed(&updated);
    let snapshot = state.plugins_snapshot();
    state.logs().info(format!(
        "Runtime mode switched: {:?} -> {:?} (matched={} active={:?})",
        previous_mode,
        updated.debug.runtime_mode,
        snapshot.matched_plugin_ids.len(),
        snapshot.active_plugin_id
    ));
    serialize_result(&DebugSetRuntimeModeResult {
        ok: true,
        runtime_mode: updated.debug.runtime_mode,
        previous_mode,
        snapshot,
    })
}

fn build_cdp_status(
    state: &Arc<HubState>,
    webview_debugging_enabled: bool,
    restart_recommended: bool,
) -> DebugCdpStatusResult {
    let dev_server_active = state.plugin_registry().has_active_dev_server();
    let effective_enabled = webview_debugging_enabled || dev_server_active;
    let host_pid = current_host_pid(state);
    let cdp_socket = if effective_enabled {
        host_pid.map(|pid| format!("webview_devtools_remote_{pid}"))
    } else {
        None
    };

    DebugCdpStatusResult {
        ok: true,
        webview_debugging_enabled,
        effective_enabled,
        dev_server_active,
        host_pid,
        cdp_socket,
        restart_recommended,
    }
}

fn current_host_pid(state: &Arc<HubState>) -> Option<u32> {
    state
        .runtime
        .read()
        .unwrap_or_else(PoisonError::into_inner)
        .host_pid
}
