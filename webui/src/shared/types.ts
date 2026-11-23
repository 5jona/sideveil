import {
  type EdgeSide,
  type PanelState,
  type ScopeMode,
  SIDEVEIL_BUILTIN_DIAGNOSTIC,
  SIDEVEIL_CONFIG_BOUNDS,
  SIDEVEIL_CONFIG_DEFAULTS,
  SIDEVEIL_ERROR_CODES,
  SIDEVEIL_EVENTS,
  SIDEVEIL_METHODS,
  SIDEVEIL_THEMES,
} from '@pochita/bridge';
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
} from '@pochita/sideveil-sdk';
import type { ColorMode, ThemeId } from '@pochita/ui';
import type { DebugConfig, DebugGestureStats } from './debugTypes.ts';

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
  type PanelState,
  type ScopeMode,
  SIDEVEIL_BUILTIN_DIAGNOSTIC,
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

export const DEFAULT_CONFIG: SideVeilConfig = {
  version: SIDEVEIL_CONFIG_DEFAULTS.VERSION,
  enabled: SIDEVEIL_CONFIG_DEFAULTS.ENABLED,
  edge: {
    side: 'right',
    topPercent: SIDEVEIL_CONFIG_DEFAULTS.EDGE_TOP_PERCENT,
    heightDp: SIDEVEIL_CONFIG_DEFAULTS.EDGE_HEIGHT_DP,
    hotzoneWidthDp: SIDEVEIL_CONFIG_DEFAULTS.EDGE_HOTZONE_WIDTH_DP,
    triggerDistanceDp: SIDEVEIL_CONFIG_DEFAULTS.EDGE_TRIGGER_DISTANCE_DP,
    edgeOffsetDp: SIDEVEIL_CONFIG_DEFAULTS.EDGE_OFFSET_DP,
    handleVisible: SIDEVEIL_CONFIG_DEFAULTS.EDGE_HANDLE_VISIBLE,
    handleOpacity: SIDEVEIL_CONFIG_DEFAULTS.EDGE_HANDLE_OPACITY,
    dimInFullscreen: SIDEVEIL_CONFIG_DEFAULTS.EDGE_DIM_IN_FULLSCREEN,
    doubleSwipeInFullscreen: SIDEVEIL_CONFIG_DEFAULTS.EDGE_DOUBLE_SWIPE_IN_FULLSCREEN,
    idleAutoDimMs: SIDEVEIL_CONFIG_DEFAULTS.EDGE_IDLE_AUTO_DIM_MS,
  },
  panel: {
    widthDp: SIDEVEIL_CONFIG_DEFAULTS.PANEL_WIDTH_DP,
    heightPercent: SIDEVEIL_CONFIG_DEFAULTS.PANEL_HEIGHT_PERCENT,
    cornerRadiusDp: SIDEVEIL_CONFIG_DEFAULTS.PANEL_CORNER_RADIUS_DP,
    themeId: SIDEVEIL_THEMES.COLOROS_GLASS,
    colorMode: SIDEVEIL_CONFIG_DEFAULTS.PANEL_COLOR_MODE as ColorMode,
    nativeBlurEnabled: SIDEVEIL_CONFIG_DEFAULTS.PANEL_NATIVE_BLUR_ENABLED,
    blurRadiusPx: SIDEVEIL_CONFIG_DEFAULTS.PANEL_BLUR_RADIUS_PX,
    scrimAlpha: SIDEVEIL_CONFIG_DEFAULTS.PANEL_SCRIM_ALPHA,
  },
  scope: {
    mode: 'allowlist',
    allowlist: [SIDEVEIL_CONFIG_DEFAULTS.SCOPE_ALLOWLIST_DEFAULT_PACKAGE],
    denylist: [SIDEVEIL_CONFIG_DEFAULTS.SCOPE_DENYLIST_DEFAULT_PACKAGE],
  },
};

