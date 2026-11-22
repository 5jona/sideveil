import { type BridgeClient, shellQuote } from '@pochita/bridge';
import { useCallback, useState } from 'react';
import {
  CTL_BIN,
  type DebugCdpStatusResult,
  type DebugInspectUiSnapshot,
  SIDEVEIL_METHODS,
} from '../../shared/types.ts';

type PushToast = (message: string, variant?: 'info' | 'success' | 'warning' | 'danger') => void;

interface RpcEnvelope<T> {
  result?: T;
  error?: { message?: string };
}

function unwrapRpcOutput<T>(stdout: string): { result?: T; errorMessage?: string } {
  const trimmed = stdout.trim();
  if (!trimmed) return { errorMessage: '守护进程返回空响应' };
  try {
    const parsed = JSON.parse(trimmed) as RpcEnvelope<T> & T;
    if (parsed && typeof parsed === 'object' && 'error' in parsed && parsed.error) {
      return { errorMessage: parsed.error.message || 'RPC 调用失败' };
    }
    if (parsed && typeof parsed === 'object' && 'result' in parsed && parsed.result !== undefined) {
      return { result: parsed.result };
    }
    return { result: parsed as T };
  } catch {
    return { errorMessage: '无法解析守护进程返回的 JSON 数据' };
  }
}

export interface UseDebugControlOptions {
  bridge: BridgeClient;
  pushToast: PushToast;
  onConfigSynced: () => Promise<void>;
  onRefreshLogs: () => Promise<void>;
}

export function useDebugControl({
  bridge,
  pushToast,
  onConfigSynced,
  onRefreshLogs,
}: UseDebugControlOptions) {
  const [cdpStatus, setCdpStatus] = useState<DebugCdpStatusResult | null>(null);
  const [uiSnapshot, setUiSnapshot] = useState<DebugInspectUiSnapshot | null>(null);
  const [inspectCapturedAt, setInspectCapturedAt] = useState<string | null>(null);
  const [debugBusy, setDebugBusy] = useState<'cdp' | 'inspect' | null>(null);
  const [debugError, setDebugError] = useState<string | null>(null);

  const setWebViewDebugging = useCallback(
    async (enabled: boolean) => {
      setDebugBusy('cdp');
      setDebugError(null);
      try {
        let result: DebugCdpStatusResult | undefined;
        if (bridge.env === 'host') {
          result = await bridge.invoke<DebugCdpStatusResult>(
            SIDEVEIL_METHODS.DEBUG_SET_WEBVIEW_DEBUGGING,
            { enabled },
          );
        } else {
          const mode = enabled ? 'on' : 'off';
          const res = await bridge.exec(`${shellQuote(CTL_BIN)} debug cdp ${mode}`);
          if (res.errno !== 0) {
            throw new Error(res.stderr.trim() || res.stdout.trim() || `errno ${res.errno}`);
          }
          const decoded = unwrapRpcOutput<DebugCdpStatusResult>(res.stdout);
          if (decoded.errorMessage) {
            throw new Error(decoded.errorMessage);
          }
          result = decoded.result;
        }
        if (result) {
          setCdpStatus(result);
        }
        await onConfigSynced();
        await onRefreshLogs();
        pushToast(
          enabled ? 'WebView CDP 调试已开启' : 'WebView CDP 调试已关闭',
          result?.restartRecommended ? 'warning' : 'success',
        );
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        setDebugError(msg);
        pushToast(`切换 WebView 调试失败：${msg}`, 'warning');
      } finally {
        setDebugBusy(null);
      }
    },
    [bridge, onConfigSynced, onRefreshLogs, pushToast],
  );

  const inspectUi = useCallback(async () => {
    setDebugBusy('inspect');
    setDebugError(null);
    const t0 = performance.now();
    try {
      let snapshot: DebugInspectUiSnapshot | undefined;
      if (bridge.env === 'host') {
        snapshot = await bridge.invoke<DebugInspectUiSnapshot>(SIDEVEIL_METHODS.DEBUG_INSPECT_UI);
      } else {
        const res = await bridge.exec(`${shellQuote(CTL_BIN)} debug inspect-ui`);
        if (res.errno !== 0) {
          throw new Error(res.stderr.trim() || res.stdout.trim() || `errno ${res.errno}`);
        }
        const decoded = unwrapRpcOutput<DebugInspectUiSnapshot>(res.stdout);
        if (decoded.errorMessage) {
          throw new Error(decoded.errorMessage);
        }
        snapshot = decoded.result;
      }
      if (!snapshot) {
        throw new Error('未收到 UI 快照数据');
      }
      setUiSnapshot(snapshot);
      setInspectCapturedAt(new Date().toTimeString().slice(0, 8));
      const dt = Math.max(1, Math.round(performance.now() - t0));
      pushToast(`UI 快照采集完成（${dt} ms）`, 'success');
      await onRefreshLogs();
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setDebugError(msg);
      pushToast(`采集 UI 快照失败：${msg}`, 'warning');
    } finally {
      setDebugBusy(null);
    }
  }, [bridge, onRefreshLogs, pushToast]);

  return {
    cdpStatus,
    uiSnapshot,
    inspectCapturedAt,
    debugBusy,
    debugError,
    setWebViewDebugging,
    inspectUi,
  };
}
