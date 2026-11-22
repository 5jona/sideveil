import { type BridgeClient, shellQuote } from '@pochita/bridge';
import { type NetworkContextSnapshot, parseNetworkContextSnapshot } from '@pochita/sideveil-sdk';
import { useCallback, useEffect, useState } from 'react';
import { CTL_BIN, SIDEVEIL_EVENTS, SIDEVEIL_METHODS } from '../../shared/types.ts';

function parseNetworkExecOutput(stdout: string): NetworkContextSnapshot | null {
  const trimmed = stdout.trim();
  if (!trimmed) return null;
  try {
    const parsed = JSON.parse(trimmed) as Record<string, unknown>;
    const candidate =
      parsed && typeof parsed === 'object' && 'result' in parsed ? parsed.result : parsed;
    return parseNetworkContextSnapshot(candidate);
  } catch {
    return null;
  }
}

export function useNetworkContextControl(bridge: BridgeClient) {
  const [networkContext, setNetworkContext] = useState<NetworkContextSnapshot | null>(null);
  const [networkLoading, setNetworkLoading] = useState(false);
  const [networkError, setNetworkError] = useState<string | null>(null);

  const refreshNetworkContext = useCallback(async () => {
    setNetworkLoading(true);
    try {
      if (bridge.env === 'host') {
        const res = await bridge.invoke<unknown>(SIDEVEIL_METHODS.NETWORK_CONTEXT, {});
        const snapshot = parseNetworkContextSnapshot(res);
        if (snapshot) {
          setNetworkContext(snapshot);
          setNetworkError(null);
        } else {
          setNetworkError('网络状态返回格式无效');
        }
      } else {
        const res = await bridge.exec(`${shellQuote(CTL_BIN)} network`);
        if (res.errno === 0) {
          const snapshot = parseNetworkExecOutput(res.stdout);
          if (snapshot) {
            setNetworkContext(snapshot);
            setNetworkError(null);
          } else {
            setNetworkError('未获取到有效网络快照');
          }
        } else {
          setNetworkError(
            res.stderr.trim() || res.stdout.trim() || `读取失败 (errno ${res.errno})`,
          );
        }
      }
    } catch (err) {
      setNetworkError(err instanceof Error ? err.message : String(err));
    } finally {
      setNetworkLoading(false);
    }
  }, [bridge]);

  useEffect(() => {
    void refreshNetworkContext();
    if (bridge.env !== 'host') return;
    return bridge.onHostEvent(SIDEVEIL_EVENTS.NETWORK_CONTEXT_CHANGED, (payload: unknown) => {
      const snapshot = parseNetworkContextSnapshot(payload);
      if (snapshot) {
        setNetworkContext(snapshot);
        setNetworkError(null);
      }
    });
  }, [bridge, refreshNetworkContext]);

  return {
    networkContext,
    networkLoading,
    networkError,
    refreshNetworkContext,
  };
}
