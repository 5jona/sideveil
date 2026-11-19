import { Plus, X } from 'lucide-react';
import { useEffect, useState } from 'react';
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
} from '@pochita/ui';
import type { ScopeMode, SideveilConfig } from '../../shared/types.ts';

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
  const { themeId, colorMode, setThemeId, setColorMode } = useTheme();
  const [allowInput, setAllowInput] = useState('');
  const [denyInput, setDenyInput] = useState('');

  // Keep config.panel.themeId and colorMode aligned with UI theme selections.
  useEffect(() => {
    if (config.panel.themeId !== themeId || config.panel.colorMode !== colorMode) {
      onChange((prev) => ({
        ...prev,
        panel: {
          ...prev.panel,
          themeId,
          colorMode,
        },
      }));
    }
  }, [themeId, colorMode, config.panel.themeId, config.panel.colorMode, onChange]);

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
    setAllowInput('');
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
    setDenyInput('');
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
            <ThemeSwitcher />
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

          <div className="space-y-2.5 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-3.5">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div>
                <div className="text-xs font-medium text-[var(--text-primary)]">
                  激活白名单（{config.scope.allowlist.length}）
                </div>
                <p className="text-[11px] text-[var(--text-secondary)]">
                  默认包含 KernelSU 管理器（me.weishu.kernelsu），方便边调参边直接滑出测试
                </p>
              </div>
              {canQuickAddFg ? (
                <Button size="sm" variant="pill" onClick={() => addAllowPackage(foregroundPackage)}>
                  <Plus className="size-3.5" />
                  加入当前前台 ({foregroundPackage})
                </Button>
              ) : null}
            </div>

            <div className="flex flex-wrap gap-1.5">
              {config.scope.allowlist.length === 0 ? (
                <span className="text-xs text-[var(--text-secondary)]">暂无白名单包名</span>
              ) : (
                config.scope.allowlist.map((pkg) => (
                  <span
                    key={pkg}
                    className="inline-flex items-center gap-1 rounded-[var(--radius-pill)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2.5 py-1 text-xs text-[var(--text-primary)]"
                  >
                    <span className="font-mono text-[11px]">{pkg}</span>
                    <button
                      type="button"
                      onClick={() => removeAllowPackage(pkg)}
                      aria-label={`移除 ${pkg}`}
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
                addAllowPackage(allowInput);
              }}
            >
              <input
                id="allowlist-pkg-input"
                name="allowlistPackage"
                type="text"
                value={allowInput}
                onChange={(ev) => setAllowInput(ev.target.value)}
                placeholder="输入包名，例如 com.example.app"
                aria-label="新增白名单包名"
                className="h-8 min-w-0 flex-1 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-3 text-xs text-[var(--text-primary)] placeholder:text-[var(--text-secondary)] focus:border-[var(--accent)] focus:outline-none"
              />
              <Button size="sm" type="submit" variant="secondary" disabled={!allowInput.trim()}>
                <Plus className="size-3.5" />
                添加
              </Button>
            </form>
          </div>

          <div className="space-y-2.5 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-3.5">
            <div>
              <div className="text-xs font-medium text-[var(--text-primary)]">
                排除黑名单（{config.scope.denylist.length}）
              </div>
              <p className="text-[11px] text-[var(--text-secondary)]">
                无论何种模式，下列系统进程界面中始终禁用侧边栏热区
              </p>
            </div>

            <div className="flex flex-wrap gap-1.5">
              {config.scope.denylist.map((pkg) => (
                <span
                  key={pkg}
                  className="inline-flex items-center gap-1 rounded-[var(--radius-pill)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2.5 py-1 text-xs text-[var(--text-primary)]"
                >
                  <span className="font-mono text-[11px]">{pkg}</span>
                  <button
                    type="button"
                    onClick={() => removeDenyPackage(pkg)}
                    aria-label={`移除排除项 ${pkg}`}
                    className="cursor-pointer rounded-full p-0.5 text-[var(--text-secondary)] hover:bg-[var(--surface-elevated)] hover:text-[var(--danger)]"
                  >
                    <X className="size-3" />
                  </button>
                </span>
              ))}
            </div>

            <form
              className="flex items-center gap-2 pt-1"
              onSubmit={(ev) => {
                ev.preventDefault();
                addDenyPackage(denyInput);
              }}
            >
              <input
                id="denylist-pkg-input"
                name="denylistPackage"
                type="text"
                value={denyInput}
                onChange={(ev) => setDenyInput(ev.target.value)}
                placeholder="输入排除包名"
                aria-label="新增排除包名"
                className="h-8 min-w-0 flex-1 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-3 text-xs text-[var(--text-primary)] placeholder:text-[var(--text-secondary)] focus:border-[var(--accent)] focus:outline-none"
              />
              <Button size="sm" type="submit" variant="secondary" disabled={!denyInput.trim()}>
                <Plus className="size-3.5" />
                排除
              </Button>
            </form>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
