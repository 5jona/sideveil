import type { NetworkContextSnapshot } from '@pochita/sideveil-sdk';
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  LogConsole,
  type LogEntry,
  SettingRow,
  Switch,
} from '@pochita/ui';
import { AlertTriangle, Bug, RefreshCw, RotateCcw, Terminal, Zap } from 'lucide-react';
import { useMemo, useState } from 'react';
import type {
  DaemonStatus,
  DebugCdpStatusResult,
  DebugInspectUiSnapshot,
  SideVeilConfig,
} from '../../shared/types.ts';
import { NetworkContextCard } from './NetworkContextCard.tsx';
import { UiInspectorCard } from './UiInspectorCard.tsx';

type LogFilterMode = 'all' | 'webview' | 'warn-error';

const LOG_FILTER_OPTIONS: readonly { id: LogFilterMode; label: string }[] = [
  { id: 'all', label: '全部' },
  { id: 'webview', label: 'WebView 控制台' },
  { id: 'warn-error', label: '仅警告与错误' },
];

function isWebViewConsoleLog(entry: LogEntry): boolean {
  return entry.message.includes('[WebView:') || entry.message.includes('host.webviewConsole');
}

function isWarnOrErrorLog(entry: LogEntry): boolean {
  return (
    entry.level === 'warn' ||
    entry.level === 'error' ||
    /\[(ERROR|WARNING|WARN)\]/i.test(entry.message)
  );
}

export interface DiagnosticsTabProps {
  config: SideVeilConfig;
  status: DaemonStatus | null;
  logs: readonly LogEntry[];
  busyAction: string | null;
  lastPingMs: number | null;
  cdpStatus: DebugCdpStatusResult | null;
  uiSnapshot: DebugInspectUiSnapshot | null;
  inspectCapturedAt: string | null;
  debugBusy: 'cdp' | 'inspect' | null;
  debugError: string | null;
  networkContext: NetworkContextSnapshot | null;
  networkLoading: boolean;
  networkError: string | null;
  onPing: () => void;
  onReloadConfig: () => void;
  onRestartHost: () => void;
  onRefreshLogs: () => void;
  onClearLogs: () => void;
  onSetWebViewDebugging: (enabled: boolean) => void;
  onInspectUi: () => void;
  onRefreshNetwork: () => void;
}

