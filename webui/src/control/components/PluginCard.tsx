import type {
  PluginBackendKind,
  PluginBackendState,
  PluginCapability,
  PluginDescriptor,
} from '@pochita/sideveil-sdk';
import { Badge, Button, Card, Switch } from '@pochita/ui';
import {
  Activity,
  Bell,
  CheckCircle2,
  Clipboard,
  Code2,
  FolderLock,
  Gauge,
  Globe,
  Pencil,
  Play,
  Radio,
  Sliders,
  Terminal,
  Vibrate,
} from 'lucide-react';
import { useState } from 'react';
import type { PluginScopeOverrideInput } from '../hooks/usePluginsControl.ts';
import { PluginDevDialog } from './PluginDevDialog.tsx';
import { PluginScopeEditor } from './PluginScopeEditor.tsx';

export interface PluginCardProps {
  plugin: PluginDescriptor;
  isActive: boolean;
  isMatched: boolean;
  isBusy: boolean;
  foregroundPackage: string;
  onSetEnabled: (pluginId: string, enabled: boolean) => Promise<void>;
  onSetPriority: (pluginId: string, priority: number) => Promise<void>;
  onSetScopeOverride: (pluginId: string, override: PluginScopeOverrideInput) => Promise<void>;
  onSetDevUrl: (pluginId: string, url: string | null) => Promise<void>;
  onSelectPlugin: (pluginId: string) => Promise<void>;
}

function renderBackendBadge(kind: PluginBackendKind, state: PluginBackendState) {
  if (kind === 'none') return null;
  const isOnline = state === 'online';
  const label = kind === 'uds-native' ? 'Native 后端' : 'App 后端';
  return (
    <Badge variant={isOnline ? 'success' : 'neutral'} dot={isOnline}>
      {label} ({isOnline ? '就绪' : '未连接'})
    </Badge>
  );
}

function renderCapabilityBadge(cap: PluginCapability) {
  switch (cap) {
    case 'storage':
      return (
        <span
          key={cap}
          className="inline-flex items-center gap-1 rounded bg-[var(--surface-elevated)] px-1.5 py-0.5 text-[11px] text-[var(--text-secondary)]"
        >
          <FolderLock className="size-3 text-[var(--accent)]" />
          私有存储
        </span>
      );
    case 'toast':
      return (
        <span
          key={cap}
          className="inline-flex items-center gap-1 rounded bg-[var(--surface-elevated)] px-1.5 py-0.5 text-[11px] text-[var(--text-secondary)]"
        >
          <Bell className="size-3 text-[var(--warning)]" />
          Toast
        </span>
      );
    case 'system-exec':
      return (
        <span
          key={cap}
          className="inline-flex items-center gap-1 rounded bg-[var(--surface-elevated)] px-1.5 py-0.5 text-[11px] text-[var(--text-secondary)]"
        >
          <Terminal className="size-3 text-[var(--danger)]" />
          Root 执行
        </span>
      );
    case 'native-hud':
      return (
        <span
          key={cap}
          className="inline-flex items-center gap-1 rounded bg-[var(--surface-elevated)] px-1.5 py-0.5 text-[11px] text-[var(--text-secondary)]"
        >
          <Gauge className="size-3 text-[var(--success)]" />
          原生 HUD
        </span>
      );
    case 'clipboard':
      return (
        <span
          key={cap}
          className="inline-flex items-center gap-1 rounded bg-[var(--surface-elevated)] px-1.5 py-0.5 text-[11px] text-[var(--text-secondary)]"
        >
          <Clipboard className="size-3 text-[var(--accent)]" />
          剪贴板
        </span>
      );
    case 'haptics':
      return (
        <span
          key={cap}
          className="inline-flex items-center gap-1 rounded bg-[var(--surface-elevated)] px-1.5 py-0.5 text-[11px] text-[var(--text-secondary)]"
        >
          <Vibrate className="size-3 text-[var(--accent)]" />
          触觉反馈
        </span>
      );
    case 'telemetry':
      return (
        <span
          key={cap}
          className="inline-flex items-center gap-1 rounded bg-[var(--surface-elevated)] px-1.5 py-0.5 text-[11px] text-[var(--text-secondary)]"
        >
          <Activity className="size-3 text-[var(--success)]" />
          系统遥测
        </span>
      );
    case 'inter-plugin-bus':
      return (
        <span
          key={cap}
          className="inline-flex items-center gap-1 rounded bg-[var(--surface-elevated)] px-1.5 py-0.5 text-[11px] text-[var(--text-secondary)]"
        >
          <Radio className="size-3 text-[var(--warning)]" />
          插件总线
        </span>
      );
    default:
      return null;
  }
}

