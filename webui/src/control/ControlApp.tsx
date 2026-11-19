import { AlertCircle, AlertTriangle, PanelRightClose, PanelRightOpen, SlidersHorizontal } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { BridgeProvider, shellQuote, useBridge, type BridgeOptions } from '@pochita/bridge';
import {
  AppShell,
  Badge,
  Button,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  ThemeProvider,
  ToastProvider,
  useToast,
  useTheme,
  type LogEntry,
} from '@pochita/ui';
import {
  CONFIG_PATH,
  CTL_BIN,
  DEFAULT_CONFIG,
  normalizeSideveilConfig,
  type CtlCommand,
  type DaemonStatus,
  type SideveilConfig,
} from '../shared/types.ts';
import { AppearanceScopeTab } from './components/AppearanceScopeTab.tsx';
import { DiagnosticsTab } from './components/DiagnosticsTab.tsx';
import { OverviewEdgeTab } from './components/OverviewEdgeTab.tsx';
import { handleMockControlExec } from './mockControlBridge.ts';

function parseDaemonLogLines(lines: readonly string[]): LogEntry[] {
  return lines.map((line, idx) => {
    const levelMatch = line.match(/\[(I|W|E)\]/);
    const level =
      levelMatch?.[1] === 'E' ? 'error' : levelMatch?.[1] === 'W' ? 'warn' : 'info';
    const timePart = line.slice(0, 12).trim() || '--:--:--';
    return {
      id: `dlog-${idx}-${timePart}`,
      timestamp: timePart,
      level,
      message: line.length > 13 ? line.slice(13).trim() : line,
    };
  });
}

