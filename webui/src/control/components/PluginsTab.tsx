import { Button, SegmentedControl, type SegmentedControlOption, StatusBanner } from '@gojona/ui';
import { Boxes, FolderUp, GlobeLock, Plus, RefreshCw, Sparkles } from 'lucide-react';
import { lazy, Suspense, useMemo, useState } from 'react';
import type {
  PluginArchiveInspection,
  PluginRuntimeDescriptor,
  PluginsSnapshot,
} from '../../shared/types.ts';
import type {
  CreateWebEmbedInput,
  PluginEmbedConfigInput,
  PluginGrantCapabilitiesInput,
  PluginInspectArchiveInput,
  PluginInstallInput,
  PluginScopeOverrideInput,
} from '../hooks/usePluginsControl.ts';
import {
  groupPluginsByCategory,
  needsPermissionAttention,
  type PluginCategory,
} from '../pluginMeta.ts';
import { CreateWebEmbedCard } from './CreateWebEmbedCard.tsx';
import { PluginCard } from './PluginCard.tsx';
import { PluginCategoryContent } from './PluginCategoryContent.tsx';
import type { PluginDetailTab } from './PluginDetailSheet.tsx';

const PluginDetailSheet = lazy(() =>
  import('./PluginDetailSheet.tsx').then((m) => ({ default: m.PluginDetailSheet })),
);
const PluginImportModal = lazy(() =>
  import('./PluginImportModal.tsx').then((m) => ({ default: m.PluginImportModal })),
);

export interface PluginsTabProps {
  snapshot: PluginsSnapshot | null;
  loadError: string | null;
  busyAction: string | null;
  foregroundPackage: string;
  currentWifiSsid?: string | null;
  networkLoaded?: boolean;
  networkLoading?: boolean;
  onDetectWifiSsid?: () => Promise<string | null>;
  onReload: () => Promise<void>;
  onSetEnabled: (pluginId: string, enabled: boolean) => Promise<void>;
  onSetPriority: (pluginId: string, priority: number) => Promise<void>;
  onSetScopeOverride: (pluginId: string, override: PluginScopeOverrideInput) => Promise<void>;
  onSetDevUrl: (pluginId: string, url: string | null) => Promise<void>;
  onGrantCapabilities: (input: PluginGrantCapabilitiesInput) => Promise<boolean>;
  onInspectArchive: (input: PluginInspectArchiveInput) => Promise<PluginArchiveInspection>;
  onInstallPlugin: (input: PluginInstallInput) => Promise<boolean>;
  onFetchDaemonLogs: (pluginId: string, limit?: number) => Promise<string[]>;
  onReloadWebView: (pluginId: string) => Promise<boolean>;
  onCreateWebEmbed: (input: CreateWebEmbedInput) => Promise<boolean>;
  onSetEmbedConfig: (pluginId: string, input: PluginEmbedConfigInput) => Promise<boolean>;
  onUninstallPlugin: (pluginId: string) => Promise<boolean>;
  onSelectPlugin: (pluginId: string) => Promise<void>;
}

