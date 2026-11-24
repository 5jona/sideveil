import { definePluginManifest, type PluginManifest } from '@gojona/sideveil-sdk';

export const VELLO_SCOPE_PLUGIN_ID = 'example.vello-scope';

export const VELLO_SCOPE_MANIFEST: PluginManifest = definePluginManifest({
  id: VELLO_SCOPE_PLUGIN_ID,
  name: 'Vello 矢量示波',
  version: '0.2.0',
  icon: 'web/icon.svg',
  entry: 'web/index.html',
  backend: 'none',
  capabilities: ['telemetry', 'haptics'],
  triggers: {
    packages: [],
    activities: [],
    global: true,
    priority: 18,
  },
  surface: {
    backdrop: 'native-glass',
    presentation: 'drawer',
    crossOriginIsolated: true,
    widthDp: 356,
    heightPercent: 62,
  },
});
