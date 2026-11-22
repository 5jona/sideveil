import type { ExecResult } from '@pochita/bridge';
import {
  CONFIG_PATH,
  type DebugAgslSnapshot,
  type DebugCdpStatusResult,
  type DebugInspectUiSnapshot,
  type DebugOverlayWindowSnapshot,
  type SideVeilConfig,
} from '../shared/types.ts';
import { MOCK_HOST_PID } from './mockPluginFixtures.ts';

function mockOverlay(
  name: string,
  overrides: Partial<DebugOverlayWindowSnapshot> = {},
): DebugOverlayWindowSnapshot {
  return {
    name,
    attached: false,
    visible: false,
    x: 0,
    y: 0,
    width: 0,
    height: 0,
    gravity: 0,
    flags: 0,
    alpha: 1,
    preferredRefreshRateHz: 0,
    preferredDisplayModeId: 0,
    appliedBlurRadiusPx: 0,
    clickThrough: false,
    imeFocused: false,
    activePluginId: null,
    ...overrides,
  };
}

function writeMockStoredConfig(next: SideVeilConfig): void {
  try {
    window.localStorage.setItem(
      `pochita:mockfs:sideveil-control:${CONFIG_PATH}`,
      JSON.stringify(next),
    );
  } catch {
    // Ignore storage write issues in preview mode.
  }
}

export interface MockDebugContext {
  cfg: SideVeilConfig;
  panelState: 'closed' | 'open';
  activePluginId: string;
  matchedPluginIds: string[];
  appendLog: (msg: string) => void;
}

