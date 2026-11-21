import type { PluginsSnapshotPayload } from '@pochita/sideveil-sdk';
import { Button, Card, MetricTile } from '@pochita/ui';
import { Boxes, Layers, RefreshCw, Sparkles } from 'lucide-react';
import type { PluginScopeOverrideInput } from '../hooks/usePluginsControl.ts';
import { PluginCard } from './PluginCard.tsx';

export interface PluginsTabProps {
    snapshot: PluginsSnapshotPayload | null;
    busyAction: string | null;
    foregroundPackage: string;
    onReload: () => Promise<void>;
    onSetEnabled: (pluginId: string, enabled: boolean) => Promise<void>;
    onSetPriority: (pluginId: string, priority: number) => Promise<void>;
    onSetScopeOverride: (pluginId: string, override: PluginScopeOverrideInput) => Promise<void>;
    onSelectPlugin: (pluginId: string) => Promise<void>;
}

export function PluginsTab({
    snapshot,
    busyAction,
    foregroundPackage,
    onReload,
    onSetEnabled,
    onSetPriority,
    onSetScopeOverride,
    onSelectPlugin,
}: PluginsTabProps) {
    const plugins = snapshot?.plugins ?? [];
    const activePluginId = snapshot?.activePluginId ?? null;
    const matchedIds = snapshot?.matchedPluginIds ?? [];
    const fgPkg = snapshot?.foregroundPackage || foregroundPackage;

    const activePlugin = plugins.find((p) => p.id === activePluginId);

    return (
        <div className="space-y-4">
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

            <div className="flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-panel)] bg-[var(--surface-elevated)] p-3">
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

            <div className="space-y-3">
                {plugins.length === 0 ? (
                    <Card className="p-8 text-center text-xs text-[var(--text-secondary)]">
                        未检测到已注册的插件。请将插件放置在 /data/system/sideveil/plugins/&lt;id&gt;/
                        目录下，并确保包含 manifest.json。
                    </Card>
                ) : (
                    plugins.map((plugin) => (
                        <PluginCard
                            key={plugin.id}
                            plugin={plugin}
                            isActive={plugin.id === activePluginId}
                            isMatched={matchedIds.includes(plugin.id)}
                            isBusy={busyAction === `plugin-${plugin.id}`}
                            foregroundPackage={fgPkg}
                            onSetEnabled={onSetEnabled}
                            onSetPriority={onSetPriority}
                            onSetScopeOverride={onSetScopeOverride}
                            onSelectPlugin={onSelectPlugin}
                        />
                    ))
                )}
            </div>
        </div>
    );
}
