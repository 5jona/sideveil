import { SettingRow, Switch } from '@gojona/ui';
import { ChevronDown, ChevronUp, SlidersHorizontal, Wifi } from 'lucide-react';
import { useEffect, useId, useRef, useState } from 'react';
import {
  cleanWifiSsid,
  DEFAULT_WEB_EMBED_ADVANCED,
  EMBED_PRESETS,
  type EmbedPreset,
  resolveWifiSsidDetection,
  WEB_EMBED_ADVANCED_TOGGLES,
  type WebEmbedAdvancedValues,
} from '../webEmbedHelpers.ts';

export { DEFAULT_WEB_EMBED_ADVANCED, EMBED_PRESETS, type EmbedPreset, type WebEmbedAdvancedValues };

export interface WebEmbedAdvancedFieldsProps {
  labelPrefix?: string;
  values: WebEmbedAdvancedValues;
  keepAlive: boolean;
  isBusy: boolean;
  expanded: boolean;
  currentWifiSsid?: string | null;
  networkLoading?: boolean;
  onDetectWifiSsid?: () => Promise<string | null>;
  onToggleExpanded: () => void;
  onChange: (patch: Partial<WebEmbedAdvancedValues>) => void;
}

export function WebEmbedAdvancedFields({
  labelPrefix = '网页嵌入',
  values,
  keepAlive,
  isBusy,
  expanded,
  currentWifiSsid,
  networkLoading = false,
  onDetectWifiSsid,
  onToggleExpanded,
  onChange,
}: WebEmbedAdvancedFieldsProps) {
  const fieldId = useId();
  const [detectingWifi, setDetectingWifi] = useState(false);
  const [wifiHint, setWifiHint] = useState<string | null>(null);
  const lanSsidsRef = useRef(values.lanSsidsText);
  lanSsidsRef.current = values.lanSsidsText;
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const hasRemote = values.remoteUrl.trim().length > 0;
  const detectedWifiSsid = cleanWifiSsid(currentWifiSsid);
  const isDetectingWifi = detectingWifi || networkLoading;
  const showWifiAction = Boolean(onDetectWifiSsid || detectedWifiSsid);

  const handleFillCurrentWifi = async () => {
    setWifiHint(null);
    setDetectingWifi(true);
    try {
      const result = await resolveWifiSsidDetection(
        lanSsidsRef.current,
        currentWifiSsid,
        onDetectWifiSsid,
      );
      if (!mountedRef.current) return;
      if (result.filledSsid) {
        onChange({ lanSsidsText: result.nextText });
        setWifiHint(null);
      } else {
        setWifiHint(result.hint);
      }
    } finally {
      if (mountedRef.current) {
        setDetectingWifi(false);
      }
    }
  };

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

            <div className="flex flex-col gap-1 text-xs">
              <div className="flex flex-wrap items-center justify-between gap-1.5">
                <label
                  className="font-medium text-[var(--text-secondary)]"
                  htmlFor={`${fieldId}-lan-ssids`}
                >
                  家庭 Wi-Fi SSID（逗号分隔）
                </label>
                {showWifiAction ? (
                  <button
                    type="button"
                    aria-label={`${labelPrefix}填入当前 Wi-Fi SSID`}
                    disabled={isBusy || isDetectingWifi}
                    onClick={() => void handleFillCurrentWifi()}
                    className="inline-flex cursor-pointer items-center gap-1 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2 py-0.5 text-[11px] font-medium text-[var(--accent)] transition-colors hover:bg-[var(--accent-soft)] disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    <Wifi className={`size-3 ${isDetectingWifi ? 'animate-pulse' : ''}`} />
                    <span>
                      {isDetectingWifi
                        ? '检测中...'
                        : detectedWifiSsid
                          ? `填入当前 Wi-Fi · ${detectedWifiSsid}`
                          : '填入当前 Wi-Fi'}
                    </span>
                  </button>
                ) : null}
              </div>
              <input
                id={`${fieldId}-lan-ssids`}
                type="text"
                aria-label={`${labelPrefix}家庭 Wi-Fi SSID`}
                placeholder="例如 HomeRouter-5G, Office-5G"
                disabled={isBusy}
                className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2.5 py-1.5 font-mono text-xs text-[var(--text-primary)]"
                value={values.lanSsidsText}
                onChange={(e) => {
                  if (wifiHint) setWifiHint(null);
                  onChange({ lanSsidsText: e.target.value });
                }}
              />
              {wifiHint ? (
                <span role="status" className="text-[11px] text-[var(--warning)]">
                  {wifiHint}
                </span>
              ) : null}
            </div>

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
            {WEB_EMBED_ADVANCED_TOGGLES.map((toggle) => (
              <SettingRow
                key={toggle.key}
                variant="plain"
                title={toggle.title}
                description={toggle.description}
                control={
                  <Switch
                    checked={values[toggle.key]}
                    onCheckedChange={(checked) => onChange({ [toggle.key]: checked })}
                    aria-label={`${labelPrefix}${toggle.title}`}
                    disabled={isBusy}
                  />
                }
              />
            ))}
          </div>
        </div>
      ) : null}
    </div>
  );
}
