import type { ExecResult } from '@gojona/bridge';
import type {
  PluginArchiveInspection,
  PluginCapability,
  PluginCapabilityReviewItem,
  PluginRuntimeDescriptor,
} from '../shared/types.ts';
import { type MockPluginHandlerCtx, rpcErr, rpcOk } from './mockPluginEmbedHandlers.ts';
import { INITIAL_MOCK_DAEMON_LOGS } from './mockPluginFixtures.ts';
import { CAPABILITY_CATALOG } from './pluginMeta.ts';

function deriveIdFromSource(params: Record<string, unknown>): string {
  const rawName = String(
    params.filename ?? params.path ?? params.sourcePath ?? params.sourceDir ?? '',
  );
  const base = rawName
    .split(/[/\\]/)
    .pop()
    ?.replace(/\.(svp|zip)$/i, '')
    .toLowerCase()
    .replace(/[^a-z0-9._-]+/g, '-');
  return base && base.length >= 3 ? `community.${base}` : 'community.demo-tool';
}

export function grantMockCapabilities(
  ctx: MockPluginHandlerCtx,
  params: Record<string, unknown>,
): ExecResult {
  const pluginId = typeof params.pluginId === 'string' ? params.pluginId.trim() : '';
  const target = ctx.state.plugins.find((p) => p.id === pluginId);
  if (!target) return rpcErr(`未找到插件: ${pluginId}`);

  ctx.state.plugins = ctx.state.plugins.map((plugin) => {
    if (plugin.id !== pluginId) return plugin;
    let nextGranted = [...plugin.grantedCapabilities];
    if (params.revokeAll === true) {
      nextGranted = nextGranted.filter(
        (c) => c === 'toast' || c === 'haptics' || c === 'telemetry',
      );
    } else if (params.grantAllPending === true) {
      nextGranted = Array.from(new Set([...plugin.grantedCapabilities, ...plugin.capabilities]));
    } else if (Array.isArray(params.capabilities)) {
      const requested = new Set(
        params.capabilities.filter((c): c is PluginCapability => typeof c === 'string'),
      );
      nextGranted = plugin.capabilities.filter((c) => requested.has(c));
    }

    const nextPending = plugin.capabilities.filter((c) => !nextGranted.includes(c));
    const nextDaemonApproved =
      typeof params.approveDaemon === 'boolean'
        ? params.approveDaemon
        : params.revokeAll === true && plugin.hasDaemon
          ? false
          : plugin.daemonApproved;
    const nextDaemonStatus = !plugin.hasDaemon
      ? 'not-configured'
      : nextDaemonApproved
        ? 'running'
        : 'awaiting-approval';

    return {
      ...plugin,
      grantedCapabilities: nextGranted,
      pendingCapabilities: nextPending,
      approvalStatus: nextPending.length === 0 ? 'approved' : 'pending-review',
      daemonApproved: nextDaemonApproved,
      daemonStatus: nextDaemonStatus,
      daemonPid: nextDaemonStatus === 'running' ? 16240 : undefined,
    };
  });

  ctx.appendLog(`Updated capability grants for ${pluginId}`);
  return rpcOk(ctx.getSnapshot());
}

export function inspectMockArchive(
  ctx: MockPluginHandlerCtx,
  params: Record<string, unknown>,
): ExecResult {
  const hasSource = Boolean(
    (typeof params.path === 'string' && params.path.trim()) ||
      (typeof params.archiveBase64 === 'string' && params.archiveBase64.trim()),
  );
  if (!hasSource) return rpcErr('请提供本地 .svp 压缩包或设备目录路径');

  const id = deriveIdFromSource(params);
  const existing = ctx.state.plugins.find((p) => p.id === id);
  const requestedCaps: PluginCapability[] = existing
    ? Array.from(new Set([...existing.capabilities, 'network-lan']))
    : ['storage', 'toast', 'network-loopback'];
  const isUpgrade = Boolean(existing);
  const hasDaemon = Boolean(existing?.hasDaemon);
  const daemonAlreadyApproved = Boolean(existing?.hasDaemon && existing.daemonApproved);

  const capabilities: PluginCapabilityReviewItem[] = requestedCaps.map((cap) => {
    const risk = CAPABILITY_CATALOG[cap]?.tier ?? 'medium';
    const requiresApproval = risk !== 'low';
    const alreadyGranted = existing
      ? existing.grantedCapabilities.includes(cap)
      : !requiresApproval;
    const isNew = !isUpgrade || !existing?.capabilities.includes(cap);
    return { capability: cap, risk, requiresApproval, isNew, alreadyGranted };
  });

  const warnings: string[] = [];
  if (hasDaemon) {
    warnings.push('插件声明了 uds-native 原生后台进程，需显式授权后方可启动。');
  }
  if (isUpgrade && capabilities.some((item) => item.isNew && item.requiresApproval)) {
    warnings.push('此版本新增了需显式审查的权限，请确认后再完成升级。');
  }

  const result: PluginArchiveInspection = {
    manifest: {
      schemaVersion: 1,
      apiVersion: 1,
      id,
      name: existing?.name ?? `导入插件 (${id.split('.').pop() ?? 'demo'})`,
      version: existing ? '1.1.0' : '1.0.0',
      description: '通过本地 .svp / 目录导入的第三方侧边栏插件',
      author: 'Third-Party Developer',
      entry: 'web/index.html',
      backend: hasDaemon ? 'uds-native' : 'none',
      capabilities: requestedCaps,
      triggers: { packages: [], global: true, priority: 10 },
      surface: { backdrop: 'native-glass', presentation: 'drawer' },
    },
    isUpgrade,
    ...(existing ? { existingVersion: existing.version } : {}),
    capabilities,
    hasDaemon,
    daemonAlreadyApproved,
    warnings,
  };
  ctx.appendLog(`Inspected archive for ${id}`);
  return rpcOk(result);
}