export function PluginCard({
  plugin,
  isActive,
  isMatched,
  isBusy,
  foregroundPackage,
  onSetEnabled,
  onSetPriority,
  onSetScopeOverride,
  onSetDevUrl,
  onSelectPlugin,
}: PluginCardProps) {
  const [editingPriority, setEditingPriority] = useState(false);
  const [tempPriority, setTempPriority] = useState(plugin.priority);
  const [editingScope, setEditingScope] = useState(false);
  const [editingDev, setEditingDev] = useState(false);

  return (
    <Card
      className={`space-y-3 p-3.5 transition-all ${
        isActive ? 'ring-1 ring-[var(--accent)] shadow-sm' : ''
      }`}
    >
      {/* Header row */}
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="space-y-1">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-sm font-semibold text-[var(--text-primary)]">{plugin.name}</span>
            <span className="text-xs font-mono text-[var(--text-secondary)]">
              v{plugin.version}
            </span>
            <Badge variant={plugin.builtin ? 'neutral' : 'accent'}>
              {plugin.builtin ? '内置' : '第三方'}
            </Badge>
            {isActive ? (
              <Badge variant="success" dot pulse>
                当前激活
              </Badge>
            ) : isMatched ? (
              <Badge variant="accent">前台匹配</Badge>
            ) : null}
            {plugin.hasScopeOverride ? <Badge variant="warning">已覆盖范围</Badge> : null}
            {plugin.devServerUrl ? <Badge variant="warning">Dev 热重载</Badge> : null}
            {plugin.surface.crossOriginIsolated ? <Badge variant="accent">WebGPU/COI</Badge> : null}
            {renderBackendBadge(plugin.backend, plugin.backendState)}
          </div>
          <div className="text-xs font-mono text-[var(--text-secondary)]">{plugin.id}</div>
        </div>

        <div className="flex items-center gap-2">
          {plugin.enabled && isMatched && !isActive ? (
            <Button
              size="sm"
              variant="secondary"
              disabled={isBusy}
              onClick={() => void onSelectPlugin(plugin.id)}
            >
              <Play className="size-3" />
              切换至此
            </Button>
          ) : null}
          <Switch
            checked={plugin.enabled}
            onCheckedChange={(checked) => void onSetEnabled(plugin.id, checked)}
            aria-label={`启用 ${plugin.name}`}
            disabled={isBusy}
          />
        </div>
      </div>

      <div className="pochita-divider" />

      {/* Details grid */}
      <div className="grid grid-cols-1 gap-2 text-xs sm:grid-cols-2">
        {/* Triggers */}
        <div className="space-y-1">
          <div className="flex items-center justify-between gap-2">
            <span className="text-[11px] font-medium text-[var(--text-secondary)]">触发规则</span>
            <button
              type="button"
              disabled={isBusy}
              onClick={() => setEditingScope((prev) => !prev)}
              className="inline-flex cursor-pointer items-center gap-1 rounded px-1.5 py-0.5 text-[11px] text-[var(--accent)] hover:bg-[var(--surface-elevated)]"
            >
              <Pencil className="size-3" />
              {editingScope ? '收起编辑' : '覆盖范围'}
            </button>
          </div>
          <div className="flex flex-wrap items-center gap-1">
            {plugin.global ? (
              <span className="inline-flex items-center gap-1 rounded bg-[var(--surface-elevated)] px-1.5 py-0.5 text-[11px] text-[var(--text-primary)]">
                <Globe className="size-3 text-[var(--accent)]" />
                全局匹配
              </span>
            ) : plugin.packages.length > 0 ? (
              plugin.packages.map((pkg) => (
                <span
                  key={pkg}
                  className="inline-flex items-center rounded bg-[var(--surface-elevated)] px-1.5 py-0.5 font-mono text-[11px] text-[var(--text-primary)]"
                >
                  {pkg}
                </span>
              ))
            ) : (
              <span className="text-[11px] text-[var(--text-secondary)]">无指定包名</span>
            )}
          </div>
        </div>

        {/* Priority & Surface */}
        <div className="space-y-1">
          <div className="flex items-center justify-between gap-2">
            <span className="text-[11px] font-medium text-[var(--text-secondary)]">
              优先级与外观
            </span>
            <button
              type="button"
              disabled={isBusy}
              onClick={() => setEditingDev((prev) => !prev)}
              className="inline-flex cursor-pointer items-center gap-1 rounded px-1.5 py-0.5 text-[11px] text-[var(--accent)] hover:bg-[var(--surface-elevated)]"
            >
              <Code2 className="size-3" />
              {editingDev ? '收起开发' : '开发模式'}
            </button>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            {editingPriority ? (
              <div className="flex items-center gap-1">
                <input
                  type="number"
                  aria-label={`编辑 ${plugin.name} 优先级`}
                  className="w-16 rounded border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-1.5 py-0.5 text-xs text-[var(--text-primary)]"
                  value={tempPriority}
                  onChange={(e) => setTempPriority(Number(e.target.value))}
                />
                <Button
                  size="icon-sm"
                  variant="primary"
                  disabled={isBusy}
                  onClick={() => {
                    void onSetPriority(plugin.id, tempPriority);
                    setEditingPriority(false);
                  }}
                >
                  <CheckCircle2 className="size-3" />
                </Button>
              </div>
            ) : (
              <button
                type="button"
                className="inline-flex cursor-pointer items-center gap-1 rounded bg-[var(--surface-elevated)] px-1.5 py-0.5 text-[11px] text-[var(--text-primary)] hover:border-[var(--accent)]"
                onClick={() => {
                  setTempPriority(plugin.priority);
                  setEditingPriority(true);
                }}
              >
                <Sliders className="size-3 text-[var(--accent)]" />
                优先级: {plugin.priority}
              </button>
            )}

            <span className="inline-flex items-center gap-1 rounded bg-[var(--surface-elevated)] px-1.5 py-0.5 text-[11px] text-[var(--text-secondary)]">
              底板: {plugin.surface.backdrop}
            </span>
            {plugin.surface.widthDp ? (
              <span className="inline-flex items-center gap-1 rounded bg-[var(--surface-elevated)] px-1.5 py-0.5 text-[11px] text-[var(--text-secondary)]">
                {plugin.surface.widthDp}dp
              </span>
            ) : null}
          </div>
        </div>
      </div>

      {/* Inline Scope Override editor */}
      {editingScope ? (
        <PluginScopeEditor
          pluginId={plugin.id}
          pluginName={plugin.name}
          initialGlobal={plugin.global}
          initialPackages={plugin.packages}
          hasScopeOverride={plugin.hasScopeOverride}
          foregroundPackage={foregroundPackage}
          isBusy={isBusy}
          onSave={onSetScopeOverride}
          onCancel={() => setEditingScope(false)}
        />
      ) : null}

      {/* Inline Dev Mode HMR editor */}
      {editingDev ? (
        <PluginDevDialog
          pluginId={plugin.id}
          pluginName={plugin.name}
          initialUrl={plugin.devServerUrl}
          isBusy={isBusy}
          onSave={onSetDevUrl}
          onCancel={() => setEditingDev(false)}
        />
      ) : null}

      {/* Capabilities row */}
      {plugin.capabilities.length > 0 ? (
        <div className="flex flex-wrap items-center gap-1.5 pt-1">
          <span className="text-[11px] font-medium text-[var(--text-secondary)]">权限：</span>
          {plugin.capabilities.map((cap) => renderCapabilityBadge(cap))}
        </div>
      ) : null}
    </Card>
  );
}
