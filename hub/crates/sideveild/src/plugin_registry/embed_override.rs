//! Web-embed runtime override validation and mutation (`set_embed_config`) for `PluginRegistry`.

use std::sync::PoisonError;

use sideveil_protocol::plugin::{PluginSetEmbedConfigParams, validate_network_url};

use super::PluginRegistry;
use super::error::PluginRegistryError;
use super::store::persist_overrides;

impl PluginRegistry {
    /// Updates or clears persisted web-embed configuration overrides for an installed plugin.
    pub fn set_embed_config(
        &self,
        params: &PluginSetEmbedConfigParams,
    ) -> Result<(), PluginRegistryError> {
        if !params.clear {
            validate_embed_bounds(params)?;
        }

        let to_save = {
            let mut guard = self.inner.write().unwrap_or_else(PoisonError::into_inner);
            let Some(loaded) = guard.plugins.get(&params.plugin_id) else {
                return Err(PluginRegistryError::PluginNotFound(
                    params.plugin_id.clone(),
                ));
            };
            let manifest_cleartext = loaded
                .manifest
                .embed
                .as_ref()
                .map(|e| e.allow_cleartext)
                .or_else(|| loaded.manifest.network.as_ref().map(|n| n.allow_cleartext))
                .unwrap_or(false);
            let existing_ov = guard.overrides.overrides.get(&params.plugin_id);
            let effective_cleartext = if params.clear {
                manifest_cleartext
            } else {
                params
                    .allow_cleartext
                    .or_else(|| existing_ov.and_then(|o| o.allow_cleartext))
                    .unwrap_or(manifest_cleartext)
            };

            let validated_url = validate_optional_url(
                params.clear,
                params.embed_url.as_deref(),
                effective_cleartext,
            )?;
            let validated_remote_url = validate_optional_url(
                params.clear,
                params.remote_url.as_deref(),
                effective_cleartext,
            )?;

            let clean_lan_ssids = if params.clear {
                None
            } else {
                params.lan_ssids.as_ref().map(|ssids| {
                    ssids
                        .iter()
                        .map(|s| s.trim().to_owned())
                        .filter(|s| !s.is_empty())
                        .collect::<Vec<String>>()
                })
            };

            let validated_origins = if params.clear {
                None
            } else if let Some(origins) = &params.allowed_origins {
                let mut clean = Vec::with_capacity(origins.len());
                for origin in origins {
                    let trimmed = origin.trim();
                    if trimmed.is_empty() {
                        continue;
                    }
                    clean.push(validate_network_url(trimmed, effective_cleartext)?);
                }
                Some(clean)
            } else {
                None
            };

            // If allow_cleartext is lowered to false while an existing embed_url or remote_url is http://,
            // reject the inconsistent override combination.
            if !params.clear && params.allow_cleartext == Some(false) {
                if params.embed_url.is_none()
                    && let Some(cur_url) = existing_ov
                        .and_then(|o| o.embed_url.as_deref())
                        .or_else(|| loaded.manifest.embed.as_ref().map(|e| e.url.as_str()))
                {
                    validate_network_url(cur_url, false)?;
                }
                if params.remote_url.is_none()
                    && let Some(cur_remote) = existing_ov
                        .and_then(|o| o.remote_url.as_deref())
                        .or_else(|| {
                            loaded
                                .manifest
                                .embed
                                .as_ref()
                                .and_then(|e| e.remote_url.as_deref())
                        })
                {
                    validate_network_url(cur_remote, false)?;
                }
            }

            let entry = guard
                .overrides
                .overrides
                .entry(params.plugin_id.clone())
                .or_default();
            apply_embed_override(
                entry,
                params,
                validated_url,
                validated_remote_url,
                clean_lan_ssids,
                validated_origins,
            );
            guard.overrides.clone()
        };
        persist_overrides(&self.overrides_path, &to_save).map_err(|source| {
            PluginRegistryError::Io {
                context: "failed to persist plugins.json".to_owned(),
                source,
            }
        })?;
        let _ = self.updates_tx.send(());
        Ok(())
    }
}

