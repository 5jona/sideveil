import { type BridgeOptions, type ContainerAction, useContainerThemeSync } from '@pochita/bridge';
import {
  BUILTIN_DIAG_PLUGIN_ID,
  SideveilPluginProvider,
  useBackPress,
  useContainerAction,
  usePluginBackendState,
  usePluginStorage,
  useSideveilPlugin,
} from '@pochita/sideveil-sdk';
import {
  AppShell,
  Badge,
  Button,
  Card,
  type ColorMode,
  cn,
  Input,
  LogConsole,
  type LogEntry,
  MetricTile,
  SettingRow,
  Switch,
  type ThemeId,
  ThemeProvider,
  ThemeSwitcher,
  ToastProvider,
  useTheme,
  useToast,
} from '@pochita/ui';
import { Activity, RefreshCw, Sparkles, X, Zap } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  type ConfigUpdatedEventPayload,
  type DaemonStatus,
  normalizeConfig,
  type PanelVisibilityEventPayload,
  type PingResult,
  type ScopeChangedEventPayload,
  SIDEVEIL_EVENTS,
  SIDEVEIL_METHODS,
  type SideveilConfig,
} from '../shared/types.ts';
import { DIAGNOSTIC_PLUGIN_MANIFEST } from './manifest.ts';
import { createMockPanelRpcHandler } from './mockPanelBridge.ts';

function nowTimeStr(): string {
  const d = new Date();
  return `${d.toTimeString().slice(0, 8)}.${String(d.getMilliseconds()).padStart(3, '0')}`;
}

