import { type BridgeClient, shellQuote } from '@gojona/bridge';
import { type NetworkContextSnapshot, parseNetworkContextSnapshot } from '@gojona/sideveil-sdk';
import { useCallback, useEffect, useRef, useState } from 'react';
import { CTL_BIN, SIDEVEIL_EVENTS, SIDEVEIL_METHODS } from '../../shared/types.ts';
import { extractConnectedWifiSsid } from '../webEmbedHelpers.ts';

export function parseNetworkExecOutput(stdout: string): NetworkContextSnapshot | null {
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
  const mountedRef = useRef(true);

  const refreshNetworkContext = useCallback(async (): Promise<NetworkContextSnapshot | null> => {
    setNetworkLoading(true);
    try {
      if (bridge.env === 'host') {
        const res = await bridge.invoke<unknown>(SIDEVEIL_METHODS.NETWORK_CONTEXT, {});
        const snapshot = parseNetworkContextSnapshot(res);
        if (!mountedRef.current) return snapshot;
        if (snapshot) {
          setNetworkContext(snapshot);
          setNetworkError(null);
          return snapshot;
        }
        setNetworkError('网络状态返回格式无效');
        return null;
      }

      const res = await bridge.exec(`${shellQuote(CTL_BIN)} network`);
      if (!mountedRef.current) {
        return res.errno === 0 ? parseNetworkExecOutput(res.stdout) : null;
      }
      if (res.errno === 0) {
        const snapshot = parseNetworkExecOutput(res.stdout);
        if (snapshot) {
          setNetworkContext(snapshot);
          setNetworkError(null);
          return snapshot;
        }
        setNetworkError('未获取到有效网络快照');
        return null;
      }
      setNetworkError(res.stderr.trim() || res.stdout.trim() || `读取失败 (errno ${res.errno})`);
      return null;
    } catch (err) {
      if (mountedRef.current) {
        setNetworkError(err instanceof Error ? err.message : String(err));
      }
      return null;
    } finally {
      if (mountedRef.current) {
        setNetworkLoading(false);
      }
    }
  }, [bridge]);

  const detectWifiSsid = useCallback(async (): Promise<string | null> => {
    const snapshot = await refreshNetworkContext();
    return extractConnectedWifiSsid(snapshot);
  }, [refreshNetworkContext]);

  useEffect(() => {
    mountedRef.current = true;
    void refreshNetworkContext();
    const unsubscribe =
      bridge.env === 'host'
        ? bridge.onHostEvent(SIDEVEIL_EVENTS.NETWORK_CONTEXT_CHANGED, (payload: unknown) => {
            const snapshot = parseNetworkContextSnapshot(payload);
            if (snapshot && mountedRef.current) {
              setNetworkContext(snapshot);
              setNetworkError(null);
            }
          })
        : undefined;
    return () => {
      mountedRef.current = false;
      unsubscribe?.();
    };
  }, [bridge, refreshNetworkContext]);

  const currentWifiSsid = extractConnectedWifiSsid(networkContext);

  return {
    networkContext,
    currentWifiSsid,
    networkLoading,
    networkError,
    refreshNetworkContext,
    detectWifiSsid,
  };
}
