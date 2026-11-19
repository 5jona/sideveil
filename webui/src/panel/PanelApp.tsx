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
  LogConsole,
  MetricTile,
  SettingRow,
  Switch,
  ThemeProvider,
  ThemeSwitcher,
  ToastProvider,
  useTheme,
  useToast,
  type LogEntry,
  type ThemeSyncPayload,
} from '@pochita/ui';
import { cn } from '@pochita/ui';
import {
  DEFAULT_CONFIG,
  normalizeConfig,
  type DaemonStatus,
  type PingResult,
  type SideveilConfig,
} from '../shared/types.ts';

function nowTimeStr(): string {
  const d = new Date();
  return d.toTimeString().slice(0, 8) + '.' + String(d.getMilliseconds()).padStart(3, '0');
}

function PanelWorkbench({
  eventsLog,
  onAppendLog,
  onClearLogs,
}: {
  eventsLog: readonly LogEntry[];
  onAppendLog: (level: LogEntry['level'], message: string) => void;
  onClearLogs: () => void;
}) {
  const bridge = useBridge();
  const { pushToast } = useToast();
  const { themeId, colorMode, resolvedScheme, setThemeId, setColorMode } = useTheme();

  const [status, setStatus] = useState<DaemonStatus | null>(null);
  const [nativeBlurEnabled, setNativeBlurEnabled] = useState(true);
  const [lastPingMs, setLastPingMs] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [imeText, setImeText] = useState('');
  const [imeFocused, setImeFocused] = useState(false);

  const syncingFromDaemonRef = useRef(false);
  const isNativeHost = bridge.env === 'host';

  const refreshStatus = useCallback(async () => {
    try {
      const res = await bridge.invoke<DaemonStatus>('daemon.status');
      if (res && typeof res.pid === 'number') {
        setStatus(res);
      }
    } catch (err) {
      onAppendLog('warn', `daemon.status failed: ${err instanceof Error ? err.message : String(err)}`);
    }
  }, [bridge, onAppendLog]);

  useEffect(() => {
    void refreshStatus();

    const unsubScope = bridge.onHostEvent('scope.changed', (payload) => {
      const p = payload as { foregroundPackage?: string; active?: boolean } | undefined;
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

    const unsubConfig = bridge.onHostEvent('config.updated', (payload) => {
      const p = payload as
        | { config?: Partial<SideveilConfig>; foregroundPackage?: string; scopeActive?: boolean }
        | undefined;
      if (!p?.config) return;
      const cfg = normalizeConfig(p.config);
      syncingFromDaemonRef.current = true;
      setThemeId(cfg.panel.themeId);
      setColorMode(cfg.panel.colorMode);
      setNativeBlurEnabled(cfg.panel.nativeBlurEnabled);
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
      window.setTimeout(() => {
        syncingFromDaemonRef.current = false;
      }, 60);
    });

    const unsubVis = bridge.onHostEvent('panel.visibility', (payload) => {
      const p = payload as { visible?: boolean; state?: string } | undefined;
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
  }, [bridge, onAppendLog, refreshStatus, setColorMode, setThemeId]);

  const handlePing = async () => {
    setBusy(true);
    const t0 = performance.now();
    try {
      const res = await bridge.invoke<PingResult>('daemon.ping');
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

  const patchAppearanceToDaemon = useCallback(
    async (nextBlur: boolean) => {
      try {
        await bridge.invoke('config.patchAppearance', {
          themeId,
          colorMode,
          nativeBlurEnabled: nextBlur,
        });
      } catch (err) {
        onAppendLog(
          'warn',
          `config.patchAppearance failed: ${err instanceof Error ? err.message : String(err)}`,
        );
      }
    },
    [bridge, colorMode, onAppendLog, themeId],
  );

  // Sync theme/colorMode initiated inside the panel back to the daemon config.
  const prevPanelStyleRef = useRef({ themeId, colorMode });
  useEffect(() => {
    const prev = prevPanelStyleRef.current;
    if (prev.themeId === themeId && prev.colorMode === colorMode) {
      return;
    }
    prevPanelStyleRef.current = { themeId, colorMode };
    if (!syncingFromDaemonRef.current) {
      void patchAppearanceToDaemon(nativeBlurEnabled);
    }
  }, [themeId, colorMode, nativeBlurEnabled, patchAppearanceToDaemon]);

  const handleToggleBlur = (checked: boolean) => {
    setNativeBlurEnabled(checked);
    void bridge.sendContainerAction({
      type: 'syncTheme',
      payload: {
        themeId,
        colorScheme: resolvedScheme,
        wantsNativeBlur: checked,
        cornerRadiusPx: 58,
      },
    });
    void patchAppearanceToDaemon(checked);
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
              size="sm"
              variant="ghost"
              aria-label="收起侧边栏"
              className="size-7 px-0"
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
            <ThemeSwitcher compact />
            <div className="h-px bg-[var(--glass-divider,var(--border-hairline))]" />
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
            <input
              id="ime-focus-input"
              name="imeFocusInput"
              type="text"
              value={imeText}
              onChange={(ev) => setImeText(ev.target.value)}
              onFocus={() => handleImeFocus(true)}
              onBlur={() => handleImeFocus(false)}
              placeholder="点击输入中文测试..."
              aria-label="中文输入法测试框"
              className="h-9 w-full rounded-[var(--radius-control)] bg-[var(--surface-elevated)] px-3 text-xs text-[var(--text-primary)] placeholder:text-[var(--text-secondary)] focus:outline-2 focus:outline-[var(--accent)]"
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
  const lastSyncKeyRef = useRef<string>('');

  const handleThemeSync = useCallback(
    (payload: ThemeSyncPayload) => {
      const key = `${payload.themeId}:${payload.colorScheme}:${payload.wantsNativeBlur}:${payload.cornerRadiusPx}`;
      if (lastSyncKeyRef.current === key) return;
      lastSyncKeyRef.current = key;
      void bridge.sendContainerAction({ type: 'syncTheme', payload });
    },
    [bridge],
  );

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
        />
      </ToastProvider>
    </ThemeProvider>
  );
}

export function PanelApp() {
  const mockConfigRef = useRef<SideveilConfig>(DEFAULT_CONFIG);
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
      onMockRpc: async (method, params) => {
        if (method === 'daemon.ping') {
          return { pong: true, daemonUptimeMs: 184_200, timestampMs: Date.now() };
        }
        if (method === 'daemon.status') {
          return {
            pid: 14820,
            uid: 1000,
            uptimeMs: 184_200,
            configVersion: mockConfigRef.current.version,
            enabled: true,
            scopeActive: true,
            foregroundPackage: 'me.weishu.kernelsu',
            hostPackage: 'pochita.sideveil.host',
            hostConnected: true,
            hostPid: 14905,
            overlayPermissionGranted: true,
            crossWindowBlurSupported: true,
            gestureMonitorActive: true,
            taskListenerActive: true,
            panelState: 'open',
            screenWidthPx: 3392,
            screenHeightPx: 2400,
            density: 2.625,
            wsPort: 41928,
          } satisfies DaemonStatus;
        }
        if (method === 'config.get') {
          return mockConfigRef.current;
        }
        if (method === 'config.patchAppearance') {
          const patch = (params ?? {}) as Partial<SideveilConfig['panel']>;
          mockConfigRef.current = {
            ...mockConfigRef.current,
            panel: { ...mockConfigRef.current.panel, ...patch },
          };
          return mockConfigRef.current;
        }
        return { ok: true };
      },
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
