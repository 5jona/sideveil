import type { ExecResult } from '@gojona/bridge';
import type { PluginRuntimeDescriptor, PluginsSnapshot } from '../shared/types.ts';
import { INITIAL_MOCK_PLUGINS } from './mockPluginFixtures.ts';

/** Mutable in-memory plugin registry state used by local Control Plane mock bridge. */
export interface MockPluginState {
  activePluginId: string;
  plugins: PluginRuntimeDescriptor[];
}

/** Shared context passed to mock plugin CLI/RPC command handlers. */
export interface MockPluginHandlerCtx {
  state: MockPluginState;
  getSnapshot: () => PluginsSnapshot;
  appendLog: (msg: string) => void;
}

/** Wraps a JSON-serializable payload into a successful mock `ExecResult`. */
export function okJson(payload: unknown): ExecResult {
  return { errno: 0, stdout: JSON.stringify(payload), stderr: '' };
}

/** Wraps a result payload into a successful JSON-RPC 2.0 mock `ExecResult`. */
export function rpcOk(result: unknown): ExecResult {
  return okJson({ jsonrpc: '2.0', id: 'ctl', result });
}

/** Wraps an error message into a failed JSON-RPC 2.0 mock `ExecResult`. */
export function rpcErr(message: string): ExecResult {
  return {
    errno: 1,
    stdout: JSON.stringify({ jsonrpc: '2.0', id: 'ctl', error: { code: -32602, message } }),
    stderr: message,
  };
}

function extractOrigin(url: string): string | null {
  try {
    return new URL(url).origin;
  } catch {
    return null;
  }
}

function pickBool<T extends boolean | undefined>(val: unknown, fallback: T): boolean | T {
  return typeof val === 'boolean' ? val : fallback;
}

function pickInt<T extends number | undefined>(val: unknown, fallback: T): number | T {
  return typeof val === 'number' && Number.isFinite(val) ? Math.trunc(val) : fallback;
}

function pickStr(val: unknown): string | undefined {
  return typeof val === 'string' && val.trim() ? val.trim() : undefined;
}

function pickStrList(val: unknown): string[] | undefined {
  return Array.isArray(val)
    ? val
        .map(String)
        .map((s) => s.trim())
        .filter(Boolean)
    : undefined;
}

/** Handles mock `plugins.createWebEmbed` RPC or CLI creation calls. */
export function createEmbedPlugin(
  ctx: MockPluginHandlerCtx,
  params: Record<string, unknown>,
): ExecResult {
  const id = pickStr(params.id) ?? '';
  const name = pickStr(params.name) ?? '';
  const url = pickStr(params.url) ?? '';
  if (!id || !name || !url) return rpcErr('id、name 与 url 不能为空');
  if (ctx.state.plugins.some((p) => p.id === id)) return rpcErr(`插件 ${id} 已存在`);

  const origin = extractOrigin(url);
  if (!origin) return rpcErr(`无效的 URL: ${url}`);
  const remoteUrl = pickStr(params.remoteUrl);
  const remoteOrigin = remoteUrl ? extractOrigin(remoteUrl) : null;
  if (remoteUrl && !remoteOrigin) return rpcErr(`无效的备选 URL: ${remoteUrl}`);
  const defaultOrigins =
    remoteOrigin && remoteOrigin !== origin ? [origin, remoteOrigin] : [origin];
  const allowedOrigins = pickStrList(params.allowedOrigins) ?? defaultOrigins;
  const lanSsids = pickStrList(params.lanSsids);
  const allowCleartext = Boolean(params.allowCleartext);

  const nextPlugin: PluginRuntimeDescriptor = {
    id,
    name,
    version: '1.0.0',
    origin: 'web-embed',
    schemaVersion: 1,
    apiVersion: 1,
    entry: '',
    embed: {
      url,
      remoteUrl,
      lanSsids: lanSsids ?? [],
      allowedOrigins: allowedOrigins.length > 0 ? allowedOrigins : defaultOrigins,
      allowCleartext,
      wideViewport: pickBool(params.wideViewport, true),
      allowZoom: pickBool(params.allowZoom, false),
      webHistoryBack: pickBool(params.webHistoryBack, true),
      textZoom: pickInt(params.textZoom, undefined),
      showToolbar: pickBool(params.showToolbar, true),
      keepAliveGraceSec: pickInt(params.keepAliveGraceSec, undefined),
    },
    builtin: false,
    defaultEnabled: true,
    enabled: true,
    priority: pickInt(params.priority, 0),
    packages: [],
    activities: [],
    global: pickBool(params.global, true),
    hasScopeOverride: false,
    backend: 'none',
    backendState: 'none',
    backendPackages: [],
    capabilities: allowCleartext ? ['network-lan', 'network-context'] : ['network-context'],
    grantedCapabilities: allowCleartext ? ['network-lan', 'network-context'] : ['network-context'],
    pendingCapabilities: [],
    approvalStatus: 'approved',
    daemonApproved: true,
    daemonStatus: 'not-configured',
    daemonRestartCount: 0,
    hasDaemon: false,
    surface: {
      backdrop: 'opaque',
      presentation: 'drawer',
      crossOriginIsolated: false,
      keepAlive: Boolean(params.keepAlive),
      widthDp: pickInt(params.widthDp, 380),
      heightPercent: pickInt(params.heightPercent, 78),
    },
  };
  ctx.state.plugins = [...ctx.state.plugins, nextPlugin];
  ctx.appendLog(`Created web-embed plugin ${id} (${url})`);
  return rpcOk({ ok: true, pluginId: id, snapshot: ctx.getSnapshot() });
}