export function normalizeSideVeilConfig(
  raw: Partial<SideVeilConfig> | null | undefined,
): SideVeilConfig {
  const edge = raw?.edge;
  const panel = raw?.panel;
  const scope = raw?.scope;
  const b = SIDEVEIL_CONFIG_BOUNDS;

  return {
    version:
      typeof raw?.version === 'number' && raw.version >= b.MIN_VERSION
        ? raw.version
        : SIDEVEIL_CONFIG_DEFAULTS.VERSION,
    enabled: raw?.enabled ?? DEFAULT_CONFIG.enabled,
    edge: {
      side: edge?.side === 'left' ? 'left' : 'right',
      topPercent: clampInt(
        edge?.topPercent,
        b.EDGE_TOP_PERCENT_MIN,
        b.EDGE_TOP_PERCENT_MAX,
        DEFAULT_CONFIG.edge.topPercent,
      ),
      heightDp: clampInt(
        edge?.heightDp,
        b.EDGE_HEIGHT_DP_MIN,
        b.EDGE_HEIGHT_DP_MAX,
        DEFAULT_CONFIG.edge.heightDp,
      ),
      hotzoneWidthDp: clampInt(
        edge?.hotzoneWidthDp,
        b.EDGE_HOTZONE_WIDTH_DP_MIN,
        b.EDGE_HOTZONE_WIDTH_DP_MAX,
        DEFAULT_CONFIG.edge.hotzoneWidthDp,
      ),
      triggerDistanceDp: clampInt(
        edge?.triggerDistanceDp,
        b.EDGE_TRIGGER_DISTANCE_DP_MIN,
        b.EDGE_TRIGGER_DISTANCE_DP_MAX,
        DEFAULT_CONFIG.edge.triggerDistanceDp,
      ),
      edgeOffsetDp: clampInt(
        edge?.edgeOffsetDp,
        b.EDGE_OFFSET_DP_MIN,
        b.EDGE_OFFSET_DP_MAX,
        DEFAULT_CONFIG.edge.edgeOffsetDp,
      ),
      handleVisible: edge?.handleVisible ?? DEFAULT_CONFIG.edge.handleVisible,
      handleOpacity: clampFloat(
        edge?.handleOpacity,
        b.EDGE_HANDLE_OPACITY_MIN,
        b.EDGE_HANDLE_OPACITY_MAX,
        DEFAULT_CONFIG.edge.handleOpacity,
      ),
      dimInFullscreen: edge?.dimInFullscreen ?? DEFAULT_CONFIG.edge.dimInFullscreen,
      doubleSwipeInFullscreen:
        edge?.doubleSwipeInFullscreen ?? DEFAULT_CONFIG.edge.doubleSwipeInFullscreen,
      idleAutoDimMs: clampInt(
        edge?.idleAutoDimMs,
        b.EDGE_IDLE_AUTO_DIM_MS_MIN,
        b.EDGE_IDLE_AUTO_DIM_MS_MAX,
        DEFAULT_CONFIG.edge.idleAutoDimMs,
      ),
    },
    panel: {
      widthDp: clampInt(
        panel?.widthDp,
        b.PANEL_WIDTH_DP_MIN,
        b.PANEL_WIDTH_DP_MAX,
        DEFAULT_CONFIG.panel.widthDp,
      ),
      heightPercent: clampInt(
        panel?.heightPercent,
        b.PANEL_HEIGHT_PERCENT_MIN,
        b.PANEL_HEIGHT_PERCENT_MAX,
        DEFAULT_CONFIG.panel.heightPercent,
      ),
      cornerRadiusDp: clampInt(
        panel?.cornerRadiusDp,
        b.PANEL_CORNER_RADIUS_DP_MIN,
        b.PANEL_CORNER_RADIUS_DP_MAX,
        DEFAULT_CONFIG.panel.cornerRadiusDp,
      ),
      themeId:
        panel?.themeId === SIDEVEIL_THEMES.MODERN_SAAS_DARK
          ? SIDEVEIL_THEMES.MODERN_SAAS_DARK
          : SIDEVEIL_THEMES.COLOROS_GLASS,
      colorMode:
        panel?.colorMode === 'light' || panel?.colorMode === 'dark' ? panel.colorMode : 'system',
      nativeBlurEnabled: panel?.nativeBlurEnabled ?? DEFAULT_CONFIG.panel.nativeBlurEnabled,
      blurRadiusPx: clampInt(
        panel?.blurRadiusPx,
        b.PANEL_BLUR_RADIUS_PX_MIN,
        b.PANEL_BLUR_RADIUS_PX_MAX,
        DEFAULT_CONFIG.panel.blurRadiusPx,
      ),
      scrimAlpha: clampFloat(
        panel?.scrimAlpha,
        b.PANEL_SCRIM_ALPHA_MIN,
        b.PANEL_SCRIM_ALPHA_MAX,
        DEFAULT_CONFIG.panel.scrimAlpha,
      ),
    },
    scope: {
      mode: scope?.mode === 'global' ? 'global' : 'allowlist',
      allowlist: sanitizePkgList(scope?.allowlist, DEFAULT_CONFIG.scope.allowlist),
      denylist: sanitizePkgList(scope?.denylist, DEFAULT_CONFIG.scope.denylist),
    },
    ...(raw?.debug !== undefined
      ? { debug: { webviewDebuggingEnabled: Boolean(raw.debug.webviewDebuggingEnabled) } }
      : {}),
  };
}

function clampInt(val: number | undefined, min: number, max: number, fallback: number): number {
  if (typeof val !== 'number' || !Number.isFinite(val)) return fallback;
  return Math.min(max, Math.max(min, Math.round(val)));
}

function clampFloat(val: number | undefined, min: number, max: number, fallback: number): number {
  if (typeof val !== 'number' || !Number.isFinite(val)) return fallback;
  return Math.min(max, Math.max(min, Number(val.toFixed(2))));
}

function sanitizePkgList(list: string[] | undefined, fallback: string[]): string[] {
  if (!Array.isArray(list)) return [...fallback];
  return Array.from(new Set(list.map((s) => s.trim()).filter(Boolean)));
}
