import { SettingRow, Switch } from '@pochita/ui';
import { ChevronDown, ChevronUp, SlidersHorizontal } from 'lucide-react';
import { useId } from 'react';

export interface WebEmbedAdvancedValues {
  remoteUrl: string;
  lanSsidsText: string;
  widthDp: number;
  heightPercent: number;
  textZoom: number;
  keepAliveGraceSec: number;
  wideViewport: boolean;
  allowZoom: boolean;
  webHistoryBack: boolean;
  showToolbar: boolean;
}

export const DEFAULT_WEB_EMBED_ADVANCED: WebEmbedAdvancedValues = {
  remoteUrl: '',
  lanSsidsText: '',
  widthDp: 380,
  heightPercent: 78,
  textZoom: 100,
  keepAliveGraceSec: 180,
  wideViewport: true,
  allowZoom: false,
  webHistoryBack: true,
  showToolbar: true,
};

export interface EmbedPreset {
  label: string;
  id: string;
  name: string;
  url: string;
  allowCleartext: boolean;
  keepAlive: boolean;
  advanced?: Partial<WebEmbedAdvancedValues>;
}

export const EMBED_PRESETS: readonly EmbedPreset[] = [
  {
    label: 'Home Assistant',
    id: 'lan.homeassistant',
    name: 'Home Assistant',
    url: 'http://homeassistant.local:8123',
    allowCleartext: true,
    keepAlive: true,
    advanced: {
      keepAliveGraceSec: 180,
      widthDp: 400,
      textZoom: 95,
      showToolbar: true,
      webHistoryBack: true,
      wideViewport: true,
      allowZoom: false,
    },
  },
  {
    label: 'OpenClash',
    id: 'lan.openclash',
    name: 'OpenClash',
    url: 'http://192.168.1.1:9090/ui',
    allowCleartext: true,
    keepAlive: true,
  },
  {
    label: 'LuCI',
    id: 'lan.luci',
    name: 'OpenWrt LuCI',
    url: 'http://192.168.1.1/cgi-bin/luci',
    allowCleartext: true,
    keepAlive: false,
  },
  {
    label: 'Zashboard',
    id: 'lan.zashboard',
    name: 'Zashboard',
    url: 'http://127.0.0.1:9090/ui',
    allowCleartext: true,
    keepAlive: true,
  },
];

export interface WebEmbedAdvancedFieldsProps {
  labelPrefix?: string;
  values: WebEmbedAdvancedValues;
  keepAlive: boolean;
  isBusy: boolean;
  expanded: boolean;
  onToggleExpanded: () => void;
  onChange: (patch: Partial<WebEmbedAdvancedValues>) => void;
}

