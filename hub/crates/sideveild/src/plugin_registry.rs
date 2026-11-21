//! Open plugin registry with `inotify` discovery, atomic overrides/MRU persistence, and scope routing.

mod store;

use std::collections::{BTreeMap, HashSet};
use std::fs;
use std::io;
use std::path::PathBuf;
use std::sync::{Arc, PoisonError, RwLock};
use std::time::Duration;

use sideveil_protocol::config::SideveilConfig;
use sideveil_protocol::plugin::{
    PluginBackendKind, PluginCapability, PluginDescriptor, PluginManifest, PluginOverrideEntry,
    PluginOverridesFile, PluginsSnapshotPayload, validate_plugin_id,
};
use tokio::sync::broadcast;

use self::store::{
    build_descriptor, load_overrides, matches_activity, persist_overrides, scan_plugins_dir,
    validate_loopback_dev_url,
};
use crate::inotify::{DIR_CHANGE_MASK, InotifyDirWatcher};
use crate::log_ring::LogRing;

#[derive(Debug, Clone)]
pub(super) struct LoadedPlugin {
    pub(super) manifest: PluginManifest,
    pub(super) root_dir: Option<PathBuf>,
    pub(super) builtin: bool,
}

#[derive(Debug)]
struct RegistryInner {
    plugins: BTreeMap<String, LoadedPlugin>,
    overrides: PluginOverridesFile,
}

#[derive(Debug)]
pub struct PluginRegistry {
    plugins_dir: PathBuf,
    overrides_path: PathBuf,
    inner: RwLock<RegistryInner>,
    updates_tx: broadcast::Sender<()>,
    logs: Arc<LogRing>,
}

impl PluginRegistry {
    pub fn open(plugins_dir: PathBuf, overrides_path: PathBuf, logs: Arc<LogRing>) -> Arc<Self> {
        let _ = fs::create_dir_all(&plugins_dir);
        let overrides = load_overrides(&overrides_path, &logs);
        let (updates_tx, _) = broadcast::channel(16);
        let registry = Arc::new(Self {
            plugins_dir,
            overrides_path,
            inner: RwLock::new(RegistryInner {
                plugins: BTreeMap::new(),
                overrides,
            }),
            updates_tx,
            logs,
        });
        let _ = registry.rescan();
        registry
    }

    #[must_use]
    pub fn subscribe(&self) -> broadcast::Receiver<()> {
        self.updates_tx.subscribe()
    }

    /// Rescans `/data/system/sideveil/plugins/<plugin_id>/manifest.json` and refreshes registry.
    pub fn rescan(&self) -> usize {
        let discovered = scan_plugins_dir(&self.plugins_dir, &self.logs);

        let count = discovered.len();
        let changed = {
            let mut guard = self.inner.write().unwrap_or_else(PoisonError::into_inner);
            let old_ids: Vec<(&String, &PluginManifest)> = guard
                .plugins
                .iter()
                .map(|(k, v)| (k, &v.manifest))
                .collect();
            let new_ids: Vec<(&String, &PluginManifest)> =
                discovered.iter().map(|(k, v)| (k, &v.manifest)).collect();
            let diff = old_ids != new_ids;
            guard.plugins = discovered;
            diff
        };
        if changed {
            self.logs.info(format!(
                "Plugin registry updated ({count} plugin(s) loaded)"
            ));
            let _ = self.updates_tx.send(());
        }
        count
    }

    pub fn spawn_watcher(self: &Arc<Self>) -> io::Result<tokio::task::JoinHandle<()>> {
        fs::create_dir_all(&self.plugins_dir)?;
        let watcher = InotifyDirWatcher::new(&self.plugins_dir, DIR_CHANGE_MASK)?;
        let registry = Arc::clone(self);
        Ok(tokio::spawn(async move {
            loop {
                match watcher.wait_any().await {
                    Ok(()) => {
                        tokio::time::sleep(Duration::from_millis(100)).await;
                        let _ = registry.rescan();
                    }
                    Err(err) => {
                        registry
                            .logs
                            .warn(format!("Plugin directory inotify watcher stopped: {err}"));
                        break;
                    }
                }
            }
        }))
    }

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

    #[must_use]
    pub fn contains_plugin(&self, plugin_id: &str) -> bool {
        let guard = self.inner.read().unwrap_or_else(PoisonError::into_inner);
        guard.plugins.contains_key(plugin_id)
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
        config: &SideveilConfig,
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
