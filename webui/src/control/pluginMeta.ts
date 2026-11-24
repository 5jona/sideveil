import type {
  CapabilityRiskTier,
  PluginApprovalStatus,
  PluginArchiveInspection,
  PluginCapability,
  PluginDaemonStatus,
  PluginOrigin,
  PluginRuntimeDescriptor,
} from '../shared/types.ts';

export type PluginCategory = 'web-embed' | 'installed' | 'builtin';

export interface CapabilityDescriptor {
  id: PluginCapability;
  label: string;
  description: string;
  tier: CapabilityRiskTier;
}

export const CAPABILITY_CATALOG: Record<PluginCapability, CapabilityDescriptor> = {
  'system-exec': {
    id: 'system-exec',
    label: 'Root 执行',
    description: '允许以 Root 权限执行系统命令，请仅授予完全信任的插件',
    tier: 'high',
  },
  storage: {
    id: 'storage',
    label: '私有存储',
    description: '读写隔离目录 /data/system/sideveil/data/<id>/ 内的插件数据',
    tier: 'medium',
  },
  clipboard: {
    id: 'clipboard',
    label: '剪贴板读写',
    description: '读取或写入系统剪贴板文本内容',
    tier: 'medium',
  },
  'native-hud': {
    id: 'native-hud',
    label: '原生 HUD 浮窗',
    description: '在屏幕上投射零 WebView 原生矢量信息浮窗',
    tier: 'medium',
  },
  'inter-plugin-bus': {
    id: 'inter-plugin-bus',
    label: '跨插件总线',
    description: '与其他已授权插件收发事件消息',
    tier: 'medium',
  },
  'network-loopback': {
    id: 'network-loopback',
    label: '本机回环网络',
    description: '访问 127.0.0.1 / localhost 本地服务接口',
    tier: 'medium',
  },
  'network-lan': {
    id: 'network-lan',
    label: '局域网访问',
    description: '访问 RFC1918 私网与 .local 局域网服务',
    tier: 'medium',
  },
  'network-internet': {
    id: 'network-internet',
    label: '公网访问',
    description: '允许连接清单中声明的公网 HTTPS 源',
    tier: 'medium',
  },
  'network-context': {
    id: 'network-context',
    label: '网络状态感知',
    description: '读取当前网络类型、Wi-Fi SSID 与 VPN 状态',
    tier: 'medium',
  },
  toast: {
    id: 'toast',
    label: '系统提示',
    description: '显示简短的 Android Toast 提示信息',
    tier: 'low',
  },
  haptics: {
    id: 'haptics',
    label: '触觉反馈',
    description: '触发细腻的线性马达振动反馈',
    tier: 'low',
  },
  telemetry: {
    id: 'telemetry',
    label: '系统遥测',
    description: '读取只读 CPU、内存与 SoC 温度快照',
    tier: 'low',
  },
};

export const RISK_TIER_ORDER: readonly CapabilityRiskTier[] = ['high', 'medium', 'low'];

export const RISK_TIER_META: Record<
  CapabilityRiskTier,
  { label: string; variant: 'danger' | 'warning' | 'neutral'; summary: string }
> = {
  high: {
    label: '高风险',
    variant: 'danger',
    summary: '涉及 Root 命令执行，默认拦截，必须由用户显式批准',
  },
  medium: {
    label: '中风险',
    variant: 'warning',
    summary: '涉及数据存储、网络访问、剪贴板或浮窗投射，需用户确认',
  },
  low: {
    label: '低风险',
    variant: 'neutral',
    summary: '基础交互与只读硬件指标，安装时可自动放行',
  },
};

export function classifyPluginCategory(plugin: PluginRuntimeDescriptor): PluginCategory {
  if (plugin.origin === 'web-embed' || plugin.embed) return 'web-embed';
  if (plugin.origin === 'builtin' || plugin.origin === 'bundled' || plugin.builtin)
    return 'builtin';
  return 'installed';
}

export interface GroupedPlugins {
  webEmbeds: PluginRuntimeDescriptor[];
  installed: PluginRuntimeDescriptor[];
  builtins: PluginRuntimeDescriptor[];
  systemBuiltins: PluginRuntimeDescriptor[];
  bundledExamples: PluginRuntimeDescriptor[];
}