function ControlWorkbench() {
  const bridge = useBridge();
  const { pushToast } = useToast();
  const { setThemeId, setColorMode } = useTheme();

  const [config, setConfigState] = useState<SideveilConfig>(DEFAULT_CONFIG);
  const [status, setStatus] = useState<DaemonStatus | null>(null);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [busyAction, setBusyAction] = useState<CtlCommand | null>(null);
  const [lastPingMs, setLastPingMs] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);
  const [configLoadError, setConfigLoadError] = useState<string | null>(null);
  const [daemonOfflineError, setDaemonOfflineError] = useState<string | null>(null);

  const latestConfigRef = useRef<SideveilConfig>(DEFAULT_CONFIG);
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
          const parsed = JSON.parse(raw) as Partial<SideveilConfig>;
          const normalized = normalizeSideveilConfig(parsed);
          latestConfigRef.current = normalized;
          setConfigState(normalized);
          setThemeId(normalized.panel.themeId);
          setColorMode(normalized.panel.colorMode);
          setConfigLoadError(null);
          configLoadErrorRef.current = null;
          isLoadedRef.current = true;
        } catch {
          setConfigLoadError('读取配置文件失败，已停止自动覆写以保护现有配置');
          configLoadErrorRef.current = '读取配置文件失败，已停止自动覆写以保护现有配置';
        }
      }
    } catch {
      setConfigLoadError('读取配置文件失败，已停止自动覆写以保护现有配置');
      configLoadErrorRef.current = '读取配置文件失败，已停止自动覆写以保护现有配置';
    }
  }, [bridge, setColorMode, setThemeId]);

  const persistConfig = useCallback(
    async (nextConfig: SideveilConfig) => {
      if (configLoadErrorRef.current !== null) {
        setSaving(false);
        return;
      }
      setSaving(true);
      try {
        const res = await bridge.writeFileAtomic(
          CONFIG_PATH,
          JSON.stringify(nextConfig, null, 2),
        );
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

  const updateConfig = useCallback(
    (updater: (prev: SideveilConfig) => SideveilConfig) => {
      if (!isLoadedRef.current || configLoadErrorRef.current !== null) {
        return;
      }
      const next = normalizeSideveilConfig(updater(latestConfigRef.current));
      latestConfigRef.current = next;
      setConfigState(next);
      setSaving(true);
      if (saveTimerRef.current !== null) {
        window.clearTimeout(saveTimerRef.current);
      }
      saveTimerRef.current = window.setTimeout(async () => {
        saveTimerRef.current = null;
        await persistConfig(next);
      }, 220);
    },
    [persistConfig],
  );

  useEffect(() => {
    return () => {
      if (saveTimerRef.current !== null) {
        window.clearTimeout(saveTimerRef.current);
        saveTimerRef.current = null;
      }
    };
  }, []);

  useEffect(() => {
    let active = true;
    const loadInitial = async () => {
      await loadConfigFromDisk();
      if (active) {
        await refreshStatus();
        await refreshLogs();
      }
    };
    void loadInitial();

    const handleVisibilityChange = () => {
      if (!document.hidden) {
        void refreshStatus();
        void refreshLogs();
      }
    };
    document.addEventListener('visibilitychange', handleVisibilityChange);

    const timer = window.setInterval(() => {
      if (!document.hidden) {
        void refreshStatus();
      }
    }, 3000);

    return () => {
      active = false;
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      window.clearInterval(timer);
    };
  }, [loadConfigFromDisk, refreshLogs, refreshStatus]);

  const runCtl = async (action: CtlCommand, label: string) => {
    setBusyAction(action);
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
      setBusyAction(null);
    }
  };

  const isPanelOpen = status?.panelState === 'open';
  const envLabel =
    bridge.env === 'ksu' ? 'KernelSU WebUI' : bridge.env === 'host' ? 'Host WebView' : 'Browser 预览';

  return (
    <AppShell
      variant="page"
      title="Sideveil 控制台"
      subtitle="全局边缘手势与悬浮窗宿主"
      headerLeading={
        <div className="flex size-9 items-center justify-center rounded-[var(--radius-control)] bg-[var(--accent-soft)] text-[var(--accent)]">
          <SlidersHorizontal className="size-4" />
        </div>
      }
      headerTrailing={
        <>
          <Badge
            variant={status?.hostConnected ? 'success' : daemonOfflineError ? 'danger' : 'warning'}
            dot
            pulse={status?.hostConnected}
          >
            {status?.hostConnected
              ? 'Host 已连接'
              : daemonOfflineError
                ? 'Daemon 离线'
                : '等待 Host'}
          </Badge>
          <Badge variant="neutral">{saving ? '正在写入...' : envLabel}</Badge>
          <Button
            size="sm"
            variant={isPanelOpen ? 'secondary' : 'primary'}
            disabled={busyAction !== null}
            onClick={() => runCtl(isPanelOpen ? 'close' : 'open', isPanelOpen ? '收起侧边栏' : '弹出侧边栏')}
          >
            {isPanelOpen ? (
              <>
                <PanelRightClose className="size-3.5" />
                收起侧边栏
              </>
            ) : (
              <>
                <PanelRightOpen className="size-3.5" />
                弹出侧边栏
              </>
            )}
          </Button>
        </>
      }
      footer={
        <div className="flex flex-wrap items-center justify-between gap-2">
          <span>配置：{CONFIG_PATH}</span>
          <span>修改自动保存并热更新</span>
        </div>
      }
    >
      {configLoadError ? (
        <div className="mb-4 flex items-center justify-between gap-3 rounded-[var(--radius-control)] border border-[color-mix(in_srgb,var(--warning)_35%,transparent)] bg-[color-mix(in_srgb,var(--warning)_12%,transparent)] p-3 text-xs text-[var(--text-primary)]">
          <div className="flex items-center gap-2">
            <AlertTriangle className="size-4 shrink-0 text-[var(--warning)]" />
            <span>{configLoadError}</span>
          </div>
          <Button
            size="sm"
            variant="secondary"
            onClick={() => void loadConfigFromDisk()}
          >
            重试读取
          </Button>
        </div>
      ) : null}

      {daemonOfflineError ? (
        <div className="mb-4 flex items-center justify-between gap-3 rounded-[var(--radius-control)] border border-[color-mix(in_srgb,var(--danger)_35%,transparent)] bg-[color-mix(in_srgb,var(--danger)_12%,transparent)] p-3 text-xs text-[var(--text-primary)]">
          <div className="flex items-center gap-2">
            <AlertCircle className="size-4 shrink-0 text-[var(--danger)]" />
            <span>Sideveil 守护进程未运行：{daemonOfflineError}</span>
          </div>
          <Button
            size="sm"
            variant="secondary"
            onClick={() => void refreshStatus()}
          >
            重试连接
          </Button>
        </div>
      ) : null}

      <Tabs defaultValue="overview" className="w-full">
        <TabsList>
          <TabsTrigger value="overview">概览与手势</TabsTrigger>
          <TabsTrigger value="appearance">外观与范围</TabsTrigger>
          <TabsTrigger value="diagnostics">诊断与日志</TabsTrigger>
        </TabsList>

        <TabsContent value="overview">
          <OverviewEdgeTab config={config} status={status} onChange={updateConfig} />
        </TabsContent>

        <TabsContent value="appearance">
          <AppearanceScopeTab
            config={config}
            foregroundPackage={status?.foregroundPackage ?? ''}
            crossWindowBlurSupported={status?.crossWindowBlurSupported ?? null}
            onChange={updateConfig}
          />
        </TabsContent>

        <TabsContent value="diagnostics">
          <DiagnosticsTab
            status={status}
            logs={logs}
            busyAction={busyAction}
            lastPingMs={lastPingMs}
            onPing={() => void runCtl('ping', 'Daemon Ping')}
            onReloadConfig={() => void runCtl('reload', '重载配置')}
            onRestartHost={() => void runCtl('restart-host', '重启 Host 服务')}
            onRefreshLogs={() => void refreshLogs()}
            onClearLogs={() => setLogs([])}
          />
        </TabsContent>
      </Tabs>
    </AppShell>
  );
}

export function ControlApp() {
  const bridgeOptions = useMemo<BridgeOptions>(
    () => ({
      mockNamespace: 'sideveil-control',
      onMockExec: handleMockControlExec,
    }),
    [],
  );

  return (
    <BridgeProvider options={bridgeOptions}>
      <ThemeProvider defaultTheme="coloros-glass" defaultColorMode="system" defaultSurfaceMode="standalone">
        <ToastProvider>
          <ControlWorkbench />
        </ToastProvider>
      </ThemeProvider>
    </BridgeProvider>
  );
}
