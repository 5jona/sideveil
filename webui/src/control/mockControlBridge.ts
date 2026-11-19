import type { ExecResult } from '@pochita/bridge';
import { CONFIG_PATH, DEFAULT_CONFIG, type DaemonStatus, type SideveilConfig } from '../shared/types.ts';

interface MockControlState {
  panelState: 'closed' | 'open';
  pingCount: number;
  logs: string[];
}

const mockState: MockControlState = {
  panelState: 'closed',
  pingCount: 0,
  logs: [
    '09:00:00.112 [I] Starting Sideveil daemon (uid=1000, pid=14820)',
    '09:00:00.245 [I] ControlServer listening on 127.0.0.1:41928',
    '09:00:00.268 [I] Registered ATM ITaskStackListener',
    '09:00:00.291 [I] Opened [Gesture Monitor] input channel: [Gesture Monitor] sideveil-edge',
    '09:00:00.610 [I] Host WebSocket connected',
    '09:00:00.614 [I] Host hello pid=14905 blurSupported=true',
  ],
};

function readMockStoredConfig(): SideveilConfig {
  try {
    const raw = window.localStorage.getItem(`pochita:mockfs:sideveil-control:${CONFIG_PATH}`);
    if (raw) {
      return JSON.parse(raw) as SideveilConfig;
    }
  } catch {
    // Ignore storage read issues.
  }
  return DEFAULT_CONFIG;
}

function appendMockLog(msg: string) {
  const now = new Date();
  const ts = now.toTimeString().slice(0, 8) + '.' + String(now.getMilliseconds()).padStart(3, '0');
  mockState.logs.push(`${ts} [I] ${msg}`);
  if (mockState.logs.length > 80) {
    mockState.logs.shift();
  }
}

export async function handleMockControlExec(cmd: string): Promise<ExecResult> {
  const cfg = readMockStoredConfig();
  const normalized = cmd.replace(/['"]/g, '');

  if (normalized.includes('sideveil-ctl status')) {
    const fg = 'me.weishu.kernelsu';
    const denylistHit = cfg.scope.denylist.includes(fg);
    const scopeActive =
      cfg.enabled &&
      !denylistHit &&
      (cfg.scope.mode === 'global' || cfg.scope.allowlist.includes(fg));

    const status: DaemonStatus = {
      pid: 14820,
      uid: 1000,
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
    };
    return { errno: 0, stdout: JSON.stringify(status), stderr: '' };
  }

  if (normalized.includes('sideveil-ctl open')) {
    mockState.panelState = 'open';
    appendMockLog('RPC panel.open executed');
    return { errno: 0, stdout: '{"jsonrpc":"2.0","id":"ctl","result":{"ok":true}}', stderr: '' };
  }

  if (normalized.includes('sideveil-ctl close')) {
    mockState.panelState = 'closed';
    appendMockLog('RPC panel.close executed');
    return { errno: 0, stdout: '{"jsonrpc":"2.0","id":"ctl","result":{"ok":true}}', stderr: '' };
  }

  if (normalized.includes('sideveil-ctl toggle')) {
    mockState.panelState = mockState.panelState === 'open' ? 'closed' : 'open';
    appendMockLog(`RPC panel.toggle -> ${mockState.panelState}`);
    return { errno: 0, stdout: '{"jsonrpc":"2.0","id":"ctl","result":{"ok":true}}', stderr: '' };
  }

  if (normalized.includes('sideveil-ctl ping')) {
    mockState.pingCount += 1;
    appendMockLog(`RPC daemon.ping #${mockState.pingCount}`);
    return {
      errno: 0,
      stdout: JSON.stringify({
        jsonrpc: '2.0',
        id: 'ctl',
        result: { pong: true, daemonUptimeMs: 184_200, timestampMs: Date.now() },
      }),
      stderr: '',
    };
  }

  if (normalized.includes('sideveil-ctl reload')) {
    appendMockLog('Configuration reloaded from disk');
    return {
      errno: 0,
      stdout: JSON.stringify({ jsonrpc: '2.0', id: 'ctl', result: cfg }),
      stderr: '',
    };
  }

  if (normalized.includes('sideveil-ctl restart-host')) {
    appendMockLog('Restarted HostService via supervisor');
    return { errno: 0, stdout: '{"jsonrpc":"2.0","id":"ctl","result":{"ok":true}}', stderr: '' };
  }

  if (normalized.includes('sideveil-ctl logs')) {
    return {
      errno: 0,
      stdout: JSON.stringify({
        jsonrpc: '2.0',
        id: 'ctl',
        result: { lines: mockState.logs },
      }),
      stderr: '',
    };
  }

  return { errno: 0, stdout: `[mock] ${cmd}`, stderr: '' };
}
