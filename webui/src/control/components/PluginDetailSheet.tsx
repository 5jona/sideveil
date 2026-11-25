import { Badge, Button, SegmentedControl, type SegmentedControlOption, Switch } from '@gojona/ui';
import { CheckCircle2, Sliders, X } from 'lucide-react';
import { useEffect, useId, useState } from 'react';
import type { PluginRuntimeDescriptor } from '../../shared/types.ts';
import type {
  PluginEmbedConfigInput,
  PluginGrantCapabilitiesInput,
  PluginScopeOverrideInput,
} from '../hooks/usePluginsControl.ts';
import { canUninstallPlugin, formatOriginLabel, needsPermissionAttention } from '../pluginMeta.ts';
import { PluginUninstallAction } from './PluginCardActions.tsx';
import { PluginDaemonSection } from './PluginDaemonSection.tsx';
import { PluginDetailOverview } from './PluginDetailOverview.tsx';
import { PluginDevDialog } from './PluginDevDialog.tsx';
import { PluginEmbedDialog } from './PluginEmbedDialog.tsx';
import { PluginScopeEditor } from './PluginScopeEditor.tsx';

export type PluginDetailTab = 'overview' | 'scope' | 'dev' | 'embed';

export interface PluginDetailSheetProps {
  plugin: PluginRuntimeDescriptor;
  initialTab?: PluginDetailTab;
  isActive: boolean;
  isMatched: boolean;
  isBusy: boolean;
  foregroundPackage: string;
  currentWifiSsid?: string | null;
  networkLoading?: boolean;
  onDetectWifiSsid?: () => Promise<string | null>;
  onClose: () => void;
  onSetEnabled: (pluginId: string, enabled: boolean) => Promise<void>;
  onSetPriority: (pluginId: string, priority: number) => Promise<void>;
  onSetScopeOverride: (pluginId: string, override: PluginScopeOverrideInput) => Promise<void>;
  onSetDevUrl: (pluginId: string, url: string | null) => Promise<void>;
  onSetEmbedConfig: (pluginId: string, input: PluginEmbedConfigInput) => Promise<boolean>;
  onGrantCapabilities: (input: PluginGrantCapabilitiesInput) => Promise<boolean>;
  onFetchDaemonLogs: (pluginId: string, limit?: number) => Promise<string[]>;
  onReloadWebView: (pluginId: string) => Promise<boolean>;
  onUninstallPlugin: (pluginId: string) => Promise<boolean>;
  onSelectPlugin: (pluginId: string) => Promise<void>;
}

