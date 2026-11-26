import assert from 'node:assert/strict';
import test from 'node:test';
import { BUILTIN_DIAG_PLUGIN_ID } from '@gojona/sideveil-sdk';
import {
  clampEmbedNumber,
  hasRpcErrorPayload,
  normalizeOriginList,
  normalizeSsidList,
} from '../src/control/hooks/pluginCommandHelpers.ts';
import {
  buildCreateWebEmbedCommand,
  buildCreateWebEmbedParams,
  buildPluginScopeCommand,
  buildSetEmbedConfigCommand,
  buildSetEmbedConfigParams,
  buildUninstallPluginCommand,
} from '../src/control/hooks/usePluginsControl.ts';
import { handleMockControlExec } from '../src/control/mockControlBridge.ts';
import { INITIAL_MOCK_PLUGINS } from '../src/control/mockPluginFixtures.ts';
import {
  CAPABILITY_CATALOG,
  groupPluginsByCategory,
  needsPermissionAttention,
} from '../src/control/pluginMeta.ts';
import { DIAGNOSTIC_PLUGIN_MANIFEST } from '../src/panel/manifest.ts';
import { SIDEVEIL_BUILTIN_DIAGNOSTIC } from '../src/shared/types.ts';

test('DIAGNOSTIC_PLUGIN_MANIFEST defines valid builtin plugin', () => {
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.id, BUILTIN_DIAG_PLUGIN_ID);
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.name, '内置诊断');
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.version, SIDEVEIL_BUILTIN_DIAGNOSTIC.VERSION);
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.triggers?.global, false);
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.backend, 'none');
  assert.deepEqual(DIAGNOSTIC_PLUGIN_MANIFEST.capabilities, ['storage', 'toast']);
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.surface?.backdrop, 'native-glass');
});

test('handleMockControlExec supports plugins list, reload, enable, disable, priority, and select', async () => {
  // list
  const listRes = await handleMockControlExec('sideveil-ctl plugins list');
  assert.equal(listRes.errno, 0);
  const snapshot = JSON.parse(listRes.stdout);
  assert.ok(Array.isArray(snapshot.plugins));
  assert.ok(snapshot.plugins.some((p) => p.id === BUILTIN_DIAG_PLUGIN_ID));

  // reload
  const reloadRes = await handleMockControlExec('sideveil-ctl plugins reload');
  assert.equal(reloadRes.errno, 0);
  const reloadData = JSON.parse(reloadRes.stdout);
  assert.equal(reloadData.ok, true);
  assert.ok(reloadData.count >= 1);

  // toggle enable
  const disableRes = await handleMockControlExec('sideveil-ctl plugins disable com.gamer.hud');
  assert.equal(disableRes.errno, 0);
  const disabledSnap = JSON.parse(disableRes.stdout);
  const gamerPlugin = disabledSnap.plugins.find((p) => p.id === 'com.gamer.hud');
  assert.equal(gamerPlugin?.enabled, false);

  const enableRes = await handleMockControlExec('sideveil-ctl plugins enable com.gamer.hud');
  assert.equal(enableRes.errno, 0);
  const enabledSnap = JSON.parse(enableRes.stdout);
  assert.equal(enabledSnap.plugins.find((p) => p.id === 'com.gamer.hud')?.enabled, true);

  // priority
  const prioRes = await handleMockControlExec('sideveil-ctl plugins priority com.gamer.hud 45');
  assert.equal(prioRes.errno, 0);
  const prioSnap = JSON.parse(prioRes.stdout);
  assert.equal(prioSnap.plugins.find((p) => p.id === 'com.gamer.hud')?.priority, 45);

  // select
  const selectRes = await handleMockControlExec('sideveil-ctl plugins select com.gamer.hud');
  assert.equal(selectRes.errno, 0);
  const selectSnap = JSON.parse(selectRes.stdout);
  assert.equal(selectSnap.activePluginId, 'com.gamer.hud');

  // scope override: set custom packages + global=false
  const setScopeCmd = buildPluginScopeCommand('com.gamer.hud', {
    global: false,
    packages: ['me.weishu.kernelsu', 'com.example.game'],
  });
  assert.match(setScopeCmd, /plugins scope 'com\.gamer\.hud' --global false --packages/);
  const scopeRes = await handleMockControlExec(setScopeCmd);
  assert.equal(scopeRes.errno, 0);
  const scopeSnap = JSON.parse(scopeRes.stdout);
  const scopedPlugin = scopeSnap.plugins.find((p) => p.id === 'com.gamer.hud');
  assert.equal(scopedPlugin?.hasScopeOverride, true);
  assert.equal(scopedPlugin?.global, false);
  assert.deepEqual(scopedPlugin?.packages, ['me.weishu.kernelsu', 'com.example.game']);
  assert.ok(scopeSnap.matchedPluginIds.includes('com.gamer.hud'));

  // scope override: clear resets to manifest defaults
  const clearScopeCmd = buildPluginScopeCommand('com.gamer.hud', { clear: true });
  assert.match(clearScopeCmd, /plugins scope 'com\.gamer\.hud' --clear$/);
  const clearRes = await handleMockControlExec(clearScopeCmd);
  assert.equal(clearRes.errno, 0);
  const clearSnap = JSON.parse(clearRes.stdout);
  const restoredPlugin = clearSnap.plugins.find((p) => p.id === 'com.gamer.hud');
  assert.equal(restoredPlugin?.hasScopeOverride, false);
  assert.deepEqual(restoredPlugin?.packages, [
    'com.miHoYo.GenshinImpact',
    'com.tencent.tmgp.sgame',
  ]);
});

