//! Tiered capability grant evaluation, upgrade diff reconciliation, and approval mutations.

use std::fs;
use std::path::Path;
use std::sync::PoisonError;

use sideveil_protocol::plugin::{
    PluginApprovalStatus, PluginBackendKind, PluginCapability, PluginGrantCapabilitiesParams,
    PluginManifest, PluginOrigin, PluginOverrideEntry,
};

use super::error::PluginRegistryError;
use super::store::persist_overrides;
use super::{LoadedPlugin, PluginRegistry};

/// Resolved capability and daemon approval state for a single loaded plugin.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct EffectiveGrants {
    pub(crate) granted: Vec<PluginCapability>,
    pub(crate) pending: Vec<PluginCapability>,
    pub(crate) approval_status: PluginApprovalStatus,
    pub(crate) has_daemon: bool,
    pub(crate) daemon_approved: bool,
}

/// Computes effective granted/pending capabilities and approval status from provenance & overrides.
#[must_use]
pub(crate) fn compute_effective_grants(
    loaded: &LoadedPlugin,
    ov: Option<&PluginOverrideEntry>,
) -> EffectiveGrants {
    let declared = loaded.manifest.effective_capabilities();
    let has_daemon = loaded.manifest.daemon.is_some()
        && loaded.manifest.effective_backend() == PluginBackendKind::UdsNative;

    match loaded.origin {
        PluginOrigin::Builtin => EffectiveGrants {
            granted: declared.to_vec(),
            pending: Vec::new(),
            approval_status: PluginApprovalStatus::Approved,
            has_daemon: false,
            daemon_approved: false,
        },
        PluginOrigin::WebEmbed => EffectiveGrants {
            granted: Vec::new(),
            pending: Vec::new(),
            approval_status: PluginApprovalStatus::Approved,
            has_daemon: false,
            daemon_approved: false,
        },
        PluginOrigin::Bundled | PluginOrigin::Installed => {
            let stored_grants = ov.map_or(&[][..], |o| o.granted_capabilities.as_slice());
            let mut granted = Vec::new();
            let mut pending = Vec::new();
            for &cap in declared {
                if !cap.requires_explicit_grant() || stored_grants.contains(&cap) {
                    if !granted.contains(&cap) {
                        granted.push(cap);
                    }
                } else if !pending.contains(&cap) {
                    pending.push(cap);
                }
            }

            let daemon_approved = if has_daemon {
                ov.is_some_and(|o| {
                    o.daemon_approved
                        && o.daemon_binary_fingerprint.is_some()
                        && o.daemon_binary_fingerprint == loaded.daemon_binary_fingerprint
                })
            } else {
                false
            };

            let was_reviewed = ov.is_some_and(|o| {
                !o.reviewed_capabilities.is_empty()
                    || !o.granted_capabilities.is_empty()
                    || o.daemon_binary_fingerprint.is_some()
            });

            let approval_status = if pending.is_empty() && (!has_daemon || daemon_approved) {
                PluginApprovalStatus::Approved
            } else if was_reviewed {
                PluginApprovalStatus::NeedsReapproval
            } else {
                PluginApprovalStatus::PendingReview
            };

            EffectiveGrants {
                granted,
                pending,
                approval_status,
                has_daemon,
                daemon_approved,
            }
        }
    }
}

/// Computes a fast deterministic FNV-1a 64-bit fingerprint for a plugin daemon binary.
#[must_use]
pub(super) fn compute_binary_fingerprint(
    plugin_root: &Path,
    manifest: &PluginManifest,
) -> Option<String> {
    let daemon = manifest.daemon.as_ref()?;
    let bin_path = plugin_root.join(&daemon.binary);
    let meta = fs::symlink_metadata(&bin_path).ok()?;
    if !meta.file_type().is_file() {
        return None;
    }
    let bytes = fs::read(&bin_path).ok()?;
    const FNV_OFFSET_BASIS: u64 = 0xcbf2_9ce4_8422_2325;
    const FNV_PRIME: u64 = 0x0000_0100_0000_01b3;
    let mut hash = FNV_OFFSET_BASIS;
    for &b in &bytes {
        hash ^= u64::from(b);
        hash = hash.wrapping_mul(FNV_PRIME);
    }
    Some(format!("{hash:016x}-{:x}", bytes.len()))
}

