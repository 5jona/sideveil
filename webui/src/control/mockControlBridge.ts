import type { ExecResult } from '@pochita/bridge';
import type {
  NetworkContextSnapshot,
  PluginDescriptor,
  PluginsSnapshotPayload,
} from '@pochita/sideveil-sdk';
import {
  CONFIG_PATH,
  type DaemonStatus,
  DEFAULT_CONFIG,
  type SideVeilConfig,
} from '../shared/types.ts';
import { handleMockDebugExec } from './mockDebugBridge.ts';
import { handleMockPluginExec } from './mockPluginBridge.ts';
import {
  INITIAL_MOCK_NETWORK_CONTEXT,
  INITIAL_MOCK_PLUGINS,
  MOCK_HOST_PID,
} from './mockPluginFixtures.ts';

interface MockControlState {
  panelState: 'closed' | 'open';
  pingCount: number;
  logSeq: number;
  logs: string[];
  activePluginId: string;
  plugins: PluginDescriptor[];
  network: NetworkContextSnapshot;
}

const mockState: MockControlState = {
  panelState: 'closed',
  pingCount: 0,
  logSeq: 9,
  logs: [
    '09:00:00.112 [I] Starting SideVeil hub daemon (uid=0, pid=14820)',
    '09:00:00.180 [I] Spawned UID 1000 sideveil-sensor (pid=14842)',
    '09:00:00.245 [I] Host WebSocket server listening on 127.0.0.1:41928',
    '09:00:00.268 [I] Registered ATM ITaskStackListener',
    '09:00:00.291 [I] Opened [Gesture Monitor] input channel: [Gesture Monitor] sideveil-edge',
    '09:00:00.610 [I] Host WebSocket connected',
    `09:00:00.614 [I] Host hello pid=${MOCK_HOST_PID} blurSupported=true`,
    '09:00:01.042 [I] [WebView:sideveil.diagnostic][INFO] Panel hydrated in 18ms',
    '09:00:02.180 [W] [WebView:com.gamer.hud][WARNING] Telemetry frame budget exceeded',
  ],
  activePluginId: 'sideveil.diagnostic',
  plugins: INITIAL_MOCK_PLUGINS.map((p) => ({
    ...p,
    packages: [...p.packages],
    ...(p.embed ? { embed: { ...p.embed } } : {}),
    surface: { ...p.surface },
  })),
  network: { ...INITIAL_MOCK_NETWORK_CONTEXT },
};

function getMockSnapshot(fgPkg = 'me.weishu.kernelsu'): PluginsSnapshotPayload {
  const matched = mockState.plugins
    .filter((p) => p.enabled && (p.global || p.packages.includes(fgPkg)))
    .map((p) => p.id);

  return {
    plugins: mockState.plugins,
    matchedPluginIds: matched,
    activePluginId: mockState.activePluginId,
    foregroundPackage: fgPkg,
  };
}

function readMockStoredConfig(): SideVeilConfig {
  try {
    const raw = window.localStorage.getItem(`pochita:mockfs:sideveil-control:${CONFIG_PATH}`);
    if (raw) {
      return JSON.parse(raw) as SideVeilConfig;
    }
  } catch {
    // Ignore storage read issues.
  }
  return DEFAULT_CONFIG;
}

function appendMockLog(msg: string) {
  const now = new Date();
  const ts = `${now.toTimeString().slice(0, 8)}.${String(now.getMilliseconds()).padStart(3, '0')}`;
  mockState.logSeq += 1;
  mockState.logs.push(`${ts} [I] ${msg}`);
  if (mockState.logs.length > 80) {
    mockState.logs.shift();
  }
}

function okJson(payload: unknown): ExecResult {
  return { errno: 0, stdout: JSON.stringify(payload), stderr: '' };
}

function rpcOk(result: unknown): ExecResult {
  return okJson({ jsonrpc: '2.0', id: 'ctl', result });
}

