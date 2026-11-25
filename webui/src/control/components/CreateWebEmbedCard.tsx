import {
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  SettingRow,
  Switch,
} from '@gojona/ui';
import { GlobeLock, Plus, X } from 'lucide-react';
import { useEffect, useId, useRef, useState } from 'react';
import type { CreateWebEmbedInput } from '../hooks/pluginCommandHelpers.ts';
import {
  buildCreateWebEmbedInput,
  buildPresetAdvancedValues,
  cleanWifiSsid,
  DEFAULT_WEB_EMBED_ADVANCED,
  EMBED_PRESETS,
  type EmbedPreset,
  isValidCreateWebEmbedDraft,
  type WebEmbedAdvancedValues,
} from '../webEmbedHelpers.ts';
import { WebEmbedAdvancedFields } from './WebEmbedAdvancedFields.tsx';

export interface CreateWebEmbedCardProps {
  isBusy: boolean;
  currentWifiSsid?: string | null;
  networkLoading?: boolean;
  onDetectWifiSsid?: () => Promise<string | null>;
  onCreate: (input: CreateWebEmbedInput) => Promise<boolean>;
  onCancel: () => void;
}

export function CreateWebEmbedCard({
  isBusy,
  currentWifiSsid,
  networkLoading = false,
  onDetectWifiSsid,
  onCreate,
  onCancel,
}: CreateWebEmbedCardProps) {
  const formId = useId();
  const mountedRef = useRef(true);
  const [pluginId, setPluginId] = useState('');
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [originsText, setOriginsText] = useState('');
  const [allowCleartext, setAllowCleartext] = useState(true);
  const [keepAlive, setKeepAlive] = useState(true);
  const [globalScope, setGlobalScope] = useState(true);
  const [priority, setPriority] = useState(0);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [advanced, setAdvanced] = useState<WebEmbedAdvancedValues>(DEFAULT_WEB_EMBED_ADVANCED);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const canSubmit = !isBusy && isValidCreateWebEmbedDraft(pluginId, name, url, advanced.remoteUrl);

  const applyPreset = (preset: EmbedPreset) => {
    setPluginId(preset.id);
    setName(preset.name);
    setUrl(preset.url);
    setAllowCleartext(preset.allowCleartext);
    setKeepAlive(preset.keepAlive);
    setOriginsText('');
    const nextAdvanced = buildPresetAdvancedValues(preset, advanced.lanSsidsText, currentWifiSsid);
    setAdvanced(nextAdvanced);
    if (preset.advanced) setShowAdvanced(true);
    if (!nextAdvanced.lanSsidsText.trim() && onDetectWifiSsid) {
      void onDetectWifiSsid()
        .then((detected) => {
          const cleaned = cleanWifiSsid(detected);
          if (!mountedRef.current || !cleaned) return;
          setAdvanced((prev) =>
            prev.lanSsidsText.trim().length > 0 ? prev : { ...prev, lanSsidsText: cleaned },
          );
        })
        .catch(() => {});
    }
  };

  const handleSubmit = async () => {
    if (!canSubmit) return;
    const ok = await onCreate(
      buildCreateWebEmbedInput({
        id: pluginId,
        name,
        url,
        originsText,
        allowCleartext,
        keepAlive,
        globalScope,
        priority,
        advanced,
      }),
    );
    if (ok) onCancel();
  };

  return (
    <Card>
      <CardHeader>
        <div className="flex items-start justify-between gap-2">
          <div className="flex flex-col gap-0.5">
            <CardTitle className="flex items-center gap-1.5">
              <GlobeLock className="size-4 text-[var(--accent)]" />
              新建隔离网页嵌入
            </CardTitle>
            <CardDescription>
              将 Home Assistant 或任意自定义局域网 / 远程网页仪表盘安全嵌入侧边栏，不注入特权桥接
            </CardDescription>
          </div>
          <Button
            size="icon-sm"
            variant="ghost"
            aria-label="关闭新建网页嵌入表单"
            onClick={onCancel}
          >
            <X className="size-4" />
          </Button>
        </div>
      </CardHeader>

      <CardContent className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-[11px] font-medium text-[var(--text-secondary)]">常用模板：</span>
          {EMBED_PRESETS.map((preset) => (
            <Button
              key={preset.id}
              size="sm"
              variant="secondary"
              aria-label={`填充 ${preset.label} 模板`}
              disabled={isBusy}
              onClick={() => applyPreset(preset)}
            >
              {preset.label}
            </Button>
          ))}
        </div>

        <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
          <label className="flex flex-col gap-1 text-xs" htmlFor={`${formId}-id`}>
            <span className="font-medium text-[var(--text-secondary)]">插件 ID</span>
            <input
              id={`${formId}-id`}
              type="text"
              aria-label="网页嵌入插件 ID"
              placeholder="例如 lan.homeassistant"
              className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-2.5 py-1.5 font-mono text-xs text-[var(--text-primary)]"
              value={pluginId}
              onChange={(e) => setPluginId(e.target.value)}
            />
          </label>

          <label className="flex flex-col gap-1 text-xs" htmlFor={`${formId}-name`}>
            <span className="font-medium text-[var(--text-secondary)]">显示名称</span>
            <input
              id={`${formId}-name`}
              type="text"
              aria-label="网页嵌入显示名称"
              placeholder="例如 Home Assistant"
              className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-2.5 py-1.5 text-xs text-[var(--text-primary)]"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </label>

          <label className="flex flex-col gap-1 text-xs sm:col-span-2" htmlFor={`${formId}-url`}>
            <span className="font-medium text-[var(--text-secondary)]">目标 URL</span>
            <input
              id={`${formId}-url`}
              type="url"
              aria-label="网页嵌入目标 URL"
              placeholder="http://homeassistant.local:8123 或 https://panel.example.com"
              className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-2.5 py-1.5 font-mono text-xs text-[var(--text-primary)]"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
            />
          </label>

          <label className="flex flex-col gap-1 text-xs" htmlFor={`${formId}-origins`}>
            <span className="font-medium text-[var(--text-secondary)]">
              允许源列表（可选，逗号分隔）
            </span>
            <input
              id={`${formId}-origins`}
              type="text"
              aria-label="网页嵌入允许源列表"
              placeholder="留空则默认仅允许目标 URL 所在源"
              className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-2.5 py-1.5 font-mono text-xs text-[var(--text-primary)]"
              value={originsText}
              onChange={(e) => setOriginsText(e.target.value)}
            />
          </label>

          <label className="flex flex-col gap-1 text-xs" htmlFor={`${formId}-priority`}>
            <span className="font-medium text-[var(--text-secondary)]">优先级</span>
            <input
              id={`${formId}-priority`}
              type="number"
              aria-label="网页嵌入优先级"
              className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-2.5 py-1.5 font-mono text-xs text-[var(--text-primary)]"
              value={priority}
              onChange={(e) => setPriority(Number(e.target.value) || 0)}
            />
          </label>
        </div>

        <div className="flex flex-col gap-2 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-2.5">
          <SettingRow
            variant="plain"
            title="允许局域网明文 HTTP（仅限回环与 RFC1918 私网，公网自动拦截）"
            description="仅对 127.0.0.1、10.x、172.16-31.x、192.168.x 放行明文 HTTP，公网地址仍强制 HTTPS"
            control={
              <Switch
                checked={allowCleartext}
                onCheckedChange={setAllowCleartext}
                aria-label="允许局域网明文 HTTP"
                disabled={isBusy}
              />
            }
          />
          <SettingRow
            variant="plain"
            title="后台保活"
            description="收起侧边栏后保持 WebView 驻留，保留登录态与实时 WebSocket 连接"
            control={
              <Switch
                checked={keepAlive}
                onCheckedChange={setKeepAlive}
                aria-label="开启网页嵌入后台保活"
                disabled={isBusy}
              />
            }
          />
          <SettingRow
            variant="plain"
            title="全局生效"
            description="在任意前台应用中均可侧滑呼出该网页嵌入插件"
            control={
              <Switch
                checked={globalScope}
                onCheckedChange={setGlobalScope}
                aria-label="网页嵌入全局生效"
                disabled={isBusy}
              />
            }
          />
        </div>

        <WebEmbedAdvancedFields
          labelPrefix="新建网页嵌入"
          values={advanced}
          keepAlive={keepAlive}
          isBusy={isBusy}
          expanded={showAdvanced}
          currentWifiSsid={currentWifiSsid}
          networkLoading={networkLoading}
          onDetectWifiSsid={onDetectWifiSsid}
          onToggleExpanded={() => setShowAdvanced((prev) => !prev)}
          onChange={(patch) => setAdvanced((prev) => ({ ...prev, ...patch }))}
        />

        <div className="flex flex-wrap items-center justify-end gap-2">
          <Button size="sm" variant="ghost" aria-label="取消新建网页嵌入" onClick={onCancel}>
            取消
          </Button>
          <Button
            size="sm"
            variant="primary"
            aria-label="确认创建网页嵌入"
            disabled={!canSubmit}
            onClick={() => void handleSubmit()}
          >
            <Plus className="size-3.5" />
            {isBusy ? '创建中...' : '创建网页嵌入'}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
