//! Read-only query, capability checks, backend authorization, and snapshot building for `PluginRegistry`.

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::PoisonError;

use sideveil_protocol::config::SideVeilConfig;
use sideveil_protocol::plugin::{
    PluginBackendKind, PluginCapability, PluginDescriptor, PluginsSnapshotPayload,
};

use super::PluginRegistry;
use super::store::{build_descriptor, matches_activity};

impl PluginRegistry {
    #[must_use]
    pub fn plugin_root(&self, plugin_id: &str) -> Option<PathBuf> {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        guard
            .plugins
            .get(plugin_id)
            .and_then(|p| p.root_dir.clone())
    }

    #[must_use]
    pub fn plugin_icon(&self, plugin_id: &str) -> Option<String> {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        guard
            .plugins
            .get(plugin_id)
            .and_then(|p| p.manifest.icon.clone())
    }

    #[must_use]
    pub fn plugin_cross_origin_isolated(&self, plugin_id: &str) -> bool {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        guard
            .plugins
            .get(plugin_id)
            .is_some_and(|p| p.manifest.surface.cross_origin_isolated)
    }

    /// Returns the relative entry HTML path when a plugin enables `network.spaFallback`.
    #[must_use]
    pub fn plugin_spa_entry(&self, plugin_id: &str) -> Option<String> {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        let plugin = guard.plugins.get(plugin_id)?;
        if plugin
            .manifest
            .network
            .as_ref()
            .is_some_and(|n| n.spa_fallback)
            && !plugin.manifest.entry.trim().is_empty()
        {
            Some(plugin.manifest.entry.clone())
        } else {
            None
        }
    }

    #[must_use]
    pub fn contains_plugin(&self, plugin_id: &str) -> bool {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        guard.plugins.contains_key(plugin_id)
    }

    #[must_use]
    pub fn has_active_dev_server(&self) -> bool {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        guard.plugins.keys().any(|id| {
            guard
                .overrides
                .overrides
                .get(id)
                .and_then(|ov| ov.dev_server_url.as_deref())
                .is_some_and(|url| !url.trim().is_empty())
        })
    }

    #[must_use]
    pub fn has_capability(&self, plugin_id: &str, capability: PluginCapability) -> bool {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        guard
            .plugins
            .get(plugin_id)
            .is_some_and(|p| p.manifest.capabilities.contains(&capability))
    }

    /// Validates whether a backend connection may register for `plugin_id`.
    pub fn authorize_backend(
        &self,
        plugin_id: &str,
        from_app_sock: bool,
        package_name: Option<&str>,
    ) -> Result<(), String> {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        let Some(plugin) = guard.plugins.get(plugin_id) else {
            return Err(format!("plugin '{plugin_id}' is not registered"));
        };
        if plugin.builtin {
            return Err("built-in plugin does not accept external backend".to_owned());
        }
        if from_app_sock {
            if plugin.manifest.backend != PluginBackendKind::UdsApp {
                return Err(format!(
                    "plugin '{plugin_id}' does not declare uds-app backend"
                ));
            }
            let Some(pkg) = package_name.map(str::trim).filter(|s| !s.is_empty()) else {
                return Err("missing packageName for uds-app backend".to_owned());
            };
            let ov = guard.overrides.overrides.get(plugin_id);
            let global = ov
                .and_then(|o| o.global_override)
                .unwrap_or(plugin.manifest.triggers.global);
            let packages = ov
                .and_then(|o| o.packages_override.as_ref())
                .unwrap_or(&plugin.manifest.triggers.packages);
            if !global && !packages.iter().any(|p| p == pkg) {
                return Err(format!(
                    "package '{pkg}' is not authorized by plugin '{plugin_id}' triggers"
                ));
            }
        } else if plugin.manifest.backend == PluginBackendKind::None {
            return Err(format!(
                "plugin '{plugin_id}' declares backend=none and cannot attach a backend"
            ));
        }
        Ok(())
    }

    /// Computes full sorted descriptors, matched plugin IDs for `(foreground_package, foreground_activity)`, and MRU active ID.
    #[must_use]
    pub fn snapshot(
        &self,
        foreground_package: &str,
        foreground_activity: &str,
        config: &SideVeilConfig,
        online_backends: &HashSet<String>,
    ) -> PluginsSnapshotPayload {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        let fg = foreground_package.trim();
        let fg_act = foreground_activity.trim();
        let clean_scope = config.scope.sanitized();
        let denied = !fg.is_empty() && clean_scope.denylist.iter().any(|d| d == fg);

        let mut descriptors: Vec<PluginDescriptor> = guard
            .plugins
            .values()
            .map(|p| {
                build_descriptor(
                    p,
                    guard.overrides.overrides.get(&p.manifest.id),
                    config,
                    online_backends.contains(&p.manifest.id),
                )
            })
            .collect();

        descriptors.sort_by(|a, b| b.priority.cmp(&a.priority).then_with(|| a.id.cmp(&b.id)));

        let matched_plugin_ids: Vec<String> = if !config.enabled || fg.is_empty() || denied {
            Vec::new()
        } else {
            descriptors
                .iter()
                .filter(|d| {
                    d.enabled
                        && (d.global || d.packages.iter().any(|p| p == fg))
                        && matches_activity(&d.activities, fg_act)
                })
                .map(|d| d.id.clone())
                .collect()
        };

        let mru = guard.overrides.mru_by_package.get(fg);
        let active_plugin_id = mru
            .filter(|id| matched_plugin_ids.contains(id))
            .cloned()
            .or_else(|| matched_plugin_ids.first().cloned());

        PluginsSnapshotPayload {
            plugins: descriptors,
            matched_plugin_ids,
            active_plugin_id,
            foreground_package: fg.to_owned(),
            foreground_activity: fg_act.to_owned(),
        }
    }
}
