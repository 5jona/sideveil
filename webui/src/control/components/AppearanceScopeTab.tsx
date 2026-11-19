import { Plus, X } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Input,
  SettingRow,
  Slider,
  Switch,
  ThemeSwitcher,
  useTheme,
} from '@pochita/ui';
import type { ScopeMode, SideveilConfig } from '../../shared/types.ts';

interface PackageScopeListProps {
  title: string;
  description: string;
  emptyText?: string;
  packages: readonly string[];
  inputId: string;
  inputName: string;
  inputPlaceholder: string;
  inputAriaLabel: string;
  removeAriaPrefix: string;
  submitLabel?: string;
  headerAction?: ReactNode;
  onAddPackage: (pkg: string) => void;
  onRemovePackage: (pkg: string) => void;
}

function PackageScopeList({
  title,
  description,
  emptyText,
  packages,
  inputId,
  inputName,
  inputPlaceholder,
  inputAriaLabel,
  removeAriaPrefix,
  submitLabel = '添加',
  headerAction,
  onAddPackage,
  onRemovePackage,
}: PackageScopeListProps) {
  const [inputValue, setInputValue] = useState('');

  const handleSubmit = (pkgRaw: string) => {
    const trimmed = pkgRaw.trim();
    if (!trimmed) return;
    onAddPackage(trimmed);
    setInputValue('');
  };

  return (
    <div className="pochita-card-elevated space-y-2.5 p-3.5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <div className="text-xs font-medium text-[var(--text-primary)]">
            {title}（{packages.length}）
          </div>
          <p className="text-[11px] text-[var(--text-secondary)]">{description}</p>
        </div>
        {headerAction}
      </div>

      <div className="flex flex-wrap gap-1.5">
        {packages.length === 0 && emptyText ? (
          <span className="text-xs text-[var(--text-secondary)]">{emptyText}</span>
        ) : (
          packages.map((pkg) => (
            <span
              key={pkg}
              className="inline-flex items-center gap-1 rounded-[var(--radius-pill)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2.5 py-1 text-xs text-[var(--text-primary)]"
            >
              <span className="font-mono text-[11px]">{pkg}</span>
              <button
                type="button"
                onClick={() => onRemovePackage(pkg)}
                aria-label={`${removeAriaPrefix} ${pkg}`}
                className="cursor-pointer rounded-full p-0.5 text-[var(--text-secondary)] hover:bg-[var(--surface-elevated)] hover:text-[var(--danger)]"
              >
                <X className="size-3" />
              </button>
            </span>
          ))
        )}
      </div>

      <form
        className="flex items-center gap-2 pt-1"
        onSubmit={(ev) => {
          ev.preventDefault();
          handleSubmit(inputValue);
        }}
      >
        <Input
          id={inputId}
          name={inputName}
          size="sm"
          className="flex-1"
          value={inputValue}
          onChange={(ev) => setInputValue(ev.target.value)}
          placeholder={inputPlaceholder}
          aria-label={inputAriaLabel}
        />
        <Button size="sm" type="submit" variant="secondary" disabled={!inputValue.trim()}>
          <Plus className="size-3.5" />
          {submitLabel}
        </Button>
      </form>
    </div>
  );
}

export interface AppearanceScopeTabProps {
  config: SideveilConfig;
  foregroundPackage: string;
  crossWindowBlurSupported: boolean | null;
  onChange: (updater: (prev: SideveilConfig) => SideveilConfig) => void;
}

