import type { ExecResult } from '@pochita/bridge';
import type { PluginDescriptor, PluginsSnapshotPayload } from '@pochita/sideveil-sdk';
import { SIDEVEIL_METHODS } from '../shared/types.ts';
import { buildMockAuditReport, INITIAL_MOCK_PLUGINS } from './mockPluginFixtures.ts';

export interface MockPluginState {
  activePluginId: string;
  plugins: PluginDescriptor[];
}

export interface MockPluginHandlerCtx {
  state: MockPluginState;
  getSnapshot: () => PluginsSnapshotPayload;
  appendLog: (msg: string) => void;
}

function okJson(payload: unknown): ExecResult {
  return { errno: 0, stdout: JSON.stringify(payload), stderr: '' };
}

function rpcOk(result: unknown): ExecResult {
  return okJson({ jsonrpc: '2.0', id: 'ctl', result });
}

function rpcErr(message: string): ExecResult {
  return {
    errno: 1,
    stdout: JSON.stringify({ jsonrpc: '2.0', id: 'ctl', error: { code: -32602, message } }),
    stderr: message,
  };
}

function parseRawRpcCall(cmd: string): { method: string; params: Record<string, unknown> } | null {
  const match = cmd.match(/sideveil-ctl['"]?\s+rpc\s+['"]?([a-zA-Z0-9_.]+)['"]?\s+([\s\S]+)$/);
  if (!match) return null;
  const method = match[1];
  let rawJson = match[2].trim();
  if (
    (rawJson.startsWith("'") && rawJson.endsWith("'")) ||
    (rawJson.startsWith('"') && rawJson.endsWith('"'))
  ) {
    rawJson = rawJson.slice(1, -1).replace(/'\\''/g, "'");
  }
  try {
    const parsed = JSON.parse(rawJson) as Record<string, unknown>;
    return { method, params: parsed };
  } catch {
    return null;
  }
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

function createEmbedPlugin(ctx: MockPluginHandlerCtx, params: Record<string, unknown>): ExecResult {
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

  const nextPlugin: PluginDescriptor = {
    id,
    name,
    version: '1.0.0',
    embed: {
      url,
      remoteUrl,
      lanSsids: lanSsids && lanSsids.length > 0 ? lanSsids : undefined,
      allowedOrigins: allowedOrigins.length > 0 ? allowedOrigins : defaultOrigins,
      allowCleartext,
      wideViewport: pickBool(params.wideViewport, true),
      allowZoom: pickBool(params.allowZoom, false),
      webHistoryBack: pickBool(params.webHistoryBack, true),
      textZoom: pickInt(params.textZoom, undefined),
      showToolbar: pickBool(params.showToolbar, undefined),
      keepAliveGraceSec: pickInt(params.keepAliveGraceSec, undefined),
    },
    builtin: false,
    enabled: true,
    priority: pickInt(params.priority, 0),
    packages: [],
    global: pickBool(params.global, true),
    hasScopeOverride: false,
    backend: 'none',
    backendState: 'none',
    capabilities: allowCleartext ? ['network-lan', 'network-context'] : ['network-context'],
    surface: {
      backdrop: 'opaque',
      keepAlive: Boolean(params.keepAlive),
      widthDp: pickInt(params.widthDp, 380),
      heightPercent: pickInt(params.heightPercent, 78),
    },
  };
  ctx.state.plugins = [...ctx.state.plugins, nextPlugin];
  ctx.appendLog(`Created web-embed plugin ${id} (${url})`);
  return rpcOk({ ok: true, pluginId: id, snapshot: ctx.getSnapshot() });
}

function updateEmbedConfig(ctx: MockPluginHandlerCtx, params: Record<string, unknown>): ExecResult {
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

function uninstallMockPlugin(ctx: MockPluginHandlerCtx, pluginId: string): ExecResult {
  const target = ctx.state.plugins.find((p) => p.id === pluginId);
  if (!target) return rpcErr(`未找到插件: ${pluginId}`);
  if (target.builtin) return rpcErr('内置插件不允许卸载');
  ctx.state.plugins = ctx.state.plugins.filter((p) => p.id !== pluginId);
  if (ctx.state.activePluginId === pluginId) {
    ctx.state.activePluginId = 'sideveil.diagnostic';
  }
  ctx.appendLog(`Uninstalled plugin ${pluginId}`);
  return rpcOk({ ok: true, pluginId, snapshot: ctx.getSnapshot() });
}

export function handleMockPluginExec(
  rawCmd: string,
  normalized: string,
  ctx: MockPluginHandlerCtx,
): ExecResult | null {
  const rpcCall = parseRawRpcCall(rawCmd);
  if (rpcCall) {
    if (rpcCall.method === SIDEVEIL_METHODS.PLUGINS_CREATE_WEB_EMBED) {
      return createEmbedPlugin(ctx, rpcCall.params);
    }
    if (rpcCall.method === SIDEVEIL_METHODS.PLUGINS_SET_EMBED_CONFIG) {
      return updateEmbedConfig(ctx, rpcCall.params);
    }
    if (rpcCall.method === SIDEVEIL_METHODS.PLUGINS_UNINSTALL) {
      return uninstallMockPlugin(ctx, String(rpcCall.params.pluginId ?? ''));
    }
  }

  if (normalized.includes('sideveil-ctl plugins list')) return okJson(ctx.getSnapshot());
  if (normalized.includes('sideveil-ctl plugins reload')) {
    ctx.appendLog('Rescanned plugins directory');
    return okJson({ ok: true, count: ctx.state.plugins.length, snapshot: ctx.getSnapshot() });
  }

  const uninstallMatch = normalized.match(/sideveil-ctl plugins uninstall ([^\s]+)/);
  if (uninstallMatch) return uninstallMockPlugin(ctx, uninstallMatch[1]);

  const createEmbedMatch = normalized.match(
    /sideveil-ctl plugins create-embed ([^\s]+) ([^\s]+) ([^\s]+)(.*)$/,
  );
  if (createEmbedMatch) {
    const rest = createEmbedMatch[4] ?? '';
    return createEmbedPlugin(ctx, {
      id: createEmbedMatch[1],
      name: createEmbedMatch[2],
      url: createEmbedMatch[3],
      allowCleartext: rest.includes('--allow-cleartext'),
      keepAlive: rest.includes('--keep-alive'),
    });
  }

  const enableMatch = normalized.match(/sideveil-ctl plugins (enable|disable) ([^\s]+)/);
  if (enableMatch) {
    const isEnable = enableMatch[1] === 'enable';
    ctx.state.plugins = ctx.state.plugins.map((p) =>
      p.id === enableMatch[2] ? { ...p, enabled: isEnable } : p,
    );
    ctx.appendLog(`Plugin ${enableMatch[2]} ${isEnable ? 'enabled' : 'disabled'}`);
    return okJson(ctx.getSnapshot());
  }

  const priorityMatch = normalized.match(/sideveil-ctl plugins priority ([^\s]+) (-?\d+)/);
  if (priorityMatch) {
    const prio = Number.parseInt(priorityMatch[2], 10);
    ctx.state.plugins = ctx.state.plugins.map((p) =>
      p.id === priorityMatch[1] ? { ...p, priority: prio } : p,
    );
    ctx.appendLog(`Plugin ${priorityMatch[1]} priority set to ${prio}`);
    return okJson(ctx.getSnapshot());
  }

  const scopeMatch = normalized.match(/sideveil-ctl plugins scope ([^\s]+)(.*)$/);
  if (scopeMatch) {
    const targetId = scopeMatch[1];
    const rest = scopeMatch[2] ?? '';
    const isClear = rest.includes('--clear');
    const globalMatch = rest.match(/--global\s+(true|false|1|0)/);
    const pkgsMatch = rest.match(/--packages\s+([^\s-][^\s]*)/);
    const hasEmptyPkgs = /--packages(?:\s+$|\s+--|$)/.test(rest);
    const defaults = INITIAL_MOCK_PLUGINS.find((p) => p.id === targetId);
    ctx.state.plugins = ctx.state.plugins.map((p) => {
      if (p.id !== targetId) return p;
      if (isClear && defaults) {
        return {
          ...p,
          global: defaults.global,
          packages: [...defaults.packages],
          hasScopeOverride: false,
        };
      }
      return {
        ...p,
        global: globalMatch ? globalMatch[1] === 'true' || globalMatch[1] === '1' : p.global,
        packages: pkgsMatch
          ? pkgsMatch[1]
              .split(',')
              .map((s) => s.trim())
              .filter(Boolean)
          : hasEmptyPkgs
            ? []
            : p.packages,
        hasScopeOverride: true,
      };
    });
    ctx.appendLog(`Plugin ${targetId} scope ${isClear ? 'cleared' : 'overridden'}`);
    return okJson(ctx.getSnapshot());
  }

  const devMatch = normalized.match(/sideveil-ctl plugins dev ([^\s]+) ([^\s]+)/);
  if (devMatch) {
    const nextUrl = devMatch[2] === '--clear' ? undefined : devMatch[2];
    ctx.state.plugins = ctx.state.plugins.map((p) =>
      p.id === devMatch[1] ? { ...p, devServerUrl: nextUrl } : p,
    );
    ctx.appendLog(`Plugin ${devMatch[1]} devServerUrl -> ${nextUrl ?? 'cleared'}`);
    return okJson(ctx.getSnapshot());
  }

  if (normalized.includes('sideveil-ctl plugins audit')) {
    return rpcOk(buildMockAuditReport());
  }

  const selectMatch = normalized.match(/sideveil-ctl plugins select ([^\s]+)/);
  if (selectMatch) {
    ctx.state.activePluginId = selectMatch[1];
    ctx.appendLog(`Active plugin switched to ${selectMatch[1]}`);
    return okJson(ctx.getSnapshot());
  }
  return null;
}
