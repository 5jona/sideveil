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
} from '@pochita/ui';
import { RefreshCw, RotateCcw, Terminal, Zap } from 'lucide-react';
import type { DaemonStatus } from '../../shared/types.ts';

export interface DiagnosticsTabProps {
  status: DaemonStatus | null;
  logs: readonly LogEntry[];
  busyAction: string | null;
  lastPingMs: number | null;
  onPing: () => void;
  onReloadConfig: () => void;
  onRestartHost: () => void;
  onRefreshLogs: () => void;
  onClearLogs: () => void;
}

export function DiagnosticsTab({
  status,
  logs,
  busyAction,
  lastPingMs,
  onPing,
  onReloadConfig,
  onRestartHost,
  onRefreshLogs,
  onClearLogs,
}: DiagnosticsTabProps) {
  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <div>
            <CardTitle>通信与运行诊断</CardTitle>
            <CardDescription>测试链路延迟、重载配置或重启宿主服务</CardDescription>
          </div>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <Button size="sm" variant="primary" disabled={busyAction !== null} onClick={onPing}>
              <Zap className="size-3.5" />
              {busyAction === 'ping' ? '测试中...' : 'Ping Daemon'}
            </Button>
            <Button
              size="sm"
              variant="secondary"
              disabled={busyAction !== null}
              onClick={onReloadConfig}
            >
              <RefreshCw className="size-3.5" />
              重载配置
            </Button>
            <Button
              size="sm"
              variant="secondary"
              disabled={busyAction !== null}
              onClick={onRestartHost}
            >
              <RotateCcw className="size-3.5" />
              重启 Host 服务
            </Button>
            <Button
              size="sm"
              variant="ghost"
              disabled={busyAction !== null}
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

      <LogConsole
        title="Daemon 运行日志"
        entries={logs}
        onClear={onClearLogs}
        maxHeightClassName="max-h-72"
      />
    </div>
  );
}
