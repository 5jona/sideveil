import assert from 'node:assert/strict';
import test from 'node:test';
import {
  shouldPollControlInBackground,
  shouldRefreshOnVisibility,
  VISIBILITY_REFRESH_COOLDOWN_MS,
} from '../src/control/hooks/controlPollingPolicy.ts';
import { parseDaemonLogLines } from '../src/control/hooks/logParser.ts';
import { handleMockControlExec } from '../src/control/mockControlBridge.ts';
import { normalizeSideVeilConfig, SIDEVEIL_METHODS } from '../src/shared/types.ts';

test('SIDEVEIL_METHODS exposes debugging, log clearing, and plugin admin RPC names', () => {
  assert.equal(SIDEVEIL_METHODS.DEBUG_CDP_STATUS, 'debug.cdpStatus');
  assert.equal(SIDEVEIL_METHODS.DEBUG_RESET_FRAME_METRICS, 'debug.resetFrameMetrics');
  assert.equal(SIDEVEIL_METHODS.DAEMON_LOGS_CLEAR, 'daemon.logsClear');
  assert.equal(SIDEVEIL_METHODS.PLUGINS_GRANT_CAPABILITIES, 'plugins.grantCapabilities');
  assert.equal(SIDEVEIL_METHODS.PLUGINS_INSPECT_ARCHIVE, 'plugins.inspectArchive');
  assert.equal(SIDEVEIL_METHODS.PLUGINS_DAEMON_LOGS, 'plugins.daemonLogs');
  assert.equal(SIDEVEIL_METHODS.PLUGINS_RELOAD_WEBVIEW, 'plugins.reloadWebView');
});

test('normalizeSideVeilConfig preserves debug.webviewDebuggingEnabled cleanly without runtimeMode', () => {
  const debugCfg = normalizeSideVeilConfig({
    debug: { webviewDebuggingEnabled: true },
  });
  assert.equal(debugCfg.debug?.webviewDebuggingEnabled, true);
  assert.equal('runtimeMode' in (debugCfg.debug ?? {}), false);
});

test('parseDaemonLogLines detects error, warn, debug, and info log levels accurately', () => {
  const entries = parseDaemonLogLines([
    '09:00:00.100 [I] Daemon started',
    '09:00:01.200 [W] Slow frame detected',
    '09:00:02.300 [E] Socket disconnected',
    '09:00:03.400 [I] [WebView:demo][ERROR] Uncaught ReferenceError',
    '09:00:04.500 [I] [WebView:demo][WARNING] Deprecated API',
    '09:00:05.600 [I] [WebView:demo][WARN] Fallback active',
    '09:00:06.700 [DEBUG] Trace payload',
  ]);

  assert.deepEqual(
    entries.map((entry) => entry.level),
    ['info', 'warn', 'error', 'error', 'warn', 'warn', 'debug'],
  );
});

test('handleMockControlExec supports debug cdp status, reset-frames, inspect-ui, and logs --clear', async () => {
  const statusRes = await handleMockControlExec('sideveil-ctl status');
  assert.equal(statusRes.errno, 0);
  const status = JSON.parse(statusRes.stdout);
  assert.equal(status.protocolVersion, 1);
  assert.equal(status.pluginApiVersion, 1);
  assert.equal(typeof status.gestureStats?.edgeSwipesTriggered, 'number');
  assert.equal(typeof status.gestureStats?.pilferInvocations, 'number');

  const cdpStatusRes = await handleMockControlExec('sideveil-ctl debug cdp status');
  assert.equal(cdpStatusRes.errno, 0);
  const cdpStatus = JSON.parse(cdpStatusRes.stdout).result;
  assert.equal(cdpStatus.ok, true);
  assert.equal(typeof cdpStatus.effectiveEnabled, 'boolean');
  assert.equal(typeof cdpStatus.devServerActive, 'boolean');

  const inspectBefore = await handleMockControlExec('sideveil-ctl debug inspect-ui');
  assert.equal(inspectBefore.errno, 0);
  const snapBefore = JSON.parse(inspectBefore.stdout).result;
  assert.ok(Array.isArray(snapBefore.webViews) && snapBefore.webViews.length >= 2);
  assert.equal(typeof snapBefore.webViews[0].dormant, 'boolean');
  assert.equal(typeof snapBefore.webViews[0].crashCount, 'number');
  assert.ok(snapBefore.frameMetrics.sampleCount > 0);

  const resetRes = await handleMockControlExec('sideveil-ctl debug reset-frames');
  assert.equal(resetRes.errno, 0);
  assert.equal(JSON.parse(resetRes.stdout).result.ok, true);

  const inspectAfter = await handleMockControlExec('sideveil-ctl debug inspect-ui');
  assert.equal(inspectAfter.errno, 0);
  const snapAfter = JSON.parse(inspectAfter.stdout).result;
  assert.equal(snapAfter.frameMetrics.sampleCount, 0);
  assert.equal(snapAfter.frameMetrics.jankCount, 0);

  const clearRes = await handleMockControlExec('sideveil-ctl logs --clear');
  assert.equal(clearRes.errno, 0);
  assert.equal(JSON.parse(clearRes.stdout).result.ok, true);

  const logsAfter = await handleMockControlExec('sideveil-ctl logs');
  assert.equal(logsAfter.errno, 0);
  assert.deepEqual(JSON.parse(logsAfter.stdout).result.lines, []);
});

test('controlPollingPolicy disables background interval under ksu and enforces 5s visibility cooldown', () => {
  assert.equal(shouldPollControlInBackground('ksu'), false);
  assert.equal(shouldPollControlInBackground('host'), true);
  assert.equal(shouldPollControlInBackground('mock'), true);

  assert.equal(VISIBILITY_REFRESH_COOLDOWN_MS, 5000);
  assert.equal(shouldRefreshOnVisibility(10_000, 14_999), false);
  assert.equal(shouldRefreshOnVisibility(10_000, 15_000), true);
  assert.equal(shouldRefreshOnVisibility(0, 5_000), true);
});
