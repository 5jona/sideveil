import { colorWithAlpha, type ResolvedColorScheme, readThemeToken } from '@pochita/ui';

export type ScopePipelineKind = 'webgpu' | 'canvas2d';

export interface ScopeRendererCallbacks {
  onPipelineReady: (kind: ScopePipelineKind) => void;
  onFpsSample: (fps: number) => void;
  readSamples: () => readonly number[];
  readScheme: () => ResolvedColorScheme;
}

interface GpuDeviceLike {
  createShaderModule: (desc: { code: string }) => unknown;
  createRenderPipeline: (desc: Record<string, unknown>) => {
    getBindGroupLayout: (index: number) => unknown;
  };
  createBuffer: (desc: { size: number; usage: number }) => unknown;
  createBindGroup: (desc: Record<string, unknown>) => unknown;
  createCommandEncoder: () => {
    beginRenderPass: (desc: Record<string, unknown>) => {
      setPipeline: (pipeline: unknown) => void;
      setBindGroup: (index: number, bg: unknown) => void;
      draw: (vertexCount: number) => void;
      end: () => void;
    };
    finish: () => unknown;
  };
  queue: {
    writeBuffer: (buffer: unknown, offset: number, data: ArrayBufferView) => void;
    submit: (commands: unknown[]) => void;
  };
}

interface GpuCanvasContextLike {
  configure: (config: Record<string, unknown>) => void;
  getCurrentTexture: () => { createView: () => unknown };
}

interface NavigatorGpuLike {
  requestAdapter: () => Promise<{ requestDevice: () => Promise<GpuDeviceLike> } | null>;
  getPreferredCanvasFormat: () => string;
}

const GPU_BUFFER_USAGE_UNIFORM = 0x0040;
const GPU_BUFFER_USAGE_COPY_DST = 0x0008;
const MAX_CANVAS_DIM_PX = 2048;
const FPS_WINDOW_MS = 500;

interface ThemeRgb {
  accentHex: string;
  accentRgb: [number, number, number];
  secondaryRgb: [number, number, number];
  gridColor: string;
}

let colorProbeCtx: CanvasRenderingContext2D | null = null;

function resolveColorToRgb(
  cssColor: string,
  fallback: [number, number, number],
): [number, number, number] {
  if (typeof document === 'undefined') return fallback;
  try {
    if (!colorProbeCtx) {
      const probe = document.createElement('canvas');
      probe.width = 1;
      probe.height = 1;
      colorProbeCtx = probe.getContext('2d', { willReadFrequently: true });
    }
    if (!colorProbeCtx) return fallback;
    colorProbeCtx.clearRect(0, 0, 1, 1);
    colorProbeCtx.fillStyle = cssColor;
    colorProbeCtx.fillRect(0, 0, 1, 1);
    const px = colorProbeCtx.getImageData(0, 0, 1, 1).data;
    if (px[3] === 0) return fallback;
    return [px[0] / 255, px[1] / 255, px[2] / 255];
  } catch {
    return fallback;
  }
}

function readScopeColors(scheme: ResolvedColorScheme): ThemeRgb {
  const accentHex = readThemeToken('--accent', scheme, '#38bdf8');
  const textSecondary = readThemeToken('--text-secondary', scheme, '#94a3b8');
  const accentRgb = resolveColorToRgb(accentHex, [0.22, 0.74, 0.97]);
  const secondaryRgb: [number, number, number] = [
    Math.min(1, accentRgb[0] * 0.72 + 0.34),
    Math.min(1, accentRgb[1] * 0.55 + 0.22),
    Math.min(1, accentRgb[2] * 0.85 + 0.28),
  ];
  return {
    accentHex,
    accentRgb,
    secondaryRgb,
    gridColor: colorWithAlpha(textSecondary, 0.16),
  };
}

function syncCanvasSize(canvas: HTMLCanvasElement, stageEl: HTMLElement) {
  const dpr = Math.min(window.devicePixelRatio || 1, 3);
  const rect = stageEl.getBoundingClientRect();
  const w = Math.min(MAX_CANVAS_DIM_PX, Math.max(1, Math.floor(rect.width * dpr)));
  const h = Math.min(MAX_CANVAS_DIM_PX, Math.max(1, Math.floor(rect.height * dpr)));
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w;
    canvas.height = h;
  }
  return { w, h, dpr };
}