export function AppearanceScopeTab({
  config,
  foregroundPackage,
  crossWindowBlurSupported,
  onChange,
}: AppearanceScopeTabProps) {
  const { setThemeId, setColorMode } = useTheme();

  const updatePanel = <K extends keyof SideveilConfig['panel']>(
    key: K,
    value: SideveilConfig['panel'][K],
  ) => {
    onChange((prev) => ({
      ...prev,
      panel: {
        ...prev.panel,
        [key]: value,
      },
    }));
  };

  const setScopeMode = (mode: ScopeMode) => {
    onChange((prev) => ({
      ...prev,
      scope: {
        ...prev.scope,
        mode,
      },
    }));
  };

  const addAllowPackage = (pkgRaw: string) => {
    const pkg = pkgRaw.trim();
    if (!pkg) return;
    onChange((prev) => ({
      ...prev,
      scope: {
        ...prev.scope,
        allowlist: prev.scope.allowlist.includes(pkg)
          ? prev.scope.allowlist
          : [...prev.scope.allowlist, pkg],
      },
    }));
  };

  const removeAllowPackage = (pkg: string) => {
    onChange((prev) => ({
      ...prev,
      scope: {
        ...prev.scope,
        allowlist: prev.scope.allowlist.filter((item) => item !== pkg),
      },
    }));
  };

  const addDenyPackage = (pkgRaw: string) => {
    const pkg = pkgRaw.trim();
    if (!pkg) return;
    onChange((prev) => ({
      ...prev,
      scope: {
        ...prev.scope,
        denylist: prev.scope.denylist.includes(pkg)
          ? prev.scope.denylist
          : [...prev.scope.denylist, pkg],
      },
    }));
  };

  const removeDenyPackage = (pkg: string) => {
    onChange((prev) => ({
      ...prev,
      scope: {
        ...prev.scope,
        denylist: prev.scope.denylist.filter((item) => item !== pkg),
      },
    }));
  };

  const canQuickAddFg =
    Boolean(foregroundPackage) &&
    foregroundPackage !== 'me.weishu.kernelsu' &&
    !config.scope.allowlist.includes(foregroundPackage);

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

      <Card>
        <CardHeader>
          <div>
            <CardTitle>激活作用域</CardTitle>
            <CardDescription>控制允许激活边缘触发条的前台应用</CardDescription>
          </div>
        </CardHeader>
        <CardContent className="space-y-4">
          <SettingRow
            variant="plain"
            title="匹配模式"
            description="白名单仅在指定应用中生效；全局模式除黑名单外全部生效"
            control={
              <div className="flex items-center gap-1.5">
                <Button
                  size="sm"
                  variant={config.scope.mode === 'allowlist' ? 'primary' : 'secondary'}
                  onClick={() => setScopeMode('allowlist')}
                >
                  白名单
                </Button>
                <Button
                  size="sm"
                  variant={config.scope.mode === 'global' ? 'primary' : 'secondary'}
                  onClick={() => setScopeMode('global')}
                >
                  全局模式
                </Button>
              </div>
            }
          />

          <PackageScopeList
            title="激活白名单"
            description="默认包含 KernelSU 管理器（me.weishu.kernelsu），方便边调参边直接滑出测试"
            emptyText="暂无白名单包名"
            packages={config.scope.allowlist}
            inputId="allowlist-pkg-input"
            inputName="allowlistPackage"
            inputPlaceholder="输入包名，例如 com.example.app"
            inputAriaLabel="新增白名单包名"
            removeAriaPrefix="移除"
            submitLabel="添加"
            headerAction={
              canQuickAddFg ? (
                <Button size="sm" variant="pill" onClick={() => addAllowPackage(foregroundPackage)}>
                  <Plus className="size-3.5" />
                  加入当前前台 ({foregroundPackage})
                </Button>
              ) : undefined
            }
            onAddPackage={addAllowPackage}
            onRemovePackage={removeAllowPackage}
          />

          <PackageScopeList
            title="排除黑名单"
            description="无论何种模式，下列系统进程界面中始终禁用侧边栏热区"
            packages={config.scope.denylist}
            inputId="denylist-pkg-input"
            inputName="denylistPackage"
            inputPlaceholder="输入排除包名"
            inputAriaLabel="新增排除包名"
            removeAriaPrefix="移除排除项"
            submitLabel="排除"
            onAddPackage={addDenyPackage}
            onRemovePackage={removeDenyPackage}
          />
        </CardContent>
      </Card>
    </div>
  );
}
