import { colorWithAlpha, readThemeToken, useTheme } from '@pochita/ui';
import { useEffect, useRef } from 'react';
import type { SideVeilConfig } from '../../shared/types.ts';

export interface EdgePreviewCanvasProps {
  config: SideVeilConfig;
  previewOpen?: boolean;
}

/**
 * Real-time tablet viewport diagram illustrating the configured edge hotzone,
 * indicator capsule, and target expanded panel bounding rectangle.
 */
export function EdgePreviewCanvas({ config, previewOpen = true }: EdgePreviewCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const { resolvedScheme } = useTheme();

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const draw = () => {
      const dpr = window.devicePixelRatio || 1;
      const cssWidth = canvas.clientWidth || 320;
      const cssHeight = Math.round(cssWidth * 0.56);
      canvas.width = Math.round(cssWidth * dpr);
      canvas.height = Math.round(cssHeight * dpr);
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

      const pad = 10;
      const w = cssWidth - pad * 2;
      const h = cssHeight - pad * 2;
      const scale = h / 912;

      ctx.save();
      ctx.beginPath();
      ctx.roundRect(pad, pad, w, h, 14);
      ctx.fillStyle = surface;
      ctx.fill();
      ctx.lineWidth = 1.25;
      ctx.strokeStyle = border;
      ctx.stroke();
      ctx.clip();

      ctx.strokeStyle = border;
      ctx.globalAlpha = 0.25;
      ctx.lineWidth = 0.75;
      for (let gx = pad + 40; gx < pad + w; gx += 40) {
        ctx.beginPath();
        ctx.moveTo(gx, pad);
        ctx.lineTo(gx, pad + h);
        ctx.stroke();
      }
      for (let gy = pad + 40; gy < pad + h; gy += 40) {
        ctx.beginPath();
        ctx.moveTo(pad, gy);
        ctx.lineTo(pad + w, gy);
        ctx.stroke();
      }
      ctx.globalAlpha = 1;

      const hotzoneW = Math.max(8, config.edge.hotzoneWidthDp * scale * 1.6);
      const hotzoneH = Math.min(h - 12, config.edge.heightDp * scale);
      const maxTop = Math.max(0, h - hotzoneH);
      const hotzoneY = pad + Math.min(maxTop, (config.edge.topPercent / 100) * h);
      const isLeft = config.edge.side === 'left';
      const hotzoneX = isLeft ? pad : pad + w - hotzoneW;

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
        const pillX = isLeft ? pad + offsetPx : pad + w - offsetPx - 4;
        ctx.globalAlpha = config.edge.handleOpacity;
        ctx.beginPath();
        ctx.roundRect(pillX, pillY, 4, pillH, 2);
        ctx.fillStyle = accent;
        ctx.fill();
        ctx.globalAlpha = 1;
      }

      if (previewOpen) {
        const panelW = Math.min(w * 0.48, Math.max(64, config.panel.widthDp * scale * 1.15));
        const panelH = Math.min(h - 16, (config.panel.heightPercent / 100) * h);
        const panelY = pad + (h - panelH) / 2;
        const panelX = isLeft ? pad + 10 : pad + w - panelW - 10;
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
        ctx.fillText(
          `${config.panel.widthDp}dp · ${config.panel.heightPercent}%`,
          panelX + panelW / 2,
          panelY + panelH / 2 + 3,
        );
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
    resizeObserver.observe(canvas);

    return () => {
      observer.disconnect();
      resizeObserver.disconnect();
    };
  }, [config, previewOpen, resolvedScheme]);

  return (
    <div className="space-y-1.5">
      <canvas
        ref={canvasRef}
        aria-label="边缘热区与侧边栏几何实时预览"
        className="block w-full rounded-[var(--radius-control)]"
      />
      <p className="text-[11px] text-[var(--text-secondary)]">
        虚线区域为点击全穿透的无感滑出热区，实线框为展开后的侧边栏面板区域。
      </p>
    </div>
  );
}