fn validate_embed_bounds(params: &PluginSetEmbedConfigParams) -> Result<(), PluginRegistryError> {
    if let Some(tz) = params.text_zoom
        && !(50..=200).contains(&tz)
    {
        return Err(PluginRegistryError::InvalidParameter {
            field: "textZoom",
            reason: "must be within 50..=200",
        });
    }
    if let Some(grace) = params.keep_alive_grace_sec
        && !(0..=1800).contains(&grace)
    {
        return Err(PluginRegistryError::InvalidParameter {
            field: "keepAliveGraceSec",
            reason: "must be within 0..=1800",
        });
    }
    if let Some(w) = params.width_dp
        && !(240..=720).contains(&w)
    {
        return Err(PluginRegistryError::InvalidParameter {
            field: "widthDp",
            reason: "must be within 240..=720",
        });
    }
    if let Some(h) = params.height_percent
        && !(30..=100).contains(&h)
    {
        return Err(PluginRegistryError::InvalidParameter {
            field: "heightPercent",
            reason: "must be within 30..=100",
        });
    }
    Ok(())
}

fn validate_optional_url(
    clear: bool,
    raw: Option<&str>,
    allow_cleartext: bool,
) -> Result<Option<String>, PluginRegistryError> {
    if clear {
        return Ok(None);
    }
    match raw {
        Some(url) if !url.trim().is_empty() => {
            Ok(Some(validate_network_url(url, allow_cleartext)?))
        }
        Some(_) | None => Ok(None),
    }
}

fn apply_embed_override(
    entry: &mut sideveil_protocol::plugin::PluginOverrideEntry,
    params: &PluginSetEmbedConfigParams,
    validated_url: Option<String>,
    validated_remote_url: Option<String>,
    clean_lan_ssids: Option<Vec<String>>,
    validated_origins: Option<Vec<String>>,
) {
    if params.clear {
        entry.embed_url = None;
        entry.remote_url = None;
        entry.lan_ssids = None;
        entry.allowed_origins = None;
        entry.allow_cleartext = None;
        entry.keep_alive = None;
        entry.wide_viewport = None;
        entry.allow_zoom = None;
        entry.web_history_back = None;
        entry.text_zoom = None;
        entry.show_toolbar = None;
        entry.keep_alive_grace_sec = None;
        entry.width_dp = None;
        entry.height_percent = None;
        return;
    }
    if params.embed_url.is_some() {
        entry.embed_url = validated_url;
    }
    if params.remote_url.is_some() {
        entry.remote_url = validated_remote_url;
    }
    if let Some(ssids) = clean_lan_ssids {
        entry.lan_ssids = Some(ssids);
    }
    if let Some(origins) = validated_origins {
        entry.allowed_origins = Some(origins);
    }
    if let Some(allow_cleartext) = params.allow_cleartext {
        entry.allow_cleartext = Some(allow_cleartext);
    }
    if let Some(keep_alive) = params.keep_alive {
        entry.keep_alive = Some(keep_alive);
    }
    if let Some(wide_viewport) = params.wide_viewport {
        entry.wide_viewport = Some(wide_viewport);
    }
    if let Some(allow_zoom) = params.allow_zoom {
        entry.allow_zoom = Some(allow_zoom);
    }
    if let Some(web_history_back) = params.web_history_back {
        entry.web_history_back = Some(web_history_back);
    }
    if let Some(text_zoom) = params.text_zoom {
        entry.text_zoom = Some(text_zoom);
    }
    if let Some(show_toolbar) = params.show_toolbar {
        entry.show_toolbar = Some(show_toolbar);
    }
    if let Some(keep_alive_grace_sec) = params.keep_alive_grace_sec {
        entry.keep_alive_grace_sec = Some(keep_alive_grace_sec);
    }
    if let Some(width_dp) = params.width_dp {
        entry.width_dp = Some(width_dp);
    }
    if let Some(height_percent) = params.height_percent {
        entry.height_percent = Some(height_percent);
    }
}
