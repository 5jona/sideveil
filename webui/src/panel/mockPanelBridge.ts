import type { MockRpcHandler } from '@pochita/bridge';
import {
  type DaemonStatus,
  DEFAULT_CONFIG,
  type PingResult,
  SIDEVEIL_METHODS,
  type SideVeilConfig,
} from '../shared/types.ts';

/**
 * Creates a stateful mock RPC handler for the embedded panel preview environment.
 */
export function createMockPanelRpcHandler(
  initialConfig: SideVeilConfig = DEFAULT_CONFIG,
): MockRpcHandler {
  let currentConfig: SideVeilConfig = initialConfig;

  return async (method: string, params?: unknown) => {
    if (method === SIDEVEIL_METHODS.DAEMON_PING) {
      return {
        pong: true,
        daemonUptimeMs: 184_200,
        timestampMs: Date.now(),
      } satisfies PingResult;
    }

    if (method === SIDEVEIL_METHODS.DAEMON_STATUS) {
      return {
        pid: 14820,
        uid: 1000,
        uptimeMs: 184_200,
        configVersion: currentConfig.version,
        enabled: true,
        scopeActive: true,
        foregroundPackage: 'me.weishu.kernelsu',
        hostPackage: 'pochita.sideveil.host',
        hostConnected: true,
        hostPid: 14905,
        overlayPermissionGranted: true,
        crossWindowBlurSupported: true,
        gestureMonitorActive: true,
        taskListenerActive: true,
        panelState: 'open',
        screenWidthPx: 3392,
        screenHeightPx: 2400,
        density: 2.625,
        wsPort: 41928,
      } satisfies DaemonStatus;
    }

    if (method === SIDEVEIL_METHODS.CONFIG_GET) {
      return currentConfig;
    }

    if (method === SIDEVEIL_METHODS.CONFIG_PATCH_APPEARANCE) {
      const patch = (params ?? {}) as Partial<SideVeilConfig['panel']>;
      currentConfig = {
        ...currentConfig,
        panel: {
          ...currentConfig.panel,
          ...patch,
        },
      };
      return currentConfig;
    }

    return { ok: true };
  };
}