/**
 * Starts the uncapped WebGPU (with automatic Canvas 2D fallback) vector oscilloscope loop.
 * Returns a cleanup function that cancels the active `requestAnimationFrame` handle.
 */
export function startScopeRenderer(
  canvas: HTMLCanvasElement,
  stageEl: HTMLElement,
  callbacks: ScopeRendererCallbacks,
): () => void {
  let disposed = false;
  let rafId = 0;
  let frameCount = 0;
  let lastFpsTick = performance.now();

  const recordFrameTiming = (now: number) => {
    frameCount += 1;
    const elapsed = now - lastFpsTick;
    if (elapsed >= FPS_WINDOW_MS) {
      const measuredFps = Math.max(1, Math.round((frameCount * 1000) / elapsed));
      callbacks.onFpsSample(measuredFps);
      frameCount = 0;
      lastFpsTick = now;
    }
  };

  const runWebGpu = (device: GpuDeviceLike, ctx: GpuCanvasContextLike, format: string) => {
    const shader = device.createShaderModule({
      code: `
        struct Uniforms {
          t: f32, load: f32, pad0: f32, pad1: f32,
          c0: vec4f,
          c1: vec4f,
        };
        @group(0) @binding(0) var<uniform> u: Uniforms;
        struct VsOut { @builtin(position) pos: vec4f, @location(0) uv: vec2f };
        @vertex fn vs(@builtin(vertex_index) idx: u32) -> VsOut {
          var p = array<vec2f, 3>(vec2f(-1.0, -1.0), vec2f(3.0, -1.0), vec2f(-1.0, 3.0));
          var o: VsOut;
          o.pos = vec4f(p[idx], 0.0, 1.0);
          o.uv = p[idx] * 0.5 + 0.5;
          return o;
        }
        @fragment fn fs(i: VsOut) -> @location(0) vec4f {
          let amp = 0.13 + u.load * 0.28;
          let w1 = sin(i.uv.x * 10.5 + u.t * 3.4) * amp;
          let w2 = cos(i.uv.x * 23.0 - u.t * 2.3) * (amp * 0.34);
          let wave = 0.5 + w1 + w2;
          let dist = abs(i.uv.y - wave);
          let line = smoothstep(0.024, 0.005, dist);
          let glow = exp(-dist * 13.0) * 0.42;
          let col = mix(u.c0.rgb, u.c1.rgb, i.uv.x);
          let alpha = clamp(line + glow, 0.0, 1.0);
          return vec4f(col * alpha, alpha);
        }
      `,
    });
    const pipeline = device.createRenderPipeline({
      layout: 'auto',
      vertex: { module: shader, entryPoint: 'vs' },
      fragment: { module: shader, entryPoint: 'fs', targets: [{ format }] },
      primitive: { topology: 'triangle-list' },
    });
    const uBuf = device.createBuffer({
      size: 48,
      usage: GPU_BUFFER_USAGE_UNIFORM | GPU_BUFFER_USAGE_COPY_DST,
    });
    const bg = device.createBindGroup({
      layout: pipeline.getBindGroupLayout(0),
      entries: [{ binding: 0, resource: { buffer: uBuf } }],
    });
    const t0 = performance.now();
    let lastScheme = callbacks.readScheme();
    let palette = readScopeColors(lastScheme);
    let lastPaletteTick = t0;

    const frame = (now: number) => {
      if (disposed) return;
      syncCanvasSize(canvas, stageEl);
      recordFrameTiming(now);
      const curScheme = callbacks.readScheme();
      if (curScheme !== lastScheme || now - lastPaletteTick >= 400) {
        lastScheme = curScheme;
        palette = readScopeColors(curScheme);
        lastPaletteTick = now;
      }
      const samples = callbacks.readSamples();
      const load = (samples[samples.length - 1] ?? 20) / 100;
      const uniforms = new Float32Array([
        (now - t0) / 1000,
        load,
        0,
        0,
        palette.accentRgb[0],
        palette.accentRgb[1],
        palette.accentRgb[2],
        1,
        palette.secondaryRgb[0],
        palette.secondaryRgb[1],
        palette.secondaryRgb[2],
        1,
      ]);
      device.queue.writeBuffer(uBuf, 0, uniforms);
      const enc = device.createCommandEncoder();
      const pass = enc.beginRenderPass({
        colorAttachments: [
          {
            view: ctx.getCurrentTexture().createView(),
            clearValue: { r: 0, g: 0, b: 0, a: 0 },
            loadOp: 'clear',
            storeOp: 'store',
          },
        ],
      });
      pass.setPipeline(pipeline);
      pass.setBindGroup(0, bg);
      pass.draw(3);
      pass.end();
      device.queue.submit([enc.finish()]);
      rafId = requestAnimationFrame(frame);
    };
    rafId = requestAnimationFrame(frame);
  };

  const runCanvas2d = () => {
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    let lastScheme = callbacks.readScheme();
    let palette = readScopeColors(lastScheme);
    let lastPaletteTick = performance.now();

    const frame = (now: number) => {
      if (disposed) return;
      const { w, h, dpr } = syncCanvasSize(canvas, stageEl);
      recordFrameTiming(now);
      const curScheme = callbacks.readScheme();
      if (curScheme !== lastScheme || now - lastPaletteTick >= 400) {
        lastScheme = curScheme;
        palette = readScopeColors(curScheme);
        lastPaletteTick = now;
      }

      ctx.clearRect(0, 0, w, h);
      ctx.strokeStyle = palette.gridColor;
      ctx.lineWidth = Math.max(1, dpr * 0.75);
      for (let i = 1; i <= 3; i += 1) {
        const gy = Math.round((h * i) / 4) + 0.5;
        ctx.beginPath();
        ctx.moveTo(0, gy);
        ctx.lineTo(w, gy);
        ctx.stroke();
      }

      const samples = callbacks.readSamples();
      if (samples.length < 2) {
        rafId = requestAnimationFrame(frame);
        return;
      }

      const points = samples.map((v, idx) => ({
        x: (idx / (samples.length - 1)) * w,
        y: h - (Math.min(100, Math.max(0, v)) / 100) * h * 0.78 - h * 0.11,
      }));

      const fillGrad = ctx.createLinearGradient(0, 0, 0, h);
      fillGrad.addColorStop(0, colorWithAlpha(palette.accentHex, 0.32));
      fillGrad.addColorStop(1, colorWithAlpha(palette.accentHex, 0.02));
      ctx.beginPath();
      ctx.moveTo(points[0].x, h);
      for (const pt of points) {
        ctx.lineTo(pt.x, pt.y);
      }
      ctx.lineTo(points[points.length - 1].x, h);
      ctx.closePath();
      ctx.fillStyle = fillGrad;
      ctx.fill();

      ctx.strokeStyle = palette.accentHex;
      ctx.lineWidth = 2.2 * dpr;
      ctx.lineJoin = 'round';
      ctx.lineCap = 'round';
      ctx.beginPath();
      points.forEach((pt, idx) => {
        if (idx === 0) ctx.moveTo(pt.x, pt.y);
        else ctx.lineTo(pt.x, pt.y);
      });
      ctx.stroke();

      const last = points[points.length - 1];
      ctx.fillStyle = palette.accentHex;
      ctx.beginPath();
      ctx.arc(last.x, last.y, 3.2 * dpr, 0, Math.PI * 2);
      ctx.fill();

      rafId = requestAnimationFrame(frame);
    };
    rafId = requestAnimationFrame(frame);
  };

  const boot = async () => {
    const gpu = (navigator as unknown as { gpu?: NavigatorGpuLike }).gpu;
    if (gpu) {
      try {
        const adapter = await gpu.requestAdapter();
        if (adapter && !disposed) {
          const device = await adapter.requestDevice();
          const ctx = canvas.getContext('webgpu') as unknown as GpuCanvasContextLike | null;
          if (ctx && !disposed) {
            const format = gpu.getPreferredCanvasFormat();
            ctx.configure({ device, format, alphaMode: 'premultiplied' });
            callbacks.onPipelineReady('webgpu');
            runWebGpu(device, ctx, format);
            return;
          }
        }
      } catch {
        // Fall through cleanly to Canvas 2D vector pipeline.
      }
    }
    if (!disposed) {
      callbacks.onPipelineReady('canvas2d');
      runCanvas2d();
    }
  };

  void boot();

  return () => {
    disposed = true;
    if (rafId) cancelAnimationFrame(rafId);
  };
}
