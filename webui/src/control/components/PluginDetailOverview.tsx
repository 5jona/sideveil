import { Button } from '@gojona/ui';
import { Play, RefreshCw } from 'lucide-react';
import type { PluginRuntimeDescriptor } from '../../shared/types.ts';
import type { PluginGrantCapabilitiesInput } from '../hooks/usePluginsControl.ts';
import { PluginPermissionsSection } from './PluginPermissionsSection.tsx';

export interface PluginDetailOverviewProps {
  plugin: PluginRuntimeDescriptor;
  isActive: boolean;
  isMatched: boolean;
  isBusy: boolean;
  onSelectPlugin: (pluginId: string) => Promise<void>;
  onReloadWebView: (pluginId: string) => Promise<boolean>;
  onGrantCapabilities: (input: PluginGrantCapabilitiesInput) => Promise<boolean>;
}

export function PluginDetailOverview({
  plugin,
  isActive,
  isMatched,
  isBusy,
  onSelectPlugin,
  onReloadWebView,
  onGrantCapabilities,
}: PluginDetailOverviewProps) {
  return (
    <div className="flex flex-col gap-3">
      {plugin.description ? (
        <p className="text-xs leading-relaxed text-[var(--text-secondary)]">{plugin.description}</p>
      ) : null}
      <div className="flex flex-wrap items-center justify-between gap-2 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-3 py-2 text-xs">
        <span className="text-[var(--text-secondary)]">
          API v{plugin.apiVersion} · 底板 {plugin.surface.backdrop}
          {plugin.surface.widthDp ? ` · ${plugin.surface.widthDp}dp` : ''}
        </span>
        <div className="flex flex-wrap items-center gap-1.5">
          {plugin.enabled && isMatched && !isActive ? (
            <Button
              size="sm"
              variant="secondary"
              aria-label={`切换到 ${plugin.name}`}
              disabled={isBusy}
              onClick={() => void onSelectPlugin(plugin.id)}
            >
              <Play className="size-3.5" />
              切换至此
            </Button>
          ) : null}
          <Button
            size="sm"
            variant="secondary"
            aria-label={`重载 ${plugin.name} WebView`}
            disabled={isBusy}
            onClick={() => void onReloadWebView(plugin.id)}
          >
            <RefreshCw className="size-3.5" />
            重载 WebView
          </Button>
        </div>
      </div>
      <PluginPermissionsSection
        plugin={plugin}
        isBusy={isBusy}
        onGrantCapabilities={onGrantCapabilities}
      />
    </div>
  );
}
