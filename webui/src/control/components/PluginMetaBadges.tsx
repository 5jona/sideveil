import type { PluginBackendKind, PluginBackendState, PluginCapability } from '@gojona/sideveil-sdk';
import { Badge } from '@gojona/ui';
import {
  Activity,
  Bell,
  Clipboard,
  FolderLock,
  Gauge,
  GlobeLock,
  Network,
  Pin,
  Radio,
  Router,
  ShieldCheck,
  Terminal,
  Vibrate,
  Wifi,
} from 'lucide-react';
import type { ReactNode } from 'react';

export interface PluginTrustBadgesProps {
  isEmbed: boolean;
  keepAlive: boolean;
}

export function PluginTrustBadges({ isEmbed, keepAlive }: PluginTrustBadgesProps) {
  return (
    <>
      <Badge variant={isEmbed ? 'warning' : 'success'}>
        {isEmbed ? (
          <>
            <GlobeLock className="size-3" />
            隔离网页嵌入
          </>
        ) : (
          <>
            <ShieldCheck className="size-3" />
            受信任本地插件
          </>
        )}
      </Badge>
      {keepAlive ? (
        <Badge variant="accent">
          <Pin className="size-3" />
          保活
        </Badge>
      ) : null}
    </>
  );
}

export interface PluginBackendBadgeProps {
  kind: PluginBackendKind;
  state: PluginBackendState;
}

export function PluginBackendBadge({ kind, state }: PluginBackendBadgeProps) {
  if (kind === 'none') return null;
  const isOnline = state === 'online';
  const label = kind === 'uds-native' ? 'Native 后端' : 'App 后端';
  return (
    <Badge variant={isOnline ? 'success' : 'neutral'} dot={isOnline}>
      {label} ({isOnline ? '就绪' : '未连接'})
    </Badge>
  );
}

interface CapabilityMeta {
  label: string;
  icon: ReactNode;
}

function getCapabilityMeta(cap: PluginCapability): CapabilityMeta | null {
  switch (cap) {
    case 'storage':
      return { label: '私有存储', icon: <FolderLock className="size-3 text-[var(--accent)]" /> };
    case 'toast':
      return { label: 'Toast', icon: <Bell className="size-3 text-[var(--warning)]" /> };
    case 'system-exec':
      return { label: 'Root 执行', icon: <Terminal className="size-3 text-[var(--danger)]" /> };
    case 'native-hud':
      return { label: '原生 HUD', icon: <Gauge className="size-3 text-[var(--success)]" /> };
    case 'clipboard':
      return { label: '剪贴板', icon: <Clipboard className="size-3 text-[var(--accent)]" /> };
    case 'haptics':
      return { label: '触觉反馈', icon: <Vibrate className="size-3 text-[var(--accent)]" /> };
    case 'telemetry':
      return { label: '系统遥测', icon: <Activity className="size-3 text-[var(--success)]" /> };
    case 'inter-plugin-bus':
      return { label: '插件总线', icon: <Radio className="size-3 text-[var(--warning)]" /> };
    case 'network-loopback':
      return { label: '回环网络', icon: <Network className="size-3 text-[var(--accent)]" /> };
    case 'network-lan':
      return { label: '局域网访问', icon: <Wifi className="size-3 text-[var(--warning)]" /> };
    case 'network-context':
      return { label: '网络状态感知', icon: <Router className="size-3 text-[var(--success)]" /> };
    default:
      return null;
  }
}

export interface PluginCapabilitiesRowProps {
  capabilities: readonly PluginCapability[];
}

export function PluginCapabilitiesRow({ capabilities }: PluginCapabilitiesRowProps) {
  if (capabilities.length === 0) return null;
  return (
    <div className="flex flex-wrap items-center gap-1.5 pt-1">
      <span className="text-[11px] font-medium text-[var(--text-secondary)]">权限：</span>
      {capabilities.map((cap) => {
        const meta = getCapabilityMeta(cap);
        if (!meta) return null;
        return (
          <span
            key={cap}
            className="inline-flex items-center gap-1 rounded bg-[var(--surface-elevated)] px-1.5 py-0.5 text-[11px] text-[var(--text-secondary)]"
          >
            {meta.icon}
            {meta.label}
          </span>
        );
      })}
    </div>
  );
}
