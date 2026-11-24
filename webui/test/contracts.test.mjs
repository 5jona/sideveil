import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  collectFixtureFiles,
  validateFixtureData,
  validateFixtureFile,
} from '@gojona/contracts-gen';
import {
  PLUGIN_CAPABILITY_VALUES,
  PLUGIN_ORIGIN_VALUES,
  SIDEVEIL_BUILTIN_DIAGNOSTIC,
  SIDEVEIL_CONFIG_BOUNDS,
  SIDEVEIL_CONFIG_DEFAULTS,
  SIDEVEIL_ERROR_CODES,
  SIDEVEIL_EVENTS,
  SIDEVEIL_HUD_DEFAULTS,
  SIDEVEIL_METHODS,
  SIDEVEIL_PROTOCOL_VERSIONS,
  SIDEVEIL_THEMES,
  SIDEVEIL_TIMEOUT_BOUNDS,
} from '../src/contracts.ts';
import { DIAGNOSTIC_PLUGIN_MANIFEST } from '../src/panel/manifest.ts';
import { DEFAULT_CONFIG, normalizeSideVeilConfig } from '../src/shared/types.ts';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '../..');
const CONTRACTS_DIR = path.join(REPO_ROOT, 'contracts');

test('generated method, event, error code, and theme registries are complete across stacks', () => {
  const constantsSpec = JSON.parse(
    fs.readFileSync(path.join(CONTRACTS_DIR, 'protocol-constants.contract.json'), 'utf8'),
  );
  const specMethods = Object.keys(constantsSpec.constants.protocol_methods.items);
  const specEvents = Object.keys(constantsSpec.constants.protocol_events.items);
  const specErrors = Object.keys(constantsSpec.constants.error_codes.items);

  assert.deepEqual(Object.keys(SIDEVEIL_METHODS), specMethods);
  assert.equal(SIDEVEIL_METHODS.PLUGINS_GRANT_CAPABILITIES, 'plugins.grantCapabilities');
  assert.equal(SIDEVEIL_METHODS.PLUGINS_INSPECT_ARCHIVE, 'plugins.inspectArchive');
  assert.equal(SIDEVEIL_METHODS.PLUGINS_DAEMON_LOGS, 'plugins.daemonLogs');
  assert.equal(SIDEVEIL_METHODS.PLUGINS_RELOAD_WEBVIEW, 'plugins.reloadWebView');
  assert.deepEqual(Object.keys(SIDEVEIL_EVENTS), specEvents);
  assert.equal(SIDEVEIL_EVENTS.HOST_READY, 'host.ready');
  assert.equal(SIDEVEIL_EVENTS.HOST_LOCAL_AUDIT, 'host.localAudit');
  assert.deepEqual(Object.keys(SIDEVEIL_ERROR_CODES), specErrors);
  assert.equal(SIDEVEIL_PROTOCOL_VERSIONS.PROTOCOL_VERSION, 1);
  assert.equal(SIDEVEIL_PROTOCOL_VERSIONS.PLUGIN_API_VERSION, 1);
  assert.equal(SIDEVEIL_PROTOCOL_VERSIONS.MANIFEST_SCHEMA_VERSION, 1);
  assert.deepEqual(PLUGIN_ORIGIN_VALUES, ['builtin', 'bundled', 'installed', 'web-embed']);
  assert.ok(PLUGIN_CAPABILITY_VALUES.includes('network-internet'));

  assert.equal(SIDEVEIL_EVENTS.CONTAINER_PRESENTATION_CHANGED, 'container.presentationChanged');
  assert.equal(SIDEVEIL_EVENTS.CONTAINER_CLICK_THROUGH_CHANGED, 'container.clickThroughChanged');
  assert.equal(SIDEVEIL_EVENTS.PANEL_VISIBILITY_CHANGED, 'panel.visibilityChanged');
  assert.equal(SIDEVEIL_EVENTS.APPEARANCE_CHANGED, 'appearance.changed');
  assert.equal(SIDEVEIL_THEMES.COLOROS_GLASS, 'coloros-glass');
  assert.equal(SIDEVEIL_THEMES.MODERN_SAAS_DARK, 'modern-saas-dark');
  assert.equal(SIDEVEIL_HUD_DEFAULTS.DEFAULT_TEXT_COLOR_ARGB, 0xfff1f5f9);
  assert.equal(SIDEVEIL_TIMEOUT_BOUNDS.MAX_PLUGIN_INVOKE_MS, 60_000);
});