export function PluginDetailSheet({
  plugin,
  initialTab,
  isActive,
  isMatched,
  isBusy,
  foregroundPackage,
  currentWifiSsid,
  networkLoading,
  onDetectWifiSsid,
  onClose,
  onSetEnabled,
  onSetPriority,
  onSetScopeOverride,
  onSetDevUrl,
  onSetEmbedConfig,
  onGrantCapabilities,
  onFetchDaemonLogs,
  onReloadWebView,
  onUninstallPlugin,
  onSelectPlugin,
}: PluginDetailSheetProps) {
  const titleId = useId();
  const isEmbed = plugin.origin === 'web-embed' || Boolean(plugin.embed);
  const [tab, setTab] = useState<PluginDetailTab>(initialTab ?? (isEmbed ? 'embed' : 'overview'));
  const [priorityDraft, setPriorityDraft] = useState(plugin.priority);

  useEffect(() => {
    setPriorityDraft(plugin.priority);
  }, [plugin.priority]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  const tabOptions: SegmentedControlOption<PluginDetailTab>[] = isEmbed
    ? [
        { value: 'embed', label: '网页设置' },
        { value: 'scope', label: '触发作用域' },
      ]
    : [
        {
          value: 'overview',
          label: needsPermissionAttention(plugin) ? '概览与权限 (!)' : '概览与权限',
        },
        { value: 'scope', label: '触发作用域' },
        { value: 'dev', label: '开发与后台' },
      ];

  const handleUninstall = async (pluginId: string) => {
    const ok = await onUninstallPlugin(pluginId);
    if (ok) onClose();
    return ok;
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-[color-mix(in_oklab,black_45%,transparent)] p-3 sm:items-center"
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
    >
      <div className="flex max-h-[88dvh] w-full max-w-2xl flex-col overflow-hidden rounded-[var(--radius-panel)] border border-[var(--border-hairline)] bg-[var(--surface-card)] shadow-xl">
        <div className="flex items-start justify-between gap-3 border-b border-[var(--border-hairline)] px-4 py-3.5">
          <div className="flex min-w-0 flex-col gap-1">
            <div className="flex flex-wrap items-center gap-2">
              <h2
                id={titleId}
                className="truncate text-sm font-semibold text-[var(--text-primary)]"
              >
                {plugin.name}
              </h2>
              <span className="font-mono text-xs text-[var(--text-secondary)]">
                v{plugin.version}
              </span>
              <Badge variant={plugin.origin === 'builtin' ? 'neutral' : 'accent'}>
                {formatOriginLabel(plugin.origin)}
              </Badge>
            </div>
            <div className="truncate font-mono text-[11px] text-[var(--text-secondary)]">
              {plugin.id}
              {plugin.author ? ` · ${plugin.author}` : ''}
            </div>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            <Switch
              checked={plugin.enabled}
              disabled={isBusy}
              aria-label={`启用或停用 ${plugin.name}`}
              onCheckedChange={(checked) => void onSetEnabled(plugin.id, checked)}
            />
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label={`关闭 ${plugin.name} 管理抽屉`}
              onClick={onClose}
            >
              <X className="size-4" />
            </Button>
          </div>
        </div>

        <div className="border-b border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-4 py-2.5">
          <SegmentedControl
            size="sm"
            ariaLabel={`${plugin.name} 设置分区`}
            options={tabOptions}
            value={tab}
            onValueChange={setTab}
          />
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto p-4">
          {tab === 'embed' && plugin.embed ? (
            <PluginEmbedDialog
              pluginId={plugin.id}
              pluginName={plugin.name}
              embed={plugin.embed}
              initialKeepAlive={plugin.surface.keepAlive}
              initialWidthDp={plugin.surface.widthDp}
              initialHeightPercent={plugin.surface.heightPercent}
              isBusy={isBusy}
              currentWifiSsid={currentWifiSsid}
              networkLoading={networkLoading}
              onDetectWifiSsid={onDetectWifiSsid}
              onSave={onSetEmbedConfig}
              onCancel={onClose}
            />
          ) : null}

          {tab === 'overview' ? (
            <PluginDetailOverview
              plugin={plugin}
              isActive={isActive}
              isMatched={isMatched}
              isBusy={isBusy}
              onSelectPlugin={onSelectPlugin}
              onReloadWebView={onReloadWebView}
              onGrantCapabilities={onGrantCapabilities}
            />
          ) : null}

          {tab === 'scope' ? (
            <div className="flex flex-col gap-3">
              <div className="flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-3">
                <div className="flex flex-col gap-0.5">
                  <span className="flex items-center gap-1.5 text-xs font-medium text-[var(--text-primary)]">
                    <Sliders className="size-3.5 text-[var(--accent)]" />
                    排序优先级
                  </span>
                  <span className="text-[11px] text-[var(--text-secondary)]">
                    数值越大，多插件同时命中前台应用时排序越靠前
                  </span>
                </div>
                <div className="flex items-center gap-1.5">
                  <input
                    type="number"
                    aria-label={`${plugin.name} 优先级数值`}
                    className="w-20 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2 py-1 font-mono text-xs text-[var(--text-primary)]"
                    value={priorityDraft}
                    onChange={(e) => setPriorityDraft(Number(e.target.value))}
                  />
                  <Button
                    size="sm"
                    variant="primary"
                    aria-label={`保存 ${plugin.name} 优先级`}
                    disabled={isBusy || priorityDraft === plugin.priority}
                    onClick={() => void onSetPriority(plugin.id, priorityDraft)}
                  >
                    <CheckCircle2 className="size-3.5" />
                    保存
                  </Button>
                </div>
              </div>

              <PluginScopeEditor
                pluginId={plugin.id}
                pluginName={plugin.name}
                initialGlobal={plugin.global}
                initialPackages={plugin.packages}
                hasScopeOverride={plugin.hasScopeOverride}
                foregroundPackage={foregroundPackage}
                isBusy={isBusy}
                onSave={onSetScopeOverride}
                onCancel={onClose}
              />
            </div>
          ) : null}

          {tab === 'dev' ? (
            <div className="flex flex-col gap-3">
              <PluginDevDialog
                pluginId={plugin.id}
                pluginName={plugin.name}
                initialUrl={plugin.devServerUrl}
                isBusy={isBusy}
                onSave={onSetDevUrl}
                onCancel={() => {}}
              />
              <PluginDaemonSection
                plugin={plugin}
                isBusy={isBusy}
                onGrantCapabilities={onGrantCapabilities}
                onFetchDaemonLogs={onFetchDaemonLogs}
              />
            </div>
          ) : null}
        </div>

        {/* Footer */}
        {canUninstallPlugin(plugin) ? (
          <div className="flex items-center justify-between gap-2 border-t border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-4 py-2.5">
            <span className="text-[11px] text-[var(--text-secondary)]">
              卸载将移除插件文件与覆盖配置
            </span>
            <PluginUninstallAction
              pluginId={plugin.id}
              pluginName={plugin.name}
              isBusy={isBusy}
              onUninstall={handleUninstall}
            />
          </div>
        ) : null}
      </div>
    </div>
  );
}
