import type { ColorMode, ThemeId } from '@gojona/ui';
import {
  CAPABILITY_RISK_TIER_VALUES,
  type CapabilityRiskTier,
  type DaemonHealth,
  type DaemonStatus,
  type DynamicThemePalette,
  type EdgeSide,
  type HostPluginDiagnosticsPayload,
  type PanelState,
  type PanelVisibilityPayload,
  type PingResult,
  PLUGIN_APPROVAL_STATUS_VALUES,
  PLUGIN_CAPABILITY_VALUES,
  PLUGIN_DAEMON_STATUS_VALUES,
  PLUGIN_ORIGIN_VALUES,
  type PluginApprovalStatus,
  type PluginBackendKind,
  type PluginBackendState,
  type PluginCapability,
  type PluginDaemonStatus,
  type PluginEmbedPrefs,
  type PluginManifest,
  type PluginOrigin,
  type PluginRuntimeDescriptor,
  type PluginSurfacePrefs,
  type ScopeChangedPayload,
  type ScopeMode,
  SIDEVEIL_BUILTIN_DIAGNOSTIC,
  SIDEVEIL_CONFIG_BOUNDS,
  SIDEVEIL_CONFIG_DEFAULTS,
  SIDEVEIL_ERROR_CODES,
  SIDEVEIL_EVENTS,
  SIDEVEIL_HUD_DEFAULTS,
  SIDEVEIL_METHODS,
  SIDEVEIL_PROTOCOL_VERSIONS,
  SIDEVEIL_THEMES,
  SIDEVEIL_TIMEOUT_BOUNDS,
  type SideVeilCoreEventPayloadMap,
  type SideVeilCoreRpcMethodMap,
  type SideVeilThemeHudEventPayloadMap,
  type SideVeilThemeHudRpcMethodMap,
} from '../contracts.ts';
import type { DebugConfig, DebugGestureStats } from './debugTypes.ts';

export { DEFAULT_CONFIG, normalizeSideVeilConfig } from './configNormalize.ts';
export type {
  DebugAgslSnapshot,
  DebugCdpStatusResult,
  DebugDisplaySnapshot,
  DebugFrameMetricsSnapshot,
  DebugInspectUiSnapshot,
  DebugOverlayWindowSnapshot,
  DebugPanelWindowSnapshot,
  DebugSafeBoundsSnapshot,
  DebugSetWebViewDebuggingParams,
  DebugWebViewSnapshot,
} from './debugTypes.ts';
export type {
  CapabilityRiskTier,
  DaemonHealth,
  DaemonStatus,
  DebugConfig,
  DebugGestureStats,
  DynamicThemePalette,
  HostPluginDiagnosticsPayload,
  PanelVisibilityPayload,
  PingResult,
  PluginApprovalStatus,
  PluginBackendKind,
  PluginBackendState,
  PluginCapability,
  PluginDaemonStatus,
  PluginEmbedPrefs,
  PluginManifest,
  PluginOrigin,
  PluginRuntimeDescriptor,
  PluginSurfacePrefs,
  ScopeChangedPayload,
};
export {
  CAPABILITY_RISK_TIER_VALUES,
  type EdgeSide,
  type PanelState,
  PLUGIN_APPROVAL_STATUS_VALUES,
  PLUGIN_CAPABILITY_VALUES,
  PLUGIN_DAEMON_STATUS_VALUES,
  PLUGIN_ORIGIN_VALUES,
  type ScopeMode,
  SIDEVEIL_BUILTIN_DIAGNOSTIC,
  SIDEVEIL_CONFIG_BOUNDS,
  SIDEVEIL_CONFIG_DEFAULTS,
  SIDEVEIL_ERROR_CODES,
  SIDEVEIL_EVENTS,
  SIDEVEIL_HUD_DEFAULTS,
  SIDEVEIL_METHODS,
  SIDEVEIL_PROTOCOL_VERSIONS,
  SIDEVEIL_THEMES,
  SIDEVEIL_TIMEOUT_BOUNDS,
};

export interface SideVeilEventPayloadMap
  extends SideVeilCoreEventPayloadMap,
    SideVeilThemeHudEventPayloadMap {}

export interface SideVeilRpcMethodMap
  extends SideVeilCoreRpcMethodMap,
    SideVeilThemeHudRpcMethodMap {}

export interface PluginsSnapshot {
  plugins: PluginRuntimeDescriptor[];
  matchedPluginIds: string[];
  activePluginId: string | null;
  foregroundPackage: string;
  foregroundActivity?: string;
}

export interface PluginCapabilityReviewItem {
  capability: PluginCapability;
  risk: CapabilityRiskTier;
  requiresApproval: boolean;
  isNew: boolean;
  alreadyGranted: boolean;
}

export interface PluginArchiveInspection {
  manifest: PluginManifest;
  existingVersion?: string;
  isUpgrade: boolean;
  capabilities: PluginCapabilityReviewItem[];
  hasDaemon: boolean;
  daemonAlreadyApproved: boolean;
  warnings: string[];
}

export interface PluginDaemonLogsResult {
  pluginId: string;
  lines: string[];
}

/** Edge gesture hotzone geometry, indicator appearance, and fullscreen trigger settings. */
export interface EdgeConfig {
  side: EdgeSide;
  topPercent: number;
  heightDp: number;
  hotzoneWidthDp: number;
  triggerDistanceDp: number;
  edgeOffsetDp: number;
  handleVisible: boolean;
  handleOpacity: number;
  dimInFullscreen: boolean;
  doubleSwipeInFullscreen: boolean;
  idleAutoDimMs: number;
}

export interface PanelConfig {
  widthDp: number;
  heightPercent: number;
  cornerRadiusDp: number;
  themeId: ThemeId;
  colorMode: ColorMode;
  nativeBlurEnabled: boolean;
  blurRadiusPx: number;
  scrimAlpha: number;
}

export interface ScopeConfig {
  mode: ScopeMode;
  allowlist: string[];
  denylist: string[];
}

export interface SideVeilConfig {
  version: number;
  enabled: boolean;
  edge: EdgeConfig;
  panel: PanelConfig;
  scope: ScopeConfig;
  debug?: DebugConfig;
}

export type ScopeChangedEventPayload = ScopeChangedPayload;

export interface ConfigUpdatedEventPayload {
  config?: Partial<SideVeilConfig>;
  foregroundPackage?: string;
  scopeActive?: boolean;
}

export type PanelVisibilityEventPayload = PanelVisibilityPayload;

export type CtlCommand =
  | 'open'
  | 'close'
  | 'toggle'
  | 'reload'
  | 'restart-host'
  | 'ping'
  | 'logs'
  | 'status'
  | 'health';

export const CONFIG_PATH = '/data/system/sideveil/config.json';
export const CTL_BIN = '/data/adb/modules/sideveil/bin/sideveil-ctl';
