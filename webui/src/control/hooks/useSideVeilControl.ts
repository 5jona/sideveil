import { useBridge } from '@gojona/bridge';
import { useTheme, useToast } from '@gojona/ui';
import { useCallback, useEffect, useRef, useState } from 'react';
import {
  CONFIG_PATH,
  DEFAULT_CONFIG,
  normalizeSideVeilConfig,
  type SideVeilConfig,
} from '../../shared/types.ts';
import {
  shouldPollControlInBackground,
  shouldRefreshOnVisibility,
} from './controlPollingPolicy.ts';
import { useDaemonStatus } from './useDaemonStatus.ts';
import { usePluginsControl } from './usePluginsControl.ts';

/**
 * Coordinates KernelSU Control Plane state: authoritative config loading and serialized
 * atomic saves, daemon status/log polling, plugin registry actions, and `sideveil-ctl` commands.
 */
export function useSideVeilControl(options?: { logsEnabled?: boolean }) {
  const bridge = useBridge();
  const { pushToast } = useToast();
  const { setThemeId, setColorMode } = useTheme();
  const logsEnabledRef = useRef(Boolean(options?.logsEnabled));
  logsEnabledRef.current = Boolean(options?.logsEnabled);

  const [config, setConfigState] = useState<SideVeilConfig>(DEFAULT_CONFIG);
  const [saving, setSaving] = useState(false);
  const [configLoadError, setConfigLoadError] = useState<string | null>(null);

  const pluginsControl = usePluginsControl(bridge, pushToast);
  const { refreshPlugins } = pluginsControl;

  const mountedRef = useRef(true);
  const latestConfigRef = useRef<SideVeilConfig>(DEFAULT_CONFIG);
  const pendingSaveConfigRef = useRef<SideVeilConfig | null>(null);
  const saveQueueRef = useRef<Promise<void>>(Promise.resolve());
  const inFlightSavesRef = useRef(0);
  const configLoadErrorRef = useRef<string | null>(null);
  configLoadErrorRef.current = configLoadError;
  const isLoadedRef = useRef(false);
  const lastVisibilityRefreshRef = useRef(0);
  const saveTimerRef = useRef<number | null>(null);
  const loadConfigRef = useRef<() => Promise<void>>(async () => {});
  const refreshStatusRef = useRef<() => Promise<void>>(async () => {});

  const daemon = useDaemonStatus({
    bridge,
    pushToast,
    logsEnabledRef,
    mountedRef,
    onConfigReloaded: useCallback(() => loadConfigRef.current(), []),
  });
  const { refreshStatus, refreshLogs } = daemon;
  refreshStatusRef.current = refreshStatus;
  const busyAction = daemon.ctlBusyAction ?? pluginsControl.pluginBusyAction;

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
            void refreshStatusRef.current();
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
    [bridge, pushToast],
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
      const errMsg = '读取配置文件失败，已停止自动覆写以保护现有配置';
      configLoadErrorRef.current = errMsg;
      if (mountedRef.current) setConfigLoadError(errMsg);
    }
  }, [bridge, enqueueAtomicWrite, setColorMode, setThemeId]);
  loadConfigRef.current = loadConfigFromDisk;

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
    mountedRef.current = true;
    const handlePageHide = () => flushPendingSave();
    window.addEventListener('pagehide', handlePageHide);
    return () => {
      mountedRef.current = false;
      window.removeEventListener('pagehide', handlePageHide);
      flushPendingSave();
    };
  }, [flushPendingSave]);

  useEffect(() => {
    const loadInitial = async () => {
      lastVisibilityRefreshRef.current = Date.now();
      await Promise.all([loadConfigFromDisk(), refreshStatus(), refreshPlugins()]);
    };
    void loadInitial();

    const handleVisibilityChange = () => {
      if (document.hidden) {
        flushPendingSave();
      } else if (mountedRef.current) {
        const now = Date.now();
        if (!shouldRefreshOnVisibility(lastVisibilityRefreshRef.current, now)) return;
        lastVisibilityRefreshRef.current = now;
        void refreshStatus();
        if (logsEnabledRef.current) void refreshLogs();
        void refreshPlugins();
      }
    };
    document.addEventListener('visibilitychange', handleVisibilityChange);

    const timer = shouldPollControlInBackground(bridge.env)
      ? window.setInterval(() => {
          if (!document.hidden && mountedRef.current) {
            void refreshStatus();
            void refreshPlugins();
          }
        }, 3000)
      : null;

    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      if (timer !== null) window.clearInterval(timer);
    };
  }, [
    bridge.env,
    flushPendingSave,
    loadConfigFromDisk,
    refreshLogs,
    refreshPlugins,
    refreshStatus,
  ]);

  return {
    bridge,
    config,
    status: daemon.status,
    logs: daemon.logs,
    ...pluginsControl,
    busyAction,
    lastPingMs: daemon.lastPingMs,
    saving,
    configLoadError,
    daemonOfflineError: daemon.daemonOfflineError,
    updateConfig,
    loadConfigFromDisk,
    refreshStatus,
    refreshLogs,
    runCtl: daemon.runCtl,
    clearLogs: daemon.clearLogs,
  };
}
