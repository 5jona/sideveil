import type { DebugGestureStats, EdgeSide, PanelState } from '@pochita/sideveil-sdk';

export type { DebugGestureStats };

export interface DebugConfig {
  webviewDebuggingEnabled: boolean;
}

export interface DebugSetWebViewDebuggingParams {
  enabled: boolean;
}

export interface DebugCdpStatusResult {
  ok: boolean;
  webviewDebuggingEnabled: boolean;
  effectiveEnabled: boolean;
  devServerActive: boolean;
  hostPid: number | null;
  cdpSocket: string | null;
  restartRecommended: boolean;
}

export interface DebugDisplaySnapshot {
  widthPx: number;
  heightPx: number;
  density: number;
  rotation: number;
  refreshRateHz: number;
  activeModeId: number;
  maxRefreshRateHz: number;
  maxSupportedModeId: number;
  crossWindowBlurSupported: boolean;
}

export interface DebugSafeBoundsSnapshot {
  screenWidthPx: number;
  screenHeightPx: number;
  safeTopPx: number;
  safeBottomPx: number;
  safeLeftPx: number;
  safeRightPx: number;
  imeBottomPx: number;
  isTabletLandscape: boolean;
}

export interface DebugAgslSnapshot {
  shaderCompiled: boolean;
  fallbackActive: boolean;
  themeId: string;
  isDark: boolean;
  blurActive: boolean;
  cornerRadiusPx: number;
  drawableAlpha: number;
  motionProgress: number;
  velocityShift: number;
  hasDynamicOverride: boolean;
  boundsWidthPx: number;
  boundsHeightPx: number;
}

export interface DebugPanelWindowSnapshot {
  attached: boolean;
  visible: boolean;
  state: PanelState;
  closingInProgress: boolean;
  edgeSide: EdgeSide;
  presentationMode: string;
  pluginBackdrop: string;
  railInsetDp: number;
  x: number;
  y: number;
  width: number;
  height: number;
  gravity: number;
  flags: number;
  dimAmount: number;
  preferredRefreshRateHz: number;
  preferredDisplayModeId: number;
  appliedBlurRadiusPx: number;
  targetBlurRadiusPx: number;
  systemBlurSupported: boolean;
  themeId: string;
  colorScheme: string;
  motionProgress: number;
  agsl: DebugAgslSnapshot;
}

export interface DebugOverlayWindowSnapshot {
  name: string;
  attached: boolean;
  visible: boolean;
  x: number;
  y: number;
  width: number;
  height: number;
  gravity: number;
  flags: number;
  alpha: number;
  preferredRefreshRateHz: number;
  preferredDisplayModeId: number;
  appliedBlurRadiusPx: number;
  clickThrough: boolean;
  imeFocused: boolean;
  activePluginId: string | null;
  agsl?: DebugAgslSnapshot | null;
}

export interface DebugWebViewSnapshot {
  pluginId: string;
  url?: string | null;
  title?: string | null;
  canGoBack: boolean;
  attachedToParent: boolean;
  isActive: boolean;
  isPinned: boolean;
  keepAlive: boolean;
  trustMode: string;
  crossOriginIsolated: boolean;
  devServerUrl?: string | null;
  backdrop: string;
  presentationMode: string;
  backInterceptEnabled: boolean;
  rendererPriority: string;
  rendererWaivedWhenNotVisible: boolean;
  widthPx: number;
  heightPx: number;
  dormant?: boolean;
  crashCount?: number;
}

export interface DebugFrameMetricsSnapshot {
  sampleCount: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  maxMs: number;
  drawP95Ms: number;
  gpuP95Ms: number;
  jankCount: number;
  deadlineMs: number;
}

export interface DebugInspectUiSnapshot {
  hostPid: number;
  webviewDebuggingEnabled: boolean;
  cdpSocket: string | null;
  activePluginId: string;
  pinnedPluginId: string | null;
  activeHudPluginId: string | null;
  matchedPluginIds: string[];
  display: DebugDisplaySnapshot;
  safeBounds: DebugSafeBoundsSnapshot;
  agsl: DebugAgslSnapshot;
  panel: DebugPanelWindowSnapshot;
  pinned: DebugOverlayWindowSnapshot;
  hud: DebugOverlayWindowSnapshot;
  rail: DebugOverlayWindowSnapshot;
  edge: DebugOverlayWindowSnapshot;
  webViews: DebugWebViewSnapshot[];
  frameMetrics: DebugFrameMetricsSnapshot;
  windowFrameMetrics: Record<string, DebugFrameMetricsSnapshot>;
  gestureStats?: DebugGestureStats;
}
