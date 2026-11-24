import { Badge, Button, Card, Switch } from '@gojona/ui';
import { Globe, GlobeLock, Settings2, ShieldAlert } from 'lucide-react';
import type { PluginRuntimeDescriptor } from '../../shared/types.ts';
import {
  formatOriginLabel,
  getApprovalBadgeMeta,
  getDaemonBadgeMeta,
  needsPermissionAttention,
} from '../pluginMeta.ts';
import type { PluginDetailTab } from './PluginDetailSheet.tsx';

export interface PluginCardProps {
  plugin: PluginRuntimeDescriptor;
  isActive: boolean;
  isMatched: boolean;
  isBusy: boolean;
  onSetEnabled: (pluginId: string, enabled: boolean) => Promise<void>;
  onOpenDetail: (pluginId: string, initialTab?: PluginDetailTab) => void;
}

function resolveStatusPill(
  plugin: PluginRuntimeDescriptor,
  isActive: boolean,
  isMatched: boolean,
): {
  label: string;
  variant: 'success' | 'accent' | 'warning' | 'danger' | 'neutral';
  dot?: boolean;
} | null {
  if (plugin.approvalStatus !== 'approved') {
    return getApprovalBadgeMeta(plugin.approvalStatus);
  }
  if (
    plugin.daemonStatus === 'awaiting-approval' ||
    plugin.daemonStatus === 'circuit-open' ||
    plugin.daemonStatus === 'crashed'
  ) {
    return getDaemonBadgeMeta(plugin.daemonStatus);
  }
  if (isActive) {
    return { label: '当前激活', variant: 'success', dot: true };
  }
  if (isMatched) {
    return { label: '前台命中', variant: 'accent' };
  }
  if (plugin.devServerUrl) {
    return { label: 'Dev 热重载', variant: 'warning' };
  }
  return null;
}

function formatScopeSummary(plugin: PluginRuntimeDescriptor): string {
  if (plugin.global) return '全局生效';
  if (plugin.packages.length === 1) return plugin.packages[0];
  if (plugin.packages.length > 1) return `${plugin.packages.length} 个应用`;
  return plugin.builtin ? '跟随侧边栏' : '未指定应用';
}

export function PluginCard({
  plugin,
  isActive,
  isMatched,
  isBusy,
  onSetEnabled,
  onOpenDetail,
}: PluginCardProps) {
  const isEmbed = plugin.origin === 'web-embed' || Boolean(plugin.embed);
  const attentionNeeded = needsPermissionAttention(plugin);
  const statusPill = resolveStatusPill(plugin, isActive, isMatched);

  return (
    <Card
      className={`flex flex-col gap-2.5 p-3.5 transition-all ${
        isActive ? 'ring-1 ring-[var(--accent)] shadow-sm' : ''
      }`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="truncate text-sm font-semibold text-[var(--text-primary)]">
              {plugin.name}
            </span>
            {!isEmbed ? (
              <span className="font-mono text-xs text-[var(--text-secondary)]">
                v{plugin.version}
              </span>
            ) : null}
            {/* At most 3 concise pills: 1) Origin/type, 2) Status/Warning, 3) Scope */}
            <Badge variant={plugin.origin === 'bundled' ? 'neutral' : 'accent'}>
              {formatOriginLabel(plugin.origin)}
            </Badge>
            {statusPill ? (
              <Badge variant={statusPill.variant} dot={statusPill.dot}>
                {statusPill.label}
              </Badge>
            ) : null}
            <Badge variant="neutral">
              <Globe className="size-3" />
              {formatScopeSummary(plugin)}
            </Badge>
          </div>

          {isEmbed && plugin.embed ? (
            <div className="flex items-center gap-1.5 text-xs text-[var(--text-secondary)]">
              <GlobeLock className="size-3.5 shrink-0 text-[var(--accent)]" />
              <code className="truncate font-mono text-[11px]">{plugin.embed.url}</code>
              {plugin.embed.remoteUrl ? (
                <span className="shrink-0 text-[11px] text-[var(--accent)]">· 内外网双地址</span>
              ) : null}
            </div>
          ) : (
            <div className="truncate text-xs text-[var(--text-secondary)]">
              {plugin.description || plugin.id}
            </div>
          )}
        </div>

        <div className="flex shrink-0 items-center gap-2">
          {attentionNeeded ? (
            <Button
              size="sm"
              variant="primary"
              aria-label={`审查 ${plugin.name} 权限`}
              disabled={isBusy}
              onClick={() => onOpenDetail(plugin.id, 'overview')}
            >
              <ShieldAlert className="size-3.5" />
              审查权限
            </Button>
          ) : null}
          <Button
            size="sm"
            variant="secondary"
            aria-label={`管理 ${plugin.name}`}
            disabled={isBusy}
            onClick={() => onOpenDetail(plugin.id, isEmbed ? 'embed' : 'overview')}
          >
            <Settings2 className="size-3.5" />
            管理
          </Button>
          <Switch
            checked={plugin.enabled}
            onCheckedChange={(checked) => void onSetEnabled(plugin.id, checked)}
            aria-label={`启用 ${plugin.name}`}
            disabled={isBusy}
          />
        </div>
      </div>
    </Card>
  );
}
