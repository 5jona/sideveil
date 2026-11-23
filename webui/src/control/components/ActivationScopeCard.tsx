import {
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  SettingRow,
} from '@pochita/ui';
import { Plus } from 'lucide-react';
import type { ScopeConfig, ScopeMode } from '../../shared/types.ts';
import { PackageScopeList } from './PackageScopeList.tsx';

/** Props for the built-in foreground activation scope editor card. */
export interface ActivationScopeCardProps {
  scope: ScopeConfig;
  foregroundPackage: string;
  onChangeScope: (updater: (prev: ScopeConfig) => ScopeConfig) => void;
}

/**
 * Renders matching-mode controls and package allowlist/denylist editors
 * governing when the built-in sidebar trigger activates.
 */
export function ActivationScopeCard({
  scope,
  foregroundPackage,
  onChangeScope,
}: ActivationScopeCardProps) {
  const setScopeMode = (mode: ScopeMode) => {
    onChangeScope((prev) => ({ ...prev, mode }));
  };

  const addAllowPackage = (pkgRaw: string) => {
    const pkg = pkgRaw.trim();
    if (!pkg) return;
    onChangeScope((prev) => ({
      ...prev,
      allowlist: prev.allowlist.includes(pkg) ? prev.allowlist : [...prev.allowlist, pkg],
    }));
  };

  const removeAllowPackage = (pkg: string) => {
    onChangeScope((prev) => ({
      ...prev,
      allowlist: prev.allowlist.filter((item) => item !== pkg),
    }));
  };

  const addDenyPackage = (pkgRaw: string) => {
    const pkg = pkgRaw.trim();
    if (!pkg) return;
    onChangeScope((prev) => ({
      ...prev,
      denylist: prev.denylist.includes(pkg) ? prev.denylist : [...prev.denylist, pkg],
    }));
  };

  const removeDenyPackage = (pkg: string) => {
    onChangeScope((prev) => ({
      ...prev,
      denylist: prev.denylist.filter((item) => item !== pkg),
    }));
  };

  const canQuickAddFg =
    Boolean(foregroundPackage) &&
    foregroundPackage !== 'me.weishu.kernelsu' &&
    !scope.allowlist.includes(foregroundPackage);

  return (
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
                variant={scope.mode === 'allowlist' ? 'primary' : 'secondary'}
                onClick={() => setScopeMode('allowlist')}
              >
                白名单
              </Button>
              <Button
                size="sm"
                variant={scope.mode === 'global' ? 'primary' : 'secondary'}
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
          packages={scope.allowlist}
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
          packages={scope.denylist}
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
  );
}
