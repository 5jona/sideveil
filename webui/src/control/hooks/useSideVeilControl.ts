import { shellQuote, useBridge } from '@pochita/bridge';
import { type LogEntry, useTheme, useToast } from '@pochita/ui';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  CONFIG_PATH,
  CTL_BIN,
  type CtlCommand,
  type DaemonStatus,
  DEFAULT_CONFIG,
  normalizeSideVeilConfig,
  SIDEVEIL_METHODS,
  type SideVeilConfig,
} from '../../shared/types.ts';
import { parseDaemonLogLines } from './logParser.ts';
import { usePluginsControl } from './usePluginsControl.ts';

/**
 * Coordinates KernelSU Control Plane state: authoritative config loading and serialized
 * atomic saves, daemon status/log polling, plugin registry actions, and `sideveil-ctl` commands.
 */
export function useSideVeilControl() {
  const bridge = useBridge();
  const { pushToast } = useToast();
  const { setThemeId, setColorMode } = useTheme();

  const [config, setConfigState] = useState<SideVeilConfig>(DEFAULT_CONFIG);
  const [status, setStatus] = useState<DaemonStatus | null>(null);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [ctlBusyAction, setCtlBusyAction] = useState<CtlCommand | null>(null);
  const [lastPingMs, setLastPingMs] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [configLoadError, setConfigLoadError] = useState<string | null>(null);
  const [daemonOfflineError, setDaemonOfflineError] = useState<string | null>(null);

  const pluginsControl = usePluginsControl(bridge, pushToast);
  const { refreshPlugins } = pluginsControl;
  const busyAction = ctlBusyAction ?? pluginsControl.pluginBusyAction;

  const mountedRef = useRef(true);
  const latestConfigRef = useRef<SideVeilConfig>(DEFAULT_CONFIG);
  const pendingSaveConfigRef = useRef<SideVeilConfig | null>(null);
  const saveQueueRef = useRef<Promise<void>>(Promise.resolve());
  const inFlightSavesRef = useRef(0);
  const configLoadErrorRef = useRef<string | null>(null);
  configLoadErrorRef.current = configLoadError;
  const isLoadedRef = useRef(false);
  const isPollingRef = useRef(false);
  const saveTimerRef = useRef<number | null>(null);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const refreshStatus = useCallback(async () => {
    if (isPollingRef.current) return;
    isPollingRef.current = true;
    try {
      const res = await bridge.exec(`${shellQuote(CTL_BIN)} status`);
      if (!mountedRef.current) return;
      if (res.errno === 0 && res.stdout.trim()) {
        try {
          setStatus(JSON.parse(res.stdout.trim()) as DaemonStatus);
          setDaemonOfflineError(null);
        } catch {
          // Ignore transient partial output.
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
      isPollingRef.current = false;
    }
  }, [bridge]);

  const refreshLogs = useCallback(async () => {
    const res = await bridge.exec(`${shellQuote(CTL_BIN)} logs`);
    if (!mountedRef.current || res.errno !== 0 || !res.stdout.trim()) return;
    try {
      const parsed = JSON.parse(res.stdout.trim()) as { result?: { lines?: string[] } };
      if (Array.isArray(parsed.result?.lines)) {
        setLogs(parseDaemonLogLines(parsed.result.lines));
      }
    } catch {
      // Ignore malformed log frame.
    }
  }, [bridge]);

  const enqueueAtomicWrite = useCallback(
    (nextConfig: SideVeilConfig, notifyOnFailure: boolean): Promise<void> => {
      inFlightSavesRef.current += 1;
      const run = saveQueueRef.current.then(async () => {
        if (configLoadErrorRef.current !== null) return;
        try {
          const res = await bridge.writeFileAtomic(
            CONFIG_PATH,
            JSON.stringify(nextConfig, null, 2),
          );
          if (!mountedRef.current) return;
          if (res.errno !== 0) {
            if (notifyOnFailure) pushToast('保存配置失败', 'danger');
          } else if (notifyOnFailure) {
            void refreshStatus();
          }
        } catch {
          if (mountedRef.current && notifyOnFailure) pushToast('保存配置失败', 'danger');
        } finally {
          inFlightSavesRef.current = Math.max(0, inFlightSavesRef.current - 1);
          if (
            mountedRef.current &&
            inFlightSavesRef.current === 0 &&
            pendingSaveConfigRef.current === null
          ) {
            setSaving(false);
          }
        }
      });
      saveQueueRef.current = run.catch(() => {});
      return run;
    },
    [bridge, pushToast, refreshStatus],
  );

  const loadConfigFromDisk = useCallback(async () => {
    const applyLoadedConfig = (next: SideVeilConfig) => {
      latestConfigRef.current = next;
      setConfigState(next);
      setThemeId(next.panel.themeId);
      setColorMode(next.panel.colorMode);
      setConfigLoadError(null);
      configLoadErrorRef.current = null;
      isLoadedRef.current = true;
    };
    const failLoad = () => {
      const errMsg = '读取配置文件失败，已停止自动覆写以保护现有配置';
      configLoadErrorRef.current = errMsg;
      if (mountedRef.current) setConfigLoadError(errMsg);
    };
    try {
      const raw = await bridge.readFile(CONFIG_PATH);
      if (!mountedRef.current) return;
      if (raw === null) {
        applyLoadedConfig(DEFAULT_CONFIG);
        await enqueueAtomicWrite(DEFAULT_CONFIG, false);
        return;
      }
      applyLoadedConfig(normalizeSideVeilConfig(JSON.parse(raw) as Partial<SideVeilConfig>));
    } catch {
      failLoad();
    }
  }, [bridge, enqueueAtomicWrite, setColorMode, setThemeId]);

  const flushPendingSave = useCallback(() => {
    if (saveTimerRef.current !== null) {
      window.clearTimeout(saveTimerRef.current);
      saveTimerRef.current = null;
    }
    const pending = pendingSaveConfigRef.current;
    if (pending) {
      pendingSaveConfigRef.current = null;
      void enqueueAtomicWrite(pending, true);
    }
  }, [enqueueAtomicWrite]);

  const updateConfig = useCallback(
    (updater: (prev: SideVeilConfig) => SideVeilConfig) => {
      if (!isLoadedRef.current || configLoadErrorRef.current !== null) return;
      const next = normalizeSideVeilConfig(updater(latestConfigRef.current));
      latestConfigRef.current = next;
      pendingSaveConfigRef.current = next;
      setConfigState(next);
      setSaving(true);
      if (saveTimerRef.current !== null) {
        window.clearTimeout(saveTimerRef.current);
      }
      saveTimerRef.current = window.setTimeout(() => {
        saveTimerRef.current = null;
        const snapshot = pendingSaveConfigRef.current ?? next;
        pendingSaveConfigRef.current = null;
        void enqueueAtomicWrite(snapshot, true);
      }, 220);
    },
    [enqueueAtomicWrite],
  );

  useEffect(() => {
    const handlePageHide = () => flushPendingSave();
    window.addEventListener('pagehide', handlePageHide);
    return () => {
      window.removeEventListener('pagehide', handlePageHide);
      flushPendingSave();
    };
  }, [flushPendingSave]);

  useEffect(() => {
    let active = true;
    const loadInitial = async () => {
      await loadConfigFromDisk();
      if (active && mountedRef.current) {
        await refreshStatus();
        await refreshLogs();
        await refreshPlugins();
      }
    };
    void loadInitial();

    const handleVisibilityChange = () => {
      if (document.hidden) {
        flushPendingSave();
      } else if (mountedRef.current) {
        void refreshStatus();
        void refreshLogs();
        void refreshPlugins();
      }
    };
    document.addEventListener('visibilitychange', handleVisibilityChange);

    const timer = window.setInterval(() => {
      if (!document.hidden && mountedRef.current) {
        void refreshStatus();
        void refreshPlugins();
      }
    }, 3000);

    return () => {
      active = false;
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      window.clearInterval(timer);
    };
  }, [flushPendingSave, loadConfigFromDisk, refreshLogs, refreshPlugins, refreshStatus]);

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
          if (action === 'reload') await loadConfigFromDisk();
          await refreshStatus();
          await refreshLogs();
        } else {
          pushToast(`${label}失败：${res.stderr || res.stdout}`, 'warning');
        }
      } finally {
        if (mountedRef.current) setCtlBusyAction(null);
      }
    },
    [bridge, loadConfigFromDisk, pushToast, refreshLogs, refreshStatus],
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
  }, [bridge]);

  return {
    bridge,
    config,
    status,
    logs,
    ...pluginsControl,
    busyAction,
    lastPingMs,
    saving,
    configLoadError,
    daemonOfflineError,
    updateConfig,
    loadConfigFromDisk,
    refreshStatus,
    refreshLogs,
    runCtl,
    clearLogs,
  };
}
