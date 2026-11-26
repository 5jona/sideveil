import { type BridgeOptions, BridgeProvider, type MockExecHandler } from '@gojona/bridge';
import {
  AppShell,
  BottomNav,
  Button,
  ErrorBoundary,
  Tabs,
  TabsContent,
  TabsTrigger,
  ThemeProvider,
  ToastProvider,
  useToast,
} from '@gojona/ui';
import {
  AlertCircle,
  AlertTriangle,
  Boxes,
  Palette,
  SlidersHorizontal,
  Terminal,
} from 'lucide-react';
import { lazy, Suspense, useCallback, useMemo, useState } from 'react';
import { ControlHeaderActions } from './components/ControlHeaderActions.tsx';
import { OverviewEdgeTab } from './components/OverviewEdgeTab.tsx';
import { useDebugControl } from './hooks/useDebugControl.ts';
import { useNetworkContextControl } from './hooks/useNetworkContextControl.ts';
import { useSideVeilControl } from './hooks/useSideVeilControl.ts';

const AppearanceScopeTab = lazy(() =>
  import('./components/AppearanceScopeTab.tsx').then((m) => ({ default: m.AppearanceScopeTab })),
);
const PluginsTab = lazy(() =>
  import('./components/PluginsTab.tsx').then((m) => ({ default: m.PluginsTab })),
);
const DiagnosticsTab = lazy(() =>
  import('./components/DiagnosticsTab.tsx').then((m) => ({ default: m.DiagnosticsTab })),
);

const handleLazyMockControlExec: MockExecHandler = async (cmd) => {
  const { handleMockControlExec } = await import('./mockControlBridge.ts');
  return handleMockControlExec(cmd);
};

const CONTROL_TAB_ORDER = ['overview', 'appearance', 'plugins', 'diagnostics'] as const;
type ControlTab = (typeof CONTROL_TAB_ORDER)[number];

const TabLoadingFallback = () => (
  <div className="flex flex-1 items-center justify-center py-12 text-xs text-[var(--text-secondary)]">
    正在加载模块...
  </div>
);

