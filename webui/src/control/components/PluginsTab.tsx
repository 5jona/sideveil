import type { PluginAuditReport, PluginsSnapshotPayload } from '@gojona/sideveil-sdk';
import { Badge, Button, Card, MetricTile } from '@gojona/ui';
import { Boxes, EyeOff, Layers, Plus, RefreshCw, Shield, Sparkles, Wrench } from 'lucide-react';
import { useState } from 'react';
import { isBuiltinTestPlugin, type RuntimeMode } from '../../shared/types.ts';
import type {
  CreateWebEmbedInput,
  PluginEmbedConfigInput,
  PluginScopeOverrideInput,
} from '../hooks/usePluginsControl.ts';
import { CreateWebEmbedCard } from './CreateWebEmbedCard.tsx';
import { PluginAuditDrawer } from './PluginAuditDrawer.tsx';
import { PluginCard } from './PluginCard.tsx';

export interface PluginsTabProps {
  snapshot: PluginsSnapshotPayload | null;
  auditReport: PluginAuditReport | null;
  busyAction: string | null;
  foregroundPackage: string;
  runtimeMode: RuntimeMode;
  runtimeModeBusy?: boolean;
  onSwitchToDebugMode: () => void;
  onReload: () => Promise<void>;
  onSetEnabled: (pluginId: string, enabled: boolean) => Promise<void>;
  onSetPriority: (pluginId: string, priority: number) => Promise<void>;
  onSetScopeOverride: (pluginId: string, override: PluginScopeOverrideInput) => Promise<void>;
  onSetDevUrl: (pluginId: string, url: string | null) => Promise<void>;
  onCreateWebEmbed: (input: CreateWebEmbedInput) => Promise<boolean>;
  onSetEmbedConfig: (pluginId: string, input: PluginEmbedConfigInput) => Promise<boolean>;
  onUninstallPlugin: (pluginId: string) => Promise<boolean>;
  onRefreshAudit: () => Promise<void>;
  onSelectPlugin: (pluginId: string) => Promise<void>;
}

