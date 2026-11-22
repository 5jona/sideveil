//! Partial configuration patch payload structs for control-plane RPCs.

use serde::{Deserialize, Serialize};

use super::EdgeSide;

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AppearancePatchParams {
    pub theme_id: Option<String>,
    pub color_mode: Option<String>,
    pub native_blur_enabled: Option<bool>,
    pub blur_radius_px: Option<i32>,
    pub corner_radius_dp: Option<i32>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct EdgePatchParams {
    pub side: Option<EdgeSide>,
    pub top_percent: Option<i32>,
}
