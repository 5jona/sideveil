import { colorWithAlpha } from '@gojona/ui';
import { readScopeColors, syncCanvasSize } from './scopeColors.ts';
import type { ScopeLoopDriver, ScopePipelineHandle } from './scopeWebGpu.ts';

/**
 * Starts the uncapped Canvas 2D oscilloscope fallback loop when WebGPU is unavailable.
 */
export function startCanvas2dPipeline(
  canvas: HTMLCanvasElement,
  stageEl: HTMLElement,
  driver: ScopeLoopDriver,
): ScopePipelineHandle {
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    return {
      resume() {},
      cleanup() {},
    };
  }

  let lastScheme = driver.readScheme();
  let palette = readScopeColors(lastScheme);
  let lastPaletteTick = performance.now();
  let cleanedUp = false;

  const frame: FrameRequestCallback = (now: number) => {
    if (cleanedUp || !driver.isRunning()) return;
    const { w, h, dpr } = syncCanvasSize(canvas, stageEl);
    driver.recordFrameTiming(now);

    const curScheme = driver.readScheme();
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

    const samples = driver.readSamples();
    if (samples.length < 2) {
      driver.scheduleFrame(frame);
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

    driver.scheduleFrame(frame);
  };

  driver.scheduleFrame(frame);

  return {
    resume() {
      if (!cleanedUp) driver.scheduleFrame(frame);
    },
    cleanup() {
      cleanedUp = true;
    },
  };
}
