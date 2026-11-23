import {
  useBackPress,
  useContainerAction,
  usePluginBackendState,
  usePluginStorage,
  useSideVeilPlugin,
} from '@pochita/sideveil-sdk';
import { type ColorMode, type LogEntry, type ThemeId, useToast } from '@pochita/ui';
import { useCallback, useEffect, useState } from 'react';
import {
  type DaemonStatus,
  type PanelVisibilityEventPayload,
  type PingResult,
  type ScopeChangedEventPayload,
  SIDEVEIL_EVENTS,
  SIDEVEIL_METHODS,
} from '../../shared/types.ts';
import { useSyncedPluginTheme } from '../../shared/useSyncedPluginTheme.ts';

/** Options passed to {@link useDiagnosticPanel}. */
export interface UseDiagnosticPanelOptions {
  onAppendLog: (level: LogEntry['level'], message: string) => void;
  onHydrated: () => void;
}

const INITIAL_STORED_PREF: { lastCheckedAt?: number } = {};

/**
 * Coordinates the built-in diagnostic panel state: daemon status/ping checks,
 * live theme/blur sync, IME focus probe, plugin storage test, and container actions.
 */
export function useDiagnosticPanel({ onAppendLog, onHydrated }: UseDiagnosticPanelOptions) {
  const { bridge, pluginId } = useSideVeilPlugin();
  const backendState = usePluginBackendState();
  const { dismiss, requestImeFocus } = useContainerAction();
  const { pushToast } = useToast();

  const [status, setStatus] = useState<DaemonStatus | null>(null);
  const [lastPingMs, setLastPingMs] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [imeText, setImeText] = useState('');
  const [imeFocused, setImeFocused] = useState(false);
  const [nativeBlurEnabled, setNativeBlurEnabled] = useState(true);

  const { isNativeHost } = useSyncedPluginTheme({
    onConfigLoaded: (cfg) => {
      setNativeBlurEnabled(cfg.panel.nativeBlurEnabled);
      onHydrated();
    },
    onConfigUpdated: (cfg, payload) => {
      setNativeBlurEnabled(cfg.panel.nativeBlurEnabled);
      onHydrated();
      const fgPkg = payload.foregroundPackage;
      if (typeof fgPkg === 'string') {
        setStatus((prev) =>
          prev
            ? {
                ...prev,
                foregroundPackage: fgPkg,
                scopeActive: Boolean(payload.scopeActive),
              }
            : prev,
        );
      }
      onAppendLog(
        'info',
        `config.updated (theme=${cfg.panel.themeId}, blur=${cfg.panel.nativeBlurEnabled})`,
      );
    },
    onError: (msg) => {
      onAppendLog('warn', msg);
      onHydrated();
    },
  });

  // Backed by jailed /data/system/sideveil/data/sideveil.diagnostic/preferences.json
  const { value: storedPref, setValue: setStoredPref } = usePluginStorage<{
    lastCheckedAt?: number;
  }>('preferences.json', INITIAL_STORED_PREF);

  // Intercept back navigation when IME focus is active
  useBackPress(() => {
    if (imeFocused) {
      setImeFocused(false);
      void requestImeFocus(false);
      onAppendLog('info', '返回按键拦截 -> 已解除输入法聚焦');
      pushToast('返回键：已退出输入法', 'info');
    }
  }, imeFocused);

  const refreshStatus = useCallback(async () => {
    try {
      const res = await bridge.invoke<DaemonStatus>(SIDEVEIL_METHODS.DAEMON_STATUS);
      if (res && typeof res.pid === 'number') {
        setStatus(res);
      }
    } catch (err) {
      onAppendLog(
        'warn',
        `daemon.status failed: ${err instanceof Error ? err.message : String(err)}`,
      );
    }
  }, [bridge, onAppendLog]);

  useEffect(() => {
    void refreshStatus();

    const unsubScope = bridge.onHostEvent(SIDEVEIL_EVENTS.SCOPE_CHANGED, (payload) => {
      const p = payload as ScopeChangedEventPayload | undefined;
      const fgPkg = p?.foregroundPackage;
      if (fgPkg) {
        setStatus((prev) =>
          prev
            ? {
                ...prev,
                foregroundPackage: fgPkg,
                scopeActive: Boolean(p.active),
              }
            : prev,
        );
        onAppendLog('info', `scope.changed -> ${fgPkg} (active=${Boolean(p.active)})`);
      }
    });

    const unsubVis = bridge.onHostEvent(SIDEVEIL_EVENTS.PANEL_VISIBILITY, (payload) => {
      const p = payload as PanelVisibilityEventPayload | undefined;
      onAppendLog('info', `panel.visibility -> ${p?.state ?? String(p?.visible)}`);
      if (p?.visible) {
        void refreshStatus();
      }
    });

    return () => {
      unsubScope();
      unsubVis();
    };
  }, [bridge, onAppendLog, refreshStatus]);

  const handlePing = useCallback(async () => {
    setBusy(true);
    const t0 = performance.now();
    try {
      const res = await bridge.invoke<PingResult>(SIDEVEIL_METHODS.DAEMON_PING);
      const rtt = Math.max(1, Math.round(performance.now() - t0));
      setLastPingMs(rtt);
      onAppendLog(
        'info',
        `daemon.ping pong=${Boolean(res?.pong)} rtt=${rtt}ms uptime=${Math.round((res?.daemonUptimeMs ?? 0) / 1000)}s`,
      );
      pushToast(`双向 RPC 响应 ${rtt} ms`, 'success');
      void setStoredPref({ lastCheckedAt: Date.now() }).catch(() => {});
    } catch (err) {
      onAppendLog(
        'error',
        `daemon.ping failed: ${err instanceof Error ? err.message : String(err)}`,
      );
      pushToast('Ping 调用失败', 'warning');
    } finally {
      setBusy(false);
    }
  }, [bridge, onAppendLog, pushToast, setStoredPref]);

  const handleThemePatch = useCallback(
    (patch: { themeId?: ThemeId; colorMode?: ColorMode }) => {
      void bridge.invoke(SIDEVEIL_METHODS.CONFIG_PATCH_APPEARANCE, patch).catch((err) => {
        onAppendLog(
          'warn',
          `config.patchAppearance failed: ${err instanceof Error ? err.message : String(err)}`,
        );
      });
    },
    [bridge, onAppendLog],
  );

  const handleToggleBlur = useCallback(
    (checked: boolean) => {
      setNativeBlurEnabled(checked);
      void bridge
        .invoke(SIDEVEIL_METHODS.CONFIG_PATCH_APPEARANCE, {
          nativeBlurEnabled: checked,
        })
        .catch((err) => {
          onAppendLog(
            'warn',
            `config.patchAppearance failed: ${err instanceof Error ? err.message : String(err)}`,
          );
        });
      onAppendLog('info', `nativeBlurEnabled -> ${checked}`);
    },
    [bridge, onAppendLog],
  );

  const handleImeFocus = useCallback(
    (focused: boolean) => {
      setImeFocused(focused);
      void requestImeFocus(focused);
      onAppendLog('info', `requestImeFocus(${focused})`);
    },
    [onAppendLog, requestImeFocus],
  );

  return {
    pluginId,
    isNativeHost,
    backendState,
    status,
    lastPingMs,
    busy,
    imeText,
    setImeText,
    imeFocused,
    nativeBlurEnabled,
    storedPref,
    dismiss,
    refreshStatus,
    handlePing,
    handleThemePatch,
    handleToggleBlur,
    handleImeFocus,
  };
}
