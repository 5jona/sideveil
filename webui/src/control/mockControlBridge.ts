import type { ExecResult } from '@pochita/bridge';
import type { PluginDescriptor, PluginsSnapshotPayload } from '@pochita/sideveil-sdk';
import {
  CONFIG_PATH,
  type DaemonStatus,
  DEFAULT_CONFIG,
  type SideveilConfig,
} from '../shared/types.ts';

interface MockControlState {
  panelState: 'closed' | 'open';
  pingCount: number;
  logs: string[];
  activePluginId: string;
  plugins: PluginDescriptor[];
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
  activePluginId: 'sideveil.diagnostic',
  plugins: [
    {
      id: 'sideveil.diagnostic',
      name: '系统诊断',
      version: '1.0.0',
      entry: 'index.html',
      builtin: true,
      enabled: true,
      priority: 0,
      packages: [],
      global: true,
      hasScopeOverride: false,
      backend: 'none',
      backendState: 'none',
      capabilities: ['storage', 'toast', 'system-exec'],
      surface: {
        backdrop: 'native-glass',
        widthDp: 340,
        heightPercent: 72,
      },
    },
    {
      id: 'com.gamer.hud',
      name: '游戏性能悬浮条',
      version: '2.1.4',
      entry: 'dist/index.html',
      builtin: false,
      enabled: true,
      priority: 20,
      packages: ['com.miHoYo.GenshinImpact', 'com.tencent.tmgp.sgame'],
      global: false,
      hasScopeOverride: false,
      backend: 'uds-native',
      backendState: 'online',
      capabilities: ['storage', 'toast', 'system-exec'],
      surface: {
        backdrop: 'transparent',
        widthDp: 380,
        heightPercent: 65,
      },
    },
    {
      id: 'org.music.remote',
      name: '全局流媒体歌词与控制',
      version: '1.3.0',
      entry: 'index.html',
      builtin: false,
      enabled: true,
      priority: 10,
      packages: ['com.netease.cloudmusic', 'com.spotify.music'],
      global: false,
      hasScopeOverride: false,
      backend: 'uds-app',
      backendState: 'offline',
      capabilities: ['toast'],
      surface: {
        backdrop: 'opaque',
        widthDp: 320,
        heightPercent: 50,
      },
    },
  ],
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
  const ts = `${now.toTimeString().slice(0, 8)}.${String(now.getMilliseconds()).padStart(3, '0')}`;
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

  if (normalized.includes('sideveil-ctl plugins list')) {
    return {
      errno: 0,
      stdout: JSON.stringify(getMockSnapshot()),
      stderr: '',
    };
  }

  if (normalized.includes('sideveil-ctl plugins reload')) {
    appendMockLog('Rescanned plugins directory');
    return {
      errno: 0,
      stdout: JSON.stringify({
        ok: true,
        count: mockState.plugins.length,
        snapshot: getMockSnapshot(),
      }),
      stderr: '',
    };
  }

  const enableMatch = normalized.match(/sideveil-ctl plugins (enable|disable) ([^\s]+)/);
  if (enableMatch) {
    const isEnable = enableMatch[1] === 'enable';
    const targetId = enableMatch[2];
    mockState.plugins = mockState.plugins.map((p) =>
      p.id === targetId ? { ...p, enabled: isEnable } : p,
    );
    appendMockLog(`Plugin ${targetId} ${isEnable ? 'enabled' : 'disabled'}`);
    return {
      errno: 0,
      stdout: JSON.stringify(getMockSnapshot()),
      stderr: '',
    };
  }

  const priorityMatch = normalized.match(/sideveil-ctl plugins priority ([^\s]+) (-?\d+)/);
  if (priorityMatch) {
    const targetId = priorityMatch[1];
    const prio = Number.parseInt(priorityMatch[2], 10);
    mockState.plugins = mockState.plugins.map((p) =>
      p.id === targetId ? { ...p, priority: prio } : p,
    );
    appendMockLog(`Plugin ${targetId} priority set to ${prio}`);
    return {
      errno: 0,
      stdout: JSON.stringify(getMockSnapshot()),
      stderr: '',
    };
  }

  const selectMatch = normalized.match(/sideveil-ctl plugins select ([^\s]+)/);
  if (selectMatch) {
    const targetId = selectMatch[1];
    mockState.activePluginId = targetId;
    appendMockLog(`Active plugin switched to ${targetId}`);
    return {
      errno: 0,
      stdout: JSON.stringify(getMockSnapshot()),
      stderr: '',
    };
  }

  return { errno: 0, stdout: `[mock] ${cmd}`, stderr: '' };
}
