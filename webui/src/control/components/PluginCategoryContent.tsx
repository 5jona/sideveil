import { Button, EmptyState } from '@gojona/ui';
import { Boxes, FolderUp, GlobeLock, Plus, Sparkles } from 'lucide-react';
import type { ReactNode } from 'react';
import type { PluginRuntimeDescriptor } from '../../shared/types.ts';
import type { GroupedPlugins, PluginCategory } from '../pluginMeta.ts';

export interface PluginCategoryContentProps {
  category: PluginCategory;
  grouped: GroupedPlugins;
  renderCard: (plugin: PluginRuntimeDescriptor) => ReactNode;
  onStartCreateEmbed: () => void;
  onStartImport: () => void;
}

export function PluginCategoryContent({
  category,
  grouped,
  renderCard,
  onStartCreateEmbed,
  onStartImport,
}: PluginCategoryContentProps) {
  if (category === 'web-embed') {
    return grouped.webEmbeds.length > 0 ? (
      <div className="flex flex-col gap-2.5">{grouped.webEmbeds.map(renderCard)}</div>
    ) : (
      <EmptyState
        icon={<GlobeLock className="size-5" />}
        title="暂无网页面板"
        description="将 Home Assistant 或任意自定义局域网 / 远程网页仪表盘安全嵌入侧边栏，不授予任何宿主特权。"
        action={
          <Button size="sm" variant="primary" onClick={onStartCreateEmbed}>
            <Plus className="size-3.5" />
            添加网页面板
          </Button>
        }
      />
    );
  }

  if (category === 'installed') {
    return grouped.installed.length > 0 ? (
      <div className="flex flex-col gap-2.5">{grouped.installed.map(renderCard)}</div>
    ) : (
      <EmptyState
        icon={<Boxes className="size-5" />}
        title="尚未安装第三方插件"
        description="点击上方「导入插件 (.svp / 目录)」即可解析清单、审查风险权限并完成安装。"
        action={
          <Button size="sm" variant="primary" onClick={onStartImport}>
            <FolderUp className="size-3.5" />
            导入插件 (.svp / 目录)
          </Button>
        }
      />
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {grouped.systemBuiltins.length > 0 ? (
        <section className="flex flex-col gap-2">
          <div className="flex items-center justify-between px-1">
            <h3 className="text-xs font-semibold text-[var(--text-secondary)]">系统内置</h3>
            <span className="text-[11px] text-[var(--text-secondary)]">随宿主内置，按需启停</span>
          </div>
          {grouped.systemBuiltins.map(renderCard)}
        </section>
      ) : null}
      {grouped.bundledExamples.length > 0 ? (
        <section className="flex flex-col gap-2">
          <div className="flex items-center justify-between px-1">
            <h3 className="text-xs font-semibold text-[var(--text-secondary)]">官方示例</h3>
            <span className="text-[11px] text-[var(--text-secondary)]">默认停用，按需启用体验</span>
          </div>
          {grouped.bundledExamples.map(renderCard)}
        </section>
      ) : null}
      {grouped.builtins.length === 0 ? (
        <EmptyState
          icon={<Sparkles className="size-5" />}
          title="暂无内置组件"
          description="当前守护进程未返回内置或示例组件。"
        />
      ) : null}
    </div>
  );
}