export function WebEmbedAdvancedFields({
  labelPrefix = '网页嵌入',
  values,
  keepAlive,
  isBusy,
  expanded,
  onToggleExpanded,
  onChange,
}: WebEmbedAdvancedFieldsProps) {
  const fieldId = useId();
  const hasRemote = values.remoteUrl.trim().length > 0;

  return (
    <div className="flex flex-col gap-2.5 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-2.5">
      <button
        type="button"
        aria-expanded={expanded}
        aria-label={`展开或收起${labelPrefix}高级选项`}
        onClick={onToggleExpanded}
        className="flex w-full cursor-pointer items-center justify-between gap-2 text-left text-xs font-medium text-[var(--text-primary)]"
      >
        <span className="inline-flex items-center gap-1.5">
          <SlidersHorizontal className="size-3.5 text-[var(--accent)]" />
          高级选项（内外网切换 / 缩放 / 尺寸 / 手势）
        </span>
        <span className="inline-flex items-center gap-1.5 font-mono text-[11px] text-[var(--text-secondary)]">
          <span>
            {hasRemote ? '双地址 · ' : ''}
            {values.widthDp}dp × {values.heightPercent}% · {values.textZoom}%
          </span>
          {expanded ? <ChevronUp className="size-3.5" /> : <ChevronDown className="size-3.5" />}
        </span>
      </button>

      {expanded ? (
        <div className="flex flex-col gap-2.5 border-t border-[var(--border-hairline)] pt-2.5">
          <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
            <label className="flex flex-col gap-1 text-xs" htmlFor={`${fieldId}-remote-url`}>
              <span className="font-medium text-[var(--text-secondary)]">
                外网/备选地址（可选）
              </span>
              <input
                id={`${fieldId}-remote-url`}
                type="url"
                aria-label={`${labelPrefix}外网或备选地址`}
                placeholder="https://ha.example.com:8123"
                disabled={isBusy}
                className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2.5 py-1.5 font-mono text-xs text-[var(--text-primary)]"
                value={values.remoteUrl}
                onChange={(e) => onChange({ remoteUrl: e.target.value })}
              />
            </label>

            <label className="flex flex-col gap-1 text-xs" htmlFor={`${fieldId}-lan-ssids`}>
              <span className="font-medium text-[var(--text-secondary)]">
                家庭 Wi-Fi SSID（逗号分隔）
              </span>
              <input
                id={`${fieldId}-lan-ssids`}
                type="text"
                aria-label={`${labelPrefix}家庭 Wi-Fi SSID`}
                placeholder="例如 Home-5G, Home-IoT"
                disabled={isBusy}
                className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2.5 py-1.5 font-mono text-xs text-[var(--text-primary)]"
                value={values.lanSsidsText}
                onChange={(e) => onChange({ lanSsidsText: e.target.value })}
              />
            </label>

            <label className="flex flex-col gap-1 text-xs" htmlFor={`${fieldId}-width-dp`}>
              <span className="font-medium text-[var(--text-secondary)]">
                独立面板宽度 (240–720 dp)
              </span>
              <input
                id={`${fieldId}-width-dp`}
                type="number"
                min={240}
                max={720}
                step={10}
                aria-label={`${labelPrefix}独立面板宽度`}
                disabled={isBusy}
                className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2.5 py-1.5 font-mono text-xs text-[var(--text-primary)]"
                value={values.widthDp}
                onChange={(e) => onChange({ widthDp: Number(e.target.value) || 0 })}
              />
            </label>

            <label className="flex flex-col gap-1 text-xs" htmlFor={`${fieldId}-height-percent`}>
              <span className="font-medium text-[var(--text-secondary)]">高度占比 (30–100 %)</span>
              <input
                id={`${fieldId}-height-percent`}
                type="number"
                min={30}
                max={100}
                step={1}
                aria-label={`${labelPrefix}高度占比`}
                disabled={isBusy}
                className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2.5 py-1.5 font-mono text-xs text-[var(--text-primary)]"
                value={values.heightPercent}
                onChange={(e) => onChange({ heightPercent: Number(e.target.value) || 0 })}
              />
            </label>

            <label className="flex flex-col gap-1 text-xs" htmlFor={`${fieldId}-text-zoom`}>
              <span className="font-medium text-[var(--text-secondary)]">文字缩放 (50–200 %)</span>
              <input
                id={`${fieldId}-text-zoom`}
                type="number"
                min={50}
                max={200}
                step={5}
                aria-label={`${labelPrefix}文字缩放`}
                disabled={isBusy}
                className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2.5 py-1.5 font-mono text-xs text-[var(--text-primary)]"
                value={values.textZoom}
                onChange={(e) => onChange({ textZoom: Number(e.target.value) || 0 })}
              />
            </label>

            <label className="flex flex-col gap-1 text-xs" htmlFor={`${fieldId}-keep-alive-grace`}>
              <span className="font-medium text-[var(--text-secondary)]">
                后台保活宽限期 (0–1800 秒，开启保活时生效)
              </span>
              <input
                id={`${fieldId}-keep-alive-grace`}
                type="number"
                min={0}
                max={1800}
                step={10}
                aria-label={`${labelPrefix}后台保活宽限期`}
                disabled={isBusy || !keepAlive}
                className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2.5 py-1.5 font-mono text-xs text-[var(--text-primary)] disabled:opacity-50"
                value={values.keepAliveGraceSec}
                onChange={(e) => onChange({ keepAliveGraceSec: Number(e.target.value) || 0 })}
              />
            </label>
          </div>

          <div className="flex flex-col gap-2">
            <SettingRow
              variant="plain"
              title="显示极简快捷顶栏"
              description="在面板顶部提供地址状态、内外网切换、刷新与外部浏览器打开按钮"
              control={
                <Switch
                  checked={values.showToolbar}
                  onCheckedChange={(checked) => onChange({ showToolbar: checked })}
                  aria-label={`${labelPrefix}显示极简快捷顶栏`}
                  disabled={isBusy}
                />
              }
            />
            <SettingRow
              variant="plain"
              title="网页内优先返回"
              description="返回手势优先后退网页历史记录，到达根页后再收起侧边栏"
              control={
                <Switch
                  checked={values.webHistoryBack}
                  onCheckedChange={(checked) => onChange({ webHistoryBack: checked })}
                  aria-label={`${labelPrefix}网页内优先返回`}
                  disabled={isBusy}
                />
              }
            />
            <SettingRow
              variant="plain"
              title="宽视口"
              description="启用自适应宽视口布局，改善仪表盘与多列卡片显示"
              control={
                <Switch
                  checked={values.wideViewport}
                  onCheckedChange={(checked) => onChange({ wideViewport: checked })}
                  aria-label={`${labelPrefix}宽视口`}
                  disabled={isBusy}
                />
              }
            />
            <SettingRow
              variant="plain"
              title="允许双指缩放"
              description="允许在网页区域内使用双指捏合手势缩放页面"
              control={
                <Switch
                  checked={values.allowZoom}
                  onCheckedChange={(checked) => onChange({ allowZoom: checked })}
                  aria-label={`${labelPrefix}允许双指缩放`}
                  disabled={isBusy}
                />
              }
            />
          </div>
        </div>
      ) : null}
    </div>
  );
}
