import { Badge, Button, StatusBanner } from '@gojona/ui';
import { RefreshCw, ShieldAlert, ShieldOff, Terminal } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import type { PluginRuntimeDescriptor } from '../../shared/types.ts';
import type { PluginGrantCapabilitiesInput } from '../hooks/usePluginsControl.ts';
import { getDaemonBadgeMeta } from '../pluginMeta.ts';

export interface PluginDaemonSectionProps {
  plugin: PluginRuntimeDescriptor;
  isBusy: boolean;
  onGrantCapabilities: (input: PluginGrantCapabilitiesInput) => Promise<boolean>;
  onFetchDaemonLogs: (pluginId: string, limit?: number) => Promise<string[]>;
}

export function PluginDaemonSection({
  plugin,
  isBusy,
  onGrantCapabilities,
  onFetchDaemonLogs,
}: PluginDaemonSectionProps) {
  const [logs, setLogs] = useState<string[]>([]);
  const [loadingLogs, setLoadingLogs] = useState(false);
  const [logError, setLogError] = useState<string | null>(null);

  const loadLogs = useCallback(async () => {
    setLoadingLogs(true);
    setLogError(null);
    try {
      const lines = await onFetchDaemonLogs(plugin.id, 100);
      setLogs(lines);
    } catch (err) {
      setLogError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoadingLogs(false);
    }
  }, [onFetchDaemonLogs, plugin.id]);

  useEffect(() => {
    if (plugin.hasDaemon) {
      void loadLogs();
    }
  }, [loadLogs, plugin.hasDaemon]);

  if (!plugin.hasDaemon) {
    return (
      <div className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-3 text-xs text-[var(--text-secondary)]">
        {plugin.backend === 'uds-app'
          ? `此插件使用 Companion App 后端（当前状态：${plugin.backendState === 'online' ? '已连接' : '未连接'}），无需托管原生守护进程。`
          : '此插件无托管原生后台进程。'}
      </div>
    );
  }

  const statusMeta = getDaemonBadgeMeta(plugin.daemonStatus);

  return (
    <div className="flex flex-col gap-3 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Terminal className="size-4 text-[var(--accent)]" />
          <span className="text-xs font-semibold text-[var(--text-primary)]">托管原生守护进程</span>
          {statusMeta ? (
            <Badge variant={statusMeta.variant} dot={plugin.daemonStatus === 'running'}>
              {statusMeta.label}
            </Badge>
          ) : null}
          {plugin.daemonPid ? <Badge variant="neutral">PID {plugin.daemonPid}</Badge> : null}
          {plugin.daemonRestartCount > 0 ? (
            <Badge variant="warning">重启 {plugin.daemonRestartCount} 次</Badge>
          ) : null}
        </div>
        <Button
          size="sm"
          variant="secondary"
          aria-label={`刷新 ${plugin.name} 后台日志`}
          disabled={loadingLogs}
          onClick={() => void loadLogs()}
        >
          <RefreshCw className="size-3.5" />
          {loadingLogs ? '拉取中...' : '刷新日志'}
        </Button>
      </div>

      {plugin.daemonLastError ? (
        <StatusBanner tone="danger" title="最近退出异常" description={plugin.daemonLastError} />
      ) : null}

      {!plugin.daemonApproved ? (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-control)] border border-[color-mix(in_oklab,var(--warning)_35%,transparent)] bg-[color-mix(in_oklab,var(--warning)_10%,transparent)] px-3 py-2 text-xs">
          <span className="text-[var(--text-primary)]">
            原生进程尚未批准启动，批准后将由 sideveild 按崩溃熔断策略自动托管。
          </span>
          <Button
            size="sm"
            variant="primary"
            aria-label={`批准启动 ${plugin.name} 原生进程`}
            disabled={isBusy}
            onClick={() => void onGrantCapabilities({ pluginId: plugin.id, approveDaemon: true })}
          >
            <ShieldAlert className="size-3.5" />
            批准后台进程
          </Button>
        </div>
      ) : (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-3 py-2 text-xs">
          <span className="text-[var(--text-secondary)]">
            已授权 sideveild 托管此原生后台进程，可随时撤销以停止并禁止自动拉起。
          </span>
          <Button
            size="sm"
            variant="danger"
            aria-label={`撤销 ${plugin.name} 后台进程授权`}
            disabled={isBusy}
            onClick={() => void onGrantCapabilities({ pluginId: plugin.id, approveDaemon: false })}
          >
            <ShieldOff className="size-3.5" />
            撤销后台进程授权
          </Button>
        </div>
      )}

      {logError ? (
        <div className="text-xs text-[var(--danger)]">获取日志失败：{logError}</div>
      ) : (
        <pre className="max-h-44 overflow-y-auto rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-card)] p-2.5 font-mono text-[11px] leading-relaxed text-[var(--text-secondary)]">
          {logs.length > 0 ? logs.join('\n') : '暂无 stderr 进程日志输出'}
        </pre>
      )}
    </div>
  );
}
