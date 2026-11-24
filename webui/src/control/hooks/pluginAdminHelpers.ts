import { shellQuote } from '@gojona/bridge';
import {
  CTL_BIN,
  type PluginArchiveInspection,
  type PluginCapability,
  type PluginDaemonLogsResult,
  type PluginRuntimeDescriptor,
  SIDEVEIL_METHODS,
} from '../../shared/types.ts';

export interface PluginGrantCapabilitiesInput {
  pluginId: string;
  capabilities?: readonly PluginCapability[];
  grantAllPending?: boolean;
  revokeAll?: boolean;
  approveDaemon?: boolean;
}

export interface PluginInspectArchiveInput {
  path?: string;
  archiveBase64?: string;
  filename?: string;
}

export interface PluginInstallInput {
  sourceDir?: string;
  sourcePath?: string;
  archiveBase64?: string;
  filename?: string;
  grantedCapabilities?: readonly PluginCapability[];
  approveDaemon?: boolean;
}

/** Normalizes a partial runtime descriptor (backward compatible with older snapshots). */
export function normalizeRuntimeDescriptor(raw: Record<string, unknown>): PluginRuntimeDescriptor {
  const builtin = Boolean(raw.builtin);
  const hasEmbed = Boolean(raw.embed);
  const rawOrigin = raw.origin;
  const origin =
    rawOrigin === 'builtin' ||
    rawOrigin === 'bundled' ||
    rawOrigin === 'installed' ||
    rawOrigin === 'web-embed'
      ? rawOrigin
      : hasEmbed
        ? 'web-embed'
        : builtin
          ? 'builtin'
          : 'installed';
  const capabilities = Array.isArray(raw.capabilities)
    ? (raw.capabilities.filter(
        (c): c is PluginCapability => typeof c === 'string',
      ) as PluginCapability[])
    : [];
  const grantedCapabilities = Array.isArray(raw.grantedCapabilities)
    ? (raw.grantedCapabilities.filter(
        (c): c is PluginCapability => typeof c === 'string',
      ) as PluginCapability[])
    : capabilities;
  const pendingCapabilities = Array.isArray(raw.pendingCapabilities)
    ? (raw.pendingCapabilities.filter(
        (c): c is PluginCapability => typeof c === 'string',
      ) as PluginCapability[])
    : [];
  const rawSurface =
    raw.surface && typeof raw.surface === 'object' ? (raw.surface as Record<string, unknown>) : {};

  return {
    ...(raw as unknown as PluginRuntimeDescriptor),
    origin,
    schemaVersion: typeof raw.schemaVersion === 'number' ? raw.schemaVersion : 1,
    apiVersion: typeof raw.apiVersion === 'number' ? raw.apiVersion : 1,
    entry: typeof raw.entry === 'string' ? raw.entry : '',
    builtin,
    defaultEnabled: typeof raw.defaultEnabled === 'boolean' ? raw.defaultEnabled : true,
    enabled: Boolean(raw.enabled),
    priority: typeof raw.priority === 'number' ? raw.priority : 0,
    packages: Array.isArray(raw.packages)
      ? raw.packages.filter((p): p is string => typeof p === 'string')
      : [],
    activities: Array.isArray(raw.activities)
      ? raw.activities.filter((a): a is string => typeof a === 'string')
      : [],
    global: Boolean(raw.global),
    hasScopeOverride: Boolean(raw.hasScopeOverride),
    backend: (raw.backend as PluginRuntimeDescriptor['backend']) ?? 'none',
    backendState: (raw.backendState as PluginRuntimeDescriptor['backendState']) ?? 'none',
    backendPackages: Array.isArray(raw.backendPackages)
      ? raw.backendPackages.filter((p): p is string => typeof p === 'string')
      : [],
    capabilities,
    grantedCapabilities,
    pendingCapabilities,
    approvalStatus:
      raw.approvalStatus === 'pending-review' || raw.approvalStatus === 'needs-reapproval'
        ? raw.approvalStatus
        : 'approved',
    daemonApproved: typeof raw.daemonApproved === 'boolean' ? raw.daemonApproved : !raw.hasDaemon,
    daemonStatus:
      typeof raw.daemonStatus === 'string'
        ? (raw.daemonStatus as PluginRuntimeDescriptor['daemonStatus'])
        : 'not-configured',
    daemonRestartCount: typeof raw.daemonRestartCount === 'number' ? raw.daemonRestartCount : 0,
    hasDaemon: Boolean(raw.hasDaemon),
    surface: {
      backdrop:
        (rawSurface.backdrop as PluginRuntimeDescriptor['surface']['backdrop']) ?? 'native-glass',
      presentation:
        (rawSurface.presentation as PluginRuntimeDescriptor['surface']['presentation']) ?? 'drawer',
      crossOriginIsolated: Boolean(rawSurface.crossOriginIsolated),
      keepAlive: Boolean(rawSurface.keepAlive),
      ...(typeof rawSurface.widthDp === 'number' ? { widthDp: rawSurface.widthDp } : {}),
      ...(typeof rawSurface.heightPercent === 'number'
        ? { heightPercent: rawSurface.heightPercent }
        : {}),
    },
  };
}

