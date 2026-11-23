import { type BridgeClient, shellQuote } from '@pochita/bridge';
import type { PluginAuditReport, PluginsSnapshotPayload } from '@pochita/sideveil-sdk';
import { useCallback, useState } from 'react';
import { CTL_BIN, SIDEVEIL_METHODS } from '../../shared/types.ts';
import {
  buildCreateWebEmbedCommand,
  buildCreateWebEmbedParams,
  buildPluginScopeCommand,
  buildSetEmbedConfigCommand,
  buildSetEmbedConfigParams,
  buildUninstallPluginCommand,
  type CreateWebEmbedInput,
  extractRpcErrorMessage,
  extractSnapshotPayload,
  hasRpcErrorPayload,
  type PluginEmbedConfigInput,
  type PluginScopeOverrideInput,
  parsePluginsSnapshot,
} from './pluginCommandHelpers.ts';

export {
  buildCreateWebEmbedCommand,
  buildCreateWebEmbedParams,
  buildPluginScopeCommand,
  buildSetEmbedConfigCommand,
  buildSetEmbedConfigParams,
  buildUninstallPluginCommand,
  type CreateWebEmbedInput,
  type PluginEmbedConfigInput,
  type PluginScopeOverrideInput,
};

type PushToast = (message: string, variant?: 'info' | 'success' | 'warning' | 'danger') => void;

/**
 * Manages plugin registry polling, enable/priority/scope/dev-mode overrides,
 * web-embed lifecycle mutations, and security audit report refreshes.
 */
