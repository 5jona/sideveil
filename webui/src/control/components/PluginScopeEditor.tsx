import { Button, Input } from '@pochita/ui';
import { Plus, RotateCcw, X } from 'lucide-react';
import { useState } from 'react';
import type { PluginScopeOverrideInput } from '../hooks/usePluginsControl.ts';

export interface PluginScopeEditorProps {
    pluginId: string;
    pluginName: string;
    initialGlobal: boolean;
    initialPackages: readonly string[];
    hasScopeOverride: boolean;
    foregroundPackage: string;
    isBusy: boolean;
    onSave: (pluginId: string, override: PluginScopeOverrideInput) => Promise<void>;
    onCancel: () => void;
}

export function PluginScopeEditor({
    pluginId,
    pluginName,
    initialGlobal,
    initialPackages,
    hasScopeOverride,
    foregroundPackage,
    isBusy,
    onSave,
    onCancel,
}: PluginScopeEditorProps) {
    const [draftGlobal, setDraftGlobal] = useState(initialGlobal);
    const [draftPackages, setDraftPackages] = useState<string[]>([...initialPackages]);
    const [pkgInput, setPkgInput] = useState('');

    const addDraftPackage = (raw: string) => {
        const pkg = raw.trim();
        if (!pkg) return;
        setDraftPackages((prev) => (prev.includes(pkg) ? prev : [...prev, pkg]));
        setPkgInput('');
    };

    const canQuickAddFg =
        Boolean(foregroundPackage) && !draftGlobal && !draftPackages.includes(foregroundPackage);

    const handleSave = async () => {
        await onSave(pluginId, {
            global: draftGlobal,
            packages: draftGlobal ? [] : draftPackages,
        });
        onCancel();
    };

    const handleClear = async () => {
        await onSave(pluginId, { clear: true });
        onCancel();
    };

    return (
        <div className="pochita-card-elevated space-y-2.5 p-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-xs font-medium text-[var(--text-primary)]">自定义触发作用域</span>
                <div className="flex items-center gap-1.5">
                    <Button
                        size="sm"
                        variant={!draftGlobal ? 'primary' : 'secondary'}
                        onClick={() => setDraftGlobal(false)}
                    >
                        指定包名
                    </Button>
                    <Button
                        size="sm"
                        variant={draftGlobal ? 'primary' : 'secondary'}
                        onClick={() => setDraftGlobal(true)}
                    >
                        全局匹配
                    </Button>
                </div>
            </div>

            {!draftGlobal ? (
                <>
                    <div className="flex flex-wrap gap-1.5">
                        {draftPackages.length === 0 ? (
                            <span className="text-[11px] text-[var(--text-secondary)]">
                                尚未添加包名（将不匹配任何应用）
                            </span>
                        ) : (
                            draftPackages.map((pkg) => (
                                <span
                                    key={pkg}
                                    className="inline-flex items-center gap-1 rounded-[var(--radius-pill)] border border-[var(--border-hairline)] bg-[var(--surface-card)] px-2 py-0.5 text-[11px] text-[var(--text-primary)]"
                                >
                                    <span className="font-mono">{pkg}</span>
                                    <button
                                        type="button"
                                        aria-label={`移除插件包名 ${pkg}`}
                                        onClick={() => setDraftPackages((prev) => prev.filter((item) => item !== pkg))}
                                        className="cursor-pointer rounded-full p-0.5 text-[var(--text-secondary)] hover:text-[var(--danger)]"
                                    >
                                        <X className="size-3" />
                                    </button>
                                </span>
                            ))
                        )}
                    </div>

                    <form
                        className="flex flex-wrap items-center gap-2"
                        onSubmit={(ev) => {
                            ev.preventDefault();
                            addDraftPackage(pkgInput);
                        }}
                    >
                        <Input
                            size="sm"
                            className="flex-1"
                            value={pkgInput}
                            onChange={(ev) => setPkgInput(ev.target.value)}
                            placeholder="输入目标包名，例如 com.example.game"
                            aria-label={`新增 ${pluginName} 触发包名`}
                        />
                        <Button size="sm" type="submit" variant="secondary" disabled={!pkgInput.trim()}>
                            <Plus className="size-3.5" />
                            添加
                        </Button>
                        {canQuickAddFg ? (
                            <Button
                                size="sm"
                                variant="pill"
                                type="button"
                                onClick={() => addDraftPackage(foregroundPackage)}
                            >
                                <Plus className="size-3.5" />
                                当前前台
                            </Button>
                        ) : null}
                    </form>
                </>
            ) : null}

            <div className="flex flex-wrap items-center justify-end gap-2 pt-1">
                {hasScopeOverride ? (
                    <Button size="sm" variant="ghost" disabled={isBusy} onClick={() => void handleClear()}>
                        <RotateCcw className="size-3.5" />
                        恢复 Manifest 默认
                    </Button>
                ) : null}
                <Button size="sm" variant="secondary" onClick={onCancel}>
                    取消
                </Button>
                <Button size="sm" variant="primary" disabled={isBusy} onClick={() => void handleSave()}>
                    保存覆盖
                </Button>
            </div>
        </div>
    );
}
