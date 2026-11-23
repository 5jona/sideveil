import {
  type EdgeSide,
  type PanelState,
  RUNTIME_MODE_VALUES,
  type RuntimeMode,
  type ScopeMode,
  SIDEVEIL_BUILTIN_DIAGNOSTIC,
  SIDEVEIL_BUILTIN_TEST_PLUGINS,
  SIDEVEIL_CONFIG_BOUNDS,
  SIDEVEIL_CONFIG_DEFAULTS,
  SIDEVEIL_ERROR_CODES,
  SIDEVEIL_EVENTS,
  SIDEVEIL_METHODS,
  SIDEVEIL_THEMES,
} from '@gojona/bridge';
import type {
  DaemonHealth,
  DaemonStatus,
  DynamicThemePalette,
  HostPluginDiagnosticsPayload,
  PanelVisibilityPayload,
  PingResult,
  ScopeChangedPayload,
  SideVeilEventPayloadMap,
  SideVeilRpcMethodMap,
} from '@gojona/sideveil-sdk';
import type { ColorMode, ThemeId } from '@gojona/ui';
import {
  type DebugConfig,
  type DebugGestureStats,
  isBuiltinTestPlugin,
  resolveRuntimeMode,
} from './debugTypes.ts';

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
  DebugSetRuntimeModeParams,
  DebugSetRuntimeModeResult,
  DebugSetWebViewDebuggingParams,
  DebugWebViewSnapshot,
} from './debugTypes.ts';
export type {
  DaemonHealth,
  DaemonStatus,
  DebugConfig,
  DebugGestureStats,
  DynamicThemePalette,
  HostPluginDiagnosticsPayload,
  PanelVisibilityPayload,
  PingResult,
  ScopeChangedPayload,
  SideVeilEventPayloadMap,
  SideVeilRpcMethodMap,
};
export {
  type EdgeSide,
  isBuiltinTestPlugin,
  type PanelState,
  RUNTIME_MODE_VALUES,
  type RuntimeMode,
  resolveRuntimeMode,
  type ScopeMode,
  SIDEVEIL_BUILTIN_DIAGNOSTIC,
  SIDEVEIL_BUILTIN_TEST_PLUGINS,
  SIDEVEIL_CONFIG_BOUNDS,
  SIDEVEIL_CONFIG_DEFAULTS,
  SIDEVEIL_ERROR_CODES,
  SIDEVEIL_EVENTS,
  SIDEVEIL_METHODS,
  SIDEVEIL_THEMES,
};

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