function ControlWorkbench() {
  const { pushToast } = useToast();
  const [activeTab, setActiveTab] = useState<ControlTab>('overview');
  const [visitedTabs, setVisitedTabs] = useState<Set<ControlTab>>(() => new Set(['overview']));

  const control = useSideVeilControl({ logsEnabled: visitedTabs.has('diagnostics') });
  const { bridge, config, status, busyAction, saving, configLoadError, daemonOfflineError } =
    control;

  const {
    networkContext,
    currentWifiSsid,
    networkLoading,
    networkError,
    refreshNetworkContext,
    detectWifiSsid,
  } = useNetworkContextControl(bridge);

  const debug = useDebugControl({
    bridge,
    pushToast,
    onConfigSynced: control.loadConfigFromDisk,
    onRefreshLogs: control.refreshLogs,
  });

  const handleTabChange = useCallback(
    (nextValue: string) => {
      const nextTab = nextValue as ControlTab;
      setActiveTab(nextTab);
      if (!visitedTabs.has(nextTab)) {
        setVisitedTabs((prev) => new Set(prev).add(nextTab));
        if (nextTab === 'diagnostics') {
          void control.refreshLogs();
        }
      }
      if (nextTab === 'plugins' && !networkLoading) {
        void refreshNetworkContext();
      }
    },
    [control, networkLoading, refreshNetworkContext, visitedTabs],
  );

  const envLabel =
    bridge.env === 'ksu'
      ? 'KernelSU WebUI'
      : bridge.env === 'host'
        ? 'Host WebView'
        : 'Browser 预览';

  return (
    <Tabs
      value={activeTab}
      onValueChange={handleTabChange}
      tabOrder={CONTROL_TAB_ORDER}
      className="h-dvh min-h-0"
    >
      <AppShell
        variant="page"
        viewportMode="fill"
        fullBleed
        headerMode="compact"
        title="SideVeil 控制台"
        headerLeading={
          <div className="flex size-8 items-center justify-center rounded-[var(--radius-control)] bg-[var(--accent-soft)] text-[var(--accent)]">
            <SlidersHorizontal className="size-4" />
          </div>
        }
        headerTrailing={
          <ControlHeaderActions
            status={status}
            daemonOfflineError={daemonOfflineError}
            saving={saving}
            envLabel={envLabel}
            busyAction={busyAction}
            onRunCtl={(action, label) => void control.runCtl(action, label)}
          />
        }
        bodyClassName="flex max-w-none flex-col px-0"
        bottomSlot={
          <BottomNav aria-label="主导航">
            <TabsTrigger value="overview" icon={<SlidersHorizontal className="size-4" />}>
              概览与手势
            </TabsTrigger>
            <TabsTrigger value="appearance" icon={<Palette className="size-4" />}>
              外观与范围
            </TabsTrigger>
            <TabsTrigger value="plugins" icon={<Boxes className="size-4" />}>
              插件管理
            </TabsTrigger>
            <TabsTrigger value="diagnostics" icon={<Terminal className="size-4" />}>
              诊断与日志
            </TabsTrigger>
          </BottomNav>
        }
      >
        {configLoadError ? (
          <div className="mb-3 w-full px-3 sm:px-5">
            <div className="flex items-center justify-between gap-3 rounded-[var(--radius-control)] border border-[color-mix(in_oklab,var(--warning)_35%,transparent)] bg-[color-mix(in_oklab,var(--warning)_12%,transparent)] p-3 text-xs text-[var(--text-primary)]">
              <div className="flex items-center gap-2">
                <AlertTriangle className="size-4 shrink-0 text-[var(--warning)]" />
                <span>{configLoadError}</span>
              </div>
              <Button
                size="sm"
                variant="secondary"
                onClick={() => void control.loadConfigFromDisk()}
              >
                重试加载
              </Button>
            </div>
          </div>
        ) : null}

        {daemonOfflineError ? (
          <div className="mb-3 w-full px-3 sm:px-5">
            <div className="flex items-center justify-between gap-3 rounded-[var(--radius-control)] border border-[color-mix(in_oklab,var(--danger)_35%,transparent)] bg-[color-mix(in_oklab,var(--danger)_12%,transparent)] p-3 text-xs text-[var(--text-primary)]">
              <div className="flex items-center gap-2">
                <AlertCircle className="size-4 shrink-0 text-[var(--danger)]" />
                <span>SideVeil 守护进程未运行：{daemonOfflineError}</span>
              </div>
              <Button size="sm" variant="secondary" onClick={() => void control.refreshStatus()}>
                重试连接
              </Button>
            </div>
          </div>
        ) : null}

        <TabsContent value="overview" className="flex flex-1 flex-col">
          <div className="w-full flex-1 px-3 sm:px-5">
            <OverviewEdgeTab config={config} status={status} onChange={control.updateConfig} />
          </div>
        </TabsContent>

        <TabsContent value="appearance" className="flex flex-1 flex-col">
          <div className="w-full flex-1 px-3 sm:px-5">
            {visitedTabs.has('appearance') ? (
              <Suspense fallback={<TabLoadingFallback />}>
                <AppearanceScopeTab
                  config={config}
                  foregroundPackage={status?.foregroundPackage ?? ''}
                  crossWindowBlurSupported={status?.crossWindowBlurSupported ?? null}
                  onChange={control.updateConfig}
                />
              </Suspense>
            ) : null}
          </div>
        </TabsContent>

        <TabsContent value="plugins" className="flex flex-1 flex-col">
          <div className="flex w-full flex-1 flex-col px-3 sm:px-5">
            {visitedTabs.has('plugins') ? (
              <Suspense fallback={<TabLoadingFallback />}>
                <PluginsTab
                  snapshot={control.pluginsSnapshot}
                  loadError={control.pluginsLoadError}
                  busyAction={busyAction}
                  foregroundPackage={status?.foregroundPackage ?? ''}
                  currentWifiSsid={currentWifiSsid}
                  networkLoaded={networkContext !== null}
                  networkLoading={networkLoading}
                  onDetectWifiSsid={detectWifiSsid}
                  onReload={control.reloadPlugins}
                  onSetEnabled={control.setPluginEnabled}
                  onSetPriority={control.setPluginPriority}
                  onSetScopeOverride={control.setPluginScopeOverride}
                  onSetDevUrl={control.setPluginDevUrl}
                  onGrantCapabilities={control.grantPluginCapabilities}
                  onInspectArchive={control.inspectPluginArchive}
                  onInstallPlugin={control.installPlugin}
                  onFetchDaemonLogs={control.fetchPluginDaemonLogs}
                  onReloadWebView={control.reloadPluginWebView}
                  onCreateWebEmbed={control.createWebEmbed}
                  onSetEmbedConfig={control.setEmbedConfig}
                  onUninstallPlugin={control.uninstallPlugin}
                  onSelectPlugin={control.selectPlugin}
                />
              </Suspense>
            ) : null}
          </div>
        </TabsContent>

        <TabsContent value="diagnostics" className="flex flex-1 flex-col">
          <div className="w-full flex-1 px-3 sm:px-5">
            {visitedTabs.has('diagnostics') ? (
              <Suspense fallback={<TabLoadingFallback />}>
                <DiagnosticsTab
                  bridge={bridge}
                  config={config}
                  status={status}
                  logs={control.logs}
                  auditReport={control.auditReport}
                  busyAction={busyAction}
                  lastPingMs={control.lastPingMs}
                  cdpStatus={debug.cdpStatus}
                  uiSnapshot={debug.uiSnapshot}
                  inspectCapturedAt={debug.inspectCapturedAt}
                  debugBusy={debug.debugBusy}
                  debugError={debug.debugError}
                  networkContext={networkContext}
                  networkLoading={networkLoading}
                  networkError={networkError}
                  onPing={() => void control.runCtl('ping', 'Daemon Ping')}
                  onReloadConfig={() => void control.runCtl('reload', '重载配置')}
                  onRestartHost={() => void control.runCtl('restart-host', '重启 Host 服务')}
                  onRefreshLogs={() => void control.refreshLogs()}
                  onClearLogs={() => void control.clearLogs()}
                  onRefreshAudit={control.refreshPluginAudit}
                  onSetWebViewDebugging={(enabled) => void debug.setWebViewDebugging(enabled)}
                  onInspectUi={() => void debug.inspectUi()}
                  onResetFrameMetrics={() => void debug.resetFrameMetrics()}
                  onRefreshNetwork={() => void refreshNetworkContext()}
                />
              </Suspense>
            ) : null}
          </div>
        </TabsContent>
      </AppShell>
    </Tabs>
  );
}

export function ControlApp() {
  const bridgeOptions = useMemo<BridgeOptions>(
    () => ({
      allowUrlOverride: false,
      mockNamespace: 'sideveil-control',
      onMockExec: handleLazyMockControlExec,
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
