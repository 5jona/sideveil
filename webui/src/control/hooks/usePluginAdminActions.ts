import type { BridgeClient, ExecResult } from '@gojona/bridge';
import { useCallback } from 'react';
import {
  type PluginArchiveInspection,
  type PluginDaemonLogsResult,
  SIDEVEIL_METHODS,
} from '../../shared/types.ts';
import {
  assertAdminRpcAllowed,
  buildDaemonLogsCommand,
  buildGrantCapabilitiesCommand,
  buildGrantCapabilitiesParams,
  buildInspectArchiveCommand,
  buildInstallPluginCommand,
  buildInstallPluginParams,
  buildReloadWebViewCommand,
  type PluginGrantCapabilitiesInput,
  type PluginInspectArchiveInput,
  type PluginInstallInput,
  unwrapRpcJsonResult,
  withStagedSvpArchiveForKsu,
} from './pluginAdminHelpers.ts';
import {
  buildCreateWebEmbedCommand,
  buildCreateWebEmbedParams,
  buildSetEmbedConfigCommand,
  buildSetEmbedConfigParams,
  buildUninstallPluginCommand,
  type CreateWebEmbedInput,
  extractRpcErrorMessage,
  hasRpcErrorPayload,
  type PluginEmbedConfigInput,
} from './pluginCommandHelpers.ts';

export type RunPluginMutationFn = (
  busyKey: string,
  method: string,
  params: Record<string, unknown>,
  execCmd: string | (() => Promise<ExecResult>),
  successMsg: string,
  failPrefix: string,
) => Promise<boolean>;

export function usePluginAdminActions(
  bridge: BridgeClient,
  setBusy: (key: string | null) => void,
  runPluginMutation: RunPluginMutationFn,
) {
  const grantPluginCapabilities = useCallback(
    (input: PluginGrantCapabilitiesInput) => {
      const successMsg = input.revokeAll
        ? '已撤销全部高危/中危权限'
        : input.approveDaemon === false
          ? '已撤销后台进程授权'
          : '权限与授权设置已更新';
      return runPluginMutation(
        `plugin-${input.pluginId}`,
        SIDEVEIL_METHODS.PLUGINS_GRANT_CAPABILITIES,
        buildGrantCapabilitiesParams(input),
        buildGrantCapabilitiesCommand(input),
        successMsg,
        '更新插件权限失败',
      );
    },
    [runPluginMutation],
  );

  const inspectPluginArchive = useCallback(
    async (input: PluginInspectArchiveInput): Promise<PluginArchiveInspection> => {
      assertAdminRpcAllowed(bridge.env, SIDEVEIL_METHODS.PLUGINS_INSPECT_ARCHIVE);
      setBusy('inspect-archive');
      try {
        const res =
          bridge.env === 'ksu' && input.archiveBase64
            ? await withStagedSvpArchiveForKsu(bridge.exec, input.archiveBase64, (svpPath) =>
                bridge.exec(buildInspectArchiveCommand({ path: svpPath })),
              )
            : await bridge.exec(buildInspectArchiveCommand(input));
        if (res.errno !== 0 || hasRpcErrorPayload(res.stdout)) {
          throw new Error(extractRpcErrorMessage(res.stdout, res.stderr));
        }
        const parsed = unwrapRpcJsonResult<PluginArchiveInspection>(res.stdout);
        if (!parsed?.manifest) throw new Error('未能解析插件清单');
        return parsed;
      } finally {
        setBusy(null);
      }
    },
    [bridge, setBusy],
  );

  const installPlugin = useCallback(
    (input: PluginInstallInput) => {
      const execAction: string | (() => Promise<ExecResult>) =
        bridge.env === 'ksu' && input.archiveBase64
          ? () =>
              withStagedSvpArchiveForKsu(bridge.exec, input.archiveBase64 ?? '', (svpPath) =>
                bridge.exec(
                  buildInstallPluginCommand({
                    sourcePath: svpPath,
                    grantedCapabilities: input.grantedCapabilities,
                    approveDaemon: input.approveDaemon,
                  }),
                ),
              )
          : buildInstallPluginCommand(input);
      return runPluginMutation(
        'install-plugin',
        SIDEVEIL_METHODS.PLUGINS_INSTALL,
        buildInstallPluginParams(input),
        execAction,
        '插件已安装并生效',
        '安装插件失败',
      );
    },
    [bridge, runPluginMutation],
  );

  const fetchPluginDaemonLogs = useCallback(
    async (pluginId: string, limit = 100): Promise<string[]> => {
      assertAdminRpcAllowed(bridge.env, SIDEVEIL_METHODS.PLUGINS_DAEMON_LOGS);
      const res = await bridge.exec(buildDaemonLogsCommand(pluginId, limit));
      if (res.errno !== 0 || hasRpcErrorPayload(res.stdout)) {
        throw new Error(extractRpcErrorMessage(res.stdout, res.stderr));
      }
      const parsed = unwrapRpcJsonResult<PluginDaemonLogsResult>(res.stdout);
      return Array.isArray(parsed?.lines) ? parsed.lines : [];
    },
    [bridge],
  );

  const reloadPluginWebView = useCallback(
    (pluginId: string) =>
      runPluginMutation(
        `plugin-${pluginId}`,
        SIDEVEIL_METHODS.PLUGINS_RELOAD_WEBVIEW,
        { pluginId },
        buildReloadWebViewCommand(pluginId),
        '已重载插件 WebView 实例',
        '重载 WebView 失败',
      ),
    [runPluginMutation],
  );

  const createWebEmbed = useCallback(
    (input: CreateWebEmbedInput) =>
      runPluginMutation(
        'create-web-embed',
        SIDEVEIL_METHODS.PLUGINS_CREATE_WEB_EMBED,
        buildCreateWebEmbedParams(input),
        buildCreateWebEmbedCommand(input),
        `网页面板 ${input.name.trim()} 已添加`,
        '添加网页面板失败',
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
        input.clear ? '已恢复默认网页设置' : '网页设置已保存',
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
    grantPluginCapabilities,
    inspectPluginArchive,
    installPlugin,
    fetchPluginDaemonLogs,
    reloadPluginWebView,
    createWebEmbed,
    setEmbedConfig,
    uninstallPlugin,
  };
}