function PanelWorkbench({
  eventsLog,
  onAppendLog,
  onClearLogs,
  onHydrated,
}: {
  eventsLog: readonly LogEntry[];
  onAppendLog: (level: LogEntry['level'], message: string) => void;
  onClearLogs: () => void;
  onHydrated: () => void;
}) {
  const { bridge, pluginId } = useSideveilPlugin();
  const backendState = usePluginBackendState();
  const { dismiss, requestImeFocus } = useContainerAction();
  const { pushToast } = useToast();
  const { setThemeId, setColorMode } = useTheme();

  const [status, setStatus] = useState<DaemonStatus | null>(null);
  const [lastPingMs, setLastPingMs] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [imeText, setImeText] = useState('');
  const [imeFocused, setImeFocused] = useState(false);
  const [nativeBlurEnabled, setNativeBlurEnabled] = useState(true);

  // Backed by jailed /data/system/sideveil/data/sideveil.diagnostic/preferences.json
  const { value: storedPref, setValue: setStoredPref } = usePluginStorage<{
    lastCheckedAt?: number;
  }>('preferences.json', {});

  // Intercept back navigation when IME focus is active
  useBackPress(() => {
    if (imeFocused) {
      setImeFocused(false);
      void requestImeFocus(false);
      onAppendLog('info', '返回按键拦截 -> 已解除输入法聚焦');
      pushToast('返回键：已退出输入法', 'info');
    }
  }, imeFocused);

  const isNativeHost = bridge.env === 'host';

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

    const loadDaemonConfig = async () => {
      try {
        const res = await bridge.invoke<unknown>(SIDEVEIL_METHODS.CONFIG_GET);
        const cfg = normalizeConfig(res as Partial<SideveilConfig> | null);
        setThemeId(cfg.panel.themeId);
        setColorMode(cfg.panel.colorMode);
        setNativeBlurEnabled(cfg.panel.nativeBlurEnabled);
      } catch (err) {
        onAppendLog(
          'warn',
          `config.get failed: ${err instanceof Error ? err.message : String(err)}`,
        );
      } finally {
        onHydrated();
      }
    };
    void loadDaemonConfig();

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

    const unsubConfig = bridge.onHostEvent(SIDEVEIL_EVENTS.CONFIG_UPDATED, (payload) => {
      const p = payload as ConfigUpdatedEventPayload | undefined;
      if (!p?.config) return;
      const cfg = normalizeConfig(p.config);
      setThemeId(cfg.panel.themeId);
      setColorMode(cfg.panel.colorMode);
      setNativeBlurEnabled(cfg.panel.nativeBlurEnabled);
      onHydrated();
      const fgPkg = p.foregroundPackage;
      if (typeof fgPkg === 'string') {
        setStatus((prev) =>
          prev
            ? {
                ...prev,
                foregroundPackage: fgPkg,
                scopeActive: Boolean(p.scopeActive),
              }
            : prev,
        );
      }
      onAppendLog(
        'info',
        `config.updated (theme=${cfg.panel.themeId}, blur=${cfg.panel.nativeBlurEnabled})`,
      );
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
      unsubConfig();
      unsubVis();
    };
  }, [bridge, onAppendLog, onHydrated, refreshStatus, setColorMode, setThemeId]);

  const handlePing = async () => {
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
  };

  const handleThemePatch = (patch: { themeId?: ThemeId; colorMode?: ColorMode }) => {
    void bridge.invoke(SIDEVEIL_METHODS.CONFIG_PATCH_APPEARANCE, patch).catch((err) => {
      onAppendLog(
        'warn',
        `config.patchAppearance failed: ${err instanceof Error ? err.message : String(err)}`,
      );
    });
  };

  const handleToggleBlur = (checked: boolean) => {
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
  };

  const handleImeFocus = (focused: boolean) => {
    setImeFocused(focused);
    void requestImeFocus(focused);
    onAppendLog('info', `requestImeFocus(${focused})`);
  };

  const fgPkg = status?.foregroundPackage || 'me.weishu.kernelsu';
  const shortPkg = fgPkg.split('.').slice(-1)[0] || fgPkg;

  return (
    <div
      className={cn(
        'flex w-full flex-col',
        isNativeHost ? 'h-dvh overflow-hidden' : 'mx-auto min-h-dvh max-w-[360px] p-2',
      )}
    >
      <AppShell
        variant="drawer"
        title="Sideveil"
        className={
          isNativeHost
            ? 'h-full min-h-0 flex-1'
            : 'min-h-[calc(100dvh-1rem)] flex-1 rounded-[var(--radius-panel)]'
        }
        headerLeading={
          <div className="flex size-7 items-center justify-center rounded-[var(--radius-control)] bg-[var(--accent-soft)] text-[var(--accent)]">
            <Sparkles className="size-3.5" />
          </div>
        }
        headerTrailing={
          <div className="flex items-center gap-1.5">
            <Badge variant={isNativeHost ? 'success' : 'accent'} dot>
              {isNativeHost ? '在线' : '预览'}
            </Badge>
            {backendState !== 'none' ? (
              <Badge variant={backendState === 'online' ? 'success' : 'neutral'}>
                {backendState === 'online' ? '后端就绪' : '后端离线'}
              </Badge>
            ) : null}
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label="收起侧边栏"
              onClick={() => void dismiss()}
            >
              <X className="size-4" />
            </Button>
          </div>
        }
        footer={
          <div className="flex items-center justify-between gap-2 text-[11px]">
            <span className="tabular-nums">
              {pluginId} · UID {status?.uid ?? 1000}
            </span>
            <span>
              {nativeBlurEnabled && status?.crossWindowBlurSupported ? '实时毛玻璃' : '磨砂底板'}
            </span>
          </div>
        }
      >
        <div className="space-y-2.5">
          <div className="grid grid-cols-2 gap-2">
            <MetricTile
              label="当前前台"
              value={shortPkg}
              hint={fgPkg}
              statusLabel={status?.scopeActive === false ? '未激活' : '已命中'}
              statusVariant={status?.scopeActive === false ? 'neutral' : 'success'}
              icon={<Activity className="size-3.5" />}
            />
            <MetricTile
              label="链路延迟"
              value={lastPingMs !== null ? lastPingMs : '--'}
              unit={lastPingMs !== null ? 'ms' : undefined}
              hint={
                storedPref?.lastCheckedAt
                  ? `上次: ${new Date(storedPref.lastCheckedAt).toLocaleTimeString()}`
                  : status
                    ? `PID ${status.hostPid ?? '-'}`
                    : '等待测速'
              }
              statusLabel={lastPingMs !== null ? '正常' : '待测'}
              statusVariant={lastPingMs !== null ? 'accent' : 'neutral'}
              icon={<Zap className="size-3.5" />}
            />
          </div>

          <div className="grid grid-cols-2 gap-2">
            <Button size="sm" variant="primary" disabled={busy} onClick={() => void handlePing()}>
              <Zap className="size-3.5" />
              双向 Ping
            </Button>
            <Button
              size="sm"
              variant="secondary"
              disabled={busy}
              onClick={() => void refreshStatus()}
            >
              <RefreshCw className="size-3.5" />
              刷新状态
            </Button>
          </div>

          <Card className="space-y-2.5 p-3">
            <ThemeSwitcher
              compact
              onThemeChange={(nextTheme) => handleThemePatch({ themeId: nextTheme })}
              onColorModeChange={(nextMode) => handleThemePatch({ colorMode: nextMode })}
            />
            <div className="pochita-divider" />
            <SettingRow
              variant="plain"
              title="跨窗毛玻璃"
              control={
                <Switch
                  checked={nativeBlurEnabled}
                  onCheckedChange={handleToggleBlur}
                  aria-label="跨窗毛玻璃"
                />
              }
            />
          </Card>

          <Card className="space-y-2 p-3">
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs font-medium text-[var(--text-primary)]">输入法聚焦</span>
              <Badge variant={imeFocused ? 'accent' : 'neutral'}>
                {imeFocused ? '已聚焦' : '待命'}
              </Badge>
            </div>
            <Input
              id="ime-focus-input"
              name="imeFocusInput"
              size="md"
              className="bg-[var(--surface-elevated)]"
              value={imeText}
              onChange={(ev) => setImeText(ev.target.value)}
              onFocus={() => handleImeFocus(true)}
              onBlur={() => handleImeFocus(false)}
              placeholder="点击输入中文测试..."
              aria-label="中文输入法测试框"
            />
            {imeText ? (
              <div className="rounded-[var(--radius-control)] bg-[var(--surface-elevated)] px-2.5 py-1.5 text-[11px] text-[var(--text-primary)]">
                回显：{imeText}
              </div>
            ) : null}
          </Card>

          <LogConsole
            title="事件日志"
            entries={eventsLog}
            onClear={onClearLogs}
            maxHeightClassName="max-h-36"
          />
        </div>
      </AppShell>
    </div>
  );
}

