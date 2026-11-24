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
  assertAdminRpcAllowed,
  buildCreateWebEmbedCommand,
  buildCreateWebEmbedParams,
  buildDaemonLogsCommand,
  buildGrantCapabilitiesCommand,
  buildInspectArchiveCommand,
  buildInstallPluginCommand,
  buildPluginScopeCommand,
  buildReloadWebViewCommand,
  buildSetEmbedConfigCommand,
  buildSetEmbedConfigParams,
  buildUninstallPluginCommand,
  HOST_ADMIN_BLOCKED_MESSAGE,
  SVP_UPLOAD_CHUNK_CHARS,
  withStagedSvpArchiveForKsu,
} from '../src/control/hooks/usePluginsControl.ts';
import { handleMockControlExec } from '../src/control/mockControlBridge.ts';
import { INITIAL_MOCK_PLUGINS } from '../src/control/mockPluginFixtures.ts';
import {
  CAPABILITY_CATALOG,
  canUninstallPlugin,
  deriveInitialImportGrants,
  groupPluginsByCategory,
  hasRevocableGrants,
  needsPermissionAttention,
} from '../src/control/pluginMeta.ts';
import { DIAGNOSTIC_PLUGIN_MANIFEST } from '../src/panel/manifest.ts';
import { SIDEVEIL_METHODS } from '../src/shared/types.ts';

