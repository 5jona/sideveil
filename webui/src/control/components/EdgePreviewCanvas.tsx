import { colorWithAlpha, readThemeToken, useTheme } from '@pochita/ui';
import { useEffect, useRef } from 'react';
import type { SideVeilConfig } from '../../shared/types.ts';
import { computePreviewViewportMetrics } from './previewLayout.ts';

export interface EdgePreviewCanvasProps {
  config: SideVeilConfig;
  previewOpen?: boolean;
  screenWidthPx?: number;
  screenHeightPx?: number;
  density?: number;
}

/**
 * Real-time device viewport diagram illustrating the configured edge hotzone,
 * indicator capsule, and target expanded panel bounding rectangle.
 */
export function EdgePreviewCanvas({
  config,
  previewOpen = true,
  screenWidthPx,
  screenHeightPx,
  density,
}: EdgePreviewCanvasProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const { resolvedScheme } = useTheme();

  useEffect(() => {
    const container = containerRef.current;
    const canvas = canvasRef.current;
    if (!container || !canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const draw = () => {
      const dpr = window.devicePixelRatio || 1;
      const metrics = computePreviewViewportMetrics({
        containerWidth: container.clientWidth || 320,
        screenWidthPx,
        screenHeightPx,
        density,
      });
      const { cssWidth, cssHeight, frameX, frameY, frameW, frameH, scale, isPortrait } = metrics;

      canvas.width = Math.round(cssWidth * dpr);
      canvas.height = Math.round(cssHeight * dpr);
      canvas.style.width = `${cssWidth}px`;
      canvas.style.height = `${cssHeight}px`;

      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, cssWidth, cssHeight);

      // Canvas 2D cannot evaluate CSS `light-dark(...)` expressions, so read the
      // concrete scheme-qualified custom properties resolved for the active theme.
      const isDark = resolvedScheme === 'dark';
      const accent = readThemeToken('accent', resolvedScheme, isDark ? '#3b82f6' : '#0066ff');
      const border = readThemeToken(
        'border-hairline',
        resolvedScheme,
        isDark ? 'rgba(255, 255, 255, 0.14)' : 'rgba(18, 24, 38, 0.12)',
      );
      const surface = readThemeToken(
        'surface-elevated',
        resolvedScheme,
        isDark ? 'rgba(46, 52, 66, 0.72)' : 'rgba(235, 241, 250, 0.88)',
      );
      const textMuted = readThemeToken(
        'text-secondary',
        resolvedScheme,
        isDark ? '#a6b0c3' : '#4e5969',
      );

      ctx.save();
      ctx.beginPath();
      ctx.roundRect(frameX, frameY, frameW, frameH, 14);
      ctx.fillStyle = surface;
      ctx.fill();
      ctx.lineWidth = 1.25;
      ctx.strokeStyle = border;
      ctx.stroke();
      ctx.clip();

      ctx.strokeStyle = border;
      ctx.globalAlpha = 0.25;
      ctx.lineWidth = 0.75;
      for (let gx = frameX + 40; gx < frameX + frameW; gx += 40) {
        ctx.beginPath();
        ctx.moveTo(gx, frameY);
        ctx.lineTo(gx, frameY + frameH);
        ctx.stroke();
      }
      for (let gy = frameY + 40; gy < frameY + frameH; gy += 40) {
        ctx.beginPath();
        ctx.moveTo(frameX, gy);
        ctx.lineTo(frameX + frameW, gy);
        ctx.stroke();
      }
      ctx.globalAlpha = 1;

      const hotzoneW = Math.max(8, config.edge.hotzoneWidthDp * scale * 1.45);
      const hotzoneH = Math.min(frameH - 12, Math.max(24, config.edge.heightDp * scale));
      const maxTop = Math.max(0, frameH - hotzoneH);
      const hotzoneY = frameY + Math.min(maxTop, (config.edge.topPercent / 100) * frameH);
      const isLeft = config.edge.side === 'left';
      const hotzoneX = isLeft ? frameX : frameX + frameW - hotzoneW;

      ctx.fillStyle = colorWithAlpha(accent, 0.22);
      ctx.fillRect(hotzoneX, hotzoneY, hotzoneW, hotzoneH);
      ctx.strokeStyle = colorWithAlpha(accent, 0.75);
      ctx.setLineDash([4, 3]);
      ctx.strokeRect(hotzoneX + 0.5, hotzoneY + 0.5, hotzoneW - 1, hotzoneH - 1);
      ctx.setLineDash([]);

      if (config.edge.handleVisible) {
        const pillH = Math.max(20, hotzoneH * 0.64);
        const pillY = hotzoneY + (hotzoneH - pillH) / 2;
        const offsetPx = Math.max(0, config.edge.edgeOffsetDp * scale);
        const pillX = isLeft ? frameX + offsetPx : frameX + frameW - offsetPx - 4;
        ctx.globalAlpha = config.edge.handleOpacity;
        ctx.beginPath();
        ctx.roundRect(pillX, pillY, 4, pillH, 2);
        ctx.fillStyle = accent;
        ctx.fill();
        ctx.globalAlpha = 1;
      }

      if (previewOpen) {
        const maxPanelW = frameW * (isPortrait ? 0.7 : 0.48);
        const panelW = Math.min(maxPanelW, Math.max(56, config.panel.widthDp * scale));
        const panelH = Math.min(frameH - 16, (config.panel.heightPercent / 100) * frameH);
        const panelY = frameY + (frameH - panelH) / 2;
        const inset = Math.min(10, Math.max(6, Math.round(frameW * 0.03)));
        const panelX = isLeft ? frameX + inset : frameX + frameW - panelW - inset;
        const corner = Math.max(6, Math.min(18, config.panel.cornerRadiusDp * scale));

        ctx.beginPath();
        ctx.roundRect(panelX, panelY, panelW, panelH, corner);
        ctx.fillStyle = colorWithAlpha(accent, config.panel.nativeBlurEnabled ? 0.14 : 0.22);
        ctx.fill();
        ctx.lineWidth = 1.2;
        ctx.strokeStyle = colorWithAlpha(accent, 0.85);
        ctx.stroke();

        ctx.fillStyle = textMuted;
        ctx.font = '500 10px sans-serif';
        ctx.textAlign = 'center';
        const centerX = panelX + panelW / 2;
        const centerY = panelY + panelH / 2;
        if (panelW < 88) {
          ctx.fillText(`${config.panel.widthDp}dp`, centerX, centerY - 3);
          ctx.fillText(`${config.panel.heightPercent}%`, centerX, centerY + 10);
        } else {
          ctx.fillText(
            `${config.panel.widthDp}dp · ${config.panel.heightPercent}%`,
            centerX,
            centerY + 3,
          );
        }
      }

      ctx.restore();
    };

    draw();
    const observer = new MutationObserver(draw);
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-theme', 'data-surface', 'class'],
    });
    const resizeObserver = new ResizeObserver(draw);
    resizeObserver.observe(container);

    return () => {
      observer.disconnect();
      resizeObserver.disconnect();
    };
  }, [config, density, previewOpen, resolvedScheme, screenHeightPx, screenWidthPx]);

  const hasDims =
    typeof screenWidthPx === 'number' &&
    screenWidthPx > 0 &&
    typeof screenHeightPx === 'number' &&
    screenHeightPx > 0;
  const orientationText = hasDims
    ? `${screenWidthPx < screenHeightPx ? '竖屏' : '横屏'} ${screenWidthPx}×${screenHeightPx}`
    : null;

  return (
    <div className="space-y-1.5">
      <div ref={containerRef} className="w-full overflow-hidden">
        <canvas
          ref={canvasRef}
          aria-label="边缘热区与侧边栏几何实时预览"
          className="block w-full rounded-[var(--radius-control)]"
        />
      </div>
      <p className="text-[11px] text-[var(--text-secondary)]">
        虚线区域为点击全穿透的无感滑出热区，实线框为展开后的侧边栏面板区域
        {orientationText ? `（当前屏幕：${orientationText}）` : ''}。
      </p>
    </div>
  );
}