export function handleMockDebugExec(normalized: string, ctx: MockDebugContext): ExecResult | null {
  if (normalized.includes('sideveil-ctl debug inspect-ui')) {
    const enabled = Boolean(ctx.cfg.debug?.webviewDebuggingEnabled);
    const panelWidth = Math.round(ctx.cfg.panel.widthDp * 2.625);
    const panelHeight = Math.round((2400 * ctx.cfg.panel.heightPercent) / 100);
    const agsl: DebugAgslSnapshot = {
      shaderCompiled: true,
      fallbackActive: false,
      themeId: ctx.cfg.panel.themeId,
      isDark: ctx.cfg.panel.colorMode !== 'light',
      blurActive: ctx.cfg.panel.nativeBlurEnabled,
      cornerRadiusPx: Math.round(ctx.cfg.panel.cornerRadiusDp * 2.625),
      drawableAlpha: 255,
      motionProgress: ctx.panelState === 'open' ? 1 : 0,
      velocityShift: 0,
      hasDynamicOverride: false,
      boundsWidthPx: panelWidth,
      boundsHeightPx: panelHeight,
    };
    const snapshot: DebugInspectUiSnapshot = {
      hostPid: MOCK_HOST_PID,
      webviewDebuggingEnabled: enabled,
      cdpSocket: enabled ? `webview_devtools_remote_${MOCK_HOST_PID}` : null,
      activePluginId: ctx.activePluginId,
      pinnedPluginId: null,
      activeHudPluginId: 'com.gamer.hud',
      matchedPluginIds: ctx.matchedPluginIds,
      display: {
        widthPx: 3392,
        heightPx: 2400,
        density: 2.625,
        rotation: 1,
        refreshRateHz: 144,
        activeModeId: 1,
        maxRefreshRateHz: 144,
        maxSupportedModeId: 1,
        crossWindowBlurSupported: true,
      },
      safeBounds: {
        screenWidthPx: 3392,
        screenHeightPx: 2400,
        safeTopPx: 84,
        safeBottomPx: 48,
        safeLeftPx: 0,
        safeRightPx: 0,
        imeBottomPx: 0,
        isTabletLandscape: true,
      },
      agsl,
      panel: {
        attached: true,
        visible: ctx.panelState === 'open',
        state: ctx.panelState,
        closingInProgress: false,
        edgeSide: ctx.cfg.edge.side,
        presentationMode: 'drawer',
        pluginBackdrop: 'native-glass',
        railInsetDp: 48,
        x: 2480,
        y: 336,
        width: panelWidth,
        height: panelHeight,
        gravity: 8388629,
        flags: 16777248,
        dimAmount: ctx.cfg.panel.scrimAlpha,
        preferredRefreshRateHz: ctx.panelState === 'open' ? 144 : 0,
        preferredDisplayModeId: ctx.panelState === 'open' ? 1 : 0,
        appliedBlurRadiusPx: ctx.cfg.panel.nativeBlurEnabled ? ctx.cfg.panel.blurRadiusPx : 0,
        targetBlurRadiusPx: ctx.cfg.panel.blurRadiusPx,
        systemBlurSupported: true,
        themeId: ctx.cfg.panel.themeId,
        colorScheme: ctx.cfg.panel.colorMode === 'light' ? 'light' : 'dark',
        motionProgress: ctx.panelState === 'open' ? 1 : 0,
        agsl,
      },
      pinned: mockOverlay('pinned'),
      hud: mockOverlay('hud', {
        attached: true,
        visible: true,
        x: 32,
        y: 96,
        width: 420,
        height: 96,
        preferredRefreshRateHz: 144,
        preferredDisplayModeId: 1,
        clickThrough: true,
        activePluginId: 'com.gamer.hud',
      }),
      rail: mockOverlay('rail', {
        attached: true,
        visible: ctx.panelState === 'open',
        width: 126,
        height: 1728,
        preferredRefreshRateHz: ctx.panelState === 'open' ? 144 : 0,
        appliedBlurRadiusPx: 64,
      }),
      edge: mockOverlay('edge', {
        attached: true,
        visible: ctx.cfg.edge.handleVisible,
        width: 24,
        height: 441,
        alpha: ctx.cfg.edge.handleOpacity,
        clickThrough: true,
      }),
      webViews: [
        {
          pluginId: 'sideveil.diagnostic',
          url: 'https://sideveil.diagnostic.plugins.sideveil.local/index.html',
          title: 'SideVeil',
          canGoBack: false,
          attachedToParent: true,
          isActive: ctx.activePluginId === 'sideveil.diagnostic',
          isPinned: false,
          keepAlive: true,
          trustMode: 'trusted',
          crossOriginIsolated: false,
          backdrop: 'native-glass',
          presentationMode: 'drawer',
          backInterceptEnabled: false,
          rendererPriority: 'important',
          rendererWaivedWhenNotVisible: true,
          widthPx: 892,
          heightPx: 1728,
        },
        {
          pluginId: 'com.gamer.hud',
          url: 'https://com.gamer.hud.plugins.sideveil.local/dist/index.html',
          title: '游戏性能悬浮条',
          canGoBack: false,
          attachedToParent: false,
          isActive: ctx.activePluginId === 'com.gamer.hud',
          isPinned: false,
          keepAlive: true,
          trustMode: 'trusted',
          crossOriginIsolated: false,
          backdrop: 'transparent',
          presentationMode: 'drawer',
          backInterceptEnabled: false,
          rendererPriority: 'bound',
          rendererWaivedWhenNotVisible: true,
          widthPx: 998,
          heightPx: 1560,
        },
      ],
      frameMetrics: {
        sampleCount: 180,
        p50Ms: 3.4,
        p95Ms: 5.8,
        p99Ms: 6.7,
        maxMs: 8.2,
        drawP95Ms: 2.1,
        gpuP95Ms: 2.6,
        jankCount: 1,
        deadlineMs: 6.94,
      },
      windowFrameMetrics: {},
    };
    ctx.appendLog('RPC debug.inspectUi captured snapshot');
    return {
      errno: 0,
      stdout: JSON.stringify({ jsonrpc: '2.0', id: 'ctl', result: snapshot }),
      stderr: '',
    };
  }

  const cdpMatch = normalized.match(/sideveil-ctl debug cdp (on|off|status)/);
  if (cdpMatch) {
    const mode = cdpMatch[1];
    const prevEnabled = Boolean(ctx.cfg.debug?.webviewDebuggingEnabled);
    if (mode === 'status') {
      return {
        errno: 0,
        stdout: JSON.stringify({
          webviewDebuggingEnabled: prevEnabled,
          hostPid: MOCK_HOST_PID,
          cdpSocket: prevEnabled ? `webview_devtools_remote_${MOCK_HOST_PID}` : null,
        }),
        stderr: '',
      };
    }
    const nextEnabled = mode === 'on';
    writeMockStoredConfig({
      ...ctx.cfg,
      debug: { webviewDebuggingEnabled: nextEnabled },
    });
    const result: DebugCdpStatusResult = {
      ok: true,
      webviewDebuggingEnabled: nextEnabled,
      hostPid: MOCK_HOST_PID,
      cdpSocket: nextEnabled ? `webview_devtools_remote_${MOCK_HOST_PID}` : null,
      restartRecommended: prevEnabled && !nextEnabled,
    };
    ctx.appendLog(`RPC debug.setWebViewDebugging enabled=${String(nextEnabled)}`);
    return {
      errno: 0,
      stdout: JSON.stringify({ jsonrpc: '2.0', id: 'ctl', result }),
      stderr: '',
    };
  }

  return null;
}