export function buildGrantCapabilitiesParams(
  input: PluginGrantCapabilitiesInput,
): Record<string, unknown> {
  return {
    pluginId: input.pluginId,
    ...(input.capabilities !== undefined ? { capabilities: [...input.capabilities] } : {}),
    ...(typeof input.grantAllPending === 'boolean'
      ? { grantAllPending: input.grantAllPending }
      : {}),
    ...(typeof input.revokeAll === 'boolean' ? { revokeAll: input.revokeAll } : {}),
    ...(typeof input.approveDaemon === 'boolean' ? { approveDaemon: input.approveDaemon } : {}),
  };
}

export function buildGrantCapabilitiesCommand(input: PluginGrantCapabilitiesInput): string {
  const payload = JSON.stringify(buildGrantCapabilitiesParams(input));
  return `${shellQuote(CTL_BIN)} rpc ${SIDEVEIL_METHODS.PLUGINS_GRANT_CAPABILITIES} ${shellQuote(payload)}`;
}

export function buildInspectArchiveParams(
  input: PluginInspectArchiveInput,
): Record<string, unknown> {
  return {
    ...(input.path?.trim() ? { path: input.path.trim() } : {}),
    ...(input.archiveBase64 ? { archiveBase64: input.archiveBase64 } : {}),
    ...(input.filename?.trim() ? { filename: input.filename.trim() } : {}),
  };
}

export function buildInspectArchiveCommand(input: PluginInspectArchiveInput): string {
  const payload = JSON.stringify(buildInspectArchiveParams(input));
  return `${shellQuote(CTL_BIN)} rpc ${SIDEVEIL_METHODS.PLUGINS_INSPECT_ARCHIVE} ${shellQuote(payload)}`;
}

export function buildInstallPluginParams(input: PluginInstallInput): Record<string, unknown> {
  return {
    ...(input.sourceDir?.trim() ? { sourceDir: input.sourceDir.trim() } : {}),
    ...(input.sourcePath?.trim() ? { sourcePath: input.sourcePath.trim() } : {}),
    ...(input.archiveBase64 ? { archiveBase64: input.archiveBase64 } : {}),
    ...(input.filename?.trim() ? { filename: input.filename.trim() } : {}),
    ...(input.grantedCapabilities !== undefined
      ? { grantedCapabilities: [...input.grantedCapabilities] }
      : {}),
    ...(typeof input.approveDaemon === 'boolean' ? { approveDaemon: input.approveDaemon } : {}),
  };
}

export function buildInstallPluginCommand(input: PluginInstallInput): string {
  const payload = JSON.stringify(buildInstallPluginParams(input));
  return `${shellQuote(CTL_BIN)} rpc ${SIDEVEIL_METHODS.PLUGINS_INSTALL} ${shellQuote(payload)}`;
}

export function buildDaemonLogsCommand(pluginId: string, limit = 100): string {
  const payload = JSON.stringify({ pluginId, limit });
  return `${shellQuote(CTL_BIN)} rpc ${SIDEVEIL_METHODS.PLUGINS_DAEMON_LOGS} ${shellQuote(payload)}`;
}

export function buildReloadWebViewCommand(pluginId: string): string {
  const payload = JSON.stringify({ pluginId });
  return `${shellQuote(CTL_BIN)} rpc ${SIDEVEIL_METHODS.PLUGINS_RELOAD_WEBVIEW} ${shellQuote(payload)}`;
}

export function unwrapRpcJsonResult<T>(stdout: string): T | null {
  const trimmed = stdout.trim();
  if (!trimmed) return null;
  try {
    const parsed = JSON.parse(trimmed) as { result?: T } & T;
    if (parsed && typeof parsed === 'object' && 'result' in parsed && parsed.result !== undefined) {
      return parsed.result;
    }
    return parsed as T;
  } catch {
    return null;
  }
}

export {
  assertAdminRpcAllowed,
  CTL_ONLY_ADMIN_METHODS,
  HOST_ADMIN_BLOCKED_MESSAGE,
  type ShellExecFn,
  type StagedSvpArchive,
  type StageSvpArchiveOptions,
  SVP_UPLOAD_CHUNK_CHARS,
  splitBase64IntoChunks,
  stageSvpArchiveForKsu,
  withStagedSvpArchiveForKsu,
} from './svpUploadStaging.ts';
export type { PluginArchiveInspection, PluginDaemonLogsResult };
