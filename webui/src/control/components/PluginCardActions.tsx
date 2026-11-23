import type { PluginEmbedPrefs } from '@gojona/sideveil-sdk';
import { Badge, Button } from '@gojona/ui';
import { GlobeLock, Pencil, Trash2 } from 'lucide-react';
import { useState } from 'react';

export interface PluginUninstallActionProps {
  pluginId: string;
  pluginName: string;
  isBusy: boolean;
  onUninstall: (pluginId: string) => Promise<boolean>;
}

export function PluginUninstallAction({
  pluginId,
  pluginName,
  isBusy,
  onUninstall,
}: PluginUninstallActionProps) {
  const [confirming, setConfirming] = useState(false);

  if (confirming) {
    return (
      <div className="flex items-center gap-1">
        <Button
          size="sm"
          variant="danger"
          aria-label={`确认卸载 ${pluginName}`}
          disabled={isBusy}
          onClick={() => {
            void onUninstall(pluginId).then((ok) => {
              if (ok) setConfirming(false);
            });
          }}
        >
          确认卸载
        </Button>
        <Button
          size="sm"
          variant="ghost"
          aria-label={`取消卸载 ${pluginName}`}
          disabled={isBusy}
          onClick={() => setConfirming(false)}
        >
          取消
        </Button>
      </div>
    );
  }

  return (
    <Button
      size="sm"
      variant="ghost"
      aria-label={`卸载 ${pluginName}`}
      disabled={isBusy}
      onClick={() => setConfirming(true)}
    >
      <Trash2 className="size-3.5 text-[var(--danger)]" />
      卸载
    </Button>
  );
}

export interface PluginEmbedBannerProps {
  pluginName: string;
  embed: PluginEmbedPrefs;
  editing: boolean;
  isBusy: boolean;
  onToggleEdit: () => void;
}

export function PluginEmbedBanner({
  pluginName,
  embed,
  editing,
  isBusy,
  onToggleEdit,
}: PluginEmbedBannerProps) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-2.5 py-1.5 text-xs">
      <div className="flex min-w-0 flex-wrap items-center gap-1.5">
        <GlobeLock className="size-3.5 shrink-0 text-[var(--accent)]" />
        <code className="truncate font-mono text-[11px] text-[var(--text-primary)]">
          {embed.url}
        </code>
        {embed.remoteUrl ? <Badge variant="accent">双地址切换</Badge> : null}
        {embed.lanSsids && embed.lanSsids.length > 0 ? (
          <Badge variant="neutral">SSID ({embed.lanSsids.length})</Badge>
        ) : null}
        {embed.allowCleartext ? <Badge variant="warning">私网明文 HTTP</Badge> : null}
        {embed.showToolbar ? <Badge variant="neutral">快捷顶栏</Badge> : null}
        {embed.textZoom && embed.textZoom !== 100 ? (
          <Badge variant="neutral">缩放 {embed.textZoom}%</Badge>
        ) : null}
        {embed.keepAliveGraceSec && embed.keepAliveGraceSec > 0 ? (
          <Badge variant="neutral">保活 {embed.keepAliveGraceSec}s</Badge>
        ) : null}
      </div>
      <button
        type="button"
        aria-label={editing ? `收起 ${pluginName} 网页设置` : `编辑 ${pluginName} 网页设置`}
        disabled={isBusy}
        onClick={onToggleEdit}
        className="inline-flex shrink-0 cursor-pointer items-center gap-1 rounded px-1.5 py-0.5 text-[11px] text-[var(--accent)] hover:bg-[var(--surface-card)]"
      >
        <Pencil className="size-3" />
        {editing ? '收起网页设置' : '网页设置'}
      </button>
    </div>
  );
}
