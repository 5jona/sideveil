import {
  BUILTIN_DIAG_PLUGIN_ID,
  definePluginManifest,
  type PluginManifest,
} from '@pochita/sideveil-sdk';

export const DIAGNOSTIC_PLUGIN_MANIFEST: PluginManifest = definePluginManifest({
  id: BUILTIN_DIAG_PLUGIN_ID,
  name: '内置诊断',
  version: '0.1.0',
  entry: 'index.html',
  backend: 'none',
  capabilities: ['storage', 'toast'],
  triggers: {
    packages: [],
    global: false,
    priority: 20,
  },
  surface: {
    backdrop: 'native-glass',
  },
});
