import { colorWithAlpha, type ResolvedColorScheme, readThemeToken } from '@pochita/ui';

/** Maximum backing texture dimension in physical pixels to keep GPU/2D memory bounded. */
export const MAX_CANVAS_DIM_PX = 2048;

/** Rolling frame-rate averaging window in milliseconds. */
export const FPS_WINDOW_MS = 500;

/** Resolved dynamic palette tokens consumed by both WebGPU and Canvas 2D scope renderers. */
export interface ThemeRgb {
  accentHex: string;
  accentRgb: [number, number, number];
  secondaryRgb: [number, number, number];
  gridColor: string;
}

let colorProbeCtx: CanvasRenderingContext2D | null = null;

/**
 * Resolves any valid CSS color expression into normalized linear-ish `[r, g, b]` in `0..1`.
 */
export function resolveColorToRgb(
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

/**
 * Reads semantic SideVeil theme CSS tokens (`--accent`, `--text-secondary`) for the active scheme.
 */
export function readScopeColors(scheme: ResolvedColorScheme): ThemeRgb {
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

/**
 * Synchronizes canvas backing dimensions with `stageEl` layout size and device pixel ratio.
 */
export function syncCanvasSize(canvas: HTMLCanvasElement, stageEl: HTMLElement) {
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
