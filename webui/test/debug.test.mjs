import assert from 'node:assert/strict';
import test from 'node:test';
import { parseDaemonLogLines } from '../src/control/hooks/logParser.ts';
import { handleMockControlExec } from '../src/control/mockControlBridge.ts';
import {
  isBuiltinTestPlugin,
  normalizeSideVeilConfig,
  RUNTIME_MODE_VALUES,
  resolveRuntimeMode,
  SIDEVEIL_BUILTIN_TEST_PLUGINS,
  SIDEVEIL_METHODS,
} from '../src/shared/types.ts';

test('SIDEVEIL_METHODS exposes new debugging and log clearing RPC names', () => {
  assert.equal(SIDEVEIL_METHODS.DEBUG_CDP_STATUS, 'debug.cdpStatus');
  assert.equal(SIDEVEIL_METHODS.DEBUG_RESET_FRAME_METRICS, 'debug.resetFrameMetrics');
  assert.equal(SIDEVEIL_METHODS.DEBUG_SET_RUNTIME_MODE, 'debug.setRuntimeMode');
  assert.equal(SIDEVEIL_METHODS.DAEMON_LOGS_CLEAR, 'daemon.logsClear');
});

test('isBuiltinTestPlugin and RuntimeMode normalization handle canonical values safely', () => {
  assert.deepEqual(RUNTIME_MODE_VALUES, ['release', 'debug']);
  assert.equal(isBuiltinTestPlugin(SIDEVEIL_BUILTIN_TEST_PLUGINS.DIAGNOSTIC), true);
  assert.equal(isBuiltinTestPlugin(SIDEVEIL_BUILTIN_TEST_PLUGINS.VELLO_SCOPE), true);
  assert.equal(isBuiltinTestPlugin(SIDEVEIL_BUILTIN_TEST_PLUGINS.NATIVE_PERF_HUD), true);
  assert.equal(isBuiltinTestPlugin('sideveil.proxy'), false);
  assert.equal(isBuiltinTestPlugin('strata.manager'), false);
  assert.equal(isBuiltinTestPlugin('lan.homeassistant'), false);

  assert.equal(resolveRuntimeMode(undefined), 'release');
  assert.equal(resolveRuntimeMode({ webviewDebuggingEnabled: false }), 'release');
  assert.equal(
    resolveRuntimeMode({ webviewDebuggingEnabled: false, runtimeMode: 'debug' }),
    'debug',
  );

  const debugCfg = normalizeSideVeilConfig({
    debug: { webviewDebuggingEnabled: true, runtimeMode: 'debug' },
  });
  assert.equal(debugCfg.debug?.runtimeMode, 'debug');
  assert.equal(debugCfg.debug?.webviewDebuggingEnabled, true);

  const fallbackCfg = normalizeSideVeilConfig({
    debug: {
      webviewDebuggingEnabled: false,
      runtimeMode: /** @type {any} */ ('staging'),
    },
  });
  assert.equal(fallbackCfg.debug?.runtimeMode, 'release');
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
  // status includes gestureStats
  const statusRes = await handleMockControlExec('sideveil-ctl status');
  assert.equal(statusRes.errno, 0);
  const status = JSON.parse(statusRes.stdout);
  assert.equal(typeof status.gestureStats?.edgeSwipesTriggered, 'number');
  assert.equal(typeof status.gestureStats?.pilferInvocations, 'number');

  // debug cdp status
  const cdpStatusRes = await handleMockControlExec('sideveil-ctl debug cdp status');
  assert.equal(cdpStatusRes.errno, 0);
  const cdpStatus = JSON.parse(cdpStatusRes.stdout).result;
  assert.equal(cdpStatus.ok, true);
  assert.equal(typeof cdpStatus.effectiveEnabled, 'boolean');
  assert.equal(typeof cdpStatus.devServerActive, 'boolean');

  // debug inspect-ui includes dormant, crashCount, and gestureStats
  const inspectBefore = await handleMockControlExec('sideveil-ctl debug inspect-ui');
  assert.equal(inspectBefore.errno, 0);
  const snapBefore = JSON.parse(inspectBefore.stdout).result;
  assert.ok(Array.isArray(snapBefore.webViews) && snapBefore.webViews.length >= 2);
  assert.equal(typeof snapBefore.webViews[0].dormant, 'boolean');
  assert.equal(typeof snapBefore.webViews[0].crashCount, 'number');
  assert.equal(typeof snapBefore.gestureStats?.edgeSwipesTriggered, 'number');
  assert.ok(snapBefore.frameMetrics.sampleCount > 0);

  // debug reset-frames resets mock frame metrics
  const resetRes = await handleMockControlExec('sideveil-ctl debug reset-frames');
  assert.equal(resetRes.errno, 0);
  assert.equal(JSON.parse(resetRes.stdout).result.ok, true);

  const inspectAfter = await handleMockControlExec('sideveil-ctl debug inspect-ui');
  assert.equal(inspectAfter.errno, 0);
  const snapAfter = JSON.parse(inspectAfter.stdout).result;
  assert.equal(snapAfter.frameMetrics.sampleCount, 0);
  assert.equal(snapAfter.frameMetrics.jankCount, 0);

  // logs --clear empties mock logs
  const clearRes = await handleMockControlExec('sideveil-ctl logs --clear');
  assert.equal(clearRes.errno, 0);
  const clearResult = JSON.parse(clearRes.stdout).result;
  assert.equal(clearResult.ok, true);
  assert.equal(typeof clearResult.nextSeq, 'number');

  const logsAfter = await handleMockControlExec('sideveil-ctl logs');
  assert.equal(logsAfter.errno, 0);
  const logsPayload = JSON.parse(logsAfter.stdout).result;
  assert.deepEqual(logsPayload.lines, []);
  assert.equal(logsPayload.totalDropped, 0);
});

test('handleMockControlExec supports debug mode get/set and gates builtin test plugins', async () => {
  const initialModeRes = await handleMockControlExec('sideveil-ctl debug mode');
  assert.equal(initialModeRes.errno, 0);
  assert.equal(JSON.parse(initialModeRes.stdout).runtimeMode, 'release');

  const releaseList = JSON.parse((await handleMockControlExec('sideveil-ctl plugins list')).stdout);
  assert.equal(
    releaseList.matchedPluginIds.includes(SIDEVEIL_BUILTIN_TEST_PLUGINS.DIAGNOSTIC),
    false,
  );

  const toDebugRes = await handleMockControlExec('sideveil-ctl debug mode debug');
  assert.equal(toDebugRes.errno, 0);
  const toDebug = JSON.parse(toDebugRes.stdout).result;
  assert.equal(toDebug.ok, true);
  assert.equal(toDebug.previousMode, 'release');
  assert.equal(toDebug.runtimeMode, 'debug');

  const readDebug = JSON.parse((await handleMockControlExec('sideveil-ctl debug mode')).stdout);
  assert.equal(readDebug.runtimeMode, 'debug');

  const toReleaseRes = await handleMockControlExec('sideveil-ctl debug mode release');
  assert.equal(toReleaseRes.errno, 0);
  const toRelease = JSON.parse(toReleaseRes.stdout).result;
  assert.equal(toRelease.ok, true);
  assert.equal(toRelease.previousMode, 'debug');
  assert.equal(toRelease.runtimeMode, 'release');
  assert.equal(
    toRelease.snapshot.matchedPluginIds.includes(SIDEVEIL_BUILTIN_TEST_PLUGINS.DIAGNOSTIC),
    false,
  );
});
