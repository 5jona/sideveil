//! Privileged debugging JSON-RPC handlers (`debug.inspectUi`, `debug.setWebViewDebugging`).

use std::sync::{Arc, PoisonError};
use std::time::Duration;

use serde_json::Value;
use sideveil_protocol::rpc::{
    DebugCdpStatusResult, DebugSetWebViewDebuggingParams, error_codes, methods,
};

use super::{decode_params, serialize_result};
use crate::state::HubState;

const INSPECT_UI_TIMEOUT: Duration = Duration::from_secs(5);

pub(super) async fn handle_inspect_ui(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    state
        .invoke_host(methods::DEBUG_INSPECT_UI, params, INSPECT_UI_TIMEOUT)
        .await
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

    let host_pid = state
        .runtime
        .read()
        .unwrap_or_else(PoisonError::into_inner)
        .host_pid;
    let restart_recommended = prev_enabled && !parsed.enabled && host_pid.is_some();
    let cdp_socket = if parsed.enabled {
        host_pid.map(|pid| format!("webview_devtools_remote_{pid}"))
    } else {
        None
    };

    serialize_result(&DebugCdpStatusResult {
        ok: true,
        webview_debugging_enabled: updated.debug.webview_debugging_enabled,
        host_pid,
        cdp_socket,
        restart_recommended,
    })
}
