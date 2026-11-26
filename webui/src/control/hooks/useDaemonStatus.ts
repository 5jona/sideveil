import { type BridgeClient, shellQuote } from '@gojona/bridge';
import type { LogEntry } from '@gojona/ui';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  CTL_BIN,
  type CtlCommand,
  type DaemonStatus,
  SIDEVEIL_METHODS,
} from '../../shared/types.ts';
import { parseDaemonLogsStdout, parseDaemonStatusStdout } from './logParser.ts';
import {
  PANEL_TRANSITION_GRACE_MS,
  type PendingPanelTransition,
  reconcilePolledPanelState,
} from './panelToggle.ts';

type PushToast = (message: string, tone?: 'info' | 'success' | 'warning' | 'danger') => void;

export interface UseDaemonStatusOptions {
  bridge: BridgeClient;
  pushToast: PushToast;
  logsEnabledRef: React.RefObject<boolean>;
  mountedRef: React.RefObject<boolean>;
  onConfigReloaded: () => Promise<void>;
}

/**
 * Manages daemon status polling, log fetching/clearing, and `sideveil-ctl` command execution
 * with optimistic panel-state reconciliation so immediate status reads never revert the UI.
 */
export function useDaemonStatus({
  bridge,
  pushToast,
  logsEnabledRef,
  mountedRef,
  onConfigReloaded,
}: UseDaemonStatusOptions) {
  const [status, setStatus] = useState<DaemonStatus | null>(null);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [ctlBusyAction, setCtlBusyAction] = useState<CtlCommand | null>(null);
  const [lastPingMs, setLastPingMs] = useState<number | null>(null);
  const [daemonOfflineError, setDaemonOfflineError] = useState<string | null>(null);

  const statusPollPromiseRef = useRef<Promise<void> | null>(null);
  const pendingPanelTransitionRef = useRef<PendingPanelTransition | null>(null);
  const panelSettleTimerRef = useRef<number | null>(null);

  useEffect(() => {
    return () => {
      if (panelSettleTimerRef.current !== null) {
        window.clearTimeout(panelSettleTimerRef.current);
        panelSettleTimerRef.current = null;
      }
    };
  }, []);

  const refreshStatus = useCallback(
    async (options?: { force?: boolean }) => {
      if (statusPollPromiseRef.current) {
        if (!options?.force) return;
        await statusPollPromiseRef.current;
      }
      const executePoll = async () => {
        try {
          const res = await bridge.exec(`${shellQuote(CTL_BIN)} status`);
          if (!mountedRef.current) return;
          if (res.errno === 0 && res.stdout.trim()) {
            const parsed = parseDaemonStatusStdout(res.stdout);
            if (parsed) {
              const reconciled = reconcilePolledPanelState(
                parsed.panelState,
                pendingPanelTransitionRef.current,
                Date.now(),
              );
              pendingPanelTransitionRef.current = reconciled.nextPending;
              setStatus(
                reconciled.panelState === parsed.panelState
                  ? parsed
                  : { ...parsed, panelState: reconciled.panelState },
              );
              setDaemonOfflineError(null);
            }
          } else {
            setDaemonOfflineError(
              res.stderr.trim() || res.stdout.trim() || `服务未就绪 (errno ${res.errno})`,
            );
          }
        } catch (err) {
          if (!mountedRef.current) return;
          const msg = err instanceof Error ? err.message : String(err);
          setDaemonOfflineError(`无法连接 Daemon: ${msg}`);
        } finally {
          if (statusPollPromiseRef.current === run) {
            statusPollPromiseRef.current = null;
          }
        }
      };
      const run = executePoll();
      statusPollPromiseRef.current = run;
      await run;
    },
    [bridge, mountedRef],
  );

  const refreshLogs = useCallback(async () => {
    const res = await bridge.exec(`${shellQuote(CTL_BIN)} logs`);
    if (!mountedRef.current || res.errno !== 0) return;
    const entries = parseDaemonLogsStdout(res.stdout);
    if (entries) setLogs(entries);
  }, [bridge, mountedRef]);

  const runCtl = useCallback(
    async (action: CtlCommand, label: string) => {
      setCtlBusyAction(action);
      const t0 = performance.now();
      try {
        const res = await bridge.exec(`${shellQuote(CTL_BIN)} ${shellQuote(action)}`);
        if (!mountedRef.current) return;
        const dt = Math.max(1, Math.round(performance.now() - t0));
        if (res.errno === 0) {
          if (action === 'ping') setLastPingMs(dt);
          pushToast(`${label}成功（${dt} ms）`, 'success');
          if (action === 'open' || action === 'close') {
            const targetState = action === 'open' ? 'open' : 'closed';
            pendingPanelTransitionRef.current = {
              targetState,
              expiresAtMs: Date.now() + PANEL_TRANSITION_GRACE_MS,
            };
            setStatus((prev) => (prev ? { ...prev, panelState: targetState } : prev));
            if (panelSettleTimerRef.current !== null) {
              window.clearTimeout(panelSettleTimerRef.current);
            }
            panelSettleTimerRef.current = window.setTimeout(() => {
              panelSettleTimerRef.current = null;
              if (mountedRef.current) void refreshStatus({ force: true });
            }, 350);
          }
          if (action === 'reload') await onConfigReloaded();
          await Promise.all([
            refreshStatus({ force: true }),
            logsEnabledRef.current ? refreshLogs() : Promise.resolve(),
          ]);
        } else {
          pushToast(`${label}失败：${res.stderr || res.stdout}`, 'warning');
        }
      } finally {
        if (mountedRef.current) setCtlBusyAction(null);
      }
    },
    [bridge, logsEnabledRef, mountedRef, onConfigReloaded, pushToast, refreshLogs, refreshStatus],
  );

  const clearLogs = useCallback(async () => {
    try {
      await (bridge.env === 'host'
        ? bridge.invoke(SIDEVEIL_METHODS.DAEMON_LOGS_CLEAR)
        : bridge.exec(`${shellQuote(CTL_BIN)} logs --clear`));
    } catch {
      // Best-effort remote clear; still clear local log buffer.
    }
    if (mountedRef.current) setLogs([]);
  }, [bridge, mountedRef]);

  return {
    status,
    logs,
    ctlBusyAction,
    lastPingMs,
    daemonOfflineError,
    refreshStatus,
    refreshLogs,
    runCtl,
    clearLogs,
  };
}