export function groupPluginsByCategory(
  plugins: readonly PluginRuntimeDescriptor[],
): GroupedPlugins {
  const webEmbeds: PluginRuntimeDescriptor[] = [];
  const installed: PluginRuntimeDescriptor[] = [];
  const builtins: PluginRuntimeDescriptor[] = [];
  const systemBuiltins: PluginRuntimeDescriptor[] = [];
  const bundledExamples: PluginRuntimeDescriptor[] = [];

  for (const plugin of plugins) {
    const category = classifyPluginCategory(plugin);
    if (category === 'web-embed') {
      webEmbeds.push(plugin);
    } else if (category === 'installed') {
      installed.push(plugin);
    } else {
      builtins.push(plugin);
      if (plugin.origin === 'builtin' || plugin.builtin) {
        systemBuiltins.push(plugin);
      } else {
        bundledExamples.push(plugin);
      }
    }
  }
  return { webEmbeds, installed, builtins, systemBuiltins, bundledExamples };
}

export function formatOriginLabel(origin: PluginOrigin): string {
  switch (origin) {
    case 'builtin':
      return '系统内置';
    case 'bundled':
      return '官方示例';
    case 'web-embed':
      return '网页面板';
    default:
      return '已安装';
  }
}

export function needsPermissionAttention(plugin: PluginRuntimeDescriptor): boolean {
  return (
    plugin.approvalStatus !== 'approved' ||
    plugin.pendingCapabilities.length > 0 ||
    (plugin.hasDaemon && !plugin.daemonApproved)
  );
}

export function getApprovalBadgeMeta(status: PluginApprovalStatus): {
  label: string;
  variant: 'success' | 'warning' | 'danger';
} {
  switch (status) {
    case 'pending-review':
      return { label: '待审权限', variant: 'warning' };
    case 'needs-reapproval':
      return { label: '权限已变更', variant: 'danger' };
    default:
      return { label: '已批准', variant: 'success' };
  }
}

export function getDaemonBadgeMeta(status: PluginDaemonStatus): {
  label: string;
  variant: 'success' | 'warning' | 'danger' | 'neutral';
} | null {
  switch (status) {
    case 'not-configured':
      return null;
    case 'awaiting-approval':
      return { label: '后台待批准', variant: 'warning' };
    case 'running':
      return { label: '后台运行中', variant: 'success' };
    case 'crashed':
      return { label: '后台异常', variant: 'danger' };
    case 'circuit-open':
      return { label: '后台熔断', variant: 'danger' };
    default:
      return { label: '后台已停止', variant: 'neutral' };
  }
}

/** Built-in and read-only module-bundled plugins cannot be uninstalled via Hub. */
export function canUninstallPlugin(
  plugin: Pick<PluginRuntimeDescriptor, 'builtin' | 'origin'>,
): boolean {
  return !plugin.builtin && plugin.origin !== 'bundled';
}

/** Returns true when any medium/high capability is granted or a native daemon is approved. */
export function hasRevocableGrants(
  plugin: Pick<PluginRuntimeDescriptor, 'grantedCapabilities' | 'hasDaemon' | 'daemonApproved'>,
): boolean {
  return (
    plugin.grantedCapabilities.some((cap) => {
      const tier = CAPABILITY_CATALOG[cap]?.tier;
      return tier === 'high' || tier === 'medium';
    }) ||
    (plugin.hasDaemon && plugin.daemonApproved)
  );
}

/**
 * Computes initial capability checkboxes and daemon approval state from an archive inspection:
 * low-risk and already-granted capabilities start checked; newly requested medium/high start unchecked.
 */
export function deriveInitialImportGrants(inspection: PluginArchiveInspection): {
  grantedCapabilities: PluginCapability[];
  approveDaemon: boolean;
} {
  return {
    grantedCapabilities: inspection.capabilities
      .filter((item) => !item.requiresApproval || item.alreadyGranted)
      .map((item) => item.capability),
    approveDaemon: Boolean(inspection.daemonAlreadyApproved),
  };
}
