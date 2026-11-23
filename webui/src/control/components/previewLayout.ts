export interface PreviewViewportInput {
  containerWidth: number;
  screenWidthPx?: number;
  screenHeightPx?: number;
  density?: number;
}

export interface PreviewViewportMetrics {
  cssWidth: number;
  cssHeight: number;
  pad: number;
  frameX: number;
  frameY: number;
  frameW: number;
  frameH: number;
  aspect: number;
  isPortrait: boolean;
  scale: number;
  screenWidthDp: number;
  screenHeightDp: number;
}

const DEFAULT_LANDSCAPE_ASPECT = 16 / 9;
const MIN_ASPECT = 0.42;
const MAX_ASPECT = 2.4;
const CANVAS_PAD = 10;
const MAX_PORTRAIT_FRAME_HEIGHT = 360;
const MAX_LANDSCAPE_FRAME_HEIGHT = 300;
const MIN_FRAME_HEIGHT = 150;

function isPositiveFinite(value: number | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

/**
 * Compute responsive device-frame geometry for the edge layout preview canvas,
 * adapting seamlessly between portrait and landscape display aspect ratios.
 */
export function computePreviewViewportMetrics(input: PreviewViewportInput): PreviewViewportMetrics {
  const cssWidth = Math.max(
    180,
    Math.round(isPositiveFinite(input.containerWidth) ? input.containerWidth : 320),
  );
  const availW = Math.max(140, cssWidth - CANVAS_PAD * 2);

  const hasScreenDims =
    isPositiveFinite(input.screenWidthPx) && isPositiveFinite(input.screenHeightPx);
  const rawAspect = hasScreenDims
    ? (input.screenWidthPx as number) / (input.screenHeightPx as number)
    : DEFAULT_LANDSCAPE_ASPECT;
  const aspect = Math.min(MAX_ASPECT, Math.max(MIN_ASPECT, rawAspect));
  const isPortrait = aspect < 1;

  let frameW: number;
  let frameH: number;

  if (isPortrait) {
    const maxHeight = Math.min(MAX_PORTRAIT_FRAME_HEIGHT, Math.max(240, Math.round(availW * 1.15)));
    frameH = Math.min(maxHeight, Math.round(availW / aspect));
    frameW = Math.round(frameH * aspect);
    if (frameW > availW) {
      frameW = availW;
      frameH = Math.round(frameW / aspect);
    }
  } else {
    frameW = availW;
    frameH = Math.round(frameW / aspect);
    if (frameH > MAX_LANDSCAPE_FRAME_HEIGHT) {
      frameH = MAX_LANDSCAPE_FRAME_HEIGHT;
      frameW = Math.min(availW, Math.round(frameH * aspect));
    }
    if (frameH < MIN_FRAME_HEIGHT) {
      frameH = MIN_FRAME_HEIGHT;
    }
  }

  const frameX = CANVAS_PAD + Math.round((availW - frameW) / 2);
  const frameY = CANVAS_PAD;
  const cssHeight = frameH + CANVAS_PAD * 2;

  const hasDensity = isPositiveFinite(input.density);
  const screenHeightDp =
    hasScreenDims && hasDensity
      ? (input.screenHeightPx as number) / (input.density as number)
      : isPortrait
        ? 960
        : 720;
  const screenWidthDp =
    hasScreenDims && hasDensity
      ? (input.screenWidthPx as number) / (input.density as number)
      : screenHeightDp * aspect;
  const scale = frameH / screenHeightDp;

  return {
    cssWidth,
    cssHeight,
    pad: CANVAS_PAD,
    frameX,
    frameY,
    frameW,
    frameH,
    aspect,
    isPortrait,
    scale,
    screenWidthDp,
    screenHeightDp,
  };
}
