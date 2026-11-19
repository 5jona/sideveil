import type { ColorMode, ThemeId } from '@pochita/ui';

export type EdgeSide = 'left' | 'right';
export type ScopeMode = 'allowlist' | 'global';
export type PanelState = 'closed' | 'dragging' | 'open';

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

export interface SideveilConfig {
  version: number;
  enabled: boolean;
  edge: EdgeConfig;
  panel: PanelConfig;
  scope: ScopeConfig;
}

export interface DaemonStatus {
  pid: number;
  uid: number;
  uptimeMs: number;
  configVersion: number;
  enabled: boolean;
  scopeActive: boolean;
  foregroundPackage: string;
  hostPackage: string;
  hostConnected: boolean;
  hostPid: number | null;
  overlayPermissionGranted: boolean;
  crossWindowBlurSupported: boolean | null;
  gestureMonitorActive: boolean;
  taskListenerActive: boolean;
  panelState: PanelState;
  screenWidthPx: number;
  screenHeightPx: number;
  density: number;
  wsPort: number;
}

export interface PingResult {
  pong: boolean;
  daemonUptimeMs: number;
  timestampMs: number;
}

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

export const DEFAULT_CONFIG: SideveilConfig = {
  version: 1,
  enabled: true,
  edge: {
    side: 'right',
    topPercent: 32,
    heightDp: 168,
    hotzoneWidthDp: 24,
    triggerDistanceDp: 36,
    edgeOffsetDp: 1,
    handleVisible: true,
    handleOpacity: 0.62,
    dimInFullscreen: true,
  },
  panel: {
    widthDp: 340,
    heightPercent: 72,
    cornerRadiusDp: 22,
    themeId: 'coloros-glass',
    colorMode: 'system',
    nativeBlurEnabled: true,
    blurRadiusPx: 160,
    scrimAlpha: 0.0,
  },
  scope: {
    mode: 'allowlist',
    allowlist: ['me.weishu.kernelsu'],
    denylist: ['com.android.systemui'],
  },
};

export function normalizeSideveilConfig(
  raw: Partial<SideveilConfig> | null | undefined,
): SideveilConfig {
  const edge = raw?.edge;
  const panel = raw?.panel;
  const scope = raw?.scope;

  return {
    version: typeof raw?.version === 'number' && raw.version >= 1 ? raw.version : 1,
    enabled: raw?.enabled ?? DEFAULT_CONFIG.enabled,
    edge: {
      side: edge?.side === 'left' ? 'left' : 'right',
      topPercent: clampInt(edge?.topPercent, 5, 85, DEFAULT_CONFIG.edge.topPercent),
      heightDp: clampInt(edge?.heightDp, 64, 360, DEFAULT_CONFIG.edge.heightDp),
      hotzoneWidthDp: clampInt(edge?.hotzoneWidthDp, 12, 48, DEFAULT_CONFIG.edge.hotzoneWidthDp),
      triggerDistanceDp: clampInt(
        edge?.triggerDistanceDp,
        18,
        96,
        DEFAULT_CONFIG.edge.triggerDistanceDp,
      ),
      edgeOffsetDp: clampInt(edge?.edgeOffsetDp, 0, 24, DEFAULT_CONFIG.edge.edgeOffsetDp),
      handleVisible: edge?.handleVisible ?? DEFAULT_CONFIG.edge.handleVisible,
      handleOpacity: clampFloat(edge?.handleOpacity, 0.1, 1.0, DEFAULT_CONFIG.edge.handleOpacity),
      dimInFullscreen: edge?.dimInFullscreen ?? DEFAULT_CONFIG.edge.dimInFullscreen,
    },
    panel: {
      widthDp: clampInt(panel?.widthDp, 260, 480, DEFAULT_CONFIG.panel.widthDp),
      heightPercent: clampInt(panel?.heightPercent, 40, 92, DEFAULT_CONFIG.panel.heightPercent),
      cornerRadiusDp: clampInt(panel?.cornerRadiusDp, 8, 36, DEFAULT_CONFIG.panel.cornerRadiusDp),
      themeId: panel?.themeId === 'modern-saas-dark' ? 'modern-saas-dark' : 'coloros-glass',
      colorMode:
        panel?.colorMode === 'light' || panel?.colorMode === 'dark' ? panel.colorMode : 'system',
      nativeBlurEnabled: panel?.nativeBlurEnabled ?? DEFAULT_CONFIG.panel.nativeBlurEnabled,
      blurRadiusPx: clampInt(panel?.blurRadiusPx, 0, 300, DEFAULT_CONFIG.panel.blurRadiusPx),
      scrimAlpha: clampFloat(panel?.scrimAlpha, 0, 0.75, DEFAULT_CONFIG.panel.scrimAlpha),
    },
    scope: {
      mode: scope?.mode === 'global' ? 'global' : 'allowlist',
      allowlist: sanitizePkgList(scope?.allowlist, DEFAULT_CONFIG.scope.allowlist),
      denylist: sanitizePkgList(scope?.denylist, DEFAULT_CONFIG.scope.denylist),
    },
  };
}

export const normalizeConfig = normalizeSideveilConfig;


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
