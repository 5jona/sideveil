import { shellQuote } from '@gojona/bridge';
import type { PluginDescriptor, PluginsSnapshotPayload } from '@gojona/sideveil-sdk';
import { CTL_BIN, SIDEVEIL_METHODS } from '../../shared/types.ts';

/** Scope override mutation input passed to `plugins.setScopeOverride`. */
export interface PluginScopeOverrideInput {
  global?: boolean;
  packages?: readonly string[];
  clear?: boolean;
}

/** Creation parameters for zero-privilege `web-embed` plugins. */
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

/** Override mutation parameters for an existing `web-embed` plugin. */
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

/** Splits comma/newline-delimited origin strings into a trimmed, deduplicated list. */
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

/** Splits comma/newline-delimited Wi-Fi SSID strings into a trimmed, deduplicated list. */
export function normalizeSsidList(raw: string): string[] {
  return normalizeOriginList(raw);
}

/** Truncates and clamps an optional finite integer parameter within `[min, max]`. */
export function clampEmbedNumber(
  value: number | undefined,
  min: number,
  max: number,
  fallback: number,
): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return fallback;
  return Math.min(max, Math.max(min, Math.trunc(value)));
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function isPluginDescriptor(value: unknown): value is PluginDescriptor {
  return isRecord(value) && typeof value.id === 'string' && typeof value.name === 'string';
}

function toPluginsSnapshot(record: Record<string, unknown>): PluginsSnapshotPayload | null {
  if (!Array.isArray(record.plugins)) return null;
  const plugins = record.plugins.filter(isPluginDescriptor);
  const matchedPluginIds = Array.isArray(record.matchedPluginIds)
    ? record.matchedPluginIds.filter((item): item is string => typeof item === 'string')
    : [];
  const activePluginId = typeof record.activePluginId === 'string' ? record.activePluginId : null;
  const foregroundPackage =
    typeof record.foregroundPackage === 'string' ? record.foregroundPackage : '';
  const foregroundActivity =
    typeof record.foregroundActivity === 'string' ? record.foregroundActivity : undefined;
  return {
    plugins,
    matchedPluginIds,
    activePluginId,
    foregroundPackage,
    ...(foregroundActivity !== undefined ? { foregroundActivity } : {}),
  };
}

/** Unwraps a `PluginsSnapshotPayload` from either a direct snapshot or a JSON-RPC result envelope. */
export function extractSnapshotPayload(value: unknown): PluginsSnapshotPayload | null {
  if (!isRecord(value)) return null;
  const direct = toPluginsSnapshot(value);
  if (direct) return direct;
  if (isRecord(value.snapshot)) {
    return extractSnapshotPayload(value.snapshot);
  }
  if (isRecord(value.result)) {
    return extractSnapshotPayload(value.result);
  }
  return null;
}

/** Parses JSON stdout returned by `sideveil-ctl` into a `PluginsSnapshotPayload`. */
export function parsePluginsSnapshot(stdout: string): PluginsSnapshotPayload | null {
  const trimmed = stdout.trim();
  if (!trimmed) return null;
  try {
    return extractSnapshotPayload(JSON.parse(trimmed));
  } catch {
    return null;
  }
}

/**
 * Inspects JSON stdout from `sideveil-ctl` and returns `true` only when the top-level
 * JSON-RPC envelope contains a truthy `error` field.
 */
export function hasRpcErrorPayload(stdout: string): boolean {
  const trimmed = stdout.trim();
  if (!trimmed) return false;
  try {
    const parsed: unknown = JSON.parse(trimmed);
    return isRecord(parsed) && Boolean(parsed.error);
  } catch {
    return false;
  }
}

/** Extracts the human-readable error message from a failed `sideveil-ctl` JSON-RPC response. */
export function extractRpcErrorMessage(stdout: string, stderr: string): string {
  const trimmed = stdout.trim();
  if (trimmed) {
    try {
      const parsed: unknown = JSON.parse(trimmed);
      if (isRecord(parsed) && isRecord(parsed.error) && typeof parsed.error.message === 'string') {
        const msg = parsed.error.message.trim();
        if (msg) return msg;
      }
    } catch {
      // Fall through to raw stderr/stdout.
    }
  }
  return stderr.trim() || trimmed || '未知错误';
}

/** Builds the shell-quoted `sideveil-ctl plugins scope` CLI command for a scope override. */
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

/** Builds normalized JSON-RPC parameters for `plugins.createWebEmbed`. */
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

/** Builds normalized JSON-RPC parameters for `plugins.setEmbedConfig`. */
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

/** Builds the shell-quoted `sideveil-ctl rpc plugins.createWebEmbed` CLI command. */
export function buildCreateWebEmbedCommand(input: CreateWebEmbedInput): string {
  const payload = JSON.stringify(buildCreateWebEmbedParams(input));
  return `${shellQuote(CTL_BIN)} rpc ${SIDEVEIL_METHODS.PLUGINS_CREATE_WEB_EMBED} ${shellQuote(payload)}`;
}

/** Builds the shell-quoted `sideveil-ctl rpc plugins.setEmbedConfig` CLI command. */
export function buildSetEmbedConfigCommand(
  pluginId: string,
  input: PluginEmbedConfigInput,
): string {
  const payload = JSON.stringify(buildSetEmbedConfigParams(pluginId, input));
  return `${shellQuote(CTL_BIN)} rpc ${SIDEVEIL_METHODS.PLUGINS_SET_EMBED_CONFIG} ${shellQuote(payload)}`;
}

/** Builds the shell-quoted `sideveil-ctl plugins uninstall` CLI command. */
export function buildUninstallPluginCommand(pluginId: string): string {
  return `${shellQuote(CTL_BIN)} plugins uninstall ${shellQuote(pluginId)}`;
}
