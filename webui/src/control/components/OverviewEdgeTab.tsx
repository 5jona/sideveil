import {
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  MetricTile,
  SettingRow,
  Slider,
  Switch,
} from '@pochita/ui';
import { Activity, Layers, ShieldCheck } from 'lucide-react';
import type { DaemonStatus, EdgeSide, SideVeilConfig } from '../../shared/types.ts';
import { EdgePreviewCanvas } from './EdgePreviewCanvas.tsx';

export interface OverviewEdgeTabProps {
  config: SideVeilConfig;
  status: DaemonStatus | null;
  onChange: (updater: (prev: SideVeilConfig) => SideVeilConfig) => void;
}

export function OverviewEdgeTab({ config, status, onChange }: OverviewEdgeTabProps) {
  const updateEdge = <K extends keyof SideVeilConfig['edge']>(
    key: K,
    value: SideVeilConfig['edge'][K],
  ) => {
    onChange((prev) => ({
      ...prev,
      edge: {
        ...prev.edge,
        [key]: value,
      },
    }));
  };

  const hostState = status?.hostConnected ? 'WebSocket 在线' : '等待 Host 连接';

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <MetricTile
          label="Daemon 守护进程"
          value={status ? `PID ${status.pid}` : '未连接'}
          statusLabel={status ? `UID ${status.uid}` : '离线'}
          statusVariant={status?.uid === 0 || status?.uid === 1000 ? 'success' : 'warning'}
          hint={
            status
              ? `运行 ${Math.round(status.uptimeMs / 1000)}s · Sensor ${status.sensorPid ?? '-'} · WS :${status.wsPort}`
              : '等待服务启动'
          }
          icon={<Activity className="size-4" />}
        />
        <MetricTile
          label="Render Host 容器"
          value={status?.hostPid ? `PID ${status.hostPid}` : '未运行'}
          statusLabel={hostState}
          statusVariant={status?.hostConnected ? 'success' : 'warning'}
          hint={status?.overlayPermissionGranted ? '悬浮窗权限已就绪' : '悬浮窗权限待授权'}
          icon={<Layers className="size-4" />}
        />
        <MetricTile
          label="边缘手势与任务监听"
          value={status?.gestureMonitorActive ? '运行中' : '异常'}
          statusLabel={status?.taskListenerActive ? 'TaskStack 就绪' : '轮询兜底'}
          statusVariant={status?.gestureMonitorActive ? 'success' : 'danger'}
          hint={
            status
              ? `${status.screenWidthPx}×${status.screenHeightPx} (${status.density}x)`
              : undefined
          }
          icon={<ShieldCheck className="size-4" />}
        />
      </div>

      <Card>
        <CardContent>
          <SettingRow
            variant="plain"
            title="启用 SideVeil 侧边栏"
            description="在命中作用域的应用中启用边缘触发条与滑动呼出"
            control={
              <Switch
                checked={config.enabled}
                onCheckedChange={(checked) => onChange((prev) => ({ ...prev, enabled: checked }))}
                aria-label="启用 SideVeil 侧边栏"
              />
            }
          />
        </CardContent>
      </Card>

      <div className="pochita-adaptive-grid">
        <Card>
          <CardHeader>
            <div>
              <CardTitle>边缘热区与手势</CardTitle>
              <CardDescription>横向滑过阈值触发，点击与竖滑完全穿透</CardDescription>
            </div>
          </CardHeader>
          <CardContent className="space-y-4">
            <SettingRow
              variant="plain"
              title="触发边缘位置"
              control={
                <div className="flex items-center gap-1.5">
                  {(['left', 'right'] as EdgeSide[]).map((side) => (
                    <Button
                      key={side}
                      size="sm"
                      variant={config.edge.side === side ? 'primary' : 'secondary'}
                      onClick={() => updateEdge('side', side)}
                    >
                      {side === 'left' ? '左侧' : '右侧'}
                    </Button>
                  ))}
                </div>
              }
            />

            <Slider
              label="胶囊贴边距离"
              value={config.edge.edgeOffsetDp}
              min={0}
              max={24}
              step={1}
              unit="dp"
              presets={[
                { label: '贴边', value: 0 },
                { label: '超近', value: 1 },
                { label: '标准', value: 4 },
              ]}
              disabled={!config.edge.handleVisible}
              onValueChange={(v) => updateEdge('edgeOffsetDp', v)}
            />

            <Slider
              label="纵向起始位置"
              value={config.edge.topPercent}
              min={5}
              max={85}
              step={1}
              unit="%"
              presets={[
                { label: '偏上', value: 20 },
                { label: '居中', value: 32 },
                { label: '偏下', value: 52 },
              ]}
              onValueChange={(v) => updateEdge('topPercent', v)}
            />

            <Slider
              label="热区高度"
              value={config.edge.heightDp}
              min={64}
              max={360}
              step={4}
              unit="dp"
              presets={[
                { label: '紧凑', value: 120 },
                { label: '标准', value: 168 },
                { label: '加长', value: 240 },
              ]}
              onValueChange={(v) => updateEdge('heightDp', v)}
            />

            <Slider
              label="边缘热区宽度"
              value={config.edge.hotzoneWidthDp}
              min={12}
              max={48}
              step={2}
              unit="dp"
              onValueChange={(v) => updateEdge('hotzoneWidthDp', v)}
            />

            <Slider
              label="触发滑出阈值"
              value={config.edge.triggerDistanceDp}
              min={18}
              max={96}
              step={2}
              unit="dp"
              onValueChange={(v) => updateEdge('triggerDistanceDp', v)}
            />

            <Slider
              label="引导胶囊不透明度"
              value={Math.round(config.edge.handleOpacity * 100)}
              min={10}
              max={100}
              step={5}
              unit="%"
              disabled={!config.edge.handleVisible}
              onValueChange={(v) => updateEdge('handleOpacity', Number((v / 100).toFixed(2)))}
            />

            <SettingRow
              variant="plain"
              title="显示引导胶囊"
              description="关闭后热区隐形但仍可正常侧滑"
              control={
                <Switch
                  checked={config.edge.handleVisible}
                  onCheckedChange={(v) => updateEdge('handleVisible', v)}
                  aria-label="显示引导胶囊"
                />
              }
            />

            <SettingRow
              variant="plain"
              title="全屏自动淡化"
              description="全屏隐藏状态栏时自动降低胶囊存在感"
              control={
                <Switch
                  checked={config.edge.dimInFullscreen}
                  onCheckedChange={(v) => updateEdge('dimInFullscreen', v)}
                  aria-label="全屏自动淡化"
                />
              }
            />

            <SettingRow
              variant="plain"
              title="全屏二次滑入防误触"
              description="全屏游戏或视频下首次内滑仅点亮胶囊，1.5s 内再次滑入展开"
              control={
                <Switch
                  checked={config.edge.doubleSwipeInFullscreen}
                  onCheckedChange={(v) => updateEdge('doubleSwipeInFullscreen', v)}
                  aria-label="全屏二次滑入防误触"
                />
              }
            />

            <Slider
              label="静置自动隐蔽延迟"
              value={config.edge.idleAutoDimMs}
              min={0}
              max={15000}
              step={500}
              unit="ms"
              presets={[
                { label: '关闭', value: 0 },
                { label: '3.5秒', value: 3500 },
                { label: '8秒', value: 8000 },
              ]}
              disabled={!config.edge.handleVisible}
              onValueChange={(v) => updateEdge('idleAutoDimMs', v)}
            />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <div>
              <CardTitle>几何布局预览</CardTitle>
              <CardDescription>实时映射热区位置、贴边距与面板比例</CardDescription>
            </div>
          </CardHeader>
          <CardContent>
            <EdgePreviewCanvas config={config} previewOpen />
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
