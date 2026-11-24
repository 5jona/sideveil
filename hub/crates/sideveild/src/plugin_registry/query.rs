//! Read-only query, effective capability checks, backend authorization, and snapshot building.

use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::PoisonError;

use sideveil_protocol::config::SideVeilConfig;
use sideveil_protocol::plugin::{
    PluginBackendKind, PluginCapability, PluginDaemonSpec, PluginDescriptor, PluginOrigin,
    PluginsSnapshotPayload,
};

use super::PluginRegistry;
use super::error::PluginRegistryError;
use super::grants::compute_effective_grants;
use super::store::{build_descriptor, matches_activity};
use crate::supervisor::DaemonRuntimeInfo;

/// Desired supervision specification for one `uds-native` plugin daemon.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PluginDaemonCandidate {
    pub plugin_id: String,
    pub root_dir: PathBuf,
    pub spec: PluginDaemonSpec,
    pub enabled: bool,
    pub daemon_approved: bool,
    pub binary_fingerprint: Option<String>,
}

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
    pub fn plugin_origin(&self, plugin_id: &str) -> Option<PluginOrigin> {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        guard.plugins.get(plugin_id).map(|p| p.origin)
    }

    #[must_use]
    pub fn plugin_cross_origin_isolated(&self, plugin_id: &str) -> bool {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        guard
            .plugins
            .get(plugin_id)
            .is_some_and(|p| p.manifest.effective_surface().cross_origin_isolated)
    }

    /// Returns the relative entry HTML path when a plugin enables `network.spaFallback`.
    #[must_use]
    pub fn plugin_spa_entry(&self, plugin_id: &str) -> Option<String> {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        let plugin = guard.plugins.get(plugin_id)?;
        let entry = plugin.manifest.effective_entry();
        if plugin
            .manifest
            .effective_network()
            .is_some_and(|n| n.spa_fallback)
            && !entry.trim().is_empty()
        {
            Some(entry.to_owned())
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
    pub fn has_dev_url(&self, plugin_id: &str) -> bool {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        guard
            .overrides
            .overrides
            .get(plugin_id)
            .and_then(|ov| ov.dev_server_url.as_deref())
            .is_some_and(|url| !url.trim().is_empty())
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
    pub fn is_plugin_enabled(&self, plugin_id: &str) -> bool {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        let Some(plugin) = guard.plugins.get(plugin_id) else {
            return false;
        };
        guard
            .overrides
            .overrides
            .get(plugin_id)
            .and_then(|o| o.enabled)
            .unwrap_or_else(|| plugin.manifest.effective_default_enabled())
    }

    /// Returns whether `plugin_id` is enabled AND effectively granted `capability`.
    #[must_use]
    pub fn has_capability(&self, plugin_id: &str, capability: PluginCapability) -> bool {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        let Some(plugin) = guard.plugins.get(plugin_id) else {
            return false;
        };
        let ov = guard.overrides.overrides.get(plugin_id);
        let enabled = ov
            .and_then(|o| o.enabled)
            .unwrap_or_else(|| plugin.manifest.effective_default_enabled());
        if !enabled {
            return false;
        }
        compute_effective_grants(plugin, ov)
            .granted
            .contains(&capability)
    }

    /// Returns supervision descriptors for all plugins declaring a `uds-native` daemon spec.
    #[must_use]
    pub fn daemon_candidates(&self) -> Vec<PluginDaemonCandidate> {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        guard
            .plugins
            .values()
            .filter_map(|p| {
                if p.manifest.effective_backend() != PluginBackendKind::UdsNative {
                    return None;
                }
                let spec = p.manifest.daemon.clone()?;
                let root_dir = p.root_dir.clone()?;
                let ov = guard.overrides.overrides.get(&p.manifest.id);
                let enabled = ov
                    .and_then(|o| o.enabled)
                    .unwrap_or_else(|| p.manifest.effective_default_enabled());
                let grants = compute_effective_grants(p, ov);
                Some(PluginDaemonCandidate {
                    plugin_id: p.manifest.id.clone(),
                    root_dir,
                    spec,
                    enabled,
                    daemon_approved: grants.daemon_approved,
                    binary_fingerprint: p.daemon_binary_fingerprint.clone(),
                })
            })
            .collect()
    }

    /// Validates whether a backend connection may register for `plugin_id`.
    pub fn authorize_backend(
        &self,
        plugin_id: &str,
        from_app_sock: bool,
        package_name: Option<&str>,
    ) -> Result<(), PluginRegistryError> {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        let Some(plugin) = guard.plugins.get(plugin_id) else {
            return Err(PluginRegistryError::BackendUnauthorized(format!(
                "plugin '{plugin_id}' is not registered"
            )));
        };
        if plugin.origin == PluginOrigin::Builtin {
            return Err(PluginRegistryError::BackendUnauthorized(
                "built-in plugin does not accept external backend".to_owned(),
            ));
        }
        let ov = guard.overrides.overrides.get(plugin_id);
        let enabled = ov
            .and_then(|o| o.enabled)
            .unwrap_or_else(|| plugin.manifest.effective_default_enabled());
        if !enabled {
            return Err(PluginRegistryError::BackendUnauthorized(format!(
                "plugin '{plugin_id}' is disabled"
            )));
        }
        let backend = plugin.manifest.effective_backend();
        if from_app_sock {
            if backend != PluginBackendKind::UdsApp {
                return Err(PluginRegistryError::BackendUnauthorized(format!(
                    "plugin '{plugin_id}' does not declare uds-app backend"
                )));
            }
            let Some(pkg) = package_name.map(str::trim).filter(|s| !s.is_empty()) else {
                return Err(PluginRegistryError::BackendUnauthorized(
                    "missing packageName for uds-app backend".to_owned(),
                ));
            };
            // Strict explicit package allowlist: never treat `triggers.global` as a wildcard.
            let allowed_pkgs = plugin.manifest.effective_backend_packages();
            if !allowed_pkgs.iter().any(|p| p == pkg) {
                return Err(PluginRegistryError::BackendUnauthorized(format!(
                    "package '{pkg}' is not in plugin '{plugin_id}' backendPackages allowlist"
                )));
            }
        } else {
            if backend != PluginBackendKind::UdsNative {
                return Err(PluginRegistryError::BackendUnauthorized(format!(
                    "plugin '{plugin_id}' must declare backend=uds-native to attach over hub.sock"
                )));
            }
            let grants = compute_effective_grants(plugin, ov);
            if grants.has_daemon && !grants.daemon_approved {
                return Err(PluginRegistryError::BackendUnauthorized(format!(
                    "plugin '{plugin_id}' native daemon has not been approved"
                )));
            }
        }
        Ok(())
    }

    /// Computes full sorted descriptors without live daemon runtime state (convenience for tests).
    #[must_use]
    pub fn snapshot(
        &self,
        foreground_package: &str,
        foreground_activity: &str,
        config: &SideVeilConfig,
        online_backends: &HashSet<String>,
    ) -> PluginsSnapshotPayload {
        self.snapshot_with_daemons(
            foreground_package,
            foreground_activity,
            config,
            online_backends,
            &HashMap::new(),
        )
    }

    /// Computes full sorted descriptors, matched plugin IDs for `(foreground_package, foreground_activity)`,
    /// live daemon statuses, and MRU active ID.
    #[must_use]
    pub fn snapshot_with_daemons(
        &self,
        foreground_package: &str,
        foreground_activity: &str,
        config: &SideVeilConfig,
        online_backends: &HashSet<String>,
        daemon_states: &HashMap<String, DaemonRuntimeInfo>,
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
                    daemon_states.get(&p.manifest.id),
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
