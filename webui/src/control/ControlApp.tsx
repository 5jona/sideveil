import { PanelRightClose, PanelRightOpen, SlidersHorizontal } from 'lucide-react';
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
  normalizeConfig,
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

  const [config, setConfig] = useState<SideveilConfig>(DEFAULT_CONFIG);
  const [status, setStatus] = useState<DaemonStatus | null>(null);
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [lastPingMs, setLastPingMs] = useState<number | null>(null);
  const [saving, setSaving] = useState(false);

  const hydratedRef = useRef(false);
  const saveTimerRef = useRef<number | null>(null);

  const refreshStatus = useCallback(async () => {
    const res = await bridge.exec(`${shellQuote(CTL_BIN)} status`);
    if (res.errno === 0 && res.stdout.trim()) {
      try {
        const parsed = JSON.parse(res.stdout.trim()) as DaemonStatus;
        setStatus(parsed);
      } catch {
        // Ignore transient partial output.
      }
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

  useEffect(() => {
    let active = true;
    const loadInitial = async () => {
      const raw = await bridge.readFile(CONFIG_PATH);
      if (raw) {
        try {
          const parsed = normalizeConfig(JSON.parse(raw) as Partial<SideveilConfig>);
          if (active) {
            setConfig(parsed);
            setThemeId(parsed.panel.themeId);
            setColorMode(parsed.panel.colorMode);
          }
        } catch {
          // Fall back to default configuration when disk JSON is invalid.
        }
      } else {
        await bridge.writeFileAtomic(CONFIG_PATH, JSON.stringify(DEFAULT_CONFIG, null, 2));
      }
      if (active) {
        hydratedRef.current = true;
        await refreshStatus();
        await refreshLogs();
      }
    };
    void loadInitial();
    const timer = window.setInterval(() => {
      void refreshStatus();
    }, 3000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [bridge, refreshLogs, refreshStatus, setColorMode, setThemeId]);

  const updateConfig = useCallback(
    (updater: (prev: SideveilConfig) => SideveilConfig) => {
      setConfig((prev) => {
        const next = normalizeConfig(updater(prev));
        if (hydratedRef.current) {
          if (saveTimerRef.current !== null) {
            window.clearTimeout(saveTimerRef.current);
          }
          setSaving(true);
          saveTimerRef.current = window.setTimeout(async () => {
            const res = await bridge.writeFileAtomic(CONFIG_PATH, JSON.stringify(next, null, 2));
            setSaving(false);
            if (res.errno !== 0) {
              pushToast(`配置保存失败：${res.stderr || '请检查权限'}`, 'warning');
            } else {
              void refreshStatus();
            }
          }, 220);
        }
        return next;
      });
    },
    [bridge, pushToast, refreshStatus],
  );

  const runCtl = async (action: string, label: string) => {
    setBusyAction(action);
    const t0 = performance.now();
    try {
      const res = await bridge.exec(`${shellQuote(CTL_BIN)} ${action}`);
      const dt = Math.max(1, Math.round(performance.now() - t0));
      if (res.errno === 0) {
        if (action === 'ping') {
          setLastPingMs(dt);
        }
        pushToast(`${label}成功（${dt} ms）`, 'success');
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
          <Badge variant={status?.hostConnected ? 'success' : 'warning'} dot pulse={status?.hostConnected}>
            {status?.hostConnected ? 'Host 已连接' : '等待 Host'}
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
