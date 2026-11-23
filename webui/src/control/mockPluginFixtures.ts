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
    name: '内置诊断',
    version: '0.1.0',
    entry: 'index.html',
    builtin: true,
    enabled: true,
    priority: 20,
    packages: [],
    global: false,
    hasScopeOverride: false,
    backend: 'none',
    backendState: 'none',
    capabilities: ['storage', 'toast'],
    surface: {
      backdrop: 'native-glass',
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
      url: 'http://homeassistant.local:8123',
      remoteUrl: 'https://ha.example.com:8123',
      lanSsids: ['HomeRouter-5G'],
      allowedOrigins: ['http://homeassistant.local:8123', 'https://ha.example.com:8123'],
      allowCleartext: true,
      wideViewport: true,
      allowZoom: false,
      webHistoryBack: true,
      textZoom: 95,
      showToolbar: true,
      keepAliveGraceSec: 180,
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
      widthDp: 400,
      heightPercent: 78,
    },
  },
];
