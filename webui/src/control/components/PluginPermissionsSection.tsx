import { Badge, Button, SettingRow, StatusBanner, Switch } from '@gojona/ui';
import { ShieldAlert, ShieldCheck, ShieldOff } from 'lucide-react';
import type { PluginCapability, PluginRuntimeDescriptor } from '../../shared/types.ts';
import type { PluginGrantCapabilitiesInput } from '../hooks/usePluginsControl.ts';
import {
  CAPABILITY_CATALOG,
  hasRevocableGrants,
  RISK_TIER_META,
  RISK_TIER_ORDER,
} from '../pluginMeta.ts';

export interface PluginPermissionsSectionProps {
  plugin: PluginRuntimeDescriptor;
  isBusy: boolean;
  onGrantCapabilities: (input: PluginGrantCapabilitiesInput) => Promise<boolean>;
}

export function PluginPermissionsSection({
  plugin,
  isBusy,
  onGrantCapabilities,
}: PluginPermissionsSectionProps) {
  const grantedSet = new Set(plugin.grantedCapabilities);
  const pendingSet = new Set(plugin.pendingCapabilities);
  const canRevokeAll = hasRevocableGrants(plugin);

  const toggleCapability = (cap: PluginCapability, enabled: boolean) => {
    const next = enabled
      ? Array.from(new Set([...plugin.grantedCapabilities, cap]))
      : plugin.grantedCapabilities.filter((item) => item !== cap);
    void onGrantCapabilities({ pluginId: plugin.id, capabilities: next });
  };

  if (plugin.capabilities.length === 0 && !plugin.hasDaemon) {
    return (
      <div className="rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-3 text-xs text-[var(--text-secondary)]">
        该插件未申请任何特权能力，仅在隔离 WebView 沙箱内运行。
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {plugin.pendingCapabilities.length > 0 ? (
        <StatusBanner
          tone="warning"
          title={`有 ${plugin.pendingCapabilities.length} 项权限等待审查`}
          description="未授权的能力调用会被宿主拦截（-32003）。可逐项开关，或一次性批准全部待审权限。"
          action={
            <Button
              size="sm"
              variant="primary"
              aria-label={`批准 ${plugin.name} 全部待审权限`}
              disabled={isBusy}
              onClick={() =>
                void onGrantCapabilities({ pluginId: plugin.id, grantAllPending: true })
              }
            >
              <ShieldCheck className="size-3.5" />
              批准待审权限
            </Button>
          }
        />
      ) : null}

      {plugin.hasDaemon && !plugin.daemonApproved ? (
        <StatusBanner
          tone="danger"
          title="后台原生进程等待批准"
          description="此插件声明了 uds-native 原生后台进程。批准前宿主不会自动拉起该进程。"
          action={
            <Button
              size="sm"
              variant="primary"
              aria-label={`批准 ${plugin.name} 后台进程运行`}
              disabled={isBusy}
              onClick={() => void onGrantCapabilities({ pluginId: plugin.id, approveDaemon: true })}
            >
              <ShieldAlert className="size-3.5" />
              批准后台进程
            </Button>
          }
        />
      ) : null}

      {RISK_TIER_ORDER.map((tier) => {
        const caps = plugin.capabilities.filter((cap) => CAPABILITY_CATALOG[cap]?.tier === tier);
        if (caps.length === 0) return null;
        const meta = RISK_TIER_META[tier];
        return (
          <div
            key={tier}
            className="flex flex-col gap-2 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-3"
          >
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="flex items-center gap-1.5">
                <Badge variant={meta.variant}>{meta.label}</Badge>
                <span className="text-[11px] text-[var(--text-secondary)]">{meta.summary}</span>
              </div>
            </div>

            <div className="flex flex-col gap-2">
              {caps.map((cap) => {
                const info = CAPABILITY_CATALOG[cap];
                const isGranted = grantedSet.has(cap);
                const isPending = pendingSet.has(cap);
                return (
                  <SettingRow
                    key={cap}
                    variant="plain"
                    title={`${info.label} (${cap})${isPending ? ' · 待审' : ''}`}
                    description={info.description}
                    control={
                      <Switch
                        checked={isGranted}
                        disabled={isBusy || plugin.builtin}
                        aria-label={`授予 ${plugin.name} ${info.label} 权限`}
                        onCheckedChange={(checked) => toggleCapability(cap, checked)}
                      />
                    }
                  />
                );
              })}
            </div>
          </div>
        );
      })}

      {canRevokeAll && !plugin.builtin ? (
        <div className="flex justify-end">
          <Button
            size="sm"
            variant="danger"
            aria-label={`撤销 ${plugin.name} 全部高危/中危权限`}
            disabled={isBusy}
            onClick={() => void onGrantCapabilities({ pluginId: plugin.id, revokeAll: true })}
          >
            <ShieldOff className="size-3.5" />
            撤销全部高危/中危权限
          </Button>
        </div>
      ) : null}
    </div>
  );
}
