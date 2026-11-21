//! Host control-plane JSON-RPC handlers (`config.patchAppearance`, `config.patchEdge`).

use std::sync::Arc;

use serde_json::Value;
use sideveil_protocol::config::{AppearancePatchParams, EdgePatchParams};
use sideveil_protocol::rpc::error_codes;

use super::serialize_result;
use crate::state::HubState;

pub(super) fn handle_patch_appearance(
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

pub(super) fn handle_patch_edge(
    state: &Arc<HubState>,
    params: Option<Value>,
) -> Result<Value, (i32, String)> {
    let patch = match params {
        Some(val) => serde_json::from_value::<EdgePatchParams>(val).map_err(|err| {
            (
                error_codes::INVALID_PARAMS,
                format!("Invalid params: {err}"),
            )
        })?,
        None => EdgePatchParams::default(),
    };
    let updated = state.config_store().patch_edge(&patch).map_err(|err| {
        (
            error_codes::INTERNAL_ERROR,
            format!("Failed to persist edge patch: {err}"),
        )
    })?;
    state.on_config_changed(&updated);
    serialize_result(&updated)
}
