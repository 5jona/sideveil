import type { ExecResult } from '@gojona/bridge';
import { SIDEVEIL_METHODS } from '../shared/types.ts';
import {
  createEmbedPlugin,
  type MockPluginHandlerCtx,
  type MockPluginState,
  okJson,
  rpcOk,
  uninstallMockPlugin,
  updateEmbedConfig,
} from './mockPluginEmbedHandlers.ts';
import { buildMockAuditReport, INITIAL_MOCK_PLUGINS } from './mockPluginFixtures.ts';

export type { MockPluginHandlerCtx, MockPluginState };

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

/**
 * Simulates `sideveil-ctl plugins ...` and raw `sideveil-ctl rpc plugins.*` commands
 * in browser preview and unit test environments without a live daemon socket.
 */
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
