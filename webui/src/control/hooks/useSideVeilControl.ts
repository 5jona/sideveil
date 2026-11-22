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
  type SideVeilConfig,
} from '../../shared/types.ts';
import { usePluginsControl } from './usePluginsControl.ts';

function parseDaemonLogLines(lines: readonly string[]): LogEntry[] {
  return lines.map((line, idx) => {
    const levelMatch = line.match(/\[(I|W|E)\]/);
    const level = levelMatch?.[1] === 'E' ? 'error' : levelMatch?.[1] === 'W' ? 'warn' : 'info';
    const timePart = line.slice(0, 12).trim() || '--:--:--';
    return {
      id: `dlog-${idx}-${timePart}`,
      timestamp: timePart,
      level,
      message: line.length > 13 ? line.slice(13).trim() : line,
    };
  });
}

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

  const latestConfigRef = useRef<SideVeilConfig>(DEFAULT_CONFIG);
  const pendingSaveConfigRef = useRef<SideVeilConfig | null>(null);
  const configLoadErrorRef = useRef<string | null>(null);
  configLoadErrorRef.current = configLoadError;
  const isLoadedRef = useRef(false);
  const isPollingRef = useRef(false);
  const saveTimerRef = useRef<number | null>(null);

  const refreshStatus = useCallback(async () => {
    if (isPollingRef.current) return;
    isPollingRef.current = true;
    try {
      const res = await bridge.exec(`${shellQuote(CTL_BIN)} status`);
      if (res.errno === 0 && res.stdout.trim()) {
        try {
          const parsed = JSON.parse(res.stdout.trim()) as DaemonStatus;
          setStatus(parsed);
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
      const msg = err instanceof Error ? err.message : String(err);
      setDaemonOfflineError(`无法连接 Daemon: ${msg}`);
    } finally {
      isPollingRef.current = false;
    }
  }, [bridge]);

  const refreshLogs = useCallback(async () => {
    const res = await bridge.exec(`${shellQuote(CTL_BIN)} logs`);
    if (res.errno === 0 && res.stdout.trim()) {
      try {
        const parsed = JSON.parse(res.stdout.trim()) as { result?: { lines?: string[] } };
        if (Array.isArray(parsed.result?.lines)) {
          setLogs(parseDaemonLogLines(parsed.result.lines));
        }
      } catch {
        // Ignore malformed log frame.
      }
    }
  }, [bridge]);

  const loadConfigFromDisk = useCallback(async () => {
    try {
      const raw = await bridge.readFile(CONFIG_PATH);
      if (raw === null) {
        setConfigState(DEFAULT_CONFIG);
        latestConfigRef.current = DEFAULT_CONFIG;
        setThemeId(DEFAULT_CONFIG.panel.themeId);
        setColorMode(DEFAULT_CONFIG.panel.colorMode);
        setConfigLoadError(null);
        configLoadErrorRef.current = null;
        isLoadedRef.current = true;
        await bridge.writeFileAtomic(CONFIG_PATH, JSON.stringify(DEFAULT_CONFIG, null, 2));
      } else {
        try {
          const parsed = JSON.parse(raw) as Partial<SideVeilConfig>;
          const normalized = normalizeSideVeilConfig(parsed);
          latestConfigRef.current = normalized;
          setConfigState(normalized);
          setThemeId(normalized.panel.themeId);
          setColorMode(normalized.panel.colorMode);
          setConfigLoadError(null);
          configLoadErrorRef.current = null;
          isLoadedRef.current = true;
        } catch {
          const errMsg = '读取配置文件失败，已停止自动覆写以保护现有配置';
          setConfigLoadError(errMsg);
          configLoadErrorRef.current = errMsg;
        }
      }
    } catch {
      const errMsg = '读取配置文件失败，已停止自动覆写以保护现有配置';
      setConfigLoadError(errMsg);
      configLoadErrorRef.current = errMsg;
    }
  }, [bridge, setColorMode, setThemeId]);

  const persistConfig = useCallback(
    async (nextConfig: SideVeilConfig) => {
      if (configLoadErrorRef.current !== null) {
        setSaving(false);
        return;
      }
      setSaving(true);
      try {
        const res = await bridge.writeFileAtomic(CONFIG_PATH, JSON.stringify(nextConfig, null, 2));
        if (res.errno !== 0) {
          pushToast('保存配置失败', 'danger');
        } else {
          void refreshStatus();
        }
      } catch {
        pushToast('保存配置失败', 'danger');
      } finally {
        setSaving(false);
      }
    },
    [bridge, pushToast, refreshStatus],
  );

  const flushPendingSave = useCallback(() => {
    if (saveTimerRef.current !== null) {
      window.clearTimeout(saveTimerRef.current);
      saveTimerRef.current = null;
    }
    const pending = pendingSaveConfigRef.current;
    if (pending) {
      pendingSaveConfigRef.current = null;
      void persistConfig(pending);
    }
  }, [persistConfig]);

  const updateConfig = useCallback(
    (updater: (prev: SideVeilConfig) => SideVeilConfig) => {
      if (!isLoadedRef.current || configLoadErrorRef.current !== null) {
        return;
      }
      const next = normalizeSideVeilConfig(updater(latestConfigRef.current));
      latestConfigRef.current = next;
      pendingSaveConfigRef.current = next;
      setConfigState(next);
      setSaving(true);
      if (saveTimerRef.current !== null) {
        window.clearTimeout(saveTimerRef.current);
      }
      saveTimerRef.current = window.setTimeout(async () => {
        saveTimerRef.current = null;
        pendingSaveConfigRef.current = null;
        await persistConfig(next);
      }, 220);
    },
    [persistConfig],
  );

  useEffect(() => {
    const handlePageHide = () => {
      flushPendingSave();
    };
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
      if (active) {
        await refreshStatus();
        await refreshLogs();
        await refreshPlugins();
      }
    };
    void loadInitial();

    const handleVisibilityChange = () => {
      if (document.hidden) {
        flushPendingSave();
      } else {
        void refreshStatus();
        void refreshLogs();
        void refreshPlugins();
      }
    };
    document.addEventListener('visibilitychange', handleVisibilityChange);

    const timer = window.setInterval(() => {
      if (!document.hidden) {
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
        const dt = Math.max(1, Math.round(performance.now() - t0));
        if (res.errno === 0) {
          if (action === 'ping') {
            setLastPingMs(dt);
          }
          pushToast(`${label}成功（${dt} ms）`, 'success');
          if (action === 'reload') {
            await loadConfigFromDisk();
          }
          await refreshStatus();
          await refreshLogs();
        } else {
          pushToast(`${label}失败：${res.stderr || res.stdout}`, 'warning');
        }
      } finally {
        setCtlBusyAction(null);
      }
    },
    [bridge, loadConfigFromDisk, pushToast, refreshLogs, refreshStatus],
  );

  const clearLogs = useCallback(() => setLogs([]), []);

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
