import type { MockRpcHandler } from '@pochita/bridge';
import { DEFAULT_CONFIG, SIDEVEIL_METHODS } from '../../shared/types.ts';

export interface PerfHudBackendState {
  enabled: boolean;
  clickThrough: boolean;
  isDark?: boolean;
}

/**
 * Browser preview mock RPC and plugin-backend handlers for `example.native-perf-hud`.
 */
export function createMockPerfHudHandlers(): {
  onMockRpc: MockRpcHandler;
  onMockPluginInvoke: (method: string, params?: unknown) => PerfHudBackendState;
} {
  const startMs = Date.now();
  const state: PerfHudBackendState = {
    enabled: false,
    clickThrough: false,
    isDark: true,
  };

  const onMockPluginInvoke = (method: string, params?: unknown): PerfHudBackendState => {
    const obj = params && typeof params === 'object' ? (params as Record<string, unknown>) : {};
    if (method === 'setEnabled' && typeof obj.enabled === 'boolean') {
      state.enabled = obj.enabled;
    } else if (method === 'setClickThrough' && typeof obj.clickThrough === 'boolean') {
      state.clickThrough = obj.clickThrough;
    } else if (method === 'setAppearance' && typeof obj.isDark === 'boolean') {
      state.isDark = obj.isDark;
    }
    return { ...state };
  };

  const onMockRpc: MockRpcHandler = (method) => {
    if (method === SIDEVEIL_METHODS.CONFIG_GET) {
      return DEFAULT_CONFIG;
    }
    if (method === 'telemetry.snapshot') {
      const elapsedSec = (Date.now() - startMs) / 1000;
      return {
        cpuPercent: Number((26 + Math.sin(elapsedSec * 1.1) * 14).toFixed(1)),
        memUsedMb: 6840,
        memTotalMb: 16384,
        socTempC: Number((35 + Math.cos(elapsedSec * 0.35) * 2).toFixed(1)),
        uptimeSec: Math.floor(elapsedSec + 14200),
      };
    }
    if (method === 'haptics.trigger') {
      return { ok: true };
    }
    return { ok: true };
  };

  return { onMockRpc, onMockPluginInvoke };
}
