import {
  BUILTIN_DIAG_PLUGIN_ID,
  definePluginManifest,
  type PluginManifest,
} from '@pochita/sideveil-sdk';

export const DIAGNOSTIC_PLUGIN_MANIFEST: PluginManifest = definePluginManifest({
  id: BUILTIN_DIAG_PLUGIN_ID,
  name: '系统诊断',
  version: '1.0.0',
  entry: 'index.html',
  backend: 'none',
  capabilities: ['storage', 'toast', 'system-exec'],
  triggers: {
    packages: [],
    global: true,
    priority: 20,
  },
  surface: {
    backdrop: 'native-glass',
    widthDp: 340,
    heightPercent: 72,
  },
});
