import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  SettingRow,
  Slider,
  Switch,
  ThemeSwitcher,
  useTheme,
} from '@gojona/ui';
import type { ScopeConfig, SideVeilConfig } from '../../shared/types.ts';
import { ActivationScopeCard } from './ActivationScopeCard.tsx';

/** Props for the Control Plane appearance, blur geometry, and scope settings tab. */
export interface AppearanceScopeTabProps {
  config: SideVeilConfig;
  foregroundPackage: string;
  crossWindowBlurSupported: boolean | null;
  onChange: (updater: (prev: SideVeilConfig) => SideVeilConfig) => void;
}

/**
 * Renders theme preset switching, panel geometry/blur sliders, and the activation scope editor.
 */
export function AppearanceScopeTab({
  config,
  foregroundPackage,
  crossWindowBlurSupported,
  onChange,
}: AppearanceScopeTabProps) {
  const { setThemeId, setColorMode } = useTheme();

  const updatePanel = <K extends keyof SideVeilConfig['panel']>(
    key: K,
    value: SideVeilConfig['panel'][K],
  ) => {
    onChange((prev) => ({
      ...prev,
      panel: {
        ...prev.panel,
        [key]: value,
      },
    }));
  };

  const updateScope = (updater: (prev: ScopeConfig) => ScopeConfig) => {
    onChange((prev) => ({
      ...prev,
      scope: updater(prev.scope),
    }));
  };

  return (
    <div className="space-y-4">
      <div className="pochita-adaptive-grid">
        <Card>
          <CardHeader>
            <div>
              <CardTitle>主题与材质</CardTitle>
              <CardDescription>实时同步侧边栏宿主外观</CardDescription>
            </div>
          </CardHeader>
          <CardContent className="space-y-3">
            <ThemeSwitcher
              onThemeChange={(nextTheme) => updatePanel('themeId', nextTheme)}
              onColorModeChange={(nextMode) => updatePanel('colorMode', nextMode)}
            />
            <div className="flex flex-wrap items-center gap-2 pt-1 text-xs text-[var(--text-secondary)]">
              <span>当前：</span>
              <Badge variant="accent">{config.panel.themeId}</Badge>
              <Badge variant="neutral">{config.panel.colorMode}</Badge>
              <Button
                size="sm"
                variant="ghost"
                onClick={() => {
                  setThemeId('coloros-glass');
                  setColorMode('system');
                  onChange((prev) => ({
                    ...prev,
                    panel: {
                      ...prev.panel,
                      themeId: 'coloros-glass',
                      colorMode: 'system',
                    },
                  }));
                }}
              >
                恢复默认
              </Button>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <div>
              <CardTitle>面板尺寸与毛玻璃</CardTitle>
              <CardDescription>
                跨窗模糊：
                {crossWindowBlurSupported === null
                  ? '检测中'
                  : crossWindowBlurSupported
                    ? '已支持'
                    : '硬件受限'}
              </CardDescription>
            </div>
          </CardHeader>
          <CardContent className="space-y-4">
            <Slider
              label="面板宽度"
              value={config.panel.widthDp}
              min={260}
              max={480}
              step={10}
              unit="dp"
              presets={[
                { label: '窄抽屉', value: 320 },
                { label: '标准', value: 340 },
                { label: '宽视图', value: 420 },
              ]}
              onValueChange={(v) => updatePanel('widthDp', v)}
            />

            <Slider
              label="面板占屏高度"
              value={config.panel.heightPercent}
              min={40}
              max={92}
              step={2}
              unit="%"
              onValueChange={(v) => updatePanel('heightPercent', v)}
            />

            <Slider
              label="卡片圆角半径"
              value={config.panel.cornerRadiusDp}
              min={8}
              max={36}
              step={2}
              unit="dp"
              onValueChange={(v) => updatePanel('cornerRadiusDp', v)}
            />

            <SettingRow
              variant="plain"
              title="跨窗实时毛玻璃"
              description="关闭后切换为不透明磨砂底板"
              control={
                <Switch
                  checked={config.panel.nativeBlurEnabled}
                  onCheckedChange={(v) => updatePanel('nativeBlurEnabled', v)}
                  aria-label="跨窗实时毛玻璃"
                />
              }
            />

            <Slider
              label="模糊半径"
              value={config.panel.blurRadiusPx}
              min={0}
              max={300}
              step={4}
              unit="px"
              presets={[
                { label: '柔和', value: 80 },
                { label: '标准', value: 160 },
                { label: '强模糊', value: 260 },
              ]}
              disabled={!config.panel.nativeBlurEnabled}
              onValueChange={(v) => updatePanel('blurRadiusPx', v)}
            />

            <Slider
              label="背景暗色遮罩"
              value={Math.round(config.panel.scrimAlpha * 100)}
              min={0}
              max={75}
              step={2}
              unit="%"
              onValueChange={(v) => updatePanel('scrimAlpha', Number((v / 100).toFixed(2)))}
            />
          </CardContent>
        </Card>
      </div>

      <ActivationScopeCard
        scope={config.scope}
        foregroundPackage={foregroundPackage}
        onChangeScope={updateScope}
      />
    </div>
  );
}
