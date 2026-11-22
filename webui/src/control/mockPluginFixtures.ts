import type {
  NetworkContextSnapshot,
  PluginAuditReport,
  PluginDescriptor,
} from '@pochita/sideveil-sdk';

export const MOCK_HOST_PID = 14905;

export function buildMockAuditReport(): PluginAuditReport {
  return {
    entries: [
      {
        timestampMs: Date.now() - 4000,
        pluginId: 'com.gamer.hud',
        action: 'telemetry.snapshot',
        allowed: true,
        detail: 'sampled /proc/stat',
      },
    ],
    diagnostics: {
      warmPluginIds: ['sideveil.diagnostic', 'com.gamer.hud', 'lan.homeassistant'],
      activeHudPluginId: 'com.gamer.hud',
      hostNativeHeapKb: 16384,
      hostPssKb: 54200,
    },
  };
}

export const INITIAL_MOCK_NETWORK_CONTEXT: NetworkContextSnapshot = {
  transport: 'wifi',
  vpnActive: false,
  validated: true,
  metered: false,
  interfaceName: 'wlan0',
  ssid: 'HomeRouter-5G',
  gateway: '192.168.1.1',
  localIpv4: '192.168.1.108',
};

export const INITIAL_MOCK_PLUGINS: readonly PluginDescriptor[] = [
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
    capabilities: ['storage', 'toast', 'system-exec', 'network-loopback'],
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
  {
    id: 'lan.homeassistant',
    name: 'Home Assistant',
    version: '1.0.0',
    embed: {
      url: 'http://192.168.1.10:8123',
      allowedOrigins: ['http://192.168.1.10:8123'],
      allowCleartext: true,
      wideViewport: true,
      webHistoryBack: true,
    },
    builtin: false,
    enabled: true,
    priority: 5,
    packages: [],
    global: true,
    hasScopeOverride: false,
    backend: 'none',
    backendState: 'none',
    capabilities: ['network-lan', 'network-context'],
    surface: {
      backdrop: 'opaque',
      keepAlive: true,
      widthDp: 380,
      heightPercent: 78,
    },
  },
];
