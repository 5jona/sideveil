import { type BridgeOptions, BridgeProvider } from '@gojona/bridge';
import {
  AppShell,
  Badge,
  Button,
  ErrorBoundary,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  ThemeProvider,
  ToastProvider,
  useToast,
} from '@gojona/ui';
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
import { useDebugControl } from './hooks/useDebugControl.ts';
import { useNetworkContextControl } from './hooks/useNetworkContextControl.ts';
import { useSideVeilControl } from './hooks/useSideVeilControl.ts';
import { handleMockControlExec } from './mockControlBridge.ts';

const CONTROL_TAB_ORDER = ['overview', 'appearance', 'plugins', 'diagnostics'] as const;

function ControlWorkbench() {
  const { pushToast } = useToast();
  const {
    bridge,
    config,
    status,
    logs,
    pluginsSnapshot,
    pluginsLoadError,
    auditReport,
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
    setPluginScopeOverride,
    setPluginDevUrl,
    grantPluginCapabilities,
    inspectPluginArchive,
    installPlugin,
    fetchPluginDaemonLogs,
    reloadPluginWebView,
    createWebEmbed,
    setEmbedConfig,
    uninstallPlugin,
    refreshPluginAudit,
    selectPlugin,
    runCtl,
    clearLogs,
  } = useSideVeilControl();

  const { networkContext, networkLoading, networkError, refreshNetworkContext } =
    useNetworkContextControl(bridge);

  const {
    cdpStatus,
    uiSnapshot,
    inspectCapturedAt,
    debugBusy,
    debugError,
    setWebViewDebugging,
    inspectUi,
    resetFrameMetrics,
  } = useDebugControl({
    bridge,
    pushToast,
    onConfigSynced: loadConfigFromDisk,
    onRefreshLogs: refreshLogs,
  });

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
      title="SideVeil 控制台"
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
      bodyClassName="flex max-w-none flex-col px-0"
      footer={
        <div className="flex flex-wrap items-center justify-between gap-2">
          <span>配置：{CONFIG_PATH}</span>
          <span>当前前台：{status?.foregroundPackage || '未知'}</span>
        </div>
      }
    >
      {configLoadError ? (
        <div className="mx-auto mb-3 w-full max-w-5xl px-4 sm:px-6">
          <div className="flex items-center justify-between gap-3 rounded-[var(--radius-control)] border border-[color-mix(in_oklab,var(--warning)_35%,transparent)] bg-[color-mix(in_oklab,var(--warning)_12%,transparent)] p-3 text-xs text-[var(--text-primary)]">
            <div className="flex items-center gap-2">
              <AlertTriangle className="size-4 shrink-0 text-[var(--warning)]" />
              <span>{configLoadError}</span>
            </div>
            <Button size="sm" variant="secondary" onClick={() => void loadConfigFromDisk()}>
              重试加载
            </Button>
          </div>
        </div>
      ) : null}

      {daemonOfflineError ? (
        <div className="mx-auto mb-3 w-full max-w-5xl px-4 sm:px-6">
          <div className="flex items-center justify-between gap-3 rounded-[var(--radius-control)] border border-[color-mix(in_oklab,var(--danger)_35%,transparent)] bg-[color-mix(in_oklab,var(--danger)_12%,transparent)] p-3 text-xs text-[var(--text-primary)]">
            <div className="flex items-center gap-2">
              <AlertCircle className="size-4 shrink-0 text-[var(--danger)]" />
              <span>SideVeil 守护进程未运行：{daemonOfflineError}</span>
            </div>
            <Button size="sm" variant="secondary" onClick={() => void refreshStatus()}>
              重试连接
            </Button>
          </div>
        </div>
      ) : null}

      <Tabs
        defaultValue="overview"
        tabOrder={CONTROL_TAB_ORDER}
        className="flex w-full flex-1 flex-col"
      >
        <div className="mx-auto w-full max-w-5xl px-4 sm:px-6">
          <TabsList>
            <TabsTrigger value="overview">概览与手势</TabsTrigger>
            <TabsTrigger value="appearance">外观与范围</TabsTrigger>
            <TabsTrigger value="plugins">插件管理</TabsTrigger>
            <TabsTrigger value="diagnostics">诊断与日志</TabsTrigger>
          </TabsList>
        </div>

        <TabsContent value="overview" className="flex flex-1 flex-col">
          <div className="mx-auto w-full max-w-5xl flex-1 px-4 sm:px-6">
            <OverviewEdgeTab config={config} status={status} onChange={updateConfig} />
          </div>
        </TabsContent>

        <TabsContent value="appearance" className="flex flex-1 flex-col">
          <div className="mx-auto w-full max-w-5xl flex-1 px-4 sm:px-6">
            <AppearanceScopeTab
              config={config}
              foregroundPackage={status?.foregroundPackage ?? ''}
              crossWindowBlurSupported={status?.crossWindowBlurSupported ?? null}
              onChange={updateConfig}
            />
          </div>
        </TabsContent>

        <TabsContent value="plugins" className="flex flex-1 flex-col">
          <div className="mx-auto flex w-full max-w-5xl flex-1 flex-col px-4 sm:px-6">
            <PluginsTab
              snapshot={pluginsSnapshot}
              loadError={pluginsLoadError}
              busyAction={busyAction}
              foregroundPackage={status?.foregroundPackage ?? ''}
              onReload={reloadPlugins}
              onSetEnabled={setPluginEnabled}
              onSetPriority={setPluginPriority}
              onSetScopeOverride={setPluginScopeOverride}
              onSetDevUrl={setPluginDevUrl}
              onGrantCapabilities={grantPluginCapabilities}
              onInspectArchive={inspectPluginArchive}
              onInstallPlugin={installPlugin}
              onFetchDaemonLogs={fetchPluginDaemonLogs}
              onReloadWebView={reloadPluginWebView}
              onCreateWebEmbed={createWebEmbed}
              onSetEmbedConfig={setEmbedConfig}
              onUninstallPlugin={uninstallPlugin}
              onSelectPlugin={selectPlugin}
            />
          </div>
        </TabsContent>

        <TabsContent value="diagnostics" className="flex flex-1 flex-col">
          <div className="mx-auto w-full max-w-5xl flex-1 px-4 sm:px-6">
            <DiagnosticsTab
              bridge={bridge}
              config={config}
              status={status}
              logs={logs}
              auditReport={auditReport}
              busyAction={busyAction}
              lastPingMs={lastPingMs}
              cdpStatus={cdpStatus}
              uiSnapshot={uiSnapshot}
              inspectCapturedAt={inspectCapturedAt}
              debugBusy={debugBusy}
              debugError={debugError}
              networkContext={networkContext}
              networkLoading={networkLoading}
              networkError={networkError}
              onPing={() => void runCtl('ping', 'Daemon Ping')}
              onReloadConfig={() => void runCtl('reload', '重载配置')}
              onRestartHost={() => void runCtl('restart-host', '重启 Host 服务')}
              onRefreshLogs={() => void refreshLogs()}
              onClearLogs={() => void clearLogs()}
              onRefreshAudit={refreshPluginAudit}
              onSetWebViewDebugging={(enabled) => void setWebViewDebugging(enabled)}
              onInspectUi={() => void inspectUi()}
              onResetFrameMetrics={() => void resetFrameMetrics()}
              onRefreshNetwork={() => void refreshNetworkContext()}
            />
          </div>
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
          <ErrorBoundary title="SideVeil 控制台渲染异常">
            <ControlWorkbench />
          </ErrorBoundary>
        </ToastProvider>
      </ThemeProvider>
    </BridgeProvider>
  );
}
