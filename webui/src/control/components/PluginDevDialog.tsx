import { Button } from '@pochita/ui';
import { Code2, RotateCcw, Save } from 'lucide-react';
import { useState } from 'react';

export interface PluginDevDialogProps {
  pluginId: string;
  pluginName: string;
  initialUrl?: string;
  isBusy: boolean;
  onSave: (pluginId: string, url: string | null) => Promise<void>;
  onCancel: () => void;
}

export function PluginDevDialog({
  pluginId,
  pluginName,
  initialUrl,
  isBusy,
  onSave,
  onCancel,
}: PluginDevDialogProps) {
  const [url, setUrl] = useState(initialUrl ?? 'http://127.0.0.1:5175');

  return (
    <div className="space-y-2.5 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-3">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-1.5 text-xs font-medium text-[var(--text-primary)]">
          <Code2 className="size-3.5 text-[var(--accent)]" />
          <span>开发模式热重载：{pluginName}</span>
        </div>
        <span className="text-[11px] text-[var(--text-secondary)]">
          仅允许 127.0.0.1 / localhost 回环地址，开启后自动启用 WebView DevTools
        </span>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <input
          type="url"
          aria-label={`${pluginName} 开发服务器地址`}
          placeholder="http://127.0.0.1:5175"
          className="min-w-52 flex-1 rounded border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2.5 py-1.5 font-mono text-xs text-[var(--text-primary)]"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
        />
        <Button
          size="sm"
          variant="primary"
          disabled={isBusy || !url.trim()}
          onClick={() => {
            void onSave(pluginId, url.trim()).then(() => onCancel());
          }}
        >
          <Save className="size-3.5" />
          启用热重载
        </Button>
        {initialUrl ? (
          <Button
            size="sm"
            variant="secondary"
            disabled={isBusy}
            onClick={() => {
              void onSave(pluginId, null).then(() => onCancel());
            }}
          >
            <RotateCcw className="size-3.5" />
            退出开发模式
          </Button>
        ) : null}
        <Button size="sm" variant="ghost" onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}
