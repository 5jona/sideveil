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
  allowedOrigins?: readonly string[];
  allowCleartext?: boolean;
  keepAlive?: boolean;
  global?: boolean;
  priority?: number;
}

export interface PluginEmbedConfigInput {
  embedUrl?: string;
  allowedOrigins?: readonly string[];
  allowCleartext?: boolean;
  keepAlive?: boolean;
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

export function buildCreateWebEmbedParams(input: CreateWebEmbedInput): Record<string, unknown> {
  const allowedOrigins = input.allowedOrigins
    ? Array.from(new Set(input.allowedOrigins.map((o) => o.trim()).filter(Boolean)))
    : undefined;
  return {
    id: input.id.trim(),
    name: input.name.trim(),
    url: input.url.trim(),
    ...(allowedOrigins && allowedOrigins.length > 0 ? { allowedOrigins } : {}),
    ...(typeof input.allowCleartext === 'boolean' ? { allowCleartext: input.allowCleartext } : {}),
    ...(typeof input.keepAlive === 'boolean' ? { keepAlive: input.keepAlive } : {}),
    ...(typeof input.global === 'boolean' ? { global: input.global } : {}),
    ...(typeof input.priority === 'number' && Number.isFinite(input.priority)
      ? { priority: Math.trunc(input.priority) }
      : {}),
  };
}

export function buildSetEmbedConfigParams(
  pluginId: string,
  input: PluginEmbedConfigInput,
): Record<string, unknown> {
  if (input.clear) {
    return { pluginId, clear: true };
  }
  const allowedOrigins = input.allowedOrigins
    ? Array.from(new Set(input.allowedOrigins.map((o) => o.trim()).filter(Boolean)))
    : undefined;
  return {
    pluginId,
    ...(input.embedUrl !== undefined ? { embedUrl: input.embedUrl.trim() } : {}),
    ...(allowedOrigins !== undefined ? { allowedOrigins } : {}),
    ...(typeof input.allowCleartext === 'boolean' ? { allowCleartext: input.allowCleartext } : {}),
    ...(typeof input.keepAlive === 'boolean' ? { keepAlive: input.keepAlive } : {}),
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