export function usePluginsControl(bridge: BridgeClient, pushToast: PushToast) {
  const [pluginsSnapshot, setPluginsSnapshot] = useState<PluginsSnapshotPayload | null>(null);
  const [auditReport, setAuditReport] = useState<PluginAuditReport | null>(null);
  const [pluginBusyAction, setPluginBusyAction] = useState<string | null>(null);

  const refreshPlugins = useCallback(async () => {
    try {
      const res = await bridge.exec(`${shellQuote(CTL_BIN)} plugins list`);
      if (res.errno === 0) {
        const snapshot = parsePluginsSnapshot(res.stdout);
        if (snapshot) {
          setPluginsSnapshot(snapshot);
        }
      }
    } catch {
      // Ignore transient polling failures.
    }
  }, [bridge]);

  const applySnapshotOrRefresh = useCallback(
    async (stdout: string) => {
      const snapshot = parsePluginsSnapshot(stdout);
      if (snapshot) {
        setPluginsSnapshot(snapshot);
      } else {
        await refreshPlugins();
      }
    },
    [refreshPlugins],
  );

  const runPluginExec = useCallback(
    async (
      busyKey: string,
      cmd: string,
      successMsg: string,
      failPrefix: string,
      forceRefresh = false,
    ) => {
      setPluginBusyAction(busyKey);
      try {
        const res = await bridge.exec(cmd);
        if (res.errno === 0) {
          pushToast(successMsg, 'success');
          if (forceRefresh) await refreshPlugins();
          else await applySnapshotOrRefresh(res.stdout);
        } else {
          pushToast(`${failPrefix}: ${res.stderr || res.stdout}`, 'warning');
        }
      } catch (err) {
        pushToast(`${failPrefix}: ${err instanceof Error ? err.message : String(err)}`, 'warning');
      } finally {
        setPluginBusyAction(null);
      }
    },
    [applySnapshotOrRefresh, bridge, pushToast, refreshPlugins],
  );

  const reloadPlugins = useCallback(
    () =>
      runPluginExec(
        'plugins',
        `${shellQuote(CTL_BIN)} plugins reload`,
        '插件扫描重载成功',
        '插件重载失败',
        true,
      ),
    [runPluginExec],
  );

  const setPluginEnabled = useCallback(
    (pluginId: string, enabled: boolean) =>
      runPluginExec(
        `plugin-${pluginId}`,
        `${shellQuote(CTL_BIN)} plugins ${enabled ? 'enable' : 'disable'} ${shellQuote(pluginId)}`,
        `插件 ${pluginId} 已${enabled ? '启用' : '禁用'}`,
        '设置插件状态失败',
      ),
    [runPluginExec],
  );

  const setPluginPriority = useCallback(
    (pluginId: string, priority: number) => {
      const normalized = Number.isFinite(priority) ? Math.trunc(priority) : 0;
      return runPluginExec(
        `plugin-${pluginId}`,
        `${shellQuote(CTL_BIN)} plugins priority ${shellQuote(pluginId)} ${normalized}`,
        `插件 ${pluginId} 优先级已更新为 ${normalized}`,
        '更新优先级失败',
      );
    },
    [runPluginExec],
  );

  const setPluginScopeOverride = useCallback(
    (pluginId: string, override: PluginScopeOverrideInput) =>
      runPluginExec(
        `plugin-${pluginId}`,
        buildPluginScopeCommand(pluginId, override),
        override.clear
          ? `插件 ${pluginId} 已恢复默认触发规则`
          : `插件 ${pluginId} 触发作用域覆盖已保存`,
        '更新触发范围失败',
      ),
    [runPluginExec],
  );

  const selectPlugin = useCallback(
    (pluginId: string) =>
      runPluginExec(
        `plugin-${pluginId}`,
        `${shellQuote(CTL_BIN)} plugins select ${shellQuote(pluginId)}`,
        `已切换至插件 ${pluginId}`,
        '切换插件失败',
        true,
      ),
    [runPluginExec],
  );

  const setPluginDevUrl = useCallback(
    (pluginId: string, devServerUrl: string | null) =>
      runPluginExec(
        `plugin-${pluginId}`,
        `${shellQuote(CTL_BIN)} plugins dev ${shellQuote(pluginId)} ${devServerUrl ? shellQuote(devServerUrl) : '--clear'}`,
        devServerUrl
          ? `插件 ${pluginId} 已启用开发模式 (${devServerUrl})`
          : `插件 ${pluginId} 已退出开发模式`,
        '设置开发模式失败',
      ),
    [runPluginExec],
  );

  const refreshPluginAudit = useCallback(async () => {
    setPluginBusyAction('audit');
    try {
      const res = await bridge.exec(`${shellQuote(CTL_BIN)} plugins audit`);
      if (res.errno === 0 && res.stdout.trim()) {
        const parsed = JSON.parse(res.stdout.trim()) as
          | PluginAuditReport
          | { result?: PluginAuditReport };
        const candidate = 'result' in parsed && parsed.result ? parsed.result : parsed;
        if (candidate && 'diagnostics' in candidate) {
          setAuditReport(candidate as PluginAuditReport);
        }
      }
    } catch {
      // Ignore transient audit failures.
    } finally {
      setPluginBusyAction(null);
    }
  }, [bridge]);

  const runPluginMutation = useCallback(
    async (
      busyKey: string,
      method: string,
      params: Record<string, unknown>,
      execCmd: string,
      successMsg: string,
      failPrefix: string,
    ): Promise<boolean> => {
      setPluginBusyAction(busyKey);
      try {
        if (bridge.env === 'host') {
          const res = await bridge.invoke<unknown>(method, params);
          const snapshot = extractSnapshotPayload(res);
          if (snapshot) setPluginsSnapshot(snapshot);
          else await refreshPlugins();
          pushToast(successMsg, 'success');
          return true;
        }
        const res = await bridge.exec(execCmd);
        if (res.errno === 0 && !hasRpcErrorPayload(res.stdout)) {
          pushToast(successMsg, 'success');
          await applySnapshotOrRefresh(res.stdout);
          return true;
        }
        pushToast(`${failPrefix}: ${extractRpcErrorMessage(res.stdout, res.stderr)}`, 'warning');
        return false;
      } catch (err) {
        pushToast(`${failPrefix}: ${err instanceof Error ? err.message : String(err)}`, 'warning');
        return false;
      } finally {
        setPluginBusyAction(null);
      }
    },
    [applySnapshotOrRefresh, bridge, pushToast, refreshPlugins],
  );

  const createWebEmbed = useCallback(
    (input: CreateWebEmbedInput) =>
      runPluginMutation(
        'create-web-embed',
        SIDEVEIL_METHODS.PLUGINS_CREATE_WEB_EMBED,
        buildCreateWebEmbedParams(input),
        buildCreateWebEmbedCommand(input),
        `网页嵌入插件 ${input.name.trim()} 已创建`,
        '创建网页嵌入失败',
      ),
    [runPluginMutation],
  );

  const setEmbedConfig = useCallback(
    (pluginId: string, input: PluginEmbedConfigInput) =>
      runPluginMutation(
        `plugin-${pluginId}`,
        SIDEVEIL_METHODS.PLUGINS_SET_EMBED_CONFIG,
        buildSetEmbedConfigParams(pluginId, input),
        buildSetEmbedConfigCommand(pluginId, input),
        input.clear
          ? `插件 ${pluginId} 网页嵌入设置已恢复默认`
          : `插件 ${pluginId} 网页嵌入设置已保存`,
        '保存网页设置失败',
      ),
    [runPluginMutation],
  );

  const uninstallPlugin = useCallback(
    (pluginId: string) =>
      runPluginMutation(
        `plugin-${pluginId}`,
        SIDEVEIL_METHODS.PLUGINS_UNINSTALL,
        { pluginId },
        buildUninstallPluginCommand(pluginId),
        `插件 ${pluginId} 已卸载`,
        '卸载插件失败',
      ),
    [runPluginMutation],
  );

  return {
    pluginsSnapshot,
    auditReport,
    pluginBusyAction,
    refreshPlugins,
    reloadPlugins,
    setPluginEnabled,
    setPluginPriority,
    setPluginScopeOverride,
    setPluginDevUrl,
    createWebEmbed,
    setEmbedConfig,
    uninstallPlugin,
    refreshPluginAudit,
    selectPlugin,
  };
}
