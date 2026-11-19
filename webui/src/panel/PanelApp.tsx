import { Activity, RefreshCw, Sparkles, X, Zap } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  BridgeProvider,
  type BridgeOptions,
  type ContainerAction,
  useBridge,
} from '@pochita/bridge';
import {
  AppShell,
  Badge,
  Button,
  Card,
  Input,
  LogConsole,
  MetricTile,
  SettingRow,
  Switch,
  ThemeProvider,
  ThemeSwitcher,
  ToastProvider,
  useTheme,
  useToast,
  type ColorMode,
  type LogEntry,
  type ThemeId,
  type ThemeSyncPayload,
} from '@pochita/ui';
import { cn } from '@pochita/ui';
import {
  normalizeConfig,
  SIDEVEIL_EVENTS,
  SIDEVEIL_METHODS,
  type ConfigUpdatedEventPayload,
  type DaemonStatus,
  type PanelVisibilityEventPayload,
  type PingResult,
  type ScopeChangedEventPayload,
  type SideveilConfig,
} from '../shared/types.ts';
import { createMockPanelRpcHandler } from './mockPanelBridge.ts';

function nowTimeStr(): string {
  const d = new Date();
  return d.toTimeString().slice(0, 8) + '.' + String(d.getMilliseconds()).padStart(3, '0');
}