impl PluginRegistry {
    /// Applies explicit capability and/or daemon approval mutations persisted in `plugins.json`.
    pub fn grant_capabilities(
        &self,
        params: &PluginGrantCapabilitiesParams,
    ) -> Result<(), PluginRegistryError> {
        let to_save = {
            let mut guard = self.inner.write().unwrap_or_else(PoisonError::into_inner);
            let Some(loaded) = guard.plugins.get(&params.plugin_id).cloned() else {
                return Err(PluginRegistryError::PluginNotFound(
                    params.plugin_id.clone(),
                ));
            };
            if loaded.origin == PluginOrigin::Builtin {
                return Err(PluginRegistryError::BuiltinProtected(loaded.manifest.id));
            }
            let declared = loaded.manifest.effective_capabilities();
            let entry = guard
                .overrides
                .overrides
                .entry(params.plugin_id.clone())
                .or_default();

            if params.revoke_all {
                entry.granted_capabilities.clear();
                entry.daemon_approved = false;
            }
            if params.grant_all_pending {
                for &cap in declared {
                    if cap.requires_explicit_grant() && !entry.granted_capabilities.contains(&cap) {
                        entry.granted_capabilities.push(cap);
                    }
                }
            }
            if let Some(ref requested) = params.capabilities {
                let mut next = Vec::new();
                for &cap in requested {
                    if cap.requires_explicit_grant()
                        && declared.contains(&cap)
                        && !next.contains(&cap)
                    {
                        next.push(cap);
                    }
                }
                entry.granted_capabilities = next;
            }
            if let Some(approve_daemon) = params.approve_daemon {
                if approve_daemon && loaded.manifest.daemon.is_some() {
                    entry.daemon_approved = true;
                    entry.daemon_binary_fingerprint = loaded.daemon_binary_fingerprint.clone();
                } else if !approve_daemon {
                    entry.daemon_approved = false;
                }
            }
            entry.reviewed_capabilities = declared.to_vec();
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

    /// Reconciles persisted capability grants and daemon approval after an install or upgrade.
    pub(super) fn reconcile_install_grants(
        &self,
        plugin_id: &str,
        manifest: &PluginManifest,
        new_binary_fingerprint: Option<String>,
        explicit_grants: Option<&[PluginCapability]>,
        approve_daemon: bool,
    ) -> Result<(), PluginRegistryError> {
        let declared = manifest.effective_capabilities();
        let has_daemon = manifest.daemon.is_some()
            && manifest.effective_backend() == PluginBackendKind::UdsNative;

        let to_save = {
            let mut guard = self.inner.write().unwrap_or_else(PoisonError::into_inner);
            let entry = guard
                .overrides
                .overrides
                .entry(plugin_id.to_owned())
                .or_default();

            if let Some(explicit) = explicit_grants {
                // Explicit capability list is authoritative: replace prior grants so unchecked items are revoked.
                let mut next = Vec::new();
                for &cap in explicit {
                    if cap.requires_explicit_grant()
                        && declared.contains(&cap)
                        && !next.contains(&cap)
                    {
                        next.push(cap);
                    }
                }
                entry.granted_capabilities = next;
                entry.reviewed_capabilities = declared.to_vec();
            } else {
                // Automated/CLI upgrade without explicit list: preserve intersection only.
                entry
                    .granted_capabilities
                    .retain(|c| c.requires_explicit_grant() && declared.contains(c));
            }

            if has_daemon {
                if approve_daemon {
                    entry.daemon_approved = true;
                    entry.daemon_binary_fingerprint = new_binary_fingerprint;
                } else if entry.daemon_binary_fingerprint != new_binary_fingerprint {
                    // Reset approval whenever daemon binary changes or is newly introduced.
                    entry.daemon_approved = false;
                }
            } else {
                entry.daemon_approved = false;
                entry.daemon_binary_fingerprint = None;
            }

            guard.overrides.clone()
        };

        persist_overrides(&self.overrides_path, &to_save).map_err(|source| {
            PluginRegistryError::Io {
                context: "failed to persist plugins.json after install".to_owned(),
                source,
            }
        })?;
        Ok(())
    }
}
