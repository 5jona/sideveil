import { Badge, Card, CardContent, CardDescription, CardHeader, CardTitle } from '@gojona/ui';
import { Hand, ShieldAlert } from 'lucide-react';
import type { DebugGestureStats } from '../../shared/types.ts';

export interface GestureStatsCardProps {
  stats: DebugGestureStats | null | undefined;
}

export function GestureStatsCard({ stats }: GestureStatsCardProps) {
  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <div className="flex flex-col gap-0.5">
          <CardTitle className="flex items-center gap-1.5">
            <Hand className="size-4 text-[var(--accent)]" />
            边缘手势统计
          </CardTitle>
          <CardDescription>记录系统传感器拦截边缘滑动与接管指针的实时计数</CardDescription>
        </div>
        {stats ? (
          <Badge variant={stats.edgeSwipesRejected > 0 ? 'warning' : 'success'} dot>
            触发 {stats.edgeSwipesTriggered} / 拒绝 {stats.edgeSwipesRejected}
          </Badge>
        ) : null}
      </CardHeader>

      <CardContent className="flex flex-col gap-3">
        {!stats ? (
          <div className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-3 py-4 text-center text-xs text-[var(--text-secondary)]">
            等待传感器上报手势统计数据
          </div>
        ) : (
          <>
            <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
              <div className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-2.5">
                <div className="text-[11px] text-[var(--text-secondary)]">触发次数</div>
                <div className="mt-1 font-mono text-sm font-semibold text-[var(--text-primary)]">
                  {stats.edgeSwipesTriggered}
                </div>
              </div>
              <div className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-2.5">
                <div className="text-[11px] text-[var(--text-secondary)]">拒绝次数</div>
                <div className="mt-1 font-mono text-sm font-semibold text-[var(--text-primary)]">
                  {stats.edgeSwipesRejected}
                </div>
              </div>
              <div className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-2.5">
                <div className="text-[11px] text-[var(--text-secondary)]">热区未命中</div>
                <div className="mt-1 font-mono text-sm font-semibold text-[var(--text-primary)]">
                  {stats.hotzoneMisses}
                </div>
              </div>
              <div className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-2.5">
                <div className="text-[11px] text-[var(--text-secondary)]">接管次数</div>
                <div className="mt-1 font-mono text-sm font-semibold text-[var(--text-primary)]">
                  {stats.pilferInvocations}
                </div>
              </div>
            </div>

            {stats.lastRejectReason ? (
              <div className="flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-control)] border border-[color-mix(in_oklab,var(--warning)_35%,transparent)] bg-[color-mix(in_oklab,var(--warning)_10%,transparent)] px-3 py-2 text-xs">
                <span className="flex items-center gap-1.5 text-[var(--text-secondary)]">
                  <ShieldAlert className="size-3.5 text-[var(--warning)]" />
                  最近拒绝原因
                </span>
                <code className="font-mono text-[var(--text-primary)]">
                  {stats.lastRejectReason}
                </code>
              </div>
            ) : null}
          </>
        )}
      </CardContent>
    </Card>
  );
}