function PanelWorkbench({
  eventsLog,
  onAppendLog,
  onClearLogs,
  onCornerRadiusDpChange,
  nativeBlurEnabled,
  onNativeBlurEnabledChange,
}: {
  eventsLog: readonly LogEntry[];
  onAppendLog: (level: LogEntry['level'], message: string) => void;
  onClearLogs: () => void;
  onCornerRadiusDpChange: (dp: number) => void;
  nativeBlurEnabled: boolean;
  onNativeBlurEnabledChange: (enabled: boolean) => void;
}) {
  const bridge = useBridge();
  const { pushToast } = useToast();
  const { themeId, colorMode, setThemeId, setColorMode } = useTheme();

  const [status, setStatus] = useState<DaemonStatus | null>(null);
  const [lastPingMs, setLastPingMs] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [imeText, setImeText] = useState('');
  const [imeFocused, setImeFocused] = useState(false);

  const isNativeHost = bridge.env === 'host';

  const refreshStatus = useCallback(async () => {
    try {
      const res = await bridge.invoke<DaemonStatus>(SIDEVEIL_METHODS.DAEMON_STATUS);
      if (res && typeof res.pid === 'number') {
        setStatus(res);
      }
    } catch (err) {
      onAppendLog('warn', `daemon.status failed: ${err instanceof Error ? err.message : String(err)}`);
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
        onNativeBlurEnabledChange(cfg.panel.nativeBlurEnabled);
        onCornerRadiusDpChange(cfg.panel.cornerRadiusDp);
      } catch (err) {
        onAppendLog('warn', `config.get failed: ${err instanceof Error ? err.message : String(err)}`);
      }
    };
    void loadDaemonConfig();

    const unsubScope = bridge.onHostEvent(SIDEVEIL_EVENTS.SCOPE_CHANGED, (payload) => {
      const p = payload as ScopeChangedEventPayload | undefined;
      if (p?.foregroundPackage) {
        setStatus((prev) =>
          prev
            ? {
              ...prev,
              foregroundPackage: p.foregroundPackage!,
              scopeActive: Boolean(p.active),
            }
            : prev,
        );
        onAppendLog('info', `scope.changed -> ${p.foregroundPackage} (active=${Boolean(p.active)})`);
      }
    });

    const unsubConfig = bridge.onHostEvent(SIDEVEIL_EVENTS.CONFIG_UPDATED, (payload) => {
      const p = payload as ConfigUpdatedEventPayload | undefined;
      if (!p?.config) return;
      const cfg = normalizeConfig(p.config);
      setThemeId(cfg.panel.themeId);
      setColorMode(cfg.panel.colorMode);
      onNativeBlurEnabledChange(cfg.panel.nativeBlurEnabled);
      onCornerRadiusDpChange(cfg.panel.cornerRadiusDp);
      if (typeof p.foregroundPackage === 'string') {
        setStatus((prev) =>
          prev
            ? {
              ...prev,
              foregroundPackage: p.foregroundPackage!,
              scopeActive: Boolean(p.scopeActive),
            }
            : prev,
        );
      }
      onAppendLog('info', `config.updated (theme=${cfg.panel.themeId}, blur=${cfg.panel.nativeBlurEnabled})`);
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
  }, [
    bridge,
    onAppendLog,
    onCornerRadiusDpChange,
    onNativeBlurEnabledChange,
    refreshStatus,
    setColorMode,
    setThemeId,
  ]);

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
    } catch (err) {
      onAppendLog('error', `daemon.ping failed: ${err instanceof Error ? err.message : String(err)}`);
      pushToast('Ping 调用失败', 'warning');
    } finally {
      setBusy(false);
    }
  };

  const handleThemePatch = (nextThemeId: ThemeId, nextColorMode: ColorMode) => {
    void bridge
      .invoke(SIDEVEIL_METHODS.CONFIG_PATCH_APPEARANCE, {
        themeId: nextThemeId,
        colorMode: nextColorMode,
      })
      .catch((err) => {
        onAppendLog(
          'warn',
          `config.patchAppearance failed: ${err instanceof Error ? err.message : String(err)}`,
        );
      });
  };

  const handleToggleBlur = (checked: boolean) => {
    onNativeBlurEnabledChange(checked);
    void bridge
      .invoke(SIDEVEIL_METHODS.CONFIG_PATCH_APPEARANCE, {
        themeId,
        colorMode,
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
    void bridge.sendContainerAction({ type: 'requestImeFocus', focused });
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
        className={isNativeHost ? 'h-full min-h-0 flex-1' : 'min-h-[calc(100dvh-1rem)] flex-1 rounded-[var(--radius-panel)]'}
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
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label="收起侧边栏"
              onClick={() => void bridge.sendContainerAction({ type: 'dismiss' })}
            >
              <X className="size-4" />
            </Button>
          </div>
        }
        footer={
          <div className="flex items-center justify-between gap-2 text-[11px]">
            <span className="tabular-nums">
              UID {status?.uid ?? 1000} · :{status?.wsPort ?? 0}
            </span>
            <span>
              {nativeBlurEnabled && status?.crossWindowBlurSupported
                ? '实时毛玻璃'
                : '磨砂底板'}
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
              hint={status ? `PID ${status.hostPid ?? '-'}` : '等待测速'}
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
              onThemeChange={(nextTheme) => handleThemePatch(nextTheme, colorMode)}
              onColorModeChange={(nextMode) => handleThemePatch(themeId, nextMode)}
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
  const bridge = useBridge();
  const [cornerRadiusDp, setCornerRadiusDp] = useState<number>(22);
  const [nativeBlurEnabled, setNativeBlurEnabled] = useState<boolean>(true);
  const lastSyncKeyRef = useRef<string>('');
  const lastThemePayloadRef = useRef<ThemeSyncPayload | null>(null);

  const handleThemeSync = useCallback(
    (payload: ThemeSyncPayload) => {
      lastThemePayloadRef.current = payload;
      const cornerRadiusPx = Math.round(cornerRadiusDp * (window.devicePixelRatio || 1));
      const syncPayload: ThemeSyncPayload = {
        ...payload,
        wantsNativeBlur: nativeBlurEnabled && payload.wantsNativeBlur,
        cornerRadiusPx,
      };
      const key = `${syncPayload.themeId}:${syncPayload.colorScheme}:${syncPayload.wantsNativeBlur}:${syncPayload.cornerRadiusPx}`;
      if (lastSyncKeyRef.current === key) return;
      lastSyncKeyRef.current = key;
      void bridge.sendContainerAction({ type: 'syncTheme', payload: syncPayload });
    },
    [bridge, cornerRadiusDp, nativeBlurEnabled],
  );

  useEffect(() => {
    if (!lastThemePayloadRef.current) return;
    const cornerRadiusPx = Math.round(cornerRadiusDp * (window.devicePixelRatio || 1));
    const syncPayload: ThemeSyncPayload = {
      ...lastThemePayloadRef.current,
      wantsNativeBlur: nativeBlurEnabled && lastThemePayloadRef.current.wantsNativeBlur,
      cornerRadiusPx,
    };
    const key = `${syncPayload.themeId}:${syncPayload.colorScheme}:${syncPayload.wantsNativeBlur}:${syncPayload.cornerRadiusPx}`;
    if (lastSyncKeyRef.current === key) return;
    lastSyncKeyRef.current = key;
    void bridge.sendContainerAction({ type: 'syncTheme', payload: syncPayload });
  }, [bridge, cornerRadiusDp, nativeBlurEnabled]);

  return (
    <ThemeProvider
      defaultTheme="coloros-glass"
      defaultColorMode="system"
      defaultSurfaceMode="native-glass"
      onThemeSync={handleThemeSync}
    >
      <ToastProvider>
        <PanelWorkbench
          eventsLog={eventsLog}
          onAppendLog={onAppendLog}
          onClearLogs={onClearLogs}
          onCornerRadiusDpChange={setCornerRadiusDp}
          nativeBlurEnabled={nativeBlurEnabled}
          onNativeBlurEnabledChange={setNativeBlurEnabled}
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
      message: 'Panel diagnostic container initialized',
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
    <BridgeProvider options={bridgeOptions}>
      <PanelThemedContainer
        eventsLog={eventsLog}
        onAppendLog={appendLog}
        onClearLogs={clearLogs}
      />
    </BridgeProvider>
  );
}
