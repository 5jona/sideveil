import type {
  PluginBackendKind,
  PluginBackendState,
  PluginCapability,
  PluginsSnapshotPayload,
} from '@pochita/sideveil-sdk';
import { Badge, Button, Card, MetricTile, Switch } from '@pochita/ui';
import {
  Bell,
  Boxes,
  CheckCircle2,
  FolderLock,
  Globe,
  Layers,
  Play,
  RefreshCw,
  Sliders,
  Sparkles,
  Terminal,
} from 'lucide-react';
import { useState } from 'react';

export interface PluginsTabProps {
  snapshot: PluginsSnapshotPayload | null;
  busyAction: string | null;
  onReload: () => Promise<void>;
  onSetEnabled: (pluginId: string, enabled: boolean) => Promise<void>;
  onSetPriority: (pluginId: string, priority: number) => Promise<void>;
  onSelectPlugin: (pluginId: string) => Promise<void>;
}

function renderBackendBadge(kind: PluginBackendKind, state: PluginBackendState) {
  if (kind === 'none') {
    return null;
  }
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
    default:
      return null;
  }
}

export function PluginsTab({
  snapshot,
  busyAction,
  onReload,
  onSetEnabled,
  onSetPriority,
  onSelectPlugin,
}: PluginsTabProps) {
  const [editingPriorityId, setEditingPriorityId] = useState<string | null>(null);
  const [tempPriority, setTempPriority] = useState<number>(0);

  const plugins = snapshot?.plugins ?? [];
  const activePluginId = snapshot?.activePluginId ?? null;
  const matchedIds = snapshot?.matchedPluginIds ?? [];
  const fgPkg = snapshot?.foregroundPackage ?? '';

  const activePlugin = plugins.find((p) => p.id === activePluginId);

  return (
    <div className="space-y-4">
      {/* Metric Tiles Summary */}
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
        <MetricTile
          label="已注册插件"
          value={plugins.length}
          hint={`启用: ${plugins.filter((p) => p.enabled).length} · 禁用: ${plugins.filter((p) => !p.enabled).length}`}
          statusLabel={plugins.length > 0 ? '正常' : '无'}
          statusVariant="accent"
          icon={<Boxes className="size-3.5" />}
        />
        <MetricTile
          label="当前激活插件"
          value={activePlugin?.name ?? '无'}
          hint={activePluginId ?? '暂无匹配插件'}
          statusLabel={activePlugin ? '展示中' : '空闲'}
          statusVariant={activePlugin ? 'success' : 'neutral'}
          icon={<Sparkles className="size-3.5" />}
        />
        <MetricTile
          label="前台命中插件"
          value={matchedIds.length}
          hint={fgPkg ? `前台: ${fgPkg}` : '未捕获前台应用'}
          statusLabel={matchedIds.length >= 2 ? '多插件Rail就绪' : '单插件模式'}
          statusVariant={matchedIds.length >= 2 ? 'accent' : 'neutral'}
          icon={<Layers className="size-3.5" />}
        />
      </div>

      {/* Action Header */}
      <div className="flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-panel)] bg-[var(--surface-soft)] p-3">
        <div className="flex items-center gap-2">
          <Boxes className="size-4 text-[var(--accent)]" />
          <span className="text-xs font-medium text-[var(--text-primary)]">
            插件目录：/data/system/sideveil/plugins/
          </span>
        </div>
        <Button
          size="sm"
          variant="secondary"
          disabled={busyAction !== null}
          onClick={() => void onReload()}
        >
          <RefreshCw className="size-3.5" />
          重新扫描插件
        </Button>
      </div>

      {/* Plugins List */}
      <div className="space-y-3">
        {plugins.length === 0 ? (
          <Card className="p-8 text-center text-xs text-[var(--text-secondary)]">
            未检测到已注册的插件。请将插件放置在 /data/system/sideveil/plugins/&lt;id&gt;/
            目录下，并确保包含 manifest.json。
          </Card>
        ) : (
          plugins.map((plugin) => {
            const isActive = plugin.id === activePluginId;
            const isMatched = matchedIds.includes(plugin.id);
            const isBusy = busyAction === `plugin-${plugin.id}`;

            return (
              <Card
                key={plugin.id}
                className={`space-y-3 p-3.5 transition-all ${
                  isActive ? 'ring-1 ring-[var(--accent)] shadow-sm' : ''
                }`}
              >
                {/* Header row */}
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <div className="space-y-1">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="text-sm font-semibold text-[var(--text-primary)]">
                        {plugin.name}
                      </span>
                      <span className="text-xs font-mono text-[var(--text-secondary)]">
                        v{plugin.version}
                      </span>
                      {plugin.builtin ? (
                        <Badge variant="neutral">内置</Badge>
                      ) : (
                        <Badge variant="accent">第三方</Badge>
                      )}
                      {isActive ? (
                        <Badge variant="success" dot pulse>
                          当前激活
                        </Badge>
                      ) : isMatched ? (
                        <Badge variant="accent">前台匹配</Badge>
                      ) : null}
                      {renderBackendBadge(plugin.backend, plugin.backendState)}
                    </div>
                    <div className="text-xs font-mono text-[var(--text-secondary)]">
                      {plugin.id}
                    </div>
                  </div>

                  {/* Switch and Select button */}
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
                    <span className="text-[11px] font-medium text-[var(--text-secondary)]">
                      触发规则
                    </span>
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
                    <span className="text-[11px] font-medium text-[var(--text-secondary)]">
                      优先级与外观
                    </span>
                    <div className="flex flex-wrap items-center gap-2">
                      {editingPriorityId === plugin.id ? (
                        <div className="flex items-center gap-1">
                          <input
                            type="number"
                            className="w-16 rounded border border-[var(--border-subtle)] bg-[var(--surface-elevated)] px-1.5 py-0.5 text-xs text-[var(--text-primary)]"
                            value={tempPriority}
                            onChange={(e) => setTempPriority(Number(e.target.value))}
                          />
                          <Button
                            size="icon-sm"
                            variant="primary"
                            onClick={() => {
                              void onSetPriority(plugin.id, tempPriority);
                              setEditingPriorityId(null);
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
                            setEditingPriorityId(plugin.id);
                            setTempPriority(plugin.priority);
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

                {/* Capabilities row */}
                {plugin.capabilities.length > 0 ? (
                  <div className="flex flex-wrap items-center gap-1.5 pt-1">
                    <span className="text-[11px] font-medium text-[var(--text-secondary)]">
                      权限：
                    </span>
                    {plugin.capabilities.map((cap) => renderCapabilityBadge(cap))}
                  </div>
                ) : null}
              </Card>
            );
          })
        )}
      </div>
    </div>
  );
}