test('DIAGNOSTIC_PLUGIN_MANIFEST defines valid builtin plugin', () => {
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.id, BUILTIN_DIAG_PLUGIN_ID);
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.name, '内置诊断');
  assert.equal(DIAGNOSTIC_PLUGIN_MANIFEST.version, '0.1.0');
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

  // create web-embed with enhanced Phase 1 fields
  const createInput = {
    id: 'lan.openclash',
    name: 'OpenClash',
    url: 'http://192.168.1.1:9090/ui',
    remoteUrl: 'https://clash.example.com:9443/ui',
    lanSsids: ['Home-5G', ' Home-IoT ', 'Home-5G'],
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
  assert.equal(createParams.remoteUrl, 'https://clash.example.com:9443/ui');
  assert.deepEqual(createParams.lanSsids, ['Home-5G', 'Home-IoT']);
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
    (p) => p.id === 'lan.openclash',
  );
  assert.equal(createdPlugin?.embed?.url, 'http://192.168.1.1:9090/ui');
  assert.equal(createdPlugin?.embed?.remoteUrl, 'https://clash.example.com:9443/ui');
  assert.deepEqual(createdPlugin?.embed?.lanSsids, ['Home-5G', 'Home-IoT']);
  assert.deepEqual(createdPlugin?.embed?.allowedOrigins, [
    'http://192.168.1.1:9090',
    'https://clash.example.com:9443',
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
    embedUrl: 'http://192.168.1.2:9090/ui',
    remoteUrl: 'https://ha.example.com:8123',
    lanSsids: ['Office-WiFi'],
    allowedOrigins: ['http://192.168.1.2:9090', 'https://ha.example.com:8123'],
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
  const editParams = buildSetEmbedConfigParams('lan.openclash', editInput);
  assert.equal(editParams.pluginId, 'lan.openclash');
  assert.equal(editParams.remoteUrl, 'https://ha.example.com:8123');
  assert.deepEqual(editParams.lanSsids, ['Office-WiFi']);
  assert.equal(editParams.wideViewport, false);
  assert.equal(editParams.allowZoom, true);
  assert.equal(editParams.webHistoryBack, false);
  assert.equal(editParams.textZoom, 110);
  assert.equal(editParams.showToolbar, false);
  assert.equal(editParams.keepAliveGraceSec, 60);
  assert.equal(editParams.widthDp, 440);
  assert.equal(editParams.heightPercent, 85);

  const editCmd = buildSetEmbedConfigCommand('lan.openclash', editInput);
  const editRes = await handleMockControlExec(editCmd);
  assert.equal(editRes.errno, 0);
  const editedEnvelope = JSON.parse(editRes.stdout);
  const editedPlugin = editedEnvelope.result.plugins.find((p) => p.id === 'lan.openclash');
  assert.equal(editedPlugin?.embed?.url, 'http://192.168.1.2:9090/ui');
  assert.equal(editedPlugin?.embed?.remoteUrl, 'https://ha.example.com:8123');
  assert.deepEqual(editedPlugin?.embed?.lanSsids, ['Office-WiFi']);
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
  assert.deepEqual(normalizeSsidList('Home-5G, Home-IoT\nHome-5G'), ['Home-5G', 'Home-IoT']);
  assert.deepEqual(normalizeOriginList('http://127.0.0.1:8123, https://ha.example.com:8123'), [
    'http://127.0.0.1:8123',
    'https://ha.example.com:8123',
  ]);
  assert.equal(clampEmbedNumber(180, 240, 720, 380), 240);
  assert.equal(clampEmbedNumber(900, 240, 720, 380), 720);
  assert.equal(clampEmbedNumber(Number.NaN, 240, 720, 380), 380);

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

test('handleMockControlExec supports grantCapabilities, inspectArchive, install, daemonLogs, and reloadWebView', async () => {
  // Grant pending capabilities + approve daemon for com.gamer.hud
  const grantCmd = buildGrantCapabilitiesCommand({
    pluginId: 'com.gamer.hud',
    grantAllPending: true,
    approveDaemon: true,
  });
  const grantRes = await handleMockControlExec(grantCmd);
  assert.equal(grantRes.errno, 0);
  const grantedSnap = JSON.parse(grantRes.stdout).result;
  const gamer = grantedSnap.plugins.find((p) => p.id === 'com.gamer.hud');
  assert.equal(gamer.approvalStatus, 'approved');
  assert.equal(gamer.daemonApproved, true);
  assert.equal(gamer.daemonStatus, 'running');
  assert.ok(gamer.grantedCapabilities.includes('system-exec'));

  // Inspect + install .svp archive (fresh install + upgrade diff)
  const inspectRes = await handleMockControlExec(
    buildInspectArchiveCommand({ path: '/sdcard/Download/weather-widget.svp' }),
  );
  assert.equal(inspectRes.errno, 0);
  const inspection = JSON.parse(inspectRes.stdout).result;
  assert.equal(inspection.manifest.id, 'community.weather-widget');
  assert.equal(inspection.isUpgrade, false);
  assert.equal(inspection.daemonAlreadyApproved, false);
  assert.ok(Array.isArray(inspection.capabilities) && inspection.capabilities.length === 3);
  assert.deepEqual(deriveInitialImportGrants(inspection), {
    grantedCapabilities: ['toast'],
    approveDaemon: false,
  });

  const installRes = await handleMockControlExec(
    buildInstallPluginCommand({
      sourcePath: '/sdcard/Download/weather-widget.svp',
      grantedCapabilities: ['storage', 'toast'],
    }),
  );
  assert.equal(installRes.errno, 0);
  const installed = JSON.parse(installRes.stdout).result;
  assert.equal(installed.ok, true);
  assert.ok(installed.snapshot.plugins.some((p) => p.id === 'community.weather-widget'));

  // Re-inspect same plugin as upgrade: pre-checks alreadyGranted + flags isNew + warnings
  const upgradeInspectRes = await handleMockControlExec(
    buildInspectArchiveCommand({ path: '/sdcard/Download/weather-widget.svp' }),
  );
  assert.equal(upgradeInspectRes.errno, 0);
  const upgradeInspection = JSON.parse(upgradeInspectRes.stdout).result;
  assert.equal(upgradeInspection.isUpgrade, true);
  assert.equal(upgradeInspection.existingVersion, '1.0.0');
  const storageItem = upgradeInspection.capabilities.find((c) => c.capability === 'storage');
  const lanItem = upgradeInspection.capabilities.find((c) => c.capability === 'network-lan');
  assert.equal(storageItem?.alreadyGranted, true);
  assert.equal(storageItem?.isNew, false);
  assert.equal(lanItem?.isNew, true);
  assert.equal(lanItem?.alreadyGranted, false);
  assert.ok(upgradeInspection.warnings.length >= 1);
  assert.deepEqual(deriveInitialImportGrants(upgradeInspection), {
    grantedCapabilities: ['storage', 'toast'],
    approveDaemon: false,
  });

  // Daemon revoke + revokeAll clears medium/high capabilities and daemon approval
  const revokeDaemonRes = await handleMockControlExec(
    buildGrantCapabilitiesCommand({ pluginId: 'com.gamer.hud', approveDaemon: false }),
  );
  assert.equal(revokeDaemonRes.errno, 0);
  const afterDaemonRevoke = JSON.parse(revokeDaemonRes.stdout).result.plugins.find(
    (p) => p.id === 'com.gamer.hud',
  );
  assert.equal(afterDaemonRevoke.daemonApproved, false);
  assert.equal(afterDaemonRevoke.daemonStatus, 'awaiting-approval');

  await handleMockControlExec(
    buildGrantCapabilitiesCommand({ pluginId: 'com.gamer.hud', approveDaemon: true }),
  );
  const revokeAllRes = await handleMockControlExec(
    buildGrantCapabilitiesCommand({ pluginId: 'com.gamer.hud', revokeAll: true }),
  );
  const afterRevokeAll = JSON.parse(revokeAllRes.stdout).result.plugins.find(
    (p) => p.id === 'com.gamer.hud',
  );
  assert.equal(afterRevokeAll.daemonApproved, false);
  assert.equal(afterRevokeAll.grantedCapabilities.includes('system-exec'), false);
  assert.equal(afterRevokeAll.grantedCapabilities.includes('storage'), false);

  // Daemon logs + WebView reload
  const logsRes = await handleMockControlExec(buildDaemonLogsCommand('com.gamer.hud', 20));
  assert.equal(logsRes.errno, 0);
  const daemonLogs = JSON.parse(logsRes.stdout).result;
  assert.equal(daemonLogs.pluginId, 'com.gamer.hud');
  assert.ok(Array.isArray(daemonLogs.lines) && daemonLogs.lines.length >= 1);

  const reloadWvRes = await handleMockControlExec(buildReloadWebViewCommand('com.gamer.hud'));
  assert.equal(reloadWvRes.errno, 0);
  assert.equal(JSON.parse(reloadWvRes.stdout).result.ok, true);
});

test('withStagedSvpArchiveForKsu chunks >100 KiB base64 uploads, decodes, and always cleans up', async () => {
  const cmds = [];
  const fakeExec = async (cmd) => {
    cmds.push(cmd);
    return { errno: 0, stdout: '{"ok":true}', stderr: '' };
  };
  const largeBase64 = 'QUJD'.repeat(26_250); // 105,000 chars (> 96 KiB)

  const out = await withStagedSvpArchiveForKsu(
    fakeExec,
    largeBase64,
    async (svpPath) => {
      assert.equal(svpPath, '/data/local/tmp/sideveil_upload_testnonce.svp');
      await fakeExec(buildInspectArchiveCommand({ path: svpPath }));
      return svpPath;
    },
    { nonce: 'testnonce' },
  );

  assert.equal(out, '/data/local/tmp/sideveil_upload_testnonce.svp');
  const writeCmds = cmds.filter((c) => c.startsWith("printf '%s'"));
  assert.equal(writeCmds.length, Math.ceil(largeBase64.length / SVP_UPLOAD_CHUNK_CHARS));
  for (const cmd of cmds) {
    assert.ok(cmd.length < 45_000, `shell command exceeded 45 KiB bound: ${cmd.length}`);
  }
  assert.ok(
    cmds.some((c) =>
      c.includes(
        "base64 -d '/data/local/tmp/sideveil_upload_testnonce.b64' > '/data/local/tmp/sideveil_upload_testnonce.svp' && rm -f '/data/local/tmp/sideveil_upload_testnonce.b64'",
      ),
    ),
  );
  assert.ok(
    cmds
      .at(-1)
      ?.includes(
        "rm -f '/data/local/tmp/sideveil_upload_testnonce.b64' '/data/local/tmp/sideveil_upload_testnonce.svp'",
      ),
  );

  // Verify cleanup also runs when the inner RPC throws
  const errorCmds = [];
  await assert.rejects(
    withStagedSvpArchiveForKsu(
      async (cmd) => {
        errorCmds.push(cmd);
        return { errno: 0, stdout: '', stderr: '' };
      },
      'QUJD',
      async () => {
        throw new Error('simulated rpc failure');
      },
      { nonce: 'errnonce' },
    ),
    /simulated rpc failure/,
  );
  assert.ok(
    errorCmds
      .at(-1)
      ?.includes(
        "rm -f '/data/local/tmp/sideveil_upload_errnonce.b64' '/data/local/tmp/sideveil_upload_errnonce.svp'",
      ),
  );
});

test('assertAdminRpcAllowed blocks Ctl-only admin RPCs in host env and bundled/revoke rules hold', async () => {
  const blockedMethods = [
    SIDEVEIL_METHODS.PLUGINS_INSPECT_ARCHIVE,
    SIDEVEIL_METHODS.PLUGINS_INSTALL,
    SIDEVEIL_METHODS.PLUGINS_GRANT_CAPABILITIES,
    SIDEVEIL_METHODS.PLUGINS_DAEMON_LOGS,
    SIDEVEIL_METHODS.PLUGINS_RELOAD_WEBVIEW,
  ];
  for (const method of blockedMethods) {
    assert.throws(
      () => assertAdminRpcAllowed('host', method),
      new RegExp(HOST_ADMIN_BLOCKED_MESSAGE),
    );
    assert.doesNotThrow(() => assertAdminRpcAllowed('ksu', method));
    assert.doesNotThrow(() => assertAdminRpcAllowed('mock', method));
  }

  const builtin = INITIAL_MOCK_PLUGINS.find((p) => p.origin === 'builtin');
  const bundled = INITIAL_MOCK_PLUGINS.find((p) => p.origin === 'bundled');
  const installed = INITIAL_MOCK_PLUGINS.find((p) => p.origin === 'installed');
  assert.equal(canUninstallPlugin(builtin), false);
  assert.equal(canUninstallPlugin(bundled), false);
  assert.equal(canUninstallPlugin(installed), true);

  const bundledUninstallRes = await handleMockControlExec(buildUninstallPluginCommand(bundled.id));
  assert.notEqual(bundledUninstallRes.errno, 0);

  assert.equal(
    hasRevocableGrants({
      grantedCapabilities: ['storage'],
      hasDaemon: false,
      daemonApproved: false,
    }),
    true,
  );
  assert.equal(
    hasRevocableGrants({ grantedCapabilities: ['toast'], hasDaemon: true, daemonApproved: true }),
    true,
  );
  assert.equal(
    hasRevocableGrants({ grantedCapabilities: ['toast'], hasDaemon: false, daemonApproved: false }),
    false,
  );
});