export function DiagnosticsTab({
  config,
  status,
  logs,
  busyAction,
  lastPingMs,
  cdpStatus,
  uiSnapshot,
  inspectCapturedAt,
  debugBusy,
  debugError,
  networkContext,
  networkLoading,
  networkError,
  onPing,
  onReloadConfig,
  onRestartHost,
  onRefreshLogs,
  onClearLogs,
  onSetWebViewDebugging,
  onInspectUi,
  onRefreshNetwork,
}: DiagnosticsTabProps) {
  const [logFilter, setLogFilter] = useState<LogFilterMode>('all');

  const webviewDebuggingEnabled = Boolean(
    cdpStatus?.webviewDebuggingEnabled ?? config.debug?.webviewDebuggingEnabled,
  );
  const activeCdpSocket = webviewDebuggingEnabled
    ? (cdpStatus?.cdpSocket ??
      (status?.hostPid ? `webview_devtools_remote_${status.hostPid}` : null))
    : null;

  const filteredLogs = useMemo(() => {
    if (logFilter === 'webview') return logs.filter(isWebViewConsoleLog);
    if (logFilter === 'warn-error') return logs.filter(isWarnOrErrorLog);
    return logs;
  }, [logFilter, logs]);

  return (
    <div className="flex flex-col gap-4">
      <Card>
        <CardHeader>
          <div className="flex flex-col gap-0.5">
            <CardTitle>通信与运行诊断</CardTitle>
            <CardDescription>测试链路延迟、重载配置或重启宿主服务</CardDescription>
          </div>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <Button
              size="sm"
              variant="primary"
              disabled={busyAction !== null}
              aria-label="Ping Daemon"
              onClick={onPing}
            >
              <Zap className="size-3.5" />
              {busyAction === 'ping' ? '测试中...' : 'Ping Daemon'}
            </Button>
            <Button
              size="sm"
              variant="secondary"
              disabled={busyAction !== null}
              aria-label="重载配置"
              onClick={onReloadConfig}
            >
              <RefreshCw className="size-3.5" />
              重载配置
            </Button>
            <Button
              size="sm"
              variant="secondary"
              disabled={busyAction !== null}
              aria-label="重启 Host 服务"
              onClick={onRestartHost}
            >
              <RotateCcw className="size-3.5" />
              重启 Host 服务
            </Button>
            <Button
              size="sm"
              variant="ghost"
              disabled={busyAction !== null}
              aria-label="刷新日志"
              onClick={onRefreshLogs}
            >
              <Terminal className="size-3.5" />
              刷新日志
            </Button>
          </div>

          <div className="flex flex-wrap items-center gap-2 pt-1">
            <Badge variant={status?.taskListenerActive ? 'success' : 'warning'} dot>
              ITaskStackListener: {status?.taskListenerActive ? '已挂载' : '轮询兜底'}
            </Badge>
            <Badge variant={status?.gestureMonitorActive ? 'success' : 'danger'} dot>
              GestureMonitor: {status?.gestureMonitorActive ? '运行中' : '未就绪'}
            </Badge>
            <Badge variant={status?.overlayPermissionGranted ? 'success' : 'warning'} dot>
              OP_SYSTEM_ALERT_WINDOW: {status?.overlayPermissionGranted ? '已授权' : '待授权'}
            </Badge>
            <Badge variant={status?.crossWindowBlurSupported ? 'accent' : 'neutral'}>
              跨窗模糊: {status?.crossWindowBlurSupported ? '支持' : '降级/未知'}
            </Badge>
            {lastPingMs !== null ? (
              <Badge variant="accent">最近 Ping: {lastPingMs} ms</Badge>
            ) : null}
          </div>
        </CardContent>
      </Card>

      <NetworkContextCard
        snapshot={networkContext}
        loading={networkLoading}
        error={networkError}
        onRefresh={onRefreshNetwork}
      />

      <Card>
        <CardHeader>
          <div className="flex flex-col gap-0.5">
            <CardTitle className="flex items-center gap-1.5">
              <Bug className="size-4 text-[var(--accent)]" />
              WebView CDP 远程调试
            </CardTitle>
            <CardDescription>开启后可通过 Chrome DevTools 附加到 Host WebView 进程</CardDescription>
          </div>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <SettingRow
            variant="plain"
            title="启用 WebView 调试 (CDP)"
            description="仅建议在开发排障时开启，关闭后可按需重启 Host 释放调试会话"
            control={
              <Switch
                checked={webviewDebuggingEnabled}
                disabled={debugBusy === 'cdp'}
                onCheckedChange={onSetWebViewDebugging}
                aria-label="启用 WebView CDP 远程调试"
              />
            }
          />

          {activeCdpSocket ? (
            <div className="flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-3 py-2 text-xs">
              <span className="text-[var(--text-secondary)]">活跃 CDP Socket</span>
              <code className="font-mono text-[var(--text-primary)]">{activeCdpSocket}</code>
            </div>
          ) : null}

          {cdpStatus?.restartRecommended ? (
            <div className="flex items-center gap-2 rounded-[var(--radius-control)] border border-[color-mix(in_oklab,var(--warning)_35%,transparent)] bg-[color-mix(in_oklab,var(--warning)_12%,transparent)] px-3 py-2 text-xs text-[var(--text-primary)]">
              <AlertTriangle className="size-4 shrink-0 text-[var(--warning)]" />
              <span>
                已关闭 WebView 调试，建议点击上方「重启 Host 服务」以彻底断开已存在的 DevTools
                套接字。
              </span>
            </div>
          ) : null}
        </CardContent>
      </Card>

      <UiInspectorCard
        snapshot={uiSnapshot}
        capturedAt={inspectCapturedAt}
        busy={debugBusy === 'inspect'}
        error={debugError}
        onCapture={onInspectUi}
      />

      <div className="flex flex-col gap-2">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <fieldset
            aria-label="日志筛选"
            className="m-0 flex flex-wrap items-center gap-1.5 border-0 p-0"
          >
            {LOG_FILTER_OPTIONS.map((option) => {
              const active = logFilter === option.id;
              return (
                <Button
                  key={option.id}
                  size="sm"
                  variant={active ? 'primary' : 'secondary'}
                  aria-pressed={active}
                  aria-label={`筛选日志：${option.label}`}
                  onClick={() => setLogFilter(option.id)}
                >
                  {option.label}
                </Button>
              );
            })}
          </fieldset>
          <Badge variant="neutral">
            显示 {filteredLogs.length} / {logs.length} 条
          </Badge>
        </div>

        <LogConsole
          title="Daemon 运行日志"
          entries={filteredLogs}
          onClear={onClearLogs}
          maxHeightClassName="max-h-72"
        />
      </div>
    </div>
  );
}
