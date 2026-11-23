import { shellQuote } from '@pochita/bridge';
import type { PluginsSnapshotPayload } from '@pochita/sideveil-sdk';
import { CTL_BIN, SIDEVEIL_METHODS } from '../../shared/types.ts';

export interface PluginScopeOverrideInput {
  global?: boolean;
  packages?: readonly string[];
  clear?: boolean;
}

export interface CreateWebEmbedInput {
  id: string;
  name: string;
  url: string;
  remoteUrl?: string;
  lanSsids?: readonly string[];
  allowedOrigins?: readonly string[];
  allowCleartext?: boolean;
  keepAlive?: boolean;
  wideViewport?: boolean;
  allowZoom?: boolean;
  webHistoryBack?: boolean;
  textZoom?: number;
  showToolbar?: boolean;
  keepAliveGraceSec?: number;
  widthDp?: number;
  heightPercent?: number;
  global?: boolean;
  priority?: number;
}

export interface PluginEmbedConfigInput {
  embedUrl?: string;
  remoteUrl?: string;
  lanSsids?: readonly string[];
  allowedOrigins?: readonly string[];
  allowCleartext?: boolean;
  keepAlive?: boolean;
  wideViewport?: boolean;
  allowZoom?: boolean;
  webHistoryBack?: boolean;
  textZoom?: number;
  showToolbar?: boolean;
  keepAliveGraceSec?: number;
  widthDp?: number;
  heightPercent?: number;
  clear?: boolean;
}

export function normalizeOriginList(raw: string): string[] {
  return Array.from(
    new Set(
      raw
        .split(/[\n,]/)
        .map((item) => item.trim())
        .filter(Boolean),
    ),
  );
}

export function normalizeSsidList(raw: string): string[] {
  return normalizeOriginList(raw);
}

export function clampEmbedNumber(
  value: number | undefined,
  min: number,
  max: number,
  fallback: number,
): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return fallback;
  return Math.min(max, Math.max(min, Math.trunc(value)));
}

export function extractSnapshotPayload(value: unknown): PluginsSnapshotPayload | null {
  if (!value || typeof value !== 'object') return null;
  const record = value as Record<string, unknown>;
  if (Array.isArray(record.plugins)) {
    return record as unknown as PluginsSnapshotPayload;
  }
  if (record.snapshot && typeof record.snapshot === 'object') {
    return extractSnapshotPayload(record.snapshot);
  }
  if (record.result && typeof record.result === 'object') {
    return extractSnapshotPayload(record.result);
  }
  return null;
}

export function parsePluginsSnapshot(stdout: string): PluginsSnapshotPayload | null {
  const trimmed = stdout.trim();
  if (!trimmed) return null;
  try {
    return extractSnapshotPayload(JSON.parse(trimmed));
  } catch {
    return null;
  }
}

export function extractRpcErrorMessage(stdout: string, stderr: string): string {
  const trimmed = stdout.trim();
  if (trimmed) {
    try {
      const parsed = JSON.parse(trimmed) as { error?: { message?: string } };
      if (typeof parsed?.error?.message === 'string' && parsed.error.message.trim()) {
        return parsed.error.message.trim();
      }
    } catch {
      // Fall through to raw stderr/stdout.
    }
  }
  return stderr.trim() || trimmed || '未知错误';
}

export function buildPluginScopeCommand(
  pluginId: string,
  override: PluginScopeOverrideInput,
): string {
  const parts = [shellQuote(CTL_BIN), 'plugins', 'scope', shellQuote(pluginId)];
  if (override.clear) {
    parts.push('--clear');
    return parts.join(' ');
  }
  if (typeof override.global === 'boolean') {
    parts.push('--global', override.global ? 'true' : 'false');
  }
  if (Array.isArray(override.packages)) {
    const cleanPackages = Array.from(
      new Set(override.packages.map((p) => p.trim()).filter(Boolean)),
    );
    parts.push('--packages', shellQuote(cleanPackages.join(',')));
  }
  return parts.join(' ');
}

function cleanStringArray(items?: readonly string[]): string[] | undefined {
  if (!items) return undefined;
  return Array.from(new Set(items.map((o) => o.trim()).filter(Boolean)));
}

function finiteInt(value: number | undefined): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? Math.trunc(value) : undefined;
}