test('handleMockControlExec supports web-embed create, edit, uninstall, and network context', async () => {
  // network context
  const netRes = await handleMockControlExec('sideveil-ctl network');
  assert.equal(netRes.errno, 0);
  const netSnap = JSON.parse(netRes.stdout);
  assert.equal(netSnap.transport, 'wifi');
  assert.equal(typeof netSnap.vpnActive, 'boolean');

  // create custom web-embed with enhanced Phase 1 fields
  const createInput = {
    id: 'lan.dashboard',
    name: 'Custom Dashboard',
    url: 'http://192.168.1.10:8080/ui',
    remoteUrl: 'https://dashboard.example.com:8443/ui',
    lanSsids: ['HomeRouter-5G', ' Office-5G ', 'HomeRouter-5G'],
    allowCleartext: true,
    keepAlive: true,
    wideViewport: true,
    allowZoom: false,
    webHistoryBack: true,
    textZoom: 95,
    showToolbar: true,
    keepAliveGraceSec: 180,
    widthDp: 400,
    heightPercent: 82,
    global: true,
    priority: 12,
  };
  const createParams = buildCreateWebEmbedParams(createInput);
  assert.equal(createParams.remoteUrl, 'https://dashboard.example.com:8443/ui');
  assert.deepEqual(createParams.lanSsids, ['HomeRouter-5G', 'Office-5G']);
  assert.equal(createParams.wideViewport, true);
  assert.equal(createParams.allowZoom, false);
  assert.equal(createParams.webHistoryBack, true);
  assert.equal(createParams.textZoom, 95);
  assert.equal(createParams.showToolbar, true);
  assert.equal(createParams.keepAliveGraceSec, 180);
  assert.equal(createParams.widthDp, 400);
  assert.equal(createParams.heightPercent, 82);

  const createCmd = buildCreateWebEmbedCommand(createInput);
  const createRes = await handleMockControlExec(createCmd);
  assert.equal(createRes.errno, 0);
  const createdEnvelope = JSON.parse(createRes.stdout);
  const createdPlugin = createdEnvelope.result.snapshot.plugins.find(
    (p) => p.id === 'lan.dashboard',
  );
  assert.equal(createdPlugin?.embed?.url, 'http://192.168.1.10:8080/ui');
  assert.equal(createdPlugin?.embed?.remoteUrl, 'https://dashboard.example.com:8443/ui');
  assert.deepEqual(createdPlugin?.embed?.lanSsids, ['HomeRouter-5G', 'Office-5G']);
  assert.deepEqual(createdPlugin?.embed?.allowedOrigins, [
    'http://192.168.1.10:8080',
    'https://dashboard.example.com:8443',
  ]);
  assert.equal(createdPlugin?.embed?.allowCleartext, true);
  assert.equal(createdPlugin?.embed?.wideViewport, true);
  assert.equal(createdPlugin?.embed?.allowZoom, false);
  assert.equal(createdPlugin?.embed?.webHistoryBack, true);
  assert.equal(createdPlugin?.embed?.textZoom, 95);
  assert.equal(createdPlugin?.embed?.showToolbar, true);
  assert.equal(createdPlugin?.embed?.keepAliveGraceSec, 180);
  assert.equal(createdPlugin?.surface?.keepAlive, true);
  assert.equal(createdPlugin?.surface?.widthDp, 400);
  assert.equal(createdPlugin?.surface?.heightPercent, 82);

  // edit embed config including new Phase 1 fields
  const editInput = {
    embedUrl: 'http://192.168.1.20:8080/ui',
    remoteUrl: 'https://ha.example.com:8123',
    lanSsids: ['Office-5G'],
    allowedOrigins: ['http://192.168.1.20:8080', 'https://ha.example.com:8123'],
    allowCleartext: false,
    keepAlive: false,
    wideViewport: false,
    allowZoom: true,
    webHistoryBack: false,
    textZoom: 110,
    showToolbar: false,
    keepAliveGraceSec: 60,
    widthDp: 440,
    heightPercent: 85,
  };
  const editParams = buildSetEmbedConfigParams('lan.dashboard', editInput);
  assert.equal(editParams.pluginId, 'lan.dashboard');
  assert.equal(editParams.remoteUrl, 'https://ha.example.com:8123');
  assert.deepEqual(editParams.lanSsids, ['Office-5G']);
  assert.equal(editParams.wideViewport, false);
  assert.equal(editParams.allowZoom, true);
  assert.equal(editParams.webHistoryBack, false);
  assert.equal(editParams.textZoom, 110);
  assert.equal(editParams.showToolbar, false);
  assert.equal(editParams.keepAliveGraceSec, 60);
  assert.equal(editParams.widthDp, 440);
  assert.equal(editParams.heightPercent, 85);

  const editCmd = buildSetEmbedConfigCommand('lan.dashboard', editInput);
  const editRes = await handleMockControlExec(editCmd);
  assert.equal(editRes.errno, 0);
  const editedEnvelope = JSON.parse(editRes.stdout);
  const editedPlugin = editedEnvelope.result.plugins.find((p) => p.id === 'lan.dashboard');
  assert.equal(editedPlugin?.embed?.url, 'http://192.168.1.20:8080/ui');
  assert.equal(editedPlugin?.embed?.remoteUrl, 'https://ha.example.com:8123');
  assert.deepEqual(editedPlugin?.embed?.lanSsids, ['Office-5G']);
  assert.equal(editedPlugin?.embed?.allowCleartext, false);
  assert.equal(editedPlugin?.embed?.wideViewport, false);
  assert.equal(editedPlugin?.embed?.allowZoom, true);
  assert.equal(editedPlugin?.embed?.webHistoryBack, false);
  assert.equal(editedPlugin?.embed?.textZoom, 110);
  assert.equal(editedPlugin?.embed?.showToolbar, false);
  assert.equal(editedPlugin?.embed?.keepAliveGraceSec, 60);
  assert.equal(editedPlugin?.surface?.keepAlive, false);
  assert.equal(editedPlugin?.surface?.widthDp, 440);
  assert.equal(editedPlugin?.surface?.heightPercent, 85);

  // normalizeSsidList, normalizeOriginList, clampEmbedNumber
  assert.deepEqual(normalizeSsidList('HomeRouter-5G, Office-5G\nHomeRouter-5G'), [
    'HomeRouter-5G',
    'Office-5G',
  ]);
  assert.deepEqual(normalizeOriginList('http://127.0.0.1:8123, https://ha.example.com:8123'), [
    'http://127.0.0.1:8123',
    'https://ha.example.com:8123',
  ]);
  assert.equal(clampEmbedNumber(180, 240, 720, 380), 240);
  assert.equal(clampEmbedNumber(900, 240, 720, 380), 720);
  assert.equal(clampEmbedNumber(Number.NaN, 240, 720, 380), 380);

  // uninstall
  const uninstallCmd = buildUninstallPluginCommand('lan.dashboard');
  const uninstallRes = await handleMockControlExec(uninstallCmd);
  assert.equal(uninstallRes.errno, 0);
  const afterUninstall = JSON.parse(uninstallRes.stdout);
  assert.equal(
    afterUninstall.result.snapshot.plugins.some((p) => p.id === 'lan.dashboard'),
    false,
  );
});