export function PluginsTab({
  snapshot,
  auditReport,
  busyAction,
  foregroundPackage,
  runtimeMode,
  runtimeModeBusy = false,
  onSwitchToDebugMode,
  onReload,
  onSetEnabled,
  onSetPriority,
  onSetScopeOverride,
  onSetDevUrl,
  onCreateWebEmbed,
  onSetEmbedConfig,
  onUninstallPlugin,
  onRefreshAudit,
  onSelectPlugin,
}: PluginsTabProps) {
  const [showAudit, setShowAudit] = useState(false);
  const [showCreateEmbed, setShowCreateEmbed] = useState(false);
  const plugins = snapshot?.plugins ?? [];
  const activePluginId = snapshot?.activePluginId ?? null;
  const matchedIds = snapshot?.matchedPluginIds ?? [];
  const fgPkg = snapshot?.foregroundPackage || foregroundPackage;

  const activePlugin = plugins.find((p) => p.id === activePluginId);
  const hiddenTestCount =
    runtimeMode === 'release' ? plugins.filter((p) => isBuiltinTestPlugin(p.id)).length : 0;

  return (
    <div className="flex flex-col gap-4">
      {runtimeMode === 'release' ? (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-panel)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-3 py-2.5 text-xs">
          <div className="flex flex-wrap items-center gap-2 text-[var(--text-secondary)]">
            <EyeOff className="size-3.5 shrink-0 text-[var(--accent)]" />
            <Badge variant="neutral">正式模式</Badge>
            <span>
              内置测试插件已自动隐藏
              {hiddenTestCount > 0 ? `（共 ${hiddenTestCount} 项，不参与侧边栏匹配）` : ''}
            </span>
          </div>
          <Button
            size="sm"
            variant="secondary"
            aria-label="切换到调试模式"
            disabled={busyAction !== null || runtimeModeBusy}
            onClick={onSwitchToDebugMode}
          >
            <Wrench className="size-3.5" />
            切换到调试模式
          </Button>
        </div>
      ) : null}

      <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
        <MetricTile
          label="已注册插件"
          value={plugins.length}
          hint={`启用: ${plugins.filter((p) => p.enabled).length} · 禁用: ${plugins.filter((p) => !p.enabled).length}`}
          statusLabel={plugins.length > 0 ? '正常' : '无'}
          statusVariant="accent"
          icon={<Boxes className="size-3.5" />}
        />
        <MetricTile
          label="当前激活插件"
          value={activePlugin?.name ?? '无'}
          hint={activePluginId ?? '暂无匹配插件'}
          statusLabel={activePlugin ? '展示中' : '空闲'}
          statusVariant={activePlugin ? 'success' : 'neutral'}
          icon={<Sparkles className="size-3.5" />}
        />
        <MetricTile
          label="前台命中插件"
          value={matchedIds.length}
          hint={fgPkg ? `前台: ${fgPkg}` : '未捕获前台应用'}
          statusLabel={matchedIds.length >= 2 ? '多插件Rail就绪' : '单插件模式'}
          statusVariant={matchedIds.length >= 2 ? 'accent' : 'neutral'}
          icon={<Layers className="size-3.5" />}
        />
      </div>

      <div className="flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-panel)] bg-[var(--surface-elevated)] p-3">
        <div className="flex items-center gap-2">
          <Boxes className="size-4 text-[var(--accent)]" />
          <span className="text-xs font-medium text-[var(--text-primary)]">
            插件目录：/data/system/sideveil/plugins/
          </span>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            size="sm"
            variant="primary"
            aria-label={showCreateEmbed ? '收起新建网页嵌入表单' : '新建网页嵌入'}
            disabled={busyAction !== null}
            onClick={() => setShowCreateEmbed((prev) => !prev)}
          >
            <Plus className="size-3.5" />
            {showCreateEmbed ? '收起表单' : '新建网页嵌入'}
          </Button>
          <Button
            size="sm"
            variant="secondary"
            aria-label={showAudit ? '收起运行时与审计' : '查看运行时与审计'}
            disabled={busyAction !== null}
            onClick={() => {
              const next = !showAudit;
              setShowAudit(next);
              if (next) void onRefreshAudit();
            }}
          >
            <Shield className="size-3.5" />
            {showAudit ? '收起审计' : '运行时与审计'}
          </Button>
          <Button
            size="sm"
            variant="secondary"
            aria-label="重新扫描插件"
            disabled={busyAction !== null}
            onClick={() => void onReload()}
          >
            <RefreshCw className="size-3.5" />
            重新扫描插件
          </Button>
        </div>
      </div>

      {showCreateEmbed ? (
        <CreateWebEmbedCard
          isBusy={busyAction === 'create-web-embed'}
          onCreate={onCreateWebEmbed}
          onCancel={() => setShowCreateEmbed(false)}
        />
      ) : null}

      {showAudit ? (
        <PluginAuditDrawer
          report={auditReport}
          loading={busyAction === 'audit'}
          onRefresh={onRefreshAudit}
        />
      ) : null}

      <div className="flex flex-col gap-3">
        {plugins.length === 0 ? (
          <Card className="p-8 text-center text-xs text-[var(--text-secondary)]">
            未检测到已注册的插件。请将插件放置在 /data/system/sideveil/plugins/&lt;id&gt;/
            目录下，或点击上方「新建网页嵌入」快速添加局域网面板。
          </Card>
        ) : (
          plugins.map((plugin) => (
            <PluginCard
              key={plugin.id}
              plugin={plugin}
              isActive={plugin.id === activePluginId}
              isMatched={matchedIds.includes(plugin.id)}
              isHiddenByReleaseMode={runtimeMode === 'release' && isBuiltinTestPlugin(plugin.id)}
              isBusy={busyAction === `plugin-${plugin.id}`}
              foregroundPackage={fgPkg}
              onSetEnabled={onSetEnabled}
              onSetPriority={onSetPriority}
              onSetScopeOverride={onSetScopeOverride}
              onSetDevUrl={onSetDevUrl}
              onSetEmbedConfig={onSetEmbedConfig}
              onUninstallPlugin={onUninstallPlugin}
              onSelectPlugin={onSelectPlugin}
            />
          ))
        )}
      </div>
    </div>
  );
}
