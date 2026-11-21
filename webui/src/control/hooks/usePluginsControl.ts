import { type BridgeClient, shellQuote } from '@pochita/bridge';
import type { PluginAuditReport, PluginsSnapshotPayload } from '@pochita/sideveil-sdk';
import { useCallback, useState } from 'react';
import { CTL_BIN } from '../../shared/types.ts';

export interface PluginScopeOverrideInput {
  global?: boolean;
  packages?: readonly string[];
  clear?: boolean;
}

type PushToast = (message: string, variant?: 'info' | 'success' | 'warning' | 'danger') => void;

function parsePluginsSnapshot(stdout: string): PluginsSnapshotPayload | null {
  const trimmed = stdout.trim();
  if (!trimmed) return null;
  try {
    const parsed = JSON.parse(trimmed) as
      | PluginsSnapshotPayload
      | { result?: PluginsSnapshotPayload };
    const candidate = 'result' in parsed && parsed.result !== undefined ? parsed.result : parsed;
    if (candidate && Array.isArray((candidate as PluginsSnapshotPayload).plugins)) {
      return candidate as PluginsSnapshotPayload;
    }
  } catch {
    // Ignore transient partial stdout.
  }
  return null;
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

  const reloadPlugins = useCallback(async () => {
    setPluginBusyAction('plugins');
    try {
      const res = await bridge.exec(`${shellQuote(CTL_BIN)} plugins reload`);
      if (res.errno === 0) {
        pushToast('插件扫描重载成功', 'success');
        await refreshPlugins();
      } else {
        pushToast(`插件重载失败: ${res.stderr || res.stdout}`, 'warning');
      }
    } catch (err) {
      pushToast(`插件重载失败: ${err instanceof Error ? err.message : String(err)}`, 'warning');
    } finally {
      setPluginBusyAction(null);
    }
  }, [bridge, pushToast, refreshPlugins]);

  const setPluginEnabled = useCallback(
    async (pluginId: string, enabled: boolean) => {
      const actionCmd = enabled ? 'enable' : 'disable';
      setPluginBusyAction(`plugin-${pluginId}`);
      try {
        const res = await bridge.exec(
          `${shellQuote(CTL_BIN)} plugins ${actionCmd} ${shellQuote(pluginId)}`,
        );
        if (res.errno === 0) {
          pushToast(`插件 ${pluginId} 已${enabled ? '启用' : '禁用'}`, 'success');
          await applySnapshotOrRefresh(res.stdout);
        } else {
          pushToast(`设置插件状态失败: ${res.stderr || res.stdout}`, 'warning');
        }
      } catch (err) {
        pushToast(`操作失败: ${err instanceof Error ? err.message : String(err)}`, 'warning');
      } finally {
        setPluginBusyAction(null);
      }
    },
    [applySnapshotOrRefresh, bridge, pushToast],
  );

  const setPluginPriority = useCallback(
    async (pluginId: string, priority: number) => {
      const normalized = Number.isFinite(priority) ? Math.trunc(priority) : 0;
      setPluginBusyAction(`plugin-${pluginId}`);
      try {
        const res = await bridge.exec(
          `${shellQuote(CTL_BIN)} plugins priority ${shellQuote(pluginId)} ${normalized}`,
        );
        if (res.errno === 0) {
          pushToast(`插件 ${pluginId} 优先级已更新为 ${normalized}`, 'success');
          await applySnapshotOrRefresh(res.stdout);
        } else {
          pushToast(`更新优先级失败: ${res.stderr || res.stdout}`, 'warning');
        }
      } catch (err) {
        pushToast(`更新优先级失败: ${err instanceof Error ? err.message : String(err)}`, 'warning');
      } finally {
        setPluginBusyAction(null);
      }
    },
    [applySnapshotOrRefresh, bridge, pushToast],
  );

  const setPluginScopeOverride = useCallback(
    async (pluginId: string, override: PluginScopeOverrideInput) => {
      setPluginBusyAction(`plugin-${pluginId}`);
      try {
        const cmd = buildPluginScopeCommand(pluginId, override);
        const res = await bridge.exec(cmd);
        if (res.errno === 0) {
          pushToast(
            override.clear
              ? `插件 ${pluginId} 已恢复默认触发规则`
              : `插件 ${pluginId} 触发作用域覆盖已保存`,
            'success',
          );
          await applySnapshotOrRefresh(res.stdout);
        } else {
          pushToast(`更新触发范围失败: ${res.stderr || res.stdout}`, 'warning');
        }
      } catch (err) {
        pushToast(
          `更新触发范围失败: ${err instanceof Error ? err.message : String(err)}`,
          'warning',
        );
      } finally {
        setPluginBusyAction(null);
      }
    },
    [applySnapshotOrRefresh, bridge, pushToast],
  );

  const selectPlugin = useCallback(
    async (pluginId: string) => {
      setPluginBusyAction(`plugin-${pluginId}`);
      try {
        const res = await bridge.exec(
          `${shellQuote(CTL_BIN)} plugins select ${shellQuote(pluginId)}`,
        );
        if (res.errno === 0) {
          pushToast(`已切换至插件 ${pluginId}`, 'success');
          await refreshPlugins();
        } else {
          pushToast(`切换插件失败: ${res.stderr || res.stdout}`, 'warning');
        }
      } catch (err) {
        pushToast(`切换插件失败: ${err instanceof Error ? err.message : String(err)}`, 'warning');
      } finally {
        setPluginBusyAction(null);
      }
    },
    [bridge, pushToast, refreshPlugins],
  );

  const setPluginDevUrl = useCallback(
    async (pluginId: string, devServerUrl: string | null) => {
      setPluginBusyAction(`plugin-${pluginId}`);
      try {
        const arg = devServerUrl ? shellQuote(devServerUrl) : '--clear';
        const res = await bridge.exec(
          `${shellQuote(CTL_BIN)} plugins dev ${shellQuote(pluginId)} ${arg}`,
        );
        if (res.errno === 0) {
          pushToast(
            devServerUrl
              ? `插件 ${pluginId} 已启用开发模式 (${devServerUrl})`
              : `插件 ${pluginId} 已退出开发模式`,
            'success',
          );
          await applySnapshotOrRefresh(res.stdout);
        } else {
          pushToast(`设置开发模式失败: ${res.stderr || res.stdout}`, 'warning');
        }
      } catch (err) {
        pushToast(
          `设置开发模式失败: ${err instanceof Error ? err.message : String(err)}`,
          'warning',
        );
      } finally {
        setPluginBusyAction(null);
      }
    },
    [applySnapshotOrRefresh, bridge, pushToast],
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
    refreshPluginAudit,
    selectPlugin,
  };
}
