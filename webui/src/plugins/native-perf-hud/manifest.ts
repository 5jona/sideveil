import { definePluginManifest, type PluginManifest } from '@pochita/sideveil-sdk';

export const NATIVE_PERF_HUD_PLUGIN_ID = 'example.native-perf-hud';

export const NATIVE_PERF_HUD_MANIFEST: PluginManifest = definePluginManifest({
  id: NATIVE_PERF_HUD_PLUGIN_ID,
  name: '性能监控',
  version: '0.1.0',
  icon: 'web/icon.svg',
  entry: 'web/index.html',
  backend: 'uds-native',
  capabilities: ['native-hud', 'telemetry', 'haptics'],
  triggers: {
    packages: [],
    activities: [],
    global: true,
    priority: 15,
  },
  surface: {
    backdrop: 'native-glass',
    presentation: 'drawer',
    crossOriginIsolated: false,
    widthDp: 320,
    heightPercent: 54,
  },
});