export function PluginsTab({
  snapshot,
  loadError,
  busyAction,
  foregroundPackage,
  currentWifiSsid,
  networkLoaded = false,
  networkLoading = false,
  onDetectWifiSsid,
  onReload,
  onSetEnabled,
  onSetPriority,
  onSetScopeOverride,
  onSetDevUrl,
  onGrantCapabilities,
  onInspectArchive,
  onInstallPlugin,
  onFetchDaemonLogs,
  onReloadWebView,
  onCreateWebEmbed,
  onSetEmbedConfig,
  onUninstallPlugin,
  onSelectPlugin,
}: PluginsTabProps) {
  const [category, setCategory] = useState<PluginCategory>('installed');
  const [showCreateEmbed, setShowCreateEmbed] = useState(false);
  const [showImportModal, setShowImportModal] = useState(false);
  const [detailState, setDetailState] = useState<{
    pluginId: string;
    initialTab?: PluginDetailTab;
  } | null>(null);

  const plugins = snapshot?.plugins ?? [];
  const activePluginId = snapshot?.activePluginId ?? null;
  const matchedIds = snapshot?.matchedPluginIds ?? [];
  const fgPkg = snapshot?.foregroundPackage || foregroundPackage;

  const grouped = useMemo(() => groupPluginsByCategory(plugins), [plugins]);
  const pendingCount = useMemo(() => plugins.filter(needsPermissionAttention).length, [plugins]);
  const activeDetailPlugin = useMemo(
    () => (detailState ? (plugins.find((p) => p.id === detailState.pluginId) ?? null) : null),
    [detailState, plugins],
  );

  const categoryOptions: SegmentedControlOption<PluginCategory>[] = [
    {
      value: 'web-embed',
      label: `网页面板 (${grouped.webEmbeds.length})`,
      icon: <GlobeLock className="size-3.5" />,
    },
    {
      value: 'installed',
      label: `已安装插件 (${grouped.installed.length})`,
      icon: <Boxes className="size-3.5" />,
    },
    {
      value: 'builtin',
      label: `内置组件 (${grouped.builtins.length})`,
      icon: <Sparkles className="size-3.5" />,
    },
  ];

  const ensureNetworkLoaded = () => {
    if (!networkLoaded && !networkLoading && onDetectWifiSsid) {
      void onDetectWifiSsid();
    }
  };

  const openCreateEmbed = () => {
    setShowCreateEmbed(true);
    ensureNetworkLoaded();
  };

  const renderCard = (plugin: PluginRuntimeDescriptor) => (
    <PluginCard
      key={plugin.id}
      plugin={plugin}
      isActive={plugin.id === activePluginId}
      isMatched={matchedIds.includes(plugin.id)}
      isBusy={busyAction === `plugin-${plugin.id}`}
      onSetEnabled={onSetEnabled}
      onOpenDetail={(pluginId, initialTab) => {
        if (plugin.origin === 'web-embed' || plugin.embed || initialTab === 'embed') {
          ensureNetworkLoaded();
        }
        setDetailState({ pluginId, initialTab });
      }}
    />
  );

  return (
    <div className="flex flex-1 flex-col gap-4">
      {loadError ? (
        <StatusBanner
          tone="danger"
          title="读取插件列表失败"
          description={loadError}
          action={
            <Button size="sm" variant="secondary" onClick={() => void onReload()}>
              重试
            </Button>
          }
        />
      ) : null}

      {pendingCount > 0 ? (
        <StatusBanner
          tone="warning"
          title={`有 ${pendingCount} 个插件等待权限或后台进程审查`}
          description="请点击对应插件卡片上的「审查权限」，确认后方可启用高危能力或原生守护进程。"
        />
      ) : null}

      <div className="flex flex-wrap items-center justify-between gap-2.5 rounded-[var(--radius-panel)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-3">
        <SegmentedControl
          size="sm"
          ariaLabel="插件分类"
          options={categoryOptions}
          value={category}
          onValueChange={(next) => {
            setCategory(next);
            if (next !== 'web-embed') setShowCreateEmbed(false);
          }}
        />
        <div className="flex flex-wrap items-center gap-2">
          {category === 'web-embed' ? (
            <Button
              size="sm"
              variant="primary"
              aria-label={showCreateEmbed ? '收起添加网页面板表单' : '添加网页面板'}
              disabled={busyAction !== null}
              onClick={() => {
                if (showCreateEmbed) {
                  setShowCreateEmbed(false);
                } else {
                  openCreateEmbed();
                }
              }}
            >
              <Plus className="size-3.5" />
              {showCreateEmbed ? '收起表单' : '添加网页面板'}
            </Button>
          ) : null}
          {category === 'installed' ? (
            <Button
              size="sm"
              variant="primary"
              aria-label="导入第三方插件"
              disabled={busyAction !== null}
              onClick={() => setShowImportModal(true)}
            >
              <FolderUp className="size-3.5" />
              导入插件 (.svp / 目录)
            </Button>
          ) : null}
          <Button
            size="sm"
            variant="secondary"
            aria-label="重新扫描插件目录"
            disabled={busyAction !== null}
            onClick={() => void onReload()}
          >
            <RefreshCw className="size-3.5" />
            重新扫描
          </Button>
        </div>
      </div>

      {showCreateEmbed && category === 'web-embed' ? (
        <CreateWebEmbedCard
          isBusy={busyAction === 'create-web-embed'}
          currentWifiSsid={currentWifiSsid}
          networkLoading={networkLoading}
          onDetectWifiSsid={onDetectWifiSsid}
          onCreate={onCreateWebEmbed}
          onCancel={() => setShowCreateEmbed(false)}
        />
      ) : null}

      <PluginCategoryContent
        category={category}
        grouped={grouped}
        renderCard={renderCard}
        onStartCreateEmbed={openCreateEmbed}
        onStartImport={() => setShowImportModal(true)}
      />

      {showImportModal ? (
        <Suspense fallback={null}>
          <PluginImportModal
            isBusy={busyAction === 'inspect-archive' || busyAction === 'install-plugin'}
            onInspect={onInspectArchive}
            onInstall={onInstallPlugin}
            onClose={() => setShowImportModal(false)}
          />
        </Suspense>
      ) : null}

      {activeDetailPlugin ? (
        <Suspense fallback={null}>
          <PluginDetailSheet
            plugin={activeDetailPlugin}
            initialTab={detailState?.initialTab}
            isActive={activeDetailPlugin.id === activePluginId}
            isMatched={matchedIds.includes(activeDetailPlugin.id)}
            isBusy={busyAction === `plugin-${activeDetailPlugin.id}`}
            foregroundPackage={fgPkg}
            currentWifiSsid={currentWifiSsid}
            networkLoading={networkLoading}
            onDetectWifiSsid={onDetectWifiSsid}
            onClose={() => setDetailState(null)}
            onSetEnabled={onSetEnabled}
            onSetPriority={onSetPriority}
            onSetScopeOverride={onSetScopeOverride}
            onSetDevUrl={onSetDevUrl}
            onSetEmbedConfig={onSetEmbedConfig}
            onGrantCapabilities={onGrantCapabilities}
            onFetchDaemonLogs={onFetchDaemonLogs}
            onReloadWebView={onReloadWebView}
            onUninstallPlugin={onUninstallPlugin}
            onSelectPlugin={onSelectPlugin}
          />
        </Suspense>
      ) : null}
    </div>
  );
}
