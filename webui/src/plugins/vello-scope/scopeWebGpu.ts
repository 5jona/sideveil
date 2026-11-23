import type { ResolvedColorScheme } from '@pochita/ui';
import { readScopeColors, syncCanvasSize } from './scopeColors.ts';

/** Minimal structural subset of WebGPU `GPUBuffer` used by the oscilloscope pipeline. */
export interface GpuBufferLike {
  destroy?: () => void;
}

/** Minimal structural subset of WebGPU `GPUDevice` used by the oscilloscope pipeline. */
export interface GpuDeviceLike {
  createShaderModule: (desc: { code: string }) => unknown;
  createRenderPipeline: (desc: Record<string, unknown>) => {
    getBindGroupLayout: (index: number) => unknown;
  };
  createBuffer: (desc: { size: number; usage: number }) => GpuBufferLike;
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
    writeBuffer: (buffer: GpuBufferLike, offset: number, data: ArrayBufferView) => void;
    submit: (commands: unknown[]) => void;
  };
  destroy?: () => void;
}

/** Minimal structural subset of WebGPU `GPUCanvasContext`. */
export interface GpuCanvasContextLike {
  configure: (config: Record<string, unknown>) => void;
  unconfigure?: () => void;
  getCurrentTexture: () => { createView: () => unknown };
}

/** Minimal structural subset of `navigator.gpu`. */
export interface NavigatorGpuLike {
  requestAdapter: () => Promise<{ requestDevice: () => Promise<GpuDeviceLike> } | null>;
  getPreferredCanvasFormat: () => string;
}

/** Shared frame-loop hooks provided by `scopeRenderer.ts` to concrete rendering backends. */
export interface ScopeLoopDriver {
  isRunning: () => boolean;
  scheduleFrame: (cb: FrameRequestCallback) => void;
  recordFrameTiming: (now: number) => void;
  readSamples: () => readonly number[];
  readScheme: () => ResolvedColorScheme;
}

/** Active pipeline handle exposing resume and deterministic resource cleanup hooks. */
export interface ScopePipelineHandle {
  resume: () => void;
  cleanup: () => void;
}

const GPU_BUFFER_USAGE_UNIFORM = 0x0040;
const GPU_BUFFER_USAGE_COPY_DST = 0x0008;

const SCOPE_WGSL_SHADER = `
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
`;

/**
 * Starts the uncapped WebGPU oscilloscope render loop and returns resume/teardown hooks
 * that release the uniform buffer, canvas swapchain, and GPU device on disposal.
 */
export function startWebGpuPipeline(
  canvas: HTMLCanvasElement,
  stageEl: HTMLElement,
  device: GpuDeviceLike,
  ctx: GpuCanvasContextLike,
  format: string,
  driver: ScopeLoopDriver,
): ScopePipelineHandle {
  const shader = device.createShaderModule({ code: SCOPE_WGSL_SHADER });
  const pipeline = device.createRenderPipeline({
    layout: 'auto',
    vertex: { module: shader, entryPoint: 'vs' },
    fragment: { module: shader, entryPoint: 'fs', targets: [{ format }] },
    primitive: { topology: 'triangle-list' },
  });
  const uniformBuf = device.createBuffer({
    size: 48,
    usage: GPU_BUFFER_USAGE_UNIFORM | GPU_BUFFER_USAGE_COPY_DST,
  });
  const bindGroup = device.createBindGroup({
    layout: pipeline.getBindGroupLayout(0),
    entries: [{ binding: 0, resource: { buffer: uniformBuf } }],
  });

  const t0 = performance.now();
  let lastScheme = driver.readScheme();
  let palette = readScopeColors(lastScheme);
  let lastPaletteTick = t0;
  let cleanedUp = false;
  const uniforms = new Float32Array(12);

  const frame: FrameRequestCallback = (now: number) => {
    if (cleanedUp || !driver.isRunning()) return;
    syncCanvasSize(canvas, stageEl);
    driver.recordFrameTiming(now);

    const curScheme = driver.readScheme();
    if (curScheme !== lastScheme || now - lastPaletteTick >= 400) {
      lastScheme = curScheme;
      palette = readScopeColors(curScheme);
      lastPaletteTick = now;
    }

    const samples = driver.readSamples();
    const load = (samples[samples.length - 1] ?? 20) / 100;
    uniforms[0] = (now - t0) / 1000;
    uniforms[1] = load;
    uniforms[2] = 0;
    uniforms[3] = 0;
    uniforms[4] = palette.accentRgb[0];
    uniforms[5] = palette.accentRgb[1];
    uniforms[6] = palette.accentRgb[2];
    uniforms[7] = 1;
    uniforms[8] = palette.secondaryRgb[0];
    uniforms[9] = palette.secondaryRgb[1];
    uniforms[10] = palette.secondaryRgb[2];
    uniforms[11] = 1;

    device.queue.writeBuffer(uniformBuf, 0, uniforms);
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
    pass.setBindGroup(0, bindGroup);
    pass.draw(3);
    pass.end();
    device.queue.submit([enc.finish()]);
    driver.scheduleFrame(frame);
  };

  driver.scheduleFrame(frame);

  return {
    resume() {
      if (!cleanedUp) driver.scheduleFrame(frame);
    },
    cleanup() {
      if (cleanedUp) return;
      cleanedUp = true;
      try {
        uniformBuf.destroy?.();
      } catch {
        // Ignore already-destroyed GPU buffer errors during teardown.
      }
      try {
        ctx.unconfigure?.();
      } catch {
        // Ignore already-unconfigured canvas context errors during teardown.
      }
      try {
        device.destroy?.();
      } catch {
        // Ignore already-destroyed GPU device errors during teardown.
      }
    },
  };
}
