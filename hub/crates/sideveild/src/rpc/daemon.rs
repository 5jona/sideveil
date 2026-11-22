//! Daemon log-ring JSON-RPC handlers (`daemon.logs`, `daemon.logsClear`).

use std::sync::Arc;

use serde_json::{Value, json};
use sideveil_protocol::rpc::{DaemonLogsParams, error_codes};

use super::serialize_result;
use crate::state::HubState;

pub(super) fn handle_daemon_logs(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let parsed = match params {
        Some(Value::Null) | None => DaemonLogsParams::default(),
        Some(val) => serde_json::from_value::<DaemonLogsParams>(val).map_err(|err| {
            (
                error_codes::INVALID_PARAMS,
                format!("Invalid daemon.logs params: {err}"),
            )
        })?,
    };
    let result = state.logs().query(
        parsed.since_seq,
        parsed.limit,
        parsed.level.as_deref(),
        parsed.source.as_deref(),
    );
    serialize_result(&result)
}

pub(super) fn handle_daemon_logs_clear(state: &Arc<HubState>) -> Result<Value, (i32, String)> {
    let next_seq = state.logs().clear();
    Ok(json!({ "ok": true, "nextSeq": next_seq }))
}