test('root config.json and DEFAULT_CONFIG match config.schema.json and generated bounds', () => {
  const configSchema = JSON.parse(
    fs.readFileSync(path.join(CONTRACTS_DIR, 'schemas/config.schema.json'), 'utf8'),
  );
  const rootConfig = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'config.json'), 'utf8'));

  const rootCheck = validateFixtureData(configSchema, 'SideVeilConfig', rootConfig);
  assert.equal(rootCheck.ok, true, `config.json schema errors: ${rootCheck.errors.join(', ')}`);

  const defaultCheck = validateFixtureData(configSchema, 'SideVeilConfig', DEFAULT_CONFIG);
  assert.equal(
    defaultCheck.ok,
    true,
    `DEFAULT_CONFIG schema errors: ${defaultCheck.errors.join(', ')}`,
  );

  assert.deepEqual(rootConfig, DEFAULT_CONFIG);
  assert.deepEqual(normalizeSideVeilConfig(rootConfig), DEFAULT_CONFIG);
  assert.equal(DEFAULT_CONFIG.edge.handleOpacity, SIDEVEIL_CONFIG_DEFAULTS.EDGE_HANDLE_OPACITY);
  assert.equal(
    normalizeSideVeilConfig({
      edge: { ...DEFAULT_CONFIG.edge, topPercent: 999, handleOpacity: Number.NaN },
      panel: { ...DEFAULT_CONFIG.panel, widthDp: 9999, scrimAlpha: Number.POSITIVE_INFINITY },
    }).edge.topPercent,
    SIDEVEIL_CONFIG_BOUNDS.EDGE_TOP_PERCENT_MAX,
  );
});

test('webui/src/panel/manifest.ts matches canonical sideveil.diagnostic contract metadata', () => {
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.id, SIDEVEIL_BUILTIN_DIAGNOSTIC.ID);
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.name, SIDEVEIL_BUILTIN_DIAGNOSTIC.NAME);
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.version, SIDEVEIL_BUILTIN_DIAGNOSTIC.VERSION);
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.entry, SIDEVEIL_BUILTIN_DIAGNOSTIC.ENTRY);
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.backend, SIDEVEIL_BUILTIN_DIAGNOSTIC.BACKEND);
  assert.equal(
    DIAGNOSTIC_PLUGIN_MANIFEST.surface?.backdrop,
    SIDEVEIL_BUILTIN_DIAGNOSTIC.SURFACE_BACKDROP,
  );
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.triggers?.global, SIDEVEIL_BUILTIN_DIAGNOSTIC.GLOBAL);
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.triggers?.priority, SIDEVEIL_BUILTIN_DIAGNOSTIC.PRIORITY);
  assert.deepEqual(DIAGNOSTIC_PLUGIN_MANIFEST.capabilities, [
    SIDEVEIL_BUILTIN_DIAGNOSTIC.CAPABILITY_STORAGE,
    SIDEVEIL_BUILTIN_DIAGNOSTIC.CAPABILITY_TOAST,
  ]);
});

test('all golden fixtures pass their generated JSON Schemas', () => {
  const suites = [
    { schema: 'schemas/config.schema.json', dir: 'fixtures/config' },
    { schema: 'schemas/protocol-status.schema.json', dir: 'fixtures/status' },
    { schema: 'schemas/protocol-events.schema.json', dir: 'fixtures/events' },
    { schema: 'schemas/protocol-theme-hud.schema.json', dir: 'fixtures/theme-hud' },
    { schema: 'schemas/plugin-manifest.schema.json', dir: 'fixtures/plugin' },
  ];

  let totalFixtures = 0;
  for (const { schema, dir } of suites) {
    const fixtureDir = path.join(CONTRACTS_DIR, dir);
    if (!fs.existsSync(fixtureDir)) continue;
    const rootSchema = JSON.parse(fs.readFileSync(path.join(CONTRACTS_DIR, schema), 'utf8'));
    const files = collectFixtureFiles([fixtureDir]);
    assert.ok(files.length > 0, `Expected fixtures under ${dir}`);
    for (const file of files) {
      totalFixtures++;
      const res = validateFixtureFile(rootSchema, file);
      assert.equal(
        res.ok,
        true,
        `Fixture ${path.relative(REPO_ROOT, file)} failed: ${res.errors.join('; ')}`,
      );
    }
  }
  assert.ok(totalFixtures >= 20, `Expected >= 20 golden fixtures, got ${totalFixtures}`);
});
