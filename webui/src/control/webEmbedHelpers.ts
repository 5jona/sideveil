import type { NetworkContextSnapshot } from '@gojona/sideveil-sdk';
import {
  type CreateWebEmbedInput,
  clampEmbedNumber,
  normalizeOriginList,
  normalizeSsidList,
} from './hooks/pluginCommandHelpers.ts';

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
];

export const WIFI_SSID_UNAVAILABLE_HINT = '未检测到当前 Wi-Fi SSID，请确认已连接 Wi-Fi 或手动输入';

/** Strips surrounding whitespace/quotes and ignores empty or Android unknown-SSID sentinels. */
export function cleanWifiSsid(raw: string | null | undefined): string | null {
  if (typeof raw !== 'string') return null;
  let cleaned = raw.trim();
  if (
    cleaned.length >= 2 &&
    ((cleaned.startsWith('"') && cleaned.endsWith('"')) ||
      (cleaned.startsWith("'") && cleaned.endsWith("'")))
  ) {
    cleaned = cleaned.slice(1, -1).trim();
  }
  if (!cleaned || cleaned.toLowerCase() === '<unknown ssid>') {
    return null;
  }
  return cleaned;
}

/** Extracts a sanitized Wi-Fi SSID only when the active network transport is Wi-Fi. */
export function extractConnectedWifiSsid(
  snapshot: NetworkContextSnapshot | null | undefined,
): string | null {
  if (snapshot?.transport !== 'wifi') return null;
  return cleanWifiSsid(snapshot.ssid);
}

/** Appends a Wi-Fi SSID to a comma/newline-delimited list without duplicating existing entries. */
export function appendWifiSsid(existingText: string, ssid: string): string {
  const candidate = cleanWifiSsid(ssid);
  if (!candidate) return existingText;

  const entries: string[] = [];
  for (const part of existingText.split(/[\n,]/)) {
    const cleaned = cleanWifiSsid(part);
    if (cleaned && !entries.includes(cleaned)) {
      entries.push(cleaned);
    }
  }
  if (!entries.includes(candidate)) {
    entries.push(candidate);
  }
  return entries.join(', ');
}

export interface WifiSsidFillResult {
  nextText: string;
  filledSsid: string | null;
  hint: string | null;
}

/** Resolves the updated SSID text and inline hint from a detected SSID candidate. */
export function resolveWifiSsidFill(
  existingText: string,
  detectedSsid: string | null | undefined,
): WifiSsidFillResult {
  const cleaned = cleanWifiSsid(detectedSsid);
  if (!cleaned) {
    return {
      nextText: existingText,
      filledSsid: null,
      hint: WIFI_SSID_UNAVAILABLE_HINT,
    };
  }
  return {
    nextText: appendWifiSsid(existingText, cleaned),
    filledSsid: cleaned,
    hint: null,
  };
}

/** Refreshes the current Wi-Fi SSID on demand (when a detector is provided) and appends it. */
export async function resolveWifiSsidDetection(
  existingText: string,
  currentWifiSsid: string | null | undefined,
  onDetectWifiSsid?: () => Promise<string | null>,
): Promise<WifiSsidFillResult> {
  let detected: string | null | undefined = currentWifiSsid;
  if (onDetectWifiSsid) {
    try {
      detected = await onDetectWifiSsid();
    } catch {
      detected = null;
    }
  }
  return resolveWifiSsidFill(existingText, detected);
}

/** Builds preset advanced defaults while preserving existing SSID edits or pre-filling current Wi-Fi. */
export function buildPresetAdvancedValues(
  preset: EmbedPreset,
  currentLanSsidsText: string,
  currentWifiSsid?: string | null,
): WebEmbedAdvancedValues {
  const base: WebEmbedAdvancedValues = {
    ...DEFAULT_WEB_EMBED_ADVANCED,
    ...preset.advanced,
  };
  if (currentLanSsidsText.trim().length > 0) {
    return { ...base, lanSsidsText: currentLanSsidsText };
  }
  if (base.lanSsidsText.trim().length > 0) {
    return base;
  }
  const detected = cleanWifiSsid(currentWifiSsid);
  return detected ? { ...base, lanSsidsText: detected } : base;
}

export interface CreateWebEmbedDraft {
  id: string;
  name: string;
  url: string;
  originsText: string;
  allowCleartext: boolean;
  keepAlive: boolean;
  globalScope: boolean;
  priority: number;
  advanced: WebEmbedAdvancedValues;
}

export const WEB_EMBED_ADVANCED_TOGGLES: readonly {
  key: 'showToolbar' | 'webHistoryBack' | 'wideViewport' | 'allowZoom';
  title: string;
  description: string;
}[] = [
  {
    key: 'showToolbar',
    title: '显示极简快捷顶栏',
    description: '在面板顶部提供地址状态、内外网切换、刷新与外部浏览器打开按钮',
  },
  {
    key: 'webHistoryBack',
    title: '网页内优先返回',
    description: '返回手势优先后退网页历史记录，到达根页后再收起侧边栏',
  },
  {
    key: 'wideViewport',
    title: '宽视口',
    description: '启用自适应宽视口布局，改善仪表盘与多列卡片显示',
  },
  {
    key: 'allowZoom',
    title: '允许双指缩放',
    description: '允许在网页区域内使用双指捏合手势缩放页面',
  },
];

/** Validates required id/name/URL fields for creating a custom or preset web embed. */
export function isValidCreateWebEmbedDraft(
  id: string,
  name: string,
  url: string,
  remoteUrl: string,
): boolean {
  const trimmedRemote = remoteUrl.trim();
  return (
    id.trim().length > 0 &&
    name.trim().length > 0 &&
    /^https?:\/\/.+/.test(url.trim()) &&
    (trimmedRemote.length === 0 || /^https?:\/\/.+/.test(trimmedRemote))
  );
}

/** Builds a normalized `CreateWebEmbedInput` payload from form draft values. */
export function buildCreateWebEmbedInput(draft: CreateWebEmbedDraft): CreateWebEmbedInput {
  const remoteUrl = draft.advanced.remoteUrl.trim();
  const origins = normalizeOriginList(draft.originsText);
  const lanSsids = normalizeSsidList(draft.advanced.lanSsidsText);
  return {
    id: draft.id.trim(),
    name: draft.name.trim(),
    url: draft.url.trim(),
    ...(remoteUrl.length > 0 ? { remoteUrl } : {}),
    ...(lanSsids.length > 0 ? { lanSsids } : {}),
    ...(origins.length > 0 ? { allowedOrigins: origins } : {}),
    allowCleartext: draft.allowCleartext,
    keepAlive: draft.keepAlive,
    wideViewport: draft.advanced.wideViewport,
    allowZoom: draft.advanced.allowZoom,
    webHistoryBack: draft.advanced.webHistoryBack,
    textZoom: clampEmbedNumber(draft.advanced.textZoom, 50, 200, 100),
    showToolbar: draft.advanced.showToolbar,
    keepAliveGraceSec: clampEmbedNumber(draft.advanced.keepAliveGraceSec, 0, 1800, 180),
    widthDp: clampEmbedNumber(draft.advanced.widthDp, 240, 720, 380),
    heightPercent: clampEmbedNumber(draft.advanced.heightPercent, 30, 100, 78),
    global: draft.globalScope,
    priority: draft.priority,
  };
}
