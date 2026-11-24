import type { BridgeClient } from '@gojona/bridge';
import type { NetworkContextSnapshot, PluginAuditReport } from '@gojona/sideveil-sdk';
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
} from '@gojona/ui';
import { ChevronDown, ChevronUp, RefreshCw, RotateCcw, Terminal, Wrench, Zap } from 'lucide-react';
import { useMemo, useState } from 'react';
import type {
  DaemonStatus,
  DebugCdpStatusResult,
  DebugInspectUiSnapshot,
  SideVeilConfig,
} from '../../shared/types.ts';
import type { DebugBusyAction } from '../hooks/useDebugControl.ts';
import { BridgeTraceCard } from './BridgeTraceCard.tsx';
import { CdpDebugCard } from './CdpDebugCard.tsx';
import { GestureStatsCard } from './GestureStatsCard.tsx';
import { NetworkContextCard } from './NetworkContextCard.tsx';
import { PluginAuditDrawer } from './PluginAuditDrawer.tsx';
import { UiInspectorCard } from './UiInspectorCard.tsx';

type LogSourceFilter = 'all' | 'webview';

const LOG_SOURCE_OPTIONS: readonly { id: LogSourceFilter; label: string }[] = [
  { id: 'all', label: '全部日志' },
  { id: 'webview', label: 'WebView 控制台' },
];

function isWebViewConsoleLog(entry: LogEntry): boolean {
  return entry.message.includes('[WebView:') || entry.message.includes('host.webviewConsole');
}

export interface DiagnosticsTabProps {
  bridge: BridgeClient;
  config: SideVeilConfig;
  status: DaemonStatus | null;
  logs: readonly LogEntry[];
  auditReport: PluginAuditReport | null;
  busyAction: string | null;
  lastPingMs: number | null;
  cdpStatus: DebugCdpStatusResult | null;
  uiSnapshot: DebugInspectUiSnapshot | null;
  inspectCapturedAt: string | null;
  debugBusy: DebugBusyAction;
  debugError: string | null;
  networkContext: NetworkContextSnapshot | null;
  networkLoading: boolean;
  networkError: string | null;
  onPing: () => void;
  onReloadConfig: () => void;
  onRestartHost: () => void;
  onRefreshLogs: () => void;
  onClearLogs: () => void;
  onRefreshAudit: () => Promise<void>;
  onSetWebViewDebugging: (enabled: boolean) => void;
  onInspectUi: () => void;
  onResetFrameMetrics: () => void;
  onRefreshNetwork: () => void;
}

export function DiagnosticsTab({
  bridge,
  config,
  status,
  logs,
  auditReport,
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
  onRefreshAudit,
  onSetWebViewDebugging,
  onInspectUi,
  onResetFrameMetrics,
  onRefreshNetwork,
}: DiagnosticsTabProps) {
  const [logSourceFilter, setLogSourceFilter] = useState<LogSourceFilter>('all');
  const [devToolsOpen, setDevToolsOpen] = useState(false);

  const filteredLogs = useMemo(
    () => (logSourceFilter === 'webview' ? logs.filter(isWebViewConsoleLog) : logs),
    [logSourceFilter, logs],
  );

  const toggleDevTools = () => {
    const next = !devToolsOpen;
    setDevToolsOpen(next);
    if (next && !auditReport) {
      void onRefreshAudit();
    }
  };

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

          <div className="flex flex-wrap items-center gap-2 pt-1 text-xs">
            <Badge variant={status?.taskListenerActive ? 'success' : 'warning'} dot>
              TaskListener: {status?.taskListenerActive ? '运行中' : '未就绪'}
            </Badge>
            <Badge variant={status?.gestureMonitorActive ? 'success' : 'warning'} dot>
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

      {/* Collapsible Developer Options (purely client-side fold, zero backend mode flag) */}
      <Card>
        <button
          type="button"
          aria-expanded={devToolsOpen}
          aria-label="展开或收起开发者选项"
          onClick={toggleDevTools}
          className="flex w-full cursor-pointer items-center justify-between gap-2 px-4 py-3.5 text-left"
        >
          <div className="flex flex-col gap-0.5">
            <span className="flex items-center gap-1.5 text-sm font-semibold text-[var(--text-primary)]">
              <Wrench className="size-4 text-[var(--accent)]" />
              开发者选项
            </span>
            <span className="text-xs text-[var(--text-secondary)]">
              WebView 远程调试 (CDP)、宿主运行时审计、UI 检查器、手势统计与 Bridge 追踪
            </span>
          </div>
          <span className="inline-flex shrink-0 items-center gap-1.5 text-xs text-[var(--text-secondary)]">
            <Badge variant={cdpStatus?.effectiveEnabled ? 'success' : 'neutral'}>
              {cdpStatus?.effectiveEnabled ? 'CDP 已开启' : devToolsOpen ? '已展开' : '已折叠'}
            </Badge>
            {devToolsOpen ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
          </span>
        </button>

        {devToolsOpen ? (
          <CardContent className="flex flex-col gap-4 border-t border-[var(--border-hairline)] pt-4">
            <CdpDebugCard
              config={config}
              status={status}
              cdpStatus={cdpStatus}
              busy={debugBusy === 'cdp'}
              onSetWebViewDebugging={onSetWebViewDebugging}
            />
            <PluginAuditDrawer
              report={auditReport}
              loading={busyAction === 'audit'}
              onRefresh={onRefreshAudit}
            />
            <GestureStatsCard stats={uiSnapshot?.gestureStats ?? status?.gestureStats} />
            <UiInspectorCard
              snapshot={uiSnapshot}
              capturedAt={inspectCapturedAt}
              busy={debugBusy === 'inspect'}
              resetBusy={debugBusy === 'reset-frames'}
              error={debugError}
              onCapture={onInspectUi}
              onResetFrameMetrics={onResetFrameMetrics}
            />
            <BridgeTraceCard bridge={bridge} />
          </CardContent>
        ) : null}
      </Card>

      <div className="flex flex-col gap-2">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <fieldset
            aria-label="日志来源筛选"
            className="m-0 flex flex-wrap items-center gap-1.5 border-0 p-0"
          >
            {LOG_SOURCE_OPTIONS.map((option) => {
              const active = logSourceFilter === option.id;
              return (
                <Button
                  key={option.id}
                  size="sm"
                  variant={active ? 'primary' : 'secondary'}
                  aria-pressed={active}
                  aria-label={`筛选日志：${option.label}`}
                  onClick={() => setLogSourceFilter(option.id)}
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
          searchable
          filterable
          onClear={onClearLogs}
          maxHeightClassName="max-h-72"
        />
      </div>
    </div>
  );
}