test('hasRpcErrorPayload inspects top-level JSON error without substring false positives', () => {
  assert.equal(
    hasRpcErrorPayload(JSON.stringify({ jsonrpc: '2.0', id: '1', error: { message: 'denied' } })),
    true,
  );
  assert.equal(
    hasRpcErrorPayload(
      JSON.stringify({
        jsonrpc: '2.0',
        id: '1',
        result: { ok: true, note: 'contains literal "error" text inside result' },
      }),
    ),
    false,
  );
  assert.equal(hasRpcErrorPayload(''), false);
  assert.equal(hasRpcErrorPayload('not-json'), false);
});

test('groupPluginsByCategory separates web-embed, installed, builtin, and bundled cleanly', () => {
  const grouped = groupPluginsByCategory(INITIAL_MOCK_PLUGINS);
  assert.deepEqual(
    grouped.webEmbeds.map((p) => p.id),
    ['lan.homeassistant'],
  );
  assert.deepEqual(
    grouped.installed.map((p) => p.id),
    ['com.gamer.hud', 'org.music.remote'],
  );
  assert.deepEqual(
    grouped.systemBuiltins.map((p) => p.id),
    ['sideveil.diagnostic'],
  );
  assert.deepEqual(
    grouped.bundledExamples.map((p) => p.id),
    ['example.vello-scope', 'example.native-perf-hud'],
  );

  const gamer = grouped.installed.find((p) => p.id === 'com.gamer.hud');
  assert.equal(needsPermissionAttention(gamer), true);
  assert.equal(CAPABILITY_CATALOG['system-exec'].tier, 'high');
  assert.equal(CAPABILITY_CATALOG['network-internet'].tier, 'medium');
  assert.equal(CAPABILITY_CATALOG.toast.tier, 'low');
});
