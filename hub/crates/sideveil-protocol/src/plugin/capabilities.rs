//! Capability risk tiers and default trait implementations for generated plugin enums.

use crate::generated::plugin_manifest::{
    CapabilityRiskTier, PluginApprovalStatus, PluginBackendKind, PluginBackendState,
    PluginCapability, PluginDaemonRestartPolicy, PluginDaemonStatus, PluginOrigin,
    PluginPresentationMode, PluginSurfaceBackdrop, PluginSurfacePrefs,
};

impl PluginCapability {
    /// Classifies this capability into a security risk tier for explicit grant enforcement.
    #[must_use]
    pub const fn risk_tier(self) -> CapabilityRiskTier {
        match self {
            Self::Toast | Self::Haptics | Self::Telemetry => CapabilityRiskTier::Low,
            Self::Storage
            | Self::Clipboard
            | Self::NetworkLoopback
            | Self::NetworkLan
            | Self::NetworkInternet
            | Self::NetworkContext
            | Self::NativeHud
            | Self::InterPluginBus => CapabilityRiskTier::Medium,
            Self::SystemExec => CapabilityRiskTier::High,
        }
    }

    /// Returns `true` when this capability requires explicit user/admin approval prior to use.
    #[must_use]
    pub const fn requires_explicit_grant(self) -> bool {
        !matches!(self.risk_tier(), CapabilityRiskTier::Low)
    }
}

#[allow(clippy::derivable_impls)]
impl Default for PluginOrigin {
    fn default() -> Self {
        Self::Installed
    }
}

#[allow(clippy::derivable_impls)]
impl Default for PluginBackendKind {
    fn default() -> Self {
        Self::None
    }
}

#[allow(clippy::derivable_impls)]
impl Default for PluginBackendState {
    fn default() -> Self {
        Self::None
    }
}

#[allow(clippy::derivable_impls)]
impl Default for PluginSurfaceBackdrop {
    fn default() -> Self {
        Self::NativeGlass
    }
}

#[allow(clippy::derivable_impls)]
impl Default for PluginPresentationMode {
    fn default() -> Self {
        Self::Drawer
    }
}

#[allow(clippy::derivable_impls)]
impl Default for PluginDaemonRestartPolicy {
    fn default() -> Self {
        Self::OnFailure
    }
}

#[allow(clippy::derivable_impls)]
impl Default for PluginDaemonStatus {
    fn default() -> Self {
        Self::NotConfigured
    }
}

#[allow(clippy::derivable_impls)]
impl Default for PluginApprovalStatus {
    fn default() -> Self {
        Self::Approved
    }
}

impl Default for PluginSurfacePrefs {
    fn default() -> Self {
        Self {
            backdrop: PluginSurfaceBackdrop::NativeGlass,
            presentation: PluginPresentationMode::Drawer,
            cross_origin_isolated: false,
            keep_alive: false,
            width_dp: None,
            height_percent: None,
        }
    }
}
