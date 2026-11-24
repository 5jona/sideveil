import { type BridgeClient, type ExecResult, shellQuote } from '@gojona/bridge';
import type { PluginAuditReport } from '@gojona/sideveil-sdk';
import { useCallback, useState } from 'react';
import { CTL_BIN, type PluginsSnapshot } from '../../shared/types.ts';
import {
  assertAdminRpcAllowed,
  buildDaemonLogsCommand,
  buildGrantCapabilitiesCommand,
  buildGrantCapabilitiesParams,
  buildInspectArchiveCommand,
  buildInspectArchiveParams,
  buildInstallPluginCommand,
  buildInstallPluginParams,
  buildReloadWebViewCommand,
  HOST_ADMIN_BLOCKED_MESSAGE,
  type PluginGrantCapabilitiesInput,
  type PluginInspectArchiveInput,
  type PluginInstallInput,
  SVP_UPLOAD_CHUNK_CHARS,
  splitBase64IntoChunks,
  stageSvpArchiveForKsu,
  unwrapRpcJsonResult,
  withStagedSvpArchiveForKsu,
} from './pluginAdminHelpers.ts';
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
import { usePluginAdminActions } from './usePluginAdminActions.ts';

export {
  assertAdminRpcAllowed,
  buildCreateWebEmbedCommand,
  buildCreateWebEmbedParams,
  buildDaemonLogsCommand,
  buildGrantCapabilitiesCommand,
  buildGrantCapabilitiesParams,
  buildInspectArchiveCommand,
  buildInspectArchiveParams,
  buildInstallPluginCommand,
  buildInstallPluginParams,
  buildPluginScopeCommand,
  buildReloadWebViewCommand,
  buildSetEmbedConfigCommand,
  buildSetEmbedConfigParams,
  buildUninstallPluginCommand,
  type CreateWebEmbedInput,
  HOST_ADMIN_BLOCKED_MESSAGE,
  type PluginEmbedConfigInput,
  type PluginGrantCapabilitiesInput,
  type PluginInspectArchiveInput,
  type PluginInstallInput,
  type PluginScopeOverrideInput,
  SVP_UPLOAD_CHUNK_CHARS,
  splitBase64IntoChunks,
  stageSvpArchiveForKsu,
  withStagedSvpArchiveForKsu,
};

type PushToast = (message: string, variant?: 'info' | 'success' | 'warning' | 'danger') => void;

/**
 * Manages plugin registry polling, enable/priority/scope/dev-mode overrides,
 * capability grants, archive inspection & installation, daemon logs, and audit refreshes.
 */
export function usePluginsControl(bridge: BridgeClient, pushToast: PushToast) {
  const [pluginsSnapshot, setPluginsSnapshot] = useState<PluginsSnapshot | null>(null);
  const [pluginsLoadError, setPluginsLoadError] = useState<string | null>(null);
  const [auditReport, setAuditReport] = useState<PluginAuditReport | null>(null);
  const [pluginBusyAction, setPluginBusyAction] = useState<string | null>(null);

  const refreshPlugins = useCallback(async () => {
    try {
      const res = await bridge.exec(`${shellQuote(CTL_BIN)} plugins list`);
      if (res.errno === 0) {
        const snapshot = parsePluginsSnapshot(res.stdout);
        if (snapshot) {
          setPluginsSnapshot(snapshot);
          setPluginsLoadError(null);
        }
      } else {
        setPluginsLoadError(res.stderr.trim() || res.stdout.trim() || `errno ${res.errno}`);
      }
    } catch (err) {
      setPluginsLoadError(err instanceof Error ? err.message : String(err));
    }
  }, [bridge]);

  const applySnapshotOrRefresh = useCallback(
    async (stdout: string) => {
      const snapshot = parsePluginsSnapshot(stdout);
      if (snapshot) {
        setPluginsSnapshot(snapshot);
        setPluginsLoadError(null);
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
        if (res.errno === 0 && !hasRpcErrorPayload(res.stdout)) {
          pushToast(successMsg, 'success');
          if (forceRefresh) await refreshPlugins();
          else await applySnapshotOrRefresh(res.stdout);
        } else {
          pushToast(`${failPrefix}: ${extractRpcErrorMessage(res.stdout, res.stderr)}`, 'warning');
        }
      } catch (err) {
        pushToast(`${failPrefix}: ${err instanceof Error ? err.message : String(err)}`, 'warning');
      } finally {
        setPluginBusyAction(null);
      }
    },
    [applySnapshotOrRefresh, bridge, pushToast, refreshPlugins],
  );

  const runPluginMutation = useCallback(
    async (
      busyKey: string,
      method: string,
      params: Record<string, unknown>,
      execCmd: string | (() => Promise<ExecResult>),
      successMsg: string,
      failPrefix: string,
    ): Promise<boolean> => {
      setPluginBusyAction(busyKey);
      try {
        assertAdminRpcAllowed(bridge.env, method);
        if (bridge.env === 'host') {
          const res = await bridge.invoke<unknown>(method, params);
          const snapshot = extractSnapshotPayload(res);
          if (snapshot) setPluginsSnapshot(snapshot);
          else await refreshPlugins();
          pushToast(successMsg, 'success');
          return true;
        }
        const res = typeof execCmd === 'function' ? await execCmd() : await bridge.exec(execCmd);
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

  const adminActions = usePluginAdminActions(bridge, setPluginBusyAction, runPluginMutation);

  const reloadPlugins = useCallback(
    () =>
      runPluginExec(
        'plugins',
        `${shellQuote(CTL_BIN)} plugins reload`,
        '插件目录已重新扫描',
        '扫描插件失败',
        true,
      ),
    [runPluginExec],
  );

  const setPluginEnabled = useCallback(
    (pluginId: string, enabled: boolean) =>
      runPluginExec(
        `plugin-${pluginId}`,
        `${shellQuote(CTL_BIN)} plugins ${enabled ? 'enable' : 'disable'} ${shellQuote(pluginId)}`,
        `插件 ${pluginId} 已${enabled ? '启用' : '停用'}`,
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
        `优先级已更新为 ${normalized}`,
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
        override.clear ? '已恢复默认触发范围' : '触发范围覆盖已保存',
        '保存触发范围失败',
      ),
    [runPluginExec],
  );

  const selectPlugin = useCallback(
    (pluginId: string) =>
      runPluginExec(
        `plugin-${pluginId}`,
        `${shellQuote(CTL_BIN)} plugins select ${shellQuote(pluginId)}`,
        `已切换至 ${pluginId}`,
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
        devServerUrl ? `已启用开发热重载 (${devServerUrl})` : '已退出开发热重载',
        '设置开发模式失败',
      ),
    [runPluginExec],
  );

  const refreshPluginAudit = useCallback(async () => {
    setPluginBusyAction('audit');
    try {
      const res = await bridge.exec(`${shellQuote(CTL_BIN)} plugins audit`);
      if (res.errno === 0 && res.stdout.trim()) {
        const candidate = unwrapRpcJsonResult<PluginAuditReport>(res.stdout);
        if (candidate && 'diagnostics' in candidate) setAuditReport(candidate);
      }
    } catch {
      // Ignore transient audit failures.
    } finally {
      setPluginBusyAction(null);
    }
  }, [bridge]);

  return {
    pluginsSnapshot,
    pluginsLoadError,
    auditReport,
    pluginBusyAction,
    refreshPlugins,
    reloadPlugins,
    setPluginEnabled,
    setPluginPriority,
    setPluginScopeOverride,
    setPluginDevUrl,
    ...adminActions,
    refreshPluginAudit,
    selectPlugin,
  };
}
