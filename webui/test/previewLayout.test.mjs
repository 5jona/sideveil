import assert from 'node:assert/strict';
import test from 'node:test';
import { computePreviewViewportMetrics } from '../src/control/components/previewLayout.ts';

test('computePreviewViewportMetrics renders portrait frame on portrait tablet screens', () => {
  const metrics = computePreviewViewportMetrics({
    containerWidth: 440,
    screenWidthPx: 1848,
    screenHeightPx: 2960,
    density: 3,
  });

  assert.equal(metrics.isPortrait, true);
  assert.ok(metrics.frameH > metrics.frameW, 'Portrait frame should be taller than wide');
  assert.ok(metrics.frameH <= 360, 'Portrait frame height should be bounded');
  assert.ok(metrics.frameX > metrics.pad, 'Portrait frame should be horizontally centered');
  assert.equal(metrics.cssHeight, metrics.frameH + metrics.pad * 2);
});

test('computePreviewViewportMetrics renders landscape frame on landscape tablet screens', () => {
  const metrics = computePreviewViewportMetrics({
    containerWidth: 440,
    screenWidthPx: 2960,
    screenHeightPx: 1848,
    density: 3,
  });

  assert.equal(metrics.isPortrait, false);
  assert.ok(metrics.frameW > metrics.frameH, 'Landscape frame should be wider than tall');
  assert.ok(metrics.frameH <= 300, 'Landscape frame height should be bounded');
  assert.equal(metrics.frameW, 420);
});

test('computePreviewViewportMetrics falls back to landscape 16:9 when dimensions are unavailable', () => {
  const metrics = computePreviewViewportMetrics({
    containerWidth: 360,
    screenWidthPx: 0,
    screenHeightPx: 0,
  });

  assert.equal(metrics.isPortrait, false);
  assert.ok(metrics.frameW > metrics.frameH);
  assert.ok(metrics.scale > 0);
});
