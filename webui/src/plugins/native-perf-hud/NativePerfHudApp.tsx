import type { BridgeOptions } from '@gojona/bridge';
import {
  SideVeilPluginProvider,
  useContainerAction,
  useHaptics,
  usePluginBackendState,
  usePluginRpc,
  useTelemetry,
} from '@gojona/sideveil-sdk';
import {
  AppShell,
  Badge,
  Button,
  Card,
  cn,
  ErrorBoundary,
  MetricTile,
  SettingRow,
  Switch,
  ThemeProvider,
} from '@gojona/ui';
import { Cpu, Gauge, Thermometer, X } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSyncedPluginTheme } from '../../shared/useSyncedPluginTheme.ts';
import { NATIVE_PERF_HUD_MANIFEST, NATIVE_PERF_HUD_PLUGIN_ID } from './manifest.ts';
import { createMockPerfHudHandlers, type PerfHudBackendState } from './mockPerfHudBridge.ts';

function NativePerfHudWorkbench() {
  const { isNativeHost, isDark } = useSyncedPluginTheme();
  const { dismiss } = useContainerAction();
  const backendState = usePluginBackendState();
  const { trigger: triggerHaptic } = useHaptics();
  const { snapshot } = useTelemetry(1000);

  const { invoke: invokeGetState } = usePluginRpc<undefined, PerfHudBackendState>('getState');
  const { invoke: invokeSetEnabled } = usePluginRpc<{ enabled: boolean }, PerfHudBackendState>(
    'setEnabled',
  );
  const { invoke: invokeSetClickThrough } = usePluginRpc<
    { clickThrough: boolean },
    PerfHudBackendState
  >('setClickThrough');
  const { invoke: invokeSetAppearance } = usePluginRpc<{ isDark: boolean }, PerfHudBackendState>(
    'setAppearance',
  );

  const [hudEnabled, setHudEnabled] = useState(false);
  const [clickThrough, setClickThrough] = useState(false);

  const applyBackendState = useCallback((res: PerfHudBackendState | null | undefined) => {
    if (!res || typeof res !== 'object') return;
    if (typeof res.enabled === 'boolean') setHudEnabled(res.enabled);
    if (typeof res.clickThrough === 'boolean') setClickThrough(res.clickThrough);
  }, []);

  useEffect(() => {
    if (isNativeHost && backendState === 'offline') return;
    let active = true;
    const syncState = async () => {
      try {
        const res = await invokeGetState();
        if (active) applyBackendState(res);
      } catch {
        // Backend daemon may still be connecting; state will refresh on next toggle or reconnect.
      }
    };
    void syncState();
    return () => {
      active = false;
    };
  }, [applyBackendState, backendState, invokeGetState, isNativeHost]);

  useEffect(() => {
    if (isNativeHost && backendState === 'offline') return;
    void invokeSetAppearance({ isDark }).catch(() => {});
  }, [backendState, invokeSetAppearance, isDark, isNativeHost]);

  const handleToggleHud = useCallback(
    (checked: boolean) => {
      setHudEnabled(checked);
      void triggerHaptic('click');
      void invokeSetEnabled({ enabled: checked })
        .then(applyBackendState)
        .catch(() => {});
    },
    [applyBackendState, invokeSetEnabled, triggerHaptic],
  );

  const handleToggleClickThrough = useCallback(
    (checked: boolean) => {
      setClickThrough(checked);
      void triggerHaptic('tick');
      void invokeSetClickThrough({ clickThrough: checked })
        .then(applyBackendState)
        .catch(() => {});
    },
    [applyBackendState, invokeSetClickThrough, triggerHaptic],
  );

  const backendOnline = !isNativeHost || backendState === 'online';
  const hudStatusText = !hudEnabled
    ? 'HUD 待命'
    : clickThrough
      ? '运行中 · 点击穿透'
      : '运行中 · 可拖动';
  const memText =
    snapshot && snapshot.memTotalMb > 0
      ? `内存 ${(snapshot.memUsedMb / 1024).toFixed(1)} / ${(snapshot.memTotalMb / 1024).toFixed(0)} GB`
      : '等待内核采样';

  return (
    <div
      className={cn(
        'flex w-full flex-col',
        isNativeHost ? 'h-dvh overflow-hidden' : 'mx-auto min-h-dvh max-w-[340px] p-2',
      )}
    >
      <AppShell
        variant="drawer"
        title="性能监控"
        className={cn(
          'sideveil-drawer-panel',
          isNativeHost
            ? 'h-full min-h-0 flex-1'
            : 'min-h-[calc(100dvh-1rem)] flex-1 rounded-[var(--radius-panel)]',
        )}
        headerLeading={
          <div className="flex size-7 items-center justify-center rounded-[var(--radius-control)] bg-[var(--accent-soft)] text-[var(--accent)]">
            <Gauge className="size-3.5" />
          </div>
        }
        headerTrailing={
          <div className="flex items-center gap-1.5">
            <Badge variant={backendOnline ? 'success' : 'neutral'} dot>
              {!isNativeHost ? '预览' : backendState === 'online' ? '后端就绪' : '后端离线'}
            </Badge>
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label="关闭性能监控"
              onClick={() => void dismiss()}
            >
              <X className="size-4" />
            </Button>
          </div>
        }
      >
        <div className="flex flex-col gap-2.5">
          <div className="sideveil-compact-metrics grid grid-cols-2 gap-2">
            <MetricTile
              label="CPU"
              value={snapshot ? snapshot.cpuPercent.toFixed(1) : '--'}
              unit={snapshot ? '%' : undefined}
              hint={memText}
              statusLabel={hudEnabled ? '已投射' : '待命'}
              statusVariant={hudEnabled ? 'success' : 'neutral'}
              icon={<Cpu className="size-3.5" />}
            />
            <MetricTile
              label="SoC"
              value={snapshot?.socTempC != null ? snapshot.socTempC.toFixed(0) : '--'}
              unit={snapshot?.socTempC != null ? '°C' : undefined}
              hint={hudStatusText}
              statusLabel={clickThrough ? '穿透' : '交互'}
              statusVariant={clickThrough ? 'accent' : 'neutral'}
              icon={<Thermometer className="size-3.5" />}
            />
          </div>

          <Card className="flex flex-col gap-2.5 p-3">
            <SettingRow
              variant="plain"
              title="HUD 悬浮窗"
              description="零 WebView 原生浮窗，支持拖拽"
              control={
                <Switch
                  checked={hudEnabled}
                  onCheckedChange={handleToggleHud}
                  aria-label="HUD 悬浮窗"
                />
              }
            />
            <div className="pochita-divider" />
            <SettingRow
              variant="plain"
              title="点击穿透"
              description="开启后触摸事件直达底层应用"
              control={
                <Switch
                  checked={clickThrough}
                  onCheckedChange={handleToggleClickThrough}
                  aria-label="点击穿透"
                />
              }
            />
          </Card>

          <Card className="p-3">
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs font-medium text-[var(--text-primary)]">原生渲染管线</span>
              <Badge variant={hudEnabled ? 'success' : 'neutral'}>{hudStatusText}</Badge>
            </div>
            <p className="mt-1.5 text-pretty text-[11px] leading-relaxed text-[var(--text-secondary)]">
              Rust 后端直连 hub.sock 采样 CPU / RAM / 温度，通过 libsideveil_hud.so
              在原生跨窗模糊之上渲染矢量曲线与等宽数字，并自动跟随系统明暗主题配色。
            </p>
          </Card>
        </div>
      </AppShell>
    </div>
  );
}

export function NativePerfHudApp() {
  const bridgeOptions = useMemo<BridgeOptions>(() => {
    const mocks = createMockPerfHudHandlers();
    return {
      allowUrlOverride: false,
      allowedOrigins: [`https://${NATIVE_PERF_HUD_PLUGIN_ID}.plugins.sideveil.local`],
      mockNamespace: 'sideveil-native-perf-hud',
      onMockRpc: mocks.onMockRpc,
      onMockPluginInvoke: mocks.onMockPluginInvoke,
    };
  }, []);

  return (
    <SideVeilPluginProvider
      pluginId={NATIVE_PERF_HUD_PLUGIN_ID}
      manifest={NATIVE_PERF_HUD_MANIFEST}
      bridgeOptions={bridgeOptions}
    >
      <ThemeProvider
        defaultTheme="coloros-glass"
        defaultColorMode="system"
        defaultSurfaceMode="native-glass"
        syncEnabled={false}
      >
        <ErrorBoundary title="性能监控渲染异常">
          <NativePerfHudWorkbench />
        </ErrorBoundary>
      </ThemeProvider>
    </SideVeilPluginProvider>
  );
}
