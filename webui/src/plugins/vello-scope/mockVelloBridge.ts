import type { MockRpcHandler } from '@gojona/bridge';
import { DEFAULT_CONFIG, SIDEVEIL_METHODS } from '../../shared/types.ts';

/**
 * Browser preview mock RPC handler for `example.vello-scope`.
 */
export function createMockVelloRpcHandler(): MockRpcHandler {
  const startMs = Date.now();
  return (method) => {
    if (method === SIDEVEIL_METHODS.CONFIG_GET) {
      return DEFAULT_CONFIG;
    }
    if (method === 'telemetry.snapshot') {
      const elapsedSec = (Date.now() - startMs) / 1000;
      const cpuPercent = Number(
        Math.min(
          96,
          Math.max(8, 34 + Math.sin(elapsedSec * 1.3) * 18 + Math.cos(elapsedSec * 2.7) * 9),
        ).toFixed(1),
      );
      return {
        cpuPercent,
        memUsedMb: 7120,
        memTotalMb: 16384,
        socTempC: Number((37 + Math.sin(elapsedSec * 0.4) * 2.5).toFixed(1)),
        uptimeSec: Math.floor(elapsedSec + 18400),
      };
    }
    if (method === 'haptics.trigger') {
      return { ok: true };
    }
    return { ok: true };
  };
}
