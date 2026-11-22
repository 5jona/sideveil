import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@pochita/ui';
import { Activity, Cpu, Layers, Monitor, ScanSearch } from 'lucide-react';
import type { DebugInspectUiSnapshot, DebugWebViewSnapshot } from '../../shared/types.ts';

export interface UiInspectorCardProps {
  snapshot: DebugInspectUiSnapshot | null;
  capturedAt: string | null;
  busy: boolean;
  error: string | null;
  onCapture: () => void;
}

function formatHz(hz: number): string {
  if (!Number.isFinite(hz) || hz <= 0) return '--';
  return `${Number.isInteger(hz) ? hz : hz.toFixed(1)} Hz`;
}

function formatMs(ms: number): string {
  if (!Number.isFinite(ms)) return '--';
  return `${ms.toFixed(1)} ms`;
}

function panelStateLabel(state: string): string {
  if (state === 'open') return '已展开';
  if (state === 'dragging') return '拖拽中';
  return '已收起';
}

function WebViewRow({ item }: { item: DebugWebViewSnapshot }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-2.5 py-2 text-xs">
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="truncate font-mono font-medium text-[var(--text-primary)]">
          {item.pluginId}
        </span>
        <span className="truncate text-[11px] text-[var(--text-secondary)]">
          {item.widthPx}×{item.heightPx} · {item.presentationMode} · 优先级 {item.rendererPriority}
        </span>
      </div>
      <div className="flex flex-wrap items-center gap-1.5">
        {item.isActive ? <Badge variant="accent">活跃</Badge> : null}
        {item.isPinned ? <Badge variant="warning">固定</Badge> : null}
        {item.keepAlive ? <Badge variant="success">常驻</Badge> : null}
        {item.attachedToParent ? (
          <Badge variant="neutral">已挂载</Badge>
        ) : (
          <Badge variant="neutral">后台预热</Badge>
        )}
      </div>
    </div>
  );
}

