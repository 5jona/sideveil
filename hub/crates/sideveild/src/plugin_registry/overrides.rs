//! Runtime override and MRU persistence mutations for `PluginRegistry`.

use std::sync::PoisonError;

use sideveil_protocol::plugin::{PluginOverrideEntry, validate_plugin_id};

use super::PluginRegistry;
use super::dev_url::validate_loopback_dev_url;
use super::error::PluginRegistryError;
use super::store::persist_overrides;

impl PluginRegistry {
    /// Sets or clears a loopback Dev Mode server URL override for `plugin_id`.
    pub fn set_dev_url(
        &self,
        plugin_id: &str,
        dev_server_url: Option<&str>,
        clear: bool,
    ) -> Result<(), PluginRegistryError> {
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

    pub(super) fn has_persisted_override(&self, plugin_id: &str) -> bool {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        guard.overrides.overrides.contains_key(plugin_id)
            || guard
                .overrides
                .mru_by_package
                .values()
                .any(|v| v == plugin_id)
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

    /// Enables or disables a registered plugin via persistent overrides.
    pub fn set_enabled(&self, plugin_id: &str, enabled: bool) -> Result<(), PluginRegistryError> {
        self.mutate_override(plugin_id, |entry| {
            entry.enabled = Some(enabled);
        })
    }

    /// Updates a plugin's sort priority override, clamped to `-1000..=1000`.
    pub fn set_priority(&self, plugin_id: &str, priority: i32) -> Result<(), PluginRegistryError> {
        let clamped = priority.clamp(-1000, 1000);
        self.mutate_override(plugin_id, |entry| {
            entry.priority = Some(clamped);
        })
    }

    /// Overrides or clears a plugin's package/global trigger scope.
    pub fn set_scope_override(
        &self,
        plugin_id: &str,
        packages: Option<Vec<String>>,
        global: Option<bool>,
        clear: bool,
    ) -> Result<(), PluginRegistryError> {
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

    /// Records `plugin_id` as the most recently used plugin for `package_name`.
    pub fn select_plugin(
        &self,
        plugin_id: &str,
        package_name: &str,
    ) -> Result<(), PluginRegistryError> {
        validate_plugin_id(plugin_id)?;
        let pkg = package_name.trim();
        let snapshot_to_save = {
            let mut guard = self.inner.write().unwrap_or_else(PoisonError::into_inner);
            if !guard.plugins.contains_key(plugin_id) {
                return Err(PluginRegistryError::PluginNotFound(plugin_id.to_owned()));
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
    ) -> Result<(), PluginRegistryError> {
        let to_save = {
            let mut guard = self.inner.write().unwrap_or_else(PoisonError::into_inner);
            if !guard.plugins.contains_key(plugin_id) {
                return Err(PluginRegistryError::PluginNotFound(plugin_id.to_owned()));
            }
            let entry = guard
                .overrides
                .overrides
                .entry(plugin_id.to_owned())
                .or_default();
            mutate(entry);
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
