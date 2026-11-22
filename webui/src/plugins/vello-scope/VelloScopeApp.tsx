import type { BridgeOptions, ContainerBackdrop } from '@pochita/bridge';
import {
  SideVeilPluginProvider,
  useContainerAction,
  useHaptics,
  usePanelVisibility,
  usePresentationMode,
  useTelemetry,
} from '@pochita/sideveil-sdk';
import {
  AppShell,
  Badge,
  Button,
  Card,
  cn,
  MetricTile,
  SettingRow,
  Switch,
  ThemeProvider,
} from '@pochita/ui';
import {
  Activity,
  Check,
  Cpu,
  Layers,
  PanelRight,
  Pin,
  Sparkles,
  Thermometer,
  X,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSyncedPluginTheme } from '../../shared/useSyncedPluginTheme.ts';
import { VELLO_SCOPE_MANIFEST, VELLO_SCOPE_PLUGIN_ID } from './manifest.ts';
import { createMockVelloRpcHandler } from './mockVelloBridge.ts';
import {
  type ScopePipelineKind,
  type ScopeRendererController,
  startScopeRenderer,
} from './scopeRenderer.ts';

const SAMPLE_COUNT = 48;
const PINNED_DEFAULT_BOUNDS = { widthDp: 316, heightDp: 452 };

const BACKDROP_OPTIONS: ReadonlyArray<{
  value: ContainerBackdrop;
  label: string;
  icon: typeof Sparkles;
}> = [
  { value: 'native-glass', label: '原生毛玻璃', icon: Sparkles },
  { value: 'transparent', label: '透明穿透', icon: Layers },
];

function createSeedSamples(): number[] {
  return Array.from({ length: SAMPLE_COUNT }, (_, idx) => 24 + Math.sin(idx * 0.25) * 12);
}