export function UiInspectorCard({
  snapshot,
  capturedAt,
  busy,
  error,
  onCapture,
}: UiInspectorCardProps) {
  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <div className="flex flex-col gap-0.5">
          <CardTitle>实时 UI 检查器</CardTitle>
          <CardDescription>
            采集 Host 显示刷新率投票、窗口布局、AGSL 渲染状态与帧耗时指标
          </CardDescription>
        </div>
        <div className="flex items-center gap-2">
          {capturedAt ? <Badge variant="neutral">采集于 {capturedAt}</Badge> : null}
          <Button
            size="sm"
            variant="primary"
            disabled={busy}
            aria-label="采集 UI 快照"
            onClick={onCapture}
          >
            <ScanSearch className="size-3.5" />
            {busy ? '采集中...' : '采集 UI 快照'}
          </Button>
        </div>
      </CardHeader>

      <CardContent className="flex flex-col gap-3">
        {error ? (
          <div className="rounded-[var(--radius-control)] border border-[color-mix(in_oklab,var(--warning)_35%,transparent)] bg-[color-mix(in_oklab,var(--warning)_12%,transparent)] px-3 py-2 text-xs text-[var(--text-primary)]">
            最近采集异常：{error}
          </div>
        ) : null}

        {!snapshot ? (
          <div className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-3 py-4 text-center text-xs text-[var(--text-secondary)]">
            点击「采集 UI 快照」实时读取 Host 渲染管线与 WebView 池状态
          </div>
        ) : (
          <div className="flex flex-col gap-3">
            <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
              <div className="flex flex-col gap-1.5 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="flex items-center gap-1.5 text-xs font-medium text-[var(--text-secondary)]">
                    <Monitor className="size-3.5 text-[var(--accent)]" />
                    显示与刷新率投票
                  </span>
                  <Badge variant="accent">
                    {formatHz(snapshot.display.refreshRateHz)} /{' '}
                    {formatHz(snapshot.display.maxRefreshRateHz)}
                  </Badge>
                </div>
                <div className="text-xs text-[var(--text-primary)]">
                  分辨率 {snapshot.display.widthPx}×{snapshot.display.heightPx} · 密度{' '}
                  {snapshot.display.density.toFixed(2)}x
                </div>
                <div className="text-[11px] text-[var(--text-secondary)]">
                  面板投票：{formatHz(snapshot.panel.preferredRefreshRateHz)} · 模式 #
                  {snapshot.display.activeModeId} / #{snapshot.display.maxSupportedModeId}
                </div>
              </div>

              <div className="flex flex-col gap-1.5 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="flex items-center gap-1.5 text-xs font-medium text-[var(--text-secondary)]">
                    <Layers className="size-3.5 text-[var(--accent)]" />
                    面板窗口与 IME 边距
                  </span>
                  <Badge variant={snapshot.panel.visible ? 'success' : 'neutral'} dot>
                    {panelStateLabel(snapshot.panel.state)}
                  </Badge>
                </div>
                <div className="text-xs text-[var(--text-primary)]">
                  尺寸 {snapshot.panel.width}×{snapshot.panel.height} px ·{' '}
                  {snapshot.panel.presentationMode}
                </div>
                <div className="text-[11px] text-[var(--text-secondary)]">
                  IME 底部边距：{snapshot.safeBounds.imeBottomPx} px · 安全底栏：
                  {snapshot.safeBounds.safeBottomPx} px
                </div>
              </div>

              <div className="flex flex-col gap-1.5 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="flex items-center gap-1.5 text-xs font-medium text-[var(--text-secondary)]">
                    <Cpu className="size-3.5 text-[var(--accent)]" />
                    AGSL 着色器与模糊
                  </span>
                  <Badge variant={snapshot.agsl.shaderCompiled ? 'success' : 'warning'} dot>
                    {snapshot.agsl.shaderCompiled ? 'AGSL 已编译' : '回退路径'}
                  </Badge>
                </div>
                <div className="text-xs text-[var(--text-primary)]">
                  实际模糊半径：{snapshot.panel.appliedBlurRadiusPx} px（目标{' '}
                  {snapshot.panel.targetBlurRadiusPx} px）
                </div>
                <div className="text-[11px] text-[var(--text-secondary)]">
                  模糊激活：{snapshot.agsl.blurActive ? '是' : '否'} · 主题 {snapshot.agsl.themeId}
                </div>
              </div>

              <div className="flex flex-col gap-1.5 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="flex items-center gap-1.5 text-xs font-medium text-[var(--text-secondary)]">
                    <Activity className="size-3.5 text-[var(--accent)]" />
                    FrameMetrics 帧耗时
                  </span>
                  <Badge
                    variant={snapshot.frameMetrics.jankCount === 0 ? 'success' : 'warning'}
                    dot
                  >
                    掉帧 {snapshot.frameMetrics.jankCount}
                  </Badge>
                </div>
                <div className="font-mono text-xs text-[var(--text-primary)]">
                  P50 {formatMs(snapshot.frameMetrics.p50Ms)} · P95{' '}
                  {formatMs(snapshot.frameMetrics.p95Ms)} · P99{' '}
                  {formatMs(snapshot.frameMetrics.p99Ms)}
                </div>
                <div className="text-[11px] text-[var(--text-secondary)]">
                  样本 {snapshot.frameMetrics.sampleCount} · 目标预算{' '}
                  {formatMs(snapshot.frameMetrics.deadlineMs)}
                </div>
              </div>
            </div>

            <div className="flex flex-col gap-2">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-medium text-[var(--text-secondary)]">
                  预热 WebView 池（{snapshot.webViews.length}）
                </span>
                {snapshot.activePluginId ? (
                  <Badge variant="neutral">当前插件：{snapshot.activePluginId}</Badge>
                ) : null}
              </div>
              {snapshot.webViews.length === 0 ? (
                <div className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-3 py-2 text-xs text-[var(--text-secondary)]">
                  暂无已创建的插件 WebView 实例
                </div>
              ) : (
                <div className="flex flex-col gap-1.5">
                  {snapshot.webViews.map((item) => (
                    <WebViewRow key={item.pluginId} item={item} />
                  ))}
                </div>
              )}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
