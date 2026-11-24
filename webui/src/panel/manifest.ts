import { definePluginManifest, type PluginManifest } from '@gojona/sideveil-sdk';
import { SIDEVEIL_BUILTIN_DIAGNOSTIC } from '../contracts.ts';

export const DIAGNOSTIC_PLUGIN_MANIFEST: PluginManifest = definePluginManifest({
  id: SIDEVEIL_BUILTIN_DIAGNOSTIC.ID,
  name: SIDEVEIL_BUILTIN_DIAGNOSTIC.NAME,
  version: SIDEVEIL_BUILTIN_DIAGNOSTIC.VERSION,
  entry: SIDEVEIL_BUILTIN_DIAGNOSTIC.ENTRY,
  backend: SIDEVEIL_BUILTIN_DIAGNOSTIC.BACKEND,
  capabilities: [
    SIDEVEIL_BUILTIN_DIAGNOSTIC.CAPABILITY_STORAGE,
    SIDEVEIL_BUILTIN_DIAGNOSTIC.CAPABILITY_TOAST,
  ],
  triggers: {
    packages: [],
    global: SIDEVEIL_BUILTIN_DIAGNOSTIC.GLOBAL,
    priority: SIDEVEIL_BUILTIN_DIAGNOSTIC.PRIORITY,
  },
  surface: {
    backdrop: SIDEVEIL_BUILTIN_DIAGNOSTIC.SURFACE_BACKDROP,
  },
});
