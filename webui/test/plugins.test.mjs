import assert from 'node:assert/strict';
import test from 'node:test';
import { BUILTIN_DIAG_PLUGIN_ID } from '@pochita/sideveil-sdk';
import {
  buildCreateWebEmbedCommand,
  buildPluginScopeCommand,
  buildSetEmbedConfigCommand,
  buildUninstallPluginCommand,
} from '../src/control/hooks/usePluginsControl.ts';
import { handleMockControlExec } from '../src/control/mockControlBridge.ts';
import { DIAGNOSTIC_PLUGIN_MANIFEST } from '../src/panel/manifest.ts';

test('DIAGNOSTIC_PLUGIN_MANIFEST defines valid builtin plugin', () => {
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.id, BUILTIN_DIAG_PLUGIN_ID);
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.name, '系统诊断');
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.backend, 'none');
  assert.deepEqual(DIAGNOSTIC_PLUGIN_MANIFEST.capabilities, ['storage', 'toast', 'system-exec']);
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

  // create web-embed
  const createCmd = buildCreateWebEmbedCommand({
    id: 'lan.openclash',
    name: 'OpenClash',
    url: 'http://192.168.1.1:9090/ui',
    allowCleartext: true,
    keepAlive: true,
    global: true,
    priority: 12,
  });
  const createRes = await handleMockControlExec(createCmd);
  assert.equal(createRes.errno, 0);
  const createdEnvelope = JSON.parse(createRes.stdout);
  const createdPlugin = createdEnvelope.result.snapshot.plugins.find(
    (p) => p.id === 'lan.openclash',
  );
  assert.equal(createdPlugin?.embed?.url, 'http://192.168.1.1:9090/ui');
  assert.equal(createdPlugin?.embed?.allowCleartext, true);
  assert.equal(createdPlugin?.surface?.keepAlive, true);

  // edit embed config
  const editCmd = buildSetEmbedConfigCommand('lan.openclash', {
    embedUrl: 'http://192.168.1.2:9090/ui',
    allowedOrigins: ['http://192.168.1.2:9090'],
    allowCleartext: false,
    keepAlive: false,
  });
  const editRes = await handleMockControlExec(editCmd);
  assert.equal(editRes.errno, 0);
  const editedEnvelope = JSON.parse(editRes.stdout);
  const editedPlugin = editedEnvelope.result.plugins.find((p) => p.id === 'lan.openclash');
  assert.equal(editedPlugin?.embed?.url, 'http://192.168.1.2:9090/ui');
  assert.equal(editedPlugin?.embed?.allowCleartext, false);
  assert.equal(editedPlugin?.surface?.keepAlive, false);

  // uninstall
  const uninstallCmd = buildUninstallPluginCommand('lan.openclash');
  const uninstallRes = await handleMockControlExec(uninstallCmd);
  assert.equal(uninstallRes.errno, 0);
  const afterUninstall = JSON.parse(uninstallRes.stdout);
  assert.equal(
    afterUninstall.result.snapshot.plugins.some((p) => p.id === 'lan.openclash'),
    false,
  );
});