export function installMockPlugin(
  ctx: MockPluginHandlerCtx,
  params: Record<string, unknown>,
): ExecResult {
  const hasSource = Boolean(
    (typeof params.sourceDir === 'string' && params.sourceDir.trim()) ||
      (typeof params.sourcePath === 'string' && params.sourcePath.trim()) ||
      (typeof params.archiveBase64 === 'string' && params.archiveBase64.trim()),
  );
  if (!hasSource) return rpcErr('缺少安装源路径或插件归档数据');

  const id = deriveIdFromSource(params);
  const capabilities: PluginCapability[] = ['storage', 'toast', 'network-loopback'];
  const granted: PluginCapability[] = Array.isArray(params.grantedCapabilities)
    ? (params.grantedCapabilities.filter(
        (c): c is PluginCapability => typeof c === 'string',
      ) as PluginCapability[])
    : ['toast'];
  const pending = capabilities.filter((c) => !granted.includes(c));

  const nextPlugin: PluginRuntimeDescriptor = {
    id,
    name: `导入插件 (${id.split('.').pop() ?? 'demo'})`,
    version: '1.0.0',
    origin: 'installed',
    schemaVersion: 1,
    apiVersion: 1,
    description: '通过本地 .svp / 目录导入的第三方侧边栏插件',
    author: 'Third-Party Developer',
    entry: 'web/index.html',
    builtin: false,
    defaultEnabled: true,
    enabled: true,
    priority: 10,
    packages: [],
    activities: [],
    global: true,
    hasScopeOverride: false,
    backend: 'none',
    backendState: 'none',
    backendPackages: [],
    capabilities,
    grantedCapabilities: granted,
    pendingCapabilities: pending,
    approvalStatus: pending.length === 0 ? 'approved' : 'pending-review',
    daemonApproved: true,
    daemonStatus: 'not-configured',
    daemonRestartCount: 0,
    hasDaemon: false,
    surface: {
      backdrop: 'native-glass',
      presentation: 'drawer',
      crossOriginIsolated: false,
      keepAlive: false,
    },
  };

  const withoutExisting = ctx.state.plugins.filter((p) => p.id !== id);
  ctx.state.plugins = [...withoutExisting, nextPlugin];
  ctx.appendLog(`Installed plugin ${id} v${nextPlugin.version}`);
  return rpcOk({
    ok: true,
    pluginId: id,
    version: nextPlugin.version,
    snapshot: ctx.getSnapshot(),
  });
}

export function fetchMockDaemonLogs(
  ctx: MockPluginHandlerCtx,
  params: Record<string, unknown>,
): ExecResult {
  const pluginId = typeof params.pluginId === 'string' ? params.pluginId.trim() : '';
  const limit =
    typeof params.limit === 'number' && params.limit > 0 ? Math.trunc(params.limit) : 100;
  const lines = (
    INITIAL_MOCK_DAEMON_LOGS[pluginId] ?? [`[info] ${pluginId} 暂无后台错误输出`]
  ).slice(-limit);
  ctx.appendLog(`Fetched daemon logs for ${pluginId}`);
  return rpcOk({ pluginId, lines });
}

export function reloadMockWebView(
  ctx: MockPluginHandlerCtx,
  params: Record<string, unknown>,
): ExecResult {
  const pluginId = typeof params.pluginId === 'string' ? params.pluginId.trim() : '';
  ctx.appendLog(`Reloaded WebView for ${pluginId}`);
  return rpcOk({ ok: true, pluginId, snapshot: ctx.getSnapshot() });
}
