import type { ResolvedColorScheme } from '@gojona/ui';
import { startCanvas2dPipeline } from './scopeCanvas2d.ts';
import { FPS_WINDOW_MS } from './scopeColors.ts';
import {
  type GpuCanvasContextLike,
  type NavigatorGpuLike,
  type ScopeLoopDriver,
  type ScopePipelineHandle,
  startWebGpuPipeline,
} from './scopeWebGpu.ts';

/** Active rendering backend selected for the vector scope canvas. */
export type ScopePipelineKind = 'webgpu' | 'canvas2d';

/** Host callbacks supplying waveform samples, theme polarity, and FPS/pipeline observers. */
export interface ScopeRendererCallbacks {
  onPipelineReady: (kind: ScopePipelineKind) => void;
  onFpsSample: (fps: number) => void;
  readSamples: () => readonly number[];
  readScheme: () => ResolvedColorScheme;
  initialActive?: boolean;
}

/** Lifecycle controller returned by {@link startScopeRenderer}. */
export interface ScopeRendererController {
  setActive: (active: boolean) => void;
  dispose: () => void;
}

function getNavigatorGpu(): NavigatorGpuLike | null {
  if (typeof navigator === 'undefined' || !('gpu' in navigator)) return null;
  const candidate = (navigator as Navigator & { gpu?: unknown }).gpu;
  if (
    candidate &&
    typeof candidate === 'object' &&
    'requestAdapter' in candidate &&
    'getPreferredCanvasFormat' in candidate &&
    typeof candidate.requestAdapter === 'function' &&
    typeof candidate.getPreferredCanvasFormat === 'function'
  ) {
    return candidate as NavigatorGpuLike;
  }
  return null;
}

function getWebGpuCanvasContext(canvas: HTMLCanvasElement): GpuCanvasContextLike | null {
  const ctx: unknown = canvas.getContext('webgpu');
  if (
    ctx &&
    typeof ctx === 'object' &&
    'configure' in ctx &&
    'getCurrentTexture' in ctx &&
    typeof ctx.configure === 'function' &&
    typeof ctx.getCurrentTexture === 'function'
  ) {
    return ctx as GpuCanvasContextLike;
  }
  return null;
}

/**
 * Starts the uncapped WebGPU (with automatic Canvas 2D fallback) vector oscilloscope loop.
 * Returns a controller that pauses/resumes `requestAnimationFrame` cleanly and releases
 * WebGPU device, buffer, and canvas swapchain resources on disposal (including mid-init).
 */
export function startScopeRenderer(
  canvas: HTMLCanvasElement,
  stageEl: HTMLElement,
  callbacks: ScopeRendererCallbacks,
): ScopeRendererController {
  let disposed = false;
  let active = callbacks.initialActive ?? true;
  let rafId = 0;
  let frameCount = 0;
  let lastFpsTick = performance.now();
  let pipelineHandle: ScopePipelineHandle | null = null;

  const driver: ScopeLoopDriver = {
    isRunning: () => !disposed && active,
    scheduleFrame: (cb) => {
      if (!disposed && active && rafId === 0) {
        rafId = requestAnimationFrame((now) => {
          rafId = 0;
          cb(now);
        });
      }
    },
    recordFrameTiming: (now) => {
      frameCount += 1;
      const elapsed = now - lastFpsTick;
      if (elapsed >= FPS_WINDOW_MS) {
        const measuredFps = Math.max(1, Math.round((frameCount * 1000) / elapsed));
        callbacks.onFpsSample(measuredFps);
        frameCount = 0;
        lastFpsTick = now;
      }
    },
    readSamples: callbacks.readSamples,
    readScheme: callbacks.readScheme,
  };

  const boot = async () => {
    const gpu = getNavigatorGpu();
    if (gpu) {
      try {
        const adapter = await gpu.requestAdapter();
        if (adapter && !disposed) {
          const device = await adapter.requestDevice();
          if (disposed) {
            device.destroy?.();
            return;
          }
          const ctx = getWebGpuCanvasContext(canvas);
          if (!ctx) {
            device.destroy?.();
          } else {
            const format = gpu.getPreferredCanvasFormat();
            ctx.configure({ device, format, alphaMode: 'premultiplied' });
            callbacks.onPipelineReady('webgpu');
            pipelineHandle = startWebGpuPipeline(canvas, stageEl, device, ctx, format, driver);
            return;
          }
        }
      } catch {
        // Fall through cleanly to Canvas 2D vector pipeline.
      }
    }
    if (!disposed) {
      callbacks.onPipelineReady('canvas2d');
      pipelineHandle = startCanvas2dPipeline(canvas, stageEl, driver);
    }
  };

  void boot();

  return {
    setActive(nextActive: boolean) {
      if (disposed || active === nextActive) return;
      active = nextActive;
      if (!active) {
        if (rafId) {
          cancelAnimationFrame(rafId);
          rafId = 0;
        }
      } else {
        frameCount = 0;
        lastFpsTick = performance.now();
        pipelineHandle?.resume();
      }
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      active = false;
      if (rafId) {
        cancelAnimationFrame(rafId);
        rafId = 0;
      }
      pipelineHandle?.cleanup();
      pipelineHandle = null;
    },
  };
}
