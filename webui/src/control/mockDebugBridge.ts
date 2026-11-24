import type { ExecResult } from '@gojona/bridge';
import { CONFIG_PATH, type DebugCdpStatusResult, type SideVeilConfig } from '../shared/types.ts';
import { buildMockInspectUiSnapshot } from './mockInspectSnapshot.ts';
import { MOCK_HOST_PID } from './mockPluginFixtures.ts';

function writeMockStoredConfig(next: SideVeilConfig): void {
  try {
    window.localStorage.setItem(
      `pochita:mockfs:sideveil-control:${CONFIG_PATH}`,
      JSON.stringify(next),
    );
  } catch {
    // Ignore storage write issues in preview mode or headless Node tests.
  }
}

export interface MockDebugContext {
  cfg: SideVeilConfig;
  panelState: 'closed' | 'open';
  activePluginId: string;
  matchedPluginIds: string[];
  devServerActive?: boolean;
  appendLog: (msg: string) => void;
}

function okJson(payload: unknown): ExecResult {
  return { errno: 0, stdout: JSON.stringify(payload), stderr: '' };
}

function rpcOk(result: unknown): ExecResult {
  return okJson({ jsonrpc: '2.0', id: 'ctl', result });
}

let mockFrameMetricsReset = false;

export function handleMockDebugExec(normalized: string, ctx: MockDebugContext): ExecResult | null {
  if (normalized.includes('sideveil-ctl debug reset-frames')) {
    mockFrameMetricsReset = true;
    ctx.appendLog('RPC debug.resetFrameMetrics cleared frame metrics');
    return rpcOk({ ok: true });
  }

  if (normalized.includes('sideveil-ctl debug inspect-ui')) {
    const snapshot = buildMockInspectUiSnapshot({
      cfg: ctx.cfg,
      panelState: ctx.panelState,
      activePluginId: ctx.activePluginId,
      matchedPluginIds: ctx.matchedPluginIds,
      devServerActive: ctx.devServerActive,
      frameMetricsReset: mockFrameMetricsReset,
    });
    ctx.appendLog('RPC debug.inspectUi captured snapshot');
    return rpcOk(snapshot);
  }

  const cdpMatch = normalized.match(/sideveil-ctl debug cdp (on|off|status)/);
  if (cdpMatch) {
    const mode = cdpMatch[1];
    const prevEnabled = Boolean(ctx.cfg.debug?.webviewDebuggingEnabled);
    const devServerActive = Boolean(ctx.devServerActive);
    if (mode === 'status') {
      const effectiveEnabled = prevEnabled || devServerActive;
      const result: DebugCdpStatusResult = {
        ok: true,
        webviewDebuggingEnabled: prevEnabled,
        effectiveEnabled,
        devServerActive,
        hostPid: MOCK_HOST_PID,
        cdpSocket: effectiveEnabled ? `webview_devtools_remote_${MOCK_HOST_PID}` : null,
        restartRecommended: false,
      };
      return rpcOk(result);
    }
    const nextEnabled = mode === 'on';
    const effectiveEnabled = nextEnabled || devServerActive;
    writeMockStoredConfig({
      ...ctx.cfg,
      debug: { webviewDebuggingEnabled: nextEnabled },
    });
    const result: DebugCdpStatusResult = {
      ok: true,
      webviewDebuggingEnabled: nextEnabled,
      effectiveEnabled,
      devServerActive,
      hostPid: MOCK_HOST_PID,
      cdpSocket: effectiveEnabled ? `webview_devtools_remote_${MOCK_HOST_PID}` : null,
      restartRecommended: prevEnabled && !nextEnabled && !devServerActive,
    };
    ctx.appendLog(`RPC debug.setWebViewDebugging enabled=${String(nextEnabled)}`);
    return rpcOk(result);
  }

  return null;
}
