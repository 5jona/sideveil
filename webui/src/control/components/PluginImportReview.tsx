import { Badge, StatusBanner, Switch } from '@gojona/ui';
import type { PluginArchiveInspection, PluginCapability } from '../../shared/types.ts';
import { CAPABILITY_CATALOG, RISK_TIER_META, RISK_TIER_ORDER } from '../pluginMeta.ts';

export interface PluginImportReviewProps {
  inspection: PluginArchiveInspection;
  grantedCaps: readonly PluginCapability[];
  approveDaemon: boolean;
  onToggleCap: (cap: PluginCapability, checked: boolean) => void;
  onToggleDaemon: (checked: boolean) => void;
}

export function PluginImportReview({
  inspection,
  grantedCaps,
  approveDaemon,
  onToggleCap,
  onToggleDaemon,
}: PluginImportReviewProps) {
  const warnings = inspection.warnings ?? [];

  return (
    <div className="flex flex-col gap-3 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-col gap-0.5">
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-sm font-semibold text-[var(--text-primary)]">
              {inspection.manifest.name}
            </span>
            <Badge variant="accent">v{inspection.manifest.version}</Badge>
            {inspection.isUpgrade ? (
              <Badge variant="warning">从 v{inspection.existingVersion ?? '?'} 升级</Badge>
            ) : (
              <Badge variant="success">全新安装</Badge>
            )}
          </div>
          <span className="font-mono text-[11px] text-[var(--text-secondary)]">
            {inspection.manifest.id}
            {inspection.manifest.author ? ` · ${inspection.manifest.author}` : ''}
          </span>
        </div>
      </div>

      {inspection.manifest.description ? (
        <p className="text-xs text-[var(--text-secondary)]">{inspection.manifest.description}</p>
      ) : null}

      {warnings.length > 0 ? (
        <StatusBanner
          tone="warning"
          title={`安全审查提示 (${warnings.length})`}
          description={warnings.join('；')}
        />
      ) : null}

      {inspection.hasDaemon ? (
        <div className="flex items-center justify-between gap-2 rounded-[var(--radius-control)] border border-[color-mix(in_oklab,var(--danger)_35%,transparent)] bg-[color-mix(in_oklab,var(--danger)_10%,transparent)] px-3 py-2 text-xs">
          <div className="flex flex-col gap-0.5">
            <span className="font-medium text-[var(--text-primary)]">
              包含 uds-native 原生后台进程：安装时立即批准启动？
            </span>
            {inspection.daemonAlreadyApproved ? (
              <span className="text-[11px] text-[var(--text-secondary)]">
                旧版本已批准后台进程，可保持开启或关闭以撤销。
              </span>
            ) : null}
          </div>
          <Switch
            checked={approveDaemon}
            onCheckedChange={onToggleDaemon}
            aria-label="安装时批准原生后台进程"
          />
        </div>
      ) : null}

      {RISK_TIER_ORDER.map((tier) => {
        const items = inspection.capabilities.filter((item) => item.risk === tier);
        if (items.length === 0) return null;
        const tierMeta = RISK_TIER_META[tier];
        return (
          <div key={tier} className="flex flex-col gap-1.5">
            <div className="flex items-center gap-1.5">
              <Badge variant={tierMeta.variant}>{tierMeta.label}</Badge>
              <span className="text-[11px] text-[var(--text-secondary)]">
                {tier === 'low' ? '默认勾选放行' : '需显式勾选授权'}
              </span>
            </div>
            {items.map((item) => {
              const info = CAPABILITY_CATALOG[item.capability];
              const label = info?.label ?? item.capability;
              const description = info?.description ?? '插件声明的扩展能力';
              return (
                <div
                  key={item.capability}
                  className="flex items-center justify-between gap-2 rounded bg-[var(--surface-card)] px-2.5 py-1.5 text-xs"
                >
                  <div className="flex flex-col gap-0.5">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="font-medium text-[var(--text-primary)]">{label}</span>
                      <code className="font-mono text-[10px] text-[var(--text-secondary)]">
                        {item.capability}
                      </code>
                      {inspection.isUpgrade && item.isNew ? (
                        <Badge variant="warning">新增</Badge>
                      ) : null}
                      {inspection.isUpgrade && item.alreadyGranted ? (
                        <Badge variant="success">已授权</Badge>
                      ) : null}
                    </div>
                    <span className="text-[11px] text-[var(--text-secondary)]">{description}</span>
                  </div>
                  <Switch
                    checked={grantedCaps.includes(item.capability)}
                    onCheckedChange={(checked) => onToggleCap(item.capability, checked)}
                    aria-label={`安装授权 ${label}`}
                  />
                </div>
              );
            })}
          </div>
        );
      })}
    </div>
  );
}