function VelloScopeWorkbench() {
  const { isNativeHost, resolvedScheme } = useSyncedPluginTheme();
  const { dismiss, setSurfaceBackdrop } = useContainerAction();
  const { isPinned, clickThrough, setMode, setClickThrough } = usePresentationMode('drawer');
  const { trigger: triggerHaptic } = useHaptics();
  const { visible: surfaceVisible } = usePanelVisibility();
  const { snapshot } = useTelemetry(600);

  const [backdrop, setBackdrop] = useState<ContainerBackdrop>('native-glass');
  const [pipeline, setPipeline] = useState<ScopePipelineKind | null>(null);
  const [fps, setFps] = useState<number | null>(null);

  const stageRef = useRef<HTMLDivElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const rendererRef = useRef<ScopeRendererController | null>(null);
  const surfaceVisibleRef = useRef(surfaceVisible);
  surfaceVisibleRef.current = surfaceVisible;
  const samplesRef = useRef<number[]>(createSeedSamples());
  const schemeRef = useRef(resolvedScheme);
  schemeRef.current = resolvedScheme;

  useEffect(() => {
    if (snapshot && typeof snapshot.cpuPercent === 'number') {
      const next = samplesRef.current.slice(1);
      next.push(snapshot.cpuPercent);
      samplesRef.current = next;
    }
  }, [snapshot]);

  useEffect(() => {
    const canvas = canvasRef.current;
    const stage = stageRef.current;
    if (!canvas || !stage) return;
    const controller = startScopeRenderer(canvas, stage, {
      onPipelineReady: setPipeline,
      onFpsSample: setFps,
      readSamples: () => samplesRef.current,
      readScheme: () => schemeRef.current,
      initialActive: surfaceVisibleRef.current,
    });
    rendererRef.current = controller;
    return () => {
      controller.dispose();
      rendererRef.current = null;
    };
  }, []);

  useEffect(() => {
    rendererRef.current?.setActive(surfaceVisible);
  }, [surfaceVisible]);

  const handleTogglePin = useCallback(() => {
    void triggerHaptic('click');
    if (isPinned) {
      void setMode('drawer').catch(() => {});
    } else {
      void setMode('pinned', PINNED_DEFAULT_BOUNDS).catch(() => {});
    }
  }, [isPinned, setMode, triggerHaptic]);

  const handleSelectBackdrop = useCallback(
    (next: ContainerBackdrop) => {
      if (backdrop === next) return;
      setBackdrop(next);
      void triggerHaptic('tick');
      void setSurfaceBackdrop(next).catch(() => {});
    },
    [backdrop, setSurfaceBackdrop, triggerHaptic],
  );

  const handleToggleClickThrough = useCallback(
    (enabled: boolean) => {
      void triggerHaptic('tick');
      void setClickThrough(enabled).catch(() => {});
    },
    [setClickThrough, triggerHaptic],
  );

  const memGb =
    snapshot && snapshot.memTotalMb > 0
      ? `${(snapshot.memUsedMb / 1024).toFixed(1)} / ${(snapshot.memTotalMb / 1024).toFixed(0)} GB`
      : '等待遥测采样';
  const pipelineLabel =
    pipeline === 'webgpu' ? 'WebGPU' : pipeline === 'canvas2d' ? 'Canvas2D' : '初始化';

  return (
    <div
      className={cn(
        'flex w-full flex-col',
        isNativeHost ? 'h-dvh overflow-hidden' : 'mx-auto min-h-dvh max-w-[356px] p-2',
      )}
    >
      <AppShell
        variant="drawer"
        title="矢量示波器"
        className={cn(
          isNativeHost
            ? 'h-full min-h-0 flex-1'
            : 'min-h-[calc(100dvh-1rem)] flex-1 rounded-[var(--radius-panel)]',
          backdrop === 'transparent' &&
            'rounded-[var(--radius-panel)] border border-[var(--border-hairline)] bg-[color-mix(in_oklab,var(--surface-toast)_74%,transparent)]',
        )}
        headerLeading={
          <div className="flex size-7 items-center justify-center rounded-[var(--radius-control)] bg-[var(--accent-soft)] text-[var(--accent)]">
            <Activity className="size-3.5" />
          </div>
        }
        headerTrailing={
          <div className="flex items-center gap-1.5">
            <Badge variant={fps !== null ? 'success' : 'neutral'} className="tabular-nums">
              {fps !== null ? `${fps} FPS` : '-- FPS'}
            </Badge>
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label="关闭矢量示波器"
              onClick={() => void dismiss()}
            >
              <X className="size-4" />
            </Button>
          </div>
        }
      >
        <div className="flex flex-col gap-2.5">
          <div className="grid grid-cols-2 gap-2">
            <MetricTile
              label="CPU"
              value={snapshot ? snapshot.cpuPercent.toFixed(1) : '--'}
              unit={snapshot ? '%' : undefined}
              hint={`管线: ${pipelineLabel}`}
              statusLabel={pipeline ? '运行中' : '启动中'}
              statusVariant={pipeline ? 'success' : 'neutral'}
              icon={<Cpu className="size-3.5" />}
            />
            <MetricTile
              label="SoC"
              value={snapshot?.socTempC != null ? snapshot.socTempC.toFixed(0) : '--'}
              unit={snapshot?.socTempC != null ? '°C' : undefined}
              hint={memGb}
              statusLabel={isPinned ? '钉选态' : '侧栏态'}
              statusVariant={isPinned ? 'accent' : 'neutral'}
              icon={<Thermometer className="size-3.5" />}
            />
          </div>

          <Card className="p-2">
            <div
              ref={stageRef}
              className={cn(
                'relative w-full overflow-hidden rounded-[var(--radius-control)] border border-[var(--border-hairline)] bg-[var(--surface-elevated)]',
                isPinned ? 'h-24' : 'h-28',
              )}
            >
              <canvas ref={canvasRef} className="absolute inset-0 block size-full" />
              <div className="pointer-events-none absolute top-2 right-2">
                <Badge variant={pipeline === 'webgpu' ? 'accent' : 'neutral'}>
                  {pipelineLabel}
                </Badge>
              </div>
            </div>
          </Card>

          <Card className="flex flex-col gap-2 p-2.5">
            <Button
              size="sm"
              variant={isPinned ? 'secondary' : 'primary'}
              className="w-full"
              aria-label={isPinned ? '返回侧栏' : '钉选悬浮'}
              onClick={handleTogglePin}
            >
              {isPinned ? <PanelRight className="size-3.5" /> : <Pin className="size-3.5" />}
              {isPinned ? '返回侧栏' : '钉选悬浮'}
            </Button>

            <fieldset
              className="m-0 grid grid-cols-2 gap-1.5 border-0 p-0"
              aria-label="窗口底板材质"
            >
              {BACKDROP_OPTIONS.map((opt) => {
                const Icon = opt.icon;
                const active = backdrop === opt.value;
                return (
                  <button
                    key={opt.value}
                    type="button"
                    aria-pressed={active}
                    onClick={() => handleSelectBackdrop(opt.value)}
                    className={cn(
                      'pochita-switcher-pill flex cursor-pointer items-center justify-between gap-1 rounded-[var(--radius-control)] px-2.5 py-1.5 text-left text-xs font-medium transition-all duration-150 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--accent)]',
                      active
                        ? 'pochita-switcher-pill-active bg-[var(--accent-soft)] text-[var(--text-primary)] ring-1 ring-inset ring-[color-mix(in_oklab,var(--accent)_45%,transparent)]'
                        : 'bg-[var(--surface-elevated)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]',
                    )}
                  >
                    <span className="flex min-w-0 items-center gap-1.5">
                      <Icon className="size-3.5 shrink-0" />
                      <span className="truncate">{opt.label}</span>
                    </span>
                    {active ? (
                      <span className="inline-flex size-3.5 shrink-0 items-center justify-center rounded-full bg-[var(--accent)] text-[var(--accent-fg)]">
                        <Check className="size-2" />
                      </span>
                    ) : null}
                  </button>
                );
              })}
            </fieldset>

            {isPinned ? (
              <>
                <div className="pochita-divider" />
                <SettingRow
                  variant="plain"
                  title="点击穿透"
                  description="开启后由底部“解除穿透”胶囊恢复"
                  control={
                    <Switch
                      checked={clickThrough}
                      onCheckedChange={handleToggleClickThrough}
                      aria-label="点击穿透"
                    />
                  }
                />
              </>
            ) : null}
          </Card>
        </div>
      </AppShell>
    </div>
  );
}

export function VelloScopeApp() {
  const bridgeOptions = useMemo<BridgeOptions>(
    () => ({
      allowUrlOverride: false,
      allowedOrigins: [`https://${VELLO_SCOPE_PLUGIN_ID}.plugins.sideveil.local`],
      mockNamespace: 'sideveil-vello-scope',
      onMockRpc: createMockVelloRpcHandler(),
    }),
    [],
  );

  return (
    <SideVeilPluginProvider
      pluginId={VELLO_SCOPE_PLUGIN_ID}
      manifest={VELLO_SCOPE_MANIFEST}
      bridgeOptions={bridgeOptions}
    >
      <ThemeProvider
        defaultTheme="coloros-glass"
        defaultColorMode="system"
        defaultSurfaceMode="native-glass"
        syncEnabled={false}
      >
        <VelloScopeWorkbench />
      </ThemeProvider>
    </SideVeilPluginProvider>
  );
}
