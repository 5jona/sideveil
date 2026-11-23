import {
  Badge,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  SegmentedControl,
  type SegmentedControlOption,
  SettingRow,
} from '@gojona/ui';
import { FlaskConical, ShieldCheck, Wrench } from 'lucide-react';
import type { RuntimeMode } from '../../shared/types.ts';

export interface RuntimeModeCardProps {
  runtimeMode: RuntimeMode;
  busy: boolean;
  onChange: (mode: RuntimeMode) => void;
}

const RUNTIME_MODE_OPTIONS: readonly SegmentedControlOption<RuntimeMode>[] = [
  {
    value: 'release',
    label: '正式模式',
    icon: <ShieldCheck className="size-3.5" />,
  },
  {
    value: 'debug',
    label: '调试模式',
    icon: <Wrench className="size-3.5" />,
  },
];

export function RuntimeModeCard({ runtimeMode, busy, onChange }: RuntimeModeCardProps) {
  const isDebug = runtimeMode === 'debug';
  const options = RUNTIME_MODE_OPTIONS.map((opt) => ({ ...opt, disabled: busy }));

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-2">
        <div className="flex flex-col gap-0.5">
          <CardTitle className="flex items-center gap-1.5">
            <FlaskConical className="size-4 text-[var(--accent)]" />
            运行模式
          </CardTitle>
          <CardDescription>
            正式模式自动隐藏内置测试插件；调试模式启用内置诊断、Vello 矢量示波与性能监控
          </CardDescription>
        </div>
        <Badge variant={isDebug ? 'warning' : 'success'} dot>
          {isDebug ? '调试模式' : '正式模式'}
        </Badge>
      </CardHeader>

      <CardContent>
        <SettingRow
          variant="plain"
          title="内置测试插件可见性"
          description={
            isDebug
              ? '已启用 sideveil.diagnostic、example.vello-scope 与 example.native-perf-hud'
              : '仅展示已安装的正式插件与网页嵌入，侧边栏更清爽'
          }
          control={
            <SegmentedControl
              size="sm"
              ariaLabel="运行模式"
              options={options}
              value={runtimeMode}
              onValueChange={onChange}
            />
          }
        />
      </CardContent>
    </Card>
  );
}