function PanelThemedContainer({
  eventsLog,
  onAppendLog,
  onClearLogs,
}: {
  eventsLog: readonly LogEntry[];
  onAppendLog: (level: LogEntry['level'], message: string) => void;
  onClearLogs: () => void;
}) {
  const [hydrated, setHydrated] = useState(false);
  const markHydrated = useCallback(() => setHydrated(true), []);
  const handleThemeSync = useContainerThemeSync({ includeAppearanceHints: false });

  return (
    <ThemeProvider
      defaultTheme="coloros-glass"
      defaultColorMode="system"
      defaultSurfaceMode="native-glass"
      syncEnabled={hydrated}
      onThemeSync={handleThemeSync}
    >
      <ToastProvider>
        <PanelWorkbench
          eventsLog={eventsLog}
          onAppendLog={onAppendLog}
          onClearLogs={onClearLogs}
          onHydrated={markHydrated}
        />
      </ToastProvider>
    </ThemeProvider>
  );
}

export function PanelApp() {
  const [eventsLog, setEventsLog] = useState<LogEntry[]>(() => [
    {
      id: 'init-1',
      timestamp: nowTimeStr(),
      level: 'info',
      message: '面板诊断插件已就绪',
    },
  ]);

  const appendLog = useCallback((level: LogEntry['level'], message: string) => {
    setEventsLog((prev) => [
      ...prev.slice(-60),
      {
        id: `ev-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
        timestamp: nowTimeStr(),
        level,
        message,
      },
    ]);
  }, []);

  const appendLogRef = useRef(appendLog);
  appendLogRef.current = appendLog;

  const bridgeOptions = useMemo<BridgeOptions>(
    () => ({
      allowUrlOverride: false,
      allowedOrigins: [
        `https://${BUILTIN_DIAG_PLUGIN_ID}.plugins.sideveil.local`,
        'https://appassets.androidplatform.net',
      ],
      mockNamespace: 'sideveil-panel',
      onMockRpc: createMockPanelRpcHandler(),
      onMockContainerAction: (action: ContainerAction) => {
        appendLogRef.current('info', `container.action -> ${action.type}`);
      },
    }),
    [],
  );

  const clearLogs = useCallback(() => setEventsLog([]), []);

  return (
    <SideveilPluginProvider
      pluginId={BUILTIN_DIAG_PLUGIN_ID}
      manifest={DIAGNOSTIC_PLUGIN_MANIFEST}
      bridgeOptions={bridgeOptions}
    >
      <PanelThemedContainer eventsLog={eventsLog} onAppendLog={appendLog} onClearLogs={clearLogs} />
    </SideveilPluginProvider>
  );
}
