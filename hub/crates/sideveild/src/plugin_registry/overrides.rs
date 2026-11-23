//! Runtime override and MRU persistence mutations for `PluginRegistry`.

use std::sync::PoisonError;

use sideveil_protocol::plugin::{
    PluginOverrideEntry, PluginSetEmbedConfigParams, validate_network_url, validate_plugin_id,
};

use super::PluginRegistry;
use super::store::{persist_overrides, validate_loopback_dev_url};

impl PluginRegistry {
    pub fn set_dev_url(
        &self,
        plugin_id: &str,
        dev_server_url: Option<&str>,
        clear: bool,
    ) -> Result<(), String> {
        let validated = if clear {
            None
        } else {
            match dev_server_url {
                Some(raw) if !raw.trim().is_empty() => Some(validate_loopback_dev_url(raw)?),
                _ => None,
            }
        };
        self.mutate_override(plugin_id, |entry| {
            entry.dev_server_url = validated;
        })
    }

    pub fn set_embed_config(&self, params: &PluginSetEmbedConfigParams) -> Result<(), String> {
        if !params.clear {
            if let Some(tz) = params.text_zoom
                && !(50..=200).contains(&tz)
            {
                return Err("invalid textZoom: must be within 50..=200".to_owned());
            }
            if let Some(grace) = params.keep_alive_grace_sec
                && !(0..=1800).contains(&grace)
            {
                return Err("invalid keepAliveGraceSec: must be within 0..=1800".to_owned());
            }
            if let Some(w) = params.width_dp
                && !(240..=720).contains(&w)
            {
                return Err("invalid widthDp: must be within 240..=720".to_owned());
            }
            if let Some(h) = params.height_percent
                && !(30..=100).contains(&h)
            {
                return Err("invalid heightPercent: must be within 30..=100".to_owned());
            }
        }

        let to_save = {
            let mut guard = self.inner.write().unwrap_or_else(PoisonError::into_inner);
            let Some(loaded) = guard.plugins.get(&params.plugin_id) else {
                return Err(format!("plugin '{}' not found", params.plugin_id));
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

            let validated_url = if params.clear {
                None
            } else {
                match params.embed_url.as_deref() {
                    Some(raw) if !raw.trim().is_empty() => Some(
                        validate_network_url(raw, effective_cleartext)
                            .map_err(|e| e.to_string())?,
                    ),
                    Some(_) | None => None,
                }
            };

            let validated_remote_url = if params.clear {
                None
            } else {
                match params.remote_url.as_deref() {
                    Some(raw) if !raw.trim().is_empty() => Some(
                        validate_network_url(raw, effective_cleartext)
                            .map_err(|e| e.to_string())?,
                    ),
                    Some(_) | None => None,
                }
            };

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
                    clean.push(
                        validate_network_url(trimmed, effective_cleartext)
                            .map_err(|e| e.to_string())?,
                    );
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
                    validate_network_url(cur_url, false).map_err(|e| e.to_string())?;
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
                    validate_network_url(cur_remote, false).map_err(|e| e.to_string())?;
                }
            }

            let entry = guard
                .overrides
                .overrides
                .entry(params.plugin_id.clone())
                .or_default();
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
            } else {
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
            guard.overrides.clone()
        };
        persist_overrides(&self.overrides_path, &to_save)
            .map_err(|e| format!("failed to persist plugins.json: {e}"))?;
        let _ = self.updates_tx.send(());
        Ok(())
    }

    pub(super) fn purge_plugin_overrides(&self, plugin_id: &str) {
        let to_save = {
            let mut guard = self.inner.write().unwrap_or_else(PoisonError::into_inner);
            let removed_ov = guard.overrides.overrides.remove(plugin_id).is_some();
            let before_mru = guard.overrides.mru_by_package.len();
            guard.overrides.mru_by_package.retain(|_, v| v != plugin_id);
            let removed_mru = guard.overrides.mru_by_package.len() != before_mru;
            if removed_ov || removed_mru {
                Some(guard.overrides.clone())
            } else {
                None
            }
        };
        if let Some(data) = to_save
            && let Err(err) = persist_overrides(&self.overrides_path, &data)
        {
            self.logs.warn(format!(
                "Failed to persist plugins.json after uninstall of '{plugin_id}': {err}"
            ));
        }
    }

    pub fn set_enabled(&self, plugin_id: &str, enabled: bool) -> Result<(), String> {
        self.mutate_override(plugin_id, |entry| {
            entry.enabled = Some(enabled);
        })
    }

    pub fn set_priority(&self, plugin_id: &str, priority: i32) -> Result<(), String> {
        let clamped = priority.clamp(-1000, 1000);
        self.mutate_override(plugin_id, |entry| {
            entry.priority = Some(clamped);
        })
    }

    pub fn set_scope_override(
        &self,
        plugin_id: &str,
        packages: Option<Vec<String>>,
        global: Option<bool>,
        clear: bool,
    ) -> Result<(), String> {
        self.mutate_override(plugin_id, |entry| {
            if clear {
                entry.packages_override = None;
                entry.global_override = None;
            } else {
                if let Some(pkgs) = packages {
                    let clean: Vec<String> = pkgs
                        .into_iter()
                        .map(|s| s.trim().to_owned())
                        .filter(|s| !s.is_empty())
                        .collect();
                    entry.packages_override = Some(clean);
                }
                if let Some(g) = global {
                    entry.global_override = Some(g);
                }
            }
        })
    }

    pub fn select_plugin(&self, plugin_id: &str, package_name: &str) -> Result<(), String> {
        validate_plugin_id(plugin_id).map_err(|e| e.to_string())?;
        let pkg = package_name.trim();
        let snapshot_to_save = {
            let mut guard = self.inner.write().unwrap_or_else(PoisonError::into_inner);
            if !guard.plugins.contains_key(plugin_id) {
                return Err(format!("plugin '{plugin_id}' not found"));
            }
            if !pkg.is_empty() {
                guard
                    .overrides
                    .mru_by_package
                    .insert(pkg.to_owned(), plugin_id.to_owned());
            }
            guard.overrides.clone()
        };
        if let Err(err) = persist_overrides(&self.overrides_path, &snapshot_to_save) {
            self.logs
                .warn(format!("Failed to persist plugin MRU state: {err}"));
        }
        Ok(())
    }

    fn mutate_override(
        &self,
        plugin_id: &str,
        mutate: impl FnOnce(&mut PluginOverrideEntry),
    ) -> Result<(), String> {
        let to_save = {
            let mut guard = self.inner.write().unwrap_or_else(PoisonError::into_inner);
            if !guard.plugins.contains_key(plugin_id) {
                return Err(format!("plugin '{plugin_id}' not found"));
            }
            let entry = guard
                .overrides
                .overrides
                .entry(plugin_id.to_owned())
                .or_default();
            mutate(entry);
            guard.overrides.clone()
        };
        persist_overrides(&self.overrides_path, &to_save)
            .map_err(|e| format!("failed to persist plugins.json: {e}"))?;
        let _ = self.updates_tx.send(());
        Ok(())
    }
}