/** Handles mock `plugins.setEmbedConfig` override mutations. */
export function updateEmbedConfig(
  ctx: MockPluginHandlerCtx,
  params: Record<string, unknown>,
): ExecResult {
  const pluginId = pickStr(params.pluginId) ?? '';
  const target = ctx.state.plugins.find((p) => p.id === pluginId);
  if (!target?.embed) return rpcErr(`未找到网页嵌入插件: ${pluginId}`);
  const clear = Boolean(params.clear);
  const defaults = INITIAL_MOCK_PLUGINS.find((p) => p.id === pluginId);

  ctx.state.plugins = ctx.state.plugins.map((p) => {
    if (p.id !== pluginId || !p.embed) return p;
    if (clear && defaults?.embed) {
      return { ...p, embed: { ...defaults.embed }, surface: { ...p.surface, ...defaults.surface } };
    }
    const nextRemoteUrl =
      typeof params.remoteUrl === 'string' ? pickStr(params.remoteUrl) : p.embed.remoteUrl;
    return {
      ...p,
      embed: {
        ...p.embed,
        url: pickStr(params.embedUrl) ?? p.embed.url,
        remoteUrl: nextRemoteUrl,
        lanSsids: pickStrList(params.lanSsids) ?? p.embed.lanSsids,
        allowedOrigins: pickStrList(params.allowedOrigins) ?? p.embed.allowedOrigins,
        allowCleartext: pickBool(params.allowCleartext, p.embed.allowCleartext),
        wideViewport: pickBool(params.wideViewport, p.embed.wideViewport),
        allowZoom: pickBool(params.allowZoom, p.embed.allowZoom),
        webHistoryBack: pickBool(params.webHistoryBack, p.embed.webHistoryBack),
        textZoom: pickInt(params.textZoom, p.embed.textZoom),
        showToolbar: pickBool(params.showToolbar, p.embed.showToolbar),
        keepAliveGraceSec: pickInt(params.keepAliveGraceSec, p.embed.keepAliveGraceSec),
      },
      surface: {
        ...p.surface,
        keepAlive: pickBool(params.keepAlive, p.surface.keepAlive),
        widthDp: pickInt(params.widthDp, p.surface.widthDp),
        heightPercent: pickInt(params.heightPercent, p.surface.heightPercent),
      },
    };
  });
  ctx.appendLog(`Updated embed config for ${pluginId}`);
  return rpcOk(ctx.getSnapshot());
}

/** Handles mock `plugins.uninstall` removals while protecting built-in and bundled plugins. */
export function uninstallMockPlugin(ctx: MockPluginHandlerCtx, pluginId: string): ExecResult {
  const target = ctx.state.plugins.find((p) => p.id === pluginId);
  if (!target) return rpcErr(`未找到插件: ${pluginId}`);
  if (target.builtin || target.origin === 'bundled') {
    return rpcErr('内置或模块随附插件不允许卸载');
  }
  ctx.state.plugins = ctx.state.plugins.filter((p) => p.id !== pluginId);
  if (ctx.state.activePluginId === pluginId) {
    ctx.state.activePluginId = 'sideveil.diagnostic';
  }
  ctx.appendLog(`Uninstalled plugin ${pluginId}`);
  return rpcOk({ ok: true, pluginId, snapshot: ctx.getSnapshot() });
}
