import { type BridgeOptions, BridgeProvider } from '@pochita/bridge';
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
} from '@pochita/ui';
import {
  AlertCircle,
  AlertTriangle,
  PanelRightClose,
  PanelRightOpen,
  SlidersHorizontal,
} from 'lucide-react';
import { useMemo } from 'react';
import { CONFIG_PATH } from '../shared/types.ts';
import { AppearanceScopeTab } from './components/AppearanceScopeTab.tsx';
import { DiagnosticsTab } from './components/DiagnosticsTab.tsx';
import { OverviewEdgeTab } from './components/OverviewEdgeTab.tsx';
import { PluginsTab } from './components/PluginsTab.tsx';
import { useSideveilControl } from './hooks/useSideveilControl.ts';
import { handleMockControlExec } from './mockControlBridge.ts';

function ControlWorkbench() {
  const {
    bridge,
    config,
    status,
    logs,
    pluginsSnapshot,
    busyAction,
    lastPingMs,
    saving,
    configLoadError,
    daemonOfflineError,
    updateConfig,
    loadConfigFromDisk,
    refreshStatus,
    refreshLogs,
    reloadPlugins,
    setPluginEnabled,
    setPluginPriority,
    selectPlugin,
    runCtl,
    clearLogs,
  } = useSideveilControl();

  const isPanelOpen = status?.panelState === 'open';
  const envLabel =
    bridge.env === 'ksu'
      ? 'KernelSU WebUI'
      : bridge.env === 'host'
        ? 'Host WebView'
        : 'Browser 预览';

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
            onClick={() =>
              runCtl(isPanelOpen ? 'close' : 'open', isPanelOpen ? '收起侧边栏' : '弹出侧边栏')
            }
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
        <div className="mb-4 flex items-center justify-between gap-3 rounded-[var(--radius-control)] border border-[color-mix(in_oklab,var(--warning)_35%,transparent)] bg-[color-mix(in_oklab,var(--warning)_12%,transparent)] p-3 text-xs text-[var(--text-primary)]">
          <div className="flex items-center gap-2">
            <AlertTriangle className="size-4 shrink-0 text-[var(--warning)]" />
            <span>{configLoadError}</span>
          </div>
          <Button size="sm" variant="secondary" onClick={() => void loadConfigFromDisk()}>
            重试读取
          </Button>
        </div>
      ) : null}

      {daemonOfflineError ? (
        <div className="mb-4 flex items-center justify-between gap-3 rounded-[var(--radius-control)] border border-[color-mix(in_oklab,var(--danger)_35%,transparent)] bg-[color-mix(in_oklab,var(--danger)_12%,transparent)] p-3 text-xs text-[var(--text-primary)]">
          <div className="flex items-center gap-2">
            <AlertCircle className="size-4 shrink-0 text-[var(--danger)]" />
            <span>Sideveil 守护进程未运行：{daemonOfflineError}</span>
          </div>
          <Button size="sm" variant="secondary" onClick={() => void refreshStatus()}>
            重试连接
          </Button>
        </div>
      ) : null}

      <Tabs defaultValue="overview" className="w-full">
        <TabsList>
          <TabsTrigger value="overview">概览与手势</TabsTrigger>
          <TabsTrigger value="appearance">外观与范围</TabsTrigger>
          <TabsTrigger value="plugins">插件管理</TabsTrigger>
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

        <TabsContent value="plugins">
          <PluginsTab
            snapshot={pluginsSnapshot}
            busyAction={busyAction}
            onReload={reloadPlugins}
            onSetEnabled={setPluginEnabled}
            onSetPriority={setPluginPriority}
            onSelectPlugin={selectPlugin}
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
            onClearLogs={clearLogs}
          />
        </TabsContent>
      </Tabs>
    </AppShell>
  );
}

export function ControlApp() {
  const bridgeOptions = useMemo<BridgeOptions>(
    () => ({
      allowUrlOverride: false,
      mockNamespace: 'sideveil-control',
      onMockExec: handleMockControlExec,
    }),
    [],
  );

  return (
    <BridgeProvider options={bridgeOptions}>
      <ThemeProvider
        defaultTheme="coloros-glass"
        defaultColorMode="system"
        defaultSurfaceMode="standalone"
      >
        <ToastProvider>
          <ControlWorkbench />
        </ToastProvider>
      </ThemeProvider>
    </BridgeProvider>
  );
}