export function buildCreateWebEmbedParams(input: CreateWebEmbedInput): Record<string, unknown> {
  const remoteUrl = input.remoteUrl?.trim();
  const lanSsids = cleanStringArray(input.lanSsids);
  const allowedOrigins = cleanStringArray(input.allowedOrigins);
  const textZoom = finiteInt(input.textZoom);
  const keepAliveGraceSec = finiteInt(input.keepAliveGraceSec);
  const widthDp = finiteInt(input.widthDp);
  const heightPercent = finiteInt(input.heightPercent);
  const priority = finiteInt(input.priority);

  return {
    id: input.id.trim(),
    name: input.name.trim(),
    url: input.url.trim(),
    ...(remoteUrl ? { remoteUrl } : {}),
    ...(lanSsids && lanSsids.length > 0 ? { lanSsids } : {}),
    ...(allowedOrigins && allowedOrigins.length > 0 ? { allowedOrigins } : {}),
    ...(typeof input.allowCleartext === 'boolean' ? { allowCleartext: input.allowCleartext } : {}),
    ...(typeof input.keepAlive === 'boolean' ? { keepAlive: input.keepAlive } : {}),
    ...(typeof input.wideViewport === 'boolean' ? { wideViewport: input.wideViewport } : {}),
    ...(typeof input.allowZoom === 'boolean' ? { allowZoom: input.allowZoom } : {}),
    ...(typeof input.webHistoryBack === 'boolean' ? { webHistoryBack: input.webHistoryBack } : {}),
    ...(textZoom !== undefined ? { textZoom } : {}),
    ...(typeof input.showToolbar === 'boolean' ? { showToolbar: input.showToolbar } : {}),
    ...(keepAliveGraceSec !== undefined ? { keepAliveGraceSec } : {}),
    ...(widthDp !== undefined ? { widthDp } : {}),
    ...(heightPercent !== undefined ? { heightPercent } : {}),
    ...(typeof input.global === 'boolean' ? { global: input.global } : {}),
    ...(priority !== undefined ? { priority } : {}),
  };
}

export function buildSetEmbedConfigParams(
  pluginId: string,
  input: PluginEmbedConfigInput,
): Record<string, unknown> {
  if (input.clear) {
    return { pluginId, clear: true };
  }
  const lanSsids = cleanStringArray(input.lanSsids);
  const allowedOrigins = cleanStringArray(input.allowedOrigins);
  const textZoom = finiteInt(input.textZoom);
  const keepAliveGraceSec = finiteInt(input.keepAliveGraceSec);
  const widthDp = finiteInt(input.widthDp);
  const heightPercent = finiteInt(input.heightPercent);

  return {
    pluginId,
    ...(input.embedUrl !== undefined ? { embedUrl: input.embedUrl.trim() } : {}),
    ...(input.remoteUrl !== undefined ? { remoteUrl: input.remoteUrl.trim() } : {}),
    ...(lanSsids !== undefined ? { lanSsids } : {}),
    ...(allowedOrigins !== undefined ? { allowedOrigins } : {}),
    ...(typeof input.allowCleartext === 'boolean' ? { allowCleartext: input.allowCleartext } : {}),
    ...(typeof input.keepAlive === 'boolean' ? { keepAlive: input.keepAlive } : {}),
    ...(typeof input.wideViewport === 'boolean' ? { wideViewport: input.wideViewport } : {}),
    ...(typeof input.allowZoom === 'boolean' ? { allowZoom: input.allowZoom } : {}),
    ...(typeof input.webHistoryBack === 'boolean' ? { webHistoryBack: input.webHistoryBack } : {}),
    ...(textZoom !== undefined ? { textZoom } : {}),
    ...(typeof input.showToolbar === 'boolean' ? { showToolbar: input.showToolbar } : {}),
    ...(keepAliveGraceSec !== undefined ? { keepAliveGraceSec } : {}),
    ...(widthDp !== undefined ? { widthDp } : {}),
    ...(heightPercent !== undefined ? { heightPercent } : {}),
  };
}

export function buildCreateWebEmbedCommand(input: CreateWebEmbedInput): string {
  const payload = JSON.stringify(buildCreateWebEmbedParams(input));
  return `${shellQuote(CTL_BIN)} rpc ${SIDEVEIL_METHODS.PLUGINS_CREATE_WEB_EMBED} ${shellQuote(payload)}`;
}

export function buildSetEmbedConfigCommand(
  pluginId: string,
  input: PluginEmbedConfigInput,
): string {
  const payload = JSON.stringify(buildSetEmbedConfigParams(pluginId, input));
  return `${shellQuote(CTL_BIN)} rpc ${SIDEVEIL_METHODS.PLUGINS_SET_EMBED_CONFIG} ${shellQuote(payload)}`;
}

export function buildUninstallPluginCommand(pluginId: string): string {
  return `${shellQuote(CTL_BIN)} plugins uninstall ${shellQuote(pluginId)}`;
}
