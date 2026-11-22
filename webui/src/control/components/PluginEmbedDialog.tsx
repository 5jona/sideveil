import type { PluginEmbedPrefs } from '@pochita/sideveil-sdk';
import { Button, SettingRow, Switch } from '@pochita/ui';
import { GlobeLock, RotateCcw, Save } from 'lucide-react';
import { useId, useState } from 'react';
import { normalizeOriginList, type PluginEmbedConfigInput } from '../hooks/pluginCommandHelpers.ts';

export interface PluginEmbedDialogProps {
  pluginId: string;
  pluginName: string;
  embed: PluginEmbedPrefs;
  initialKeepAlive: boolean;
  isBusy: boolean;
  onSave: (pluginId: string, input: PluginEmbedConfigInput) => Promise<boolean>;
  onCancel: () => void;
}

export function PluginEmbedDialog({
  pluginId,
  pluginName,
  embed,
  initialKeepAlive,
  isBusy,
  onSave,
  onCancel,
}: PluginEmbedDialogProps) {
  const formId = useId();
  const [embedUrl, setEmbedUrl] = useState(embed.url);
  const [originsText, setOriginsText] = useState((embed.allowedOrigins ?? []).join(', '));
  const [allowCleartext, setAllowCleartext] = useState(Boolean(embed.allowCleartext));
  const [keepAlive, setKeepAlive] = useState(initialKeepAlive);

  const trimmedUrl = embedUrl.trim();
  const validUrl = /^https?:\/\/.+/.test(trimmedUrl);

  const handleSave = async () => {
    if (!validUrl) return;
    const ok = await onSave(pluginId, {
      embedUrl: trimmedUrl,
      allowedOrigins: normalizeOriginList(originsText),
      allowCleartext,
      keepAlive,
    });
    if (ok) onCancel();
  };

  const handleReset = async () => {
    const ok = await onSave(pluginId, { clear: true });
    if (ok) onCancel();
  };

  return (
    <div className="flex flex-col gap-3 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-1.5 text-xs font-medium text-[var(--text-primary)]">
          <GlobeLock className="size-3.5 text-[var(--accent)]" />
          <span>网页嵌入设置：{pluginName}</span>
        </div>
        <span className="text-[11px] text-[var(--text-secondary)]">
          修改目标地址、允许源及明文私网策略（保存后立即热更新）
        </span>
      </div>

      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        <label className="flex flex-col gap-1 text-xs sm:col-span-2" htmlFor={`${formId}-url`}>
          <span className="font-medium text-[var(--text-secondary)]">目标 URL</span>
          <input
            id={`${formId}-url`}
            type="url"
            aria-label={`${pluginName} 目标 URL`}
            placeholder="http://192.168.1.10:8123"
            className="rounded border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2.5 py-1.5 font-mono text-xs text-[var(--text-primary)]"
            value={embedUrl}
            onChange={(e) => setEmbedUrl(e.target.value)}
          />
        </label>

        <label className="flex flex-col gap-1 text-xs sm:col-span-2" htmlFor={`${formId}-origins`}>
          <span className="font-medium text-[var(--text-secondary)]">
            允许源列表（逗号或换行分隔）
          </span>
          <input
            id={`${formId}-origins`}
            type="text"
            aria-label={`${pluginName} 允许源列表`}
            placeholder="http://192.168.1.10:8123, https://ha.local"
            className="rounded border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2.5 py-1.5 font-mono text-xs text-[var(--text-primary)]"
            value={originsText}
            onChange={(e) => setOriginsText(e.target.value)}
          />
        </label>
      </div>

      <div className="flex flex-col gap-2">
        <SettingRow
          variant="plain"
          title="允许局域网明文 HTTP（仅限回环与 RFC1918 私网，公网自动拦截）"
          description="关闭后仅允许 HTTPS 与 127.0.0.1 回环地址"
          control={
            <Switch
              checked={allowCleartext}
              onCheckedChange={setAllowCleartext}
              aria-label={`切换 ${pluginName} 允许局域网明文 HTTP`}
              disabled={isBusy}
            />
          }
        />
        <SettingRow
          variant="plain"
          title="后台保活"
          description="收起面板后保持页面状态不销毁"
          control={
            <Switch
              checked={keepAlive}
              onCheckedChange={setKeepAlive}
              aria-label={`切换 ${pluginName} 后台保活`}
              disabled={isBusy}
            />
          }
        />
      </div>

      <div className="flex flex-wrap items-center justify-end gap-2">
        <Button
          size="sm"
          variant="secondary"
          aria-label={`恢复 ${pluginName} 默认网页设置`}
          disabled={isBusy}
          onClick={() => void handleReset()}
        >
          <RotateCcw className="size-3.5" />
          恢复默认
        </Button>
        <Button
          size="sm"
          variant="primary"
          aria-label={`保存 ${pluginName} 网页设置`}
          disabled={isBusy || !validUrl}
          onClick={() => void handleSave()}
        >
          <Save className="size-3.5" />
          保存设置
        </Button>
        <Button
          size="sm"
          variant="ghost"
          aria-label={`取消编辑 ${pluginName} 网页设置`}
          onClick={onCancel}
        >
          取消
        </Button>
      </div>
    </div>
  );
}
