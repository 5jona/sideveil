import type { PluginAuditReport } from '@pochita/sideveil-sdk';
import { Badge, Button, Card } from '@pochita/ui';
import { Cpu, RefreshCw, ShieldAlert, ShieldCheck } from 'lucide-react';

export interface PluginAuditDrawerProps {
  report: PluginAuditReport | null;
  loading: boolean;
  onRefresh: () => Promise<void>;
}

function formatKbToMb(kb: number | null | undefined): string {
  if (typeof kb !== 'number' || !Number.isFinite(kb)) return '--';
  return `${(kb / 1024).toFixed(1)} MB`;
}

export function PluginAuditDrawer({ report, loading, onRefresh }: PluginAuditDrawerProps) {
  const diag = report?.diagnostics;
  const webviewCrashCount = diag?.webviewCrashCount ?? 0;
  const entries = report?.entries ?? [];

  return (
    <Card className="space-y-3 p-3.5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Cpu className="size-4 text-[var(--accent)]" />
          <span className="text-xs font-semibold text-[var(--text-primary)]">
            宿主运行时与权限审计
          </span>
          {webviewCrashCount > 0 ? (
            <Badge variant="danger">WebView 崩溃 {webviewCrashCount} 次</Badge>
          ) : null}
        </div>
        <Button size="sm" variant="secondary" disabled={loading} onClick={() => void onRefresh()}>
          <RefreshCw className="size-3.5" />
          刷新审计
        </Button>
      </div>

      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <div className="rounded bg-[var(--surface-elevated)] p-2">
          <div className="text-[11px] text-[var(--text-secondary)]">WebView LRU 热池</div>
          <div className="mt-0.5 text-xs font-semibold text-[var(--text-primary)]">
            {diag?.warmPluginIds?.length ?? 0} 个实例
          </div>
        </div>
        <div className="rounded bg-[var(--surface-elevated)] p-2">
          <div className="text-[11px] text-[var(--text-secondary)]">活跃 Native HUD</div>
          <div className="mt-0.5 truncate font-mono text-xs font-semibold text-[var(--text-primary)]">
            {diag?.activeHudPluginId || '空闲'}
          </div>
        </div>
        <div className="rounded bg-[var(--surface-elevated)] p-2">
          <div className="text-[11px] text-[var(--text-secondary)]">Host Native Heap</div>
          <div className="mt-0.5 font-mono text-xs font-semibold text-[var(--text-primary)]">
            {formatKbToMb(diag?.hostNativeHeapKb)}
          </div>
        </div>
        <div className="rounded bg-[var(--surface-elevated)] p-2">
          <div className="text-[11px] text-[var(--text-secondary)]">Host Total PSS</div>
          <div className="mt-0.5 font-mono text-xs font-semibold text-[var(--text-primary)]">
            {formatKbToMb(diag?.hostPssKb)}
          </div>
        </div>
      </div>

      {diag?.warmPluginIds && diag.warmPluginIds.length > 0 ? (
        <div className="flex flex-wrap items-center gap-1">
          <span className="text-[11px] text-[var(--text-secondary)]">常驻沙箱：</span>
          {diag.warmPluginIds.map((id) => (
            <span
              key={id}
              className="rounded bg-[var(--surface-elevated)] px-1.5 py-0.5 font-mono text-[11px] text-[var(--text-primary)]"
            >
              {id}
            </span>
          ))}
        </div>
      ) : null}

      <div className="space-y-1.5">
        <div className="text-[11px] font-medium text-[var(--text-secondary)]">
          最近权限审计记录（{entries.length}）
        </div>
        {entries.length === 0 ? (
          <div className="rounded bg-[var(--surface-elevated)] p-2.5 text-center text-[11px] text-[var(--text-secondary)]">
            暂无权限调用审计记录
          </div>
        ) : (
          <div className="max-h-44 space-y-1 overflow-y-auto pr-1">
            {entries
              .slice()
              .reverse()
              .slice(0, 20)
              .map((entry) => (
                <div
                  key={`${entry.timestampMs}-${entry.pluginId}-${entry.action}-${entry.detail}`}
                  className="flex flex-wrap items-center justify-between gap-2 rounded bg-[var(--surface-elevated)] px-2 py-1 text-[11px]"
                >
                  <div className="flex items-center gap-1.5">
                    {entry.allowed ? (
                      <ShieldCheck className="size-3 text-[var(--success)]" />
                    ) : (
                      <ShieldAlert className="size-3 text-[var(--danger)]" />
                    )}
                    <span className="font-mono text-[var(--text-primary)]">{entry.pluginId}</span>
                    <span className="text-[var(--text-secondary)]">{entry.action}</span>
                    {entry.detail ? (
                      <span className="truncate text-[var(--text-secondary)]">
                        · {entry.detail}
                      </span>
                    ) : null}
                  </div>
                  <Badge variant={entry.allowed ? 'success' : 'danger'}>
                    {entry.allowed ? '放行' : '拒绝'}
                  </Badge>
                </div>
              ))}
          </div>
        )}
      </div>
    </Card>
  );
}
