import type { NetworkContextSnapshot, NetworkTransportKind } from '@gojona/sideveil-sdk';
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@gojona/ui';
import { Cable, Radio, RefreshCw, Router, Shield, Wifi, WifiOff } from 'lucide-react';

export interface NetworkContextCardProps {
  snapshot: NetworkContextSnapshot | null;
  loading: boolean;
  error: string | null;
  onRefresh: () => void;
}

function getTransportLabel(transport: NetworkTransportKind): string {
  switch (transport) {
    case 'wifi':
      return 'Wi-Fi';
    case 'cellular':
      return '蜂窝网络';
    case 'ethernet':
      return '以太网';
    case 'other':
      return '其他网络';
    default:
      return '无网络';
  }
}

function TransportIcon({ transport }: { transport: NetworkTransportKind }) {
  switch (transport) {
    case 'wifi':
      return <Wifi className="size-4 text-[var(--accent)]" />;
    case 'cellular':
      return <Radio className="size-4 text-[var(--warning)]" />;
    case 'ethernet':
      return <Cable className="size-4 text-[var(--success)]" />;
    case 'none':
      return <WifiOff className="size-4 text-[var(--danger)]" />;
    default:
      return <Router className="size-4 text-[var(--accent)]" />;
  }
}

export function NetworkContextCard({
  snapshot,
  loading,
  error,
  onRefresh,
}: NetworkContextCardProps) {
  const transport = snapshot?.transport ?? 'none';

  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="flex flex-col gap-0.5">
            <CardTitle className="flex items-center gap-1.5">
              <TransportIcon transport={transport} />
              实时网络上下文（:sensor）
            </CardTitle>
            <CardDescription>
              检测当前传输方式、家庭路由器 Wi-Fi 与蜂窝网络切换、网关及本机 IPv4
            </CardDescription>
          </div>
          <Button
            size="sm"
            variant="secondary"
            disabled={loading}
            aria-label="刷新网络状态"
            onClick={onRefresh}
          >
            <RefreshCw className={`size-3.5 ${loading ? 'animate-spin' : ''}`} />
            {loading ? '刷新中...' : '刷新状态'}
          </Button>
        </div>
      </CardHeader>

      <CardContent className="flex flex-col gap-3">
        {error ? (
          <div className="rounded-[var(--radius-control)] border border-[color-mix(in_oklab,var(--warning)_35%,transparent)] bg-[color-mix(in_oklab,var(--warning)_12%,transparent)] px-3 py-2 text-xs text-[var(--text-primary)]">
            网络状态获取失败：{error}
          </div>
        ) : null}

        <div className="flex flex-wrap items-center gap-2">
          <Badge variant={transport === 'none' ? 'danger' : 'accent'} dot>
            传输方式：{getTransportLabel(transport)}
          </Badge>
          <Badge variant={snapshot?.vpnActive ? 'warning' : 'neutral'}>
            <Shield className="size-3" />
            {snapshot?.vpnActive ? 'VPN 已开启' : '未启用 VPN'}
          </Badge>
          <Badge variant={snapshot?.metered ? 'warning' : 'success'}>
            {snapshot?.metered ? '计费网络' : '非计费网络'}
          </Badge>
          <Badge variant={snapshot?.validated ? 'success' : 'neutral'}>
            {snapshot?.validated ? '连通性已验证' : '未验证'}
          </Badge>
        </div>

        <div className="grid grid-cols-1 gap-2 text-xs sm:grid-cols-2">
          <div className="flex items-center justify-between gap-2 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-3 py-2">
            <span className="text-[var(--text-secondary)]">SSID</span>
            <code className="font-mono text-[var(--text-primary)]">
              {snapshot?.ssid || '未获取 / 非 Wi-Fi'}
            </code>
          </div>
          <div className="flex items-center justify-between gap-2 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-3 py-2">
            <span className="text-[var(--text-secondary)]">接口名称</span>
            <code className="font-mono text-[var(--text-primary)]">
              {snapshot?.interfaceName || '—'}
            </code>
          </div>
          <div className="flex items-center justify-between gap-2 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-3 py-2">
            <span className="text-[var(--text-secondary)]">默认网关</span>
            <code className="font-mono text-[var(--text-primary)]">{snapshot?.gateway || '—'}</code>
          </div>
          <div className="flex items-center justify-between gap-2 rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)] px-3 py-2">
            <span className="text-[var(--text-secondary)]">本机 IPv4</span>
            <code className="font-mono text-[var(--text-primary)]">
              {snapshot?.localIpv4 || '—'}
            </code>
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