export async function handleMockControlExec(cmd: string): Promise<ExecResult> {
  const cfg = readMockStoredConfig();
  const normalized = cmd.replace(/['"]/g, '');

  const debugRes = handleMockDebugExec(normalized, {
    cfg,
    panelState: mockState.panelState,
    activePluginId: mockState.activePluginId,
    matchedPluginIds: getMockSnapshot().matchedPluginIds,
    devServerActive: mockState.plugins.some((p) => Boolean(p.devServerUrl)),
    appendLog: appendMockLog,
  });
  if (debugRes) return debugRes;

  if (normalized.includes('sideveil-ctl status')) {
    const fg = 'me.weishu.kernelsu';
    const denylistHit = cfg.scope.denylist.includes(fg);
    const scopeActive =
      cfg.enabled &&
      !denylistHit &&
      (cfg.scope.mode === 'global' || cfg.scope.allowlist.includes(fg));

    const status: DaemonStatus = {
      pid: 14820,
      uid: 0,
      uptimeMs: 184_200,
      configVersion: cfg.version,
      enabled: cfg.enabled,
      scopeActive,
      foregroundPackage: fg,
      hostPackage: 'pochita.sideveil.host',
      hostConnected: true,
      hostPid: 14905,
      overlayPermissionGranted: true,
      crossWindowBlurSupported: true,
      gestureMonitorActive: true,
      taskListenerActive: true,
      panelState: mockState.panelState,
      screenWidthPx: 3392,
      screenHeightPx: 2400,
      density: 2.625,
      wsPort: 41928,
      sensorConnected: true,
      sensorPid: 14842,
      activePluginId: mockState.activePluginId,
      matchedPluginsCount: getMockSnapshot().matchedPluginIds.length,
      gestureStats: {
        edgeSwipesTriggered: 18,
        edgeSwipesRejected: 2,
        hotzoneMisses: 5,
        pilferInvocations: 18,
        lastRejectReason: 'fullscreen_single_swipe',
        lastEventTimestampMs: Date.now() - 12_000,
      },
    };
    return { errno: 0, stdout: JSON.stringify(status), stderr: '' };
  }

  if (normalized.includes('sideveil-ctl open')) {
    mockState.panelState = 'open';
    appendMockLog('RPC panel.open executed');
    return rpcOk({ ok: true });
  }
  if (normalized.includes('sideveil-ctl close')) {
    mockState.panelState = 'closed';
    appendMockLog('RPC panel.close executed');
    return rpcOk({ ok: true });
  }
  if (normalized.includes('sideveil-ctl toggle')) {
    mockState.panelState = mockState.panelState === 'open' ? 'closed' : 'open';
    appendMockLog(`RPC panel.toggle -> ${mockState.panelState}`);
    return rpcOk({ ok: true });
  }
  if (normalized.includes('sideveil-ctl ping')) {
    mockState.pingCount += 1;
    appendMockLog(`RPC daemon.ping #${mockState.pingCount}`);
    return rpcOk({ pong: true, daemonUptimeMs: 184_200, timestampMs: Date.now() });
  }
  if (normalized.includes('sideveil-ctl reload')) {
    appendMockLog('Configuration reloaded from disk');
    return rpcOk(cfg);
  }
  if (normalized.includes('sideveil-ctl restart-host')) {
    appendMockLog('Restarted HostService via supervisor');
    return rpcOk({ ok: true });
  }
  if (normalized.includes('sideveil-ctl logs --clear')) {
    mockState.logs = [];
    return rpcOk({ ok: true, nextSeq: mockState.logSeq });
  }
  if (normalized.includes('sideveil-ctl logs')) {
    return rpcOk({ lines: mockState.logs, nextSeq: mockState.logSeq, totalDropped: 0 });
  }
  if (normalized.includes('sideveil-ctl network')) {
    return okJson(mockState.network);
  }

  const pluginRes = handleMockPluginExec(cmd, normalized, {
    state: mockState,
    getSnapshot: getMockSnapshot,
    appendLog: appendMockLog,
  });
  if (pluginRes) return pluginRes;

  return { errno: 0, stdout: `[mock] ${cmd}`, stderr: '' };
}
