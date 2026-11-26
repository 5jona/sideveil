import assert from 'node:assert/strict';
import test from 'node:test';
import {
  assertAdminRpcAllowed,
  buildDaemonLogsCommand,
  buildGrantCapabilitiesCommand,
  buildInspectArchiveCommand,
  buildInstallPluginCommand,
  buildReloadWebViewCommand,
  buildUninstallPluginCommand,
  HOST_ADMIN_BLOCKED_MESSAGE,
  SVP_UPLOAD_CHUNK_CHARS,
  withStagedSvpArchiveForKsu,
} from '../src/control/hooks/usePluginsControl.ts';
import { handleMockControlExec } from '../src/control/mockControlBridge.ts';
import { INITIAL_MOCK_PLUGINS } from '../src/control/mockPluginFixtures.ts';
import {
  canUninstallPlugin,
  deriveInitialImportGrants,
  hasRevocableGrants,
} from '../src/control/pluginMeta.ts';
import { SIDEVEIL_METHODS } from '../src/shared/types.ts';

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
