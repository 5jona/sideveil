import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_CONFIG,
  normalizeSideveilConfig,
  normalizeConfig,
} from '../src/shared/types.ts';

test('DEFAULT_CONFIG conforms to expected structural and numerical bounds', () => {
  assert.equal(DEFAULT_CONFIG.version, 1);
  assert.equal(DEFAULT_CONFIG.enabled, true);

  // Edge bounds
  assert.ok(DEFAULT_CONFIG.edge.side === 'left' || DEFAULT_CONFIG.edge.side === 'right');
  assert.ok(DEFAULT_CONFIG.edge.topPercent >= 5 && DEFAULT_CONFIG.edge.topPercent <= 85);
  assert.ok(DEFAULT_CONFIG.edge.heightDp >= 64 && DEFAULT_CONFIG.edge.heightDp <= 360);
  assert.ok(DEFAULT_CONFIG.edge.hotzoneWidthDp >= 12 && DEFAULT_CONFIG.edge.hotzoneWidthDp <= 48);
  assert.ok(DEFAULT_CONFIG.edge.triggerDistanceDp >= 18 && DEFAULT_CONFIG.edge.triggerDistanceDp <= 96);
  assert.ok(DEFAULT_CONFIG.edge.edgeOffsetDp >= 0 && DEFAULT_CONFIG.edge.edgeOffsetDp <= 24);
  assert.ok(DEFAULT_CONFIG.edge.handleOpacity >= 0.1 && DEFAULT_CONFIG.edge.handleOpacity <= 1.0);
  assert.equal(typeof DEFAULT_CONFIG.edge.handleVisible, 'boolean');
  assert.equal(typeof DEFAULT_CONFIG.edge.dimInFullscreen, 'boolean');

  // Panel bounds
  assert.ok(DEFAULT_CONFIG.panel.widthDp >= 260 && DEFAULT_CONFIG.panel.widthDp <= 480);
  assert.ok(DEFAULT_CONFIG.panel.heightPercent >= 40 && DEFAULT_CONFIG.panel.heightPercent <= 92);
  assert.ok(DEFAULT_CONFIG.panel.cornerRadiusDp >= 8 && DEFAULT_CONFIG.panel.cornerRadiusDp <= 36);
  assert.ok(
    DEFAULT_CONFIG.panel.themeId === 'coloros-glass' ||
      DEFAULT_CONFIG.panel.themeId === 'modern-saas-dark',
  );
  assert.ok(
    DEFAULT_CONFIG.panel.colorMode === 'system' ||
      DEFAULT_CONFIG.panel.colorMode === 'light' ||
      DEFAULT_CONFIG.panel.colorMode === 'dark',
  );
  assert.ok(DEFAULT_CONFIG.panel.blurRadiusPx >= 0 && DEFAULT_CONFIG.panel.blurRadiusPx <= 300);
  assert.ok(DEFAULT_CONFIG.panel.scrimAlpha >= 0.0 && DEFAULT_CONFIG.panel.scrimAlpha <= 0.75);

  // Scope bounds
  assert.ok(DEFAULT_CONFIG.scope.mode === 'allowlist' || DEFAULT_CONFIG.scope.mode === 'global');
  assert.ok(Array.isArray(DEFAULT_CONFIG.scope.allowlist) && DEFAULT_CONFIG.scope.allowlist.length > 0);
  assert.ok(Array.isArray(DEFAULT_CONFIG.scope.denylist) && DEFAULT_CONFIG.scope.denylist.length > 0);
});

test('normalizeSideveilConfig handles nullish inputs gracefully', () => {
  const fromNull = normalizeSideveilConfig(null);
  assert.deepEqual(fromNull, DEFAULT_CONFIG);

  const fromUndefined = normalizeSideveilConfig(undefined);
  assert.deepEqual(fromUndefined, DEFAULT_CONFIG);

  const fromEmpty = normalizeSideveilConfig({});
  assert.deepEqual(fromEmpty, DEFAULT_CONFIG);

  // Alias equality
  assert.equal(normalizeConfig, normalizeSideveilConfig);
});

test('normalizeSideveilConfig clamps numerical values to valid ranges', () => {
  const clampedMin = normalizeSideveilConfig({
    version: 0,
    edge: {
      side: 'left',
      topPercent: -100,
      heightDp: 10,
      hotzoneWidthDp: 2,
      triggerDistanceDp: 5,
      edgeOffsetDp: -5,
      handleVisible: false,
      handleOpacity: 0.01,
      dimInFullscreen: false,
    },
    panel: {
      widthDp: 100,
      heightPercent: 10,
      cornerRadiusDp: 2,
      themeId: 'coloros-glass',
      colorMode: 'system',
      nativeBlurEnabled: false,
      blurRadiusPx: -50,
      scrimAlpha: -0.5,
    },
  });

  assert.equal(clampedMin.version, 1);
  assert.equal(clampedMin.edge.side, 'left');
  assert.equal(clampedMin.edge.topPercent, 5);
  assert.equal(clampedMin.edge.heightDp, 64);
  assert.equal(clampedMin.edge.hotzoneWidthDp, 12);
  assert.equal(clampedMin.edge.triggerDistanceDp, 18);
  assert.equal(clampedMin.edge.edgeOffsetDp, 0);
  assert.equal(clampedMin.edge.handleOpacity, 0.1);
  assert.equal(clampedMin.edge.handleVisible, false);
  assert.equal(clampedMin.edge.dimInFullscreen, false);

  assert.equal(clampedMin.panel.widthDp, 260);
  assert.equal(clampedMin.panel.heightPercent, 40);
  assert.equal(clampedMin.panel.cornerRadiusDp, 8);
  assert.equal(clampedMin.panel.nativeBlurEnabled, false);
  assert.equal(clampedMin.panel.blurRadiusPx, 0);
  assert.equal(clampedMin.panel.scrimAlpha, 0);

  const clampedMax = normalizeSideveilConfig({
    version: 4,
    edge: {
      side: 'right',
      topPercent: 999,
      heightDp: 999,
      hotzoneWidthDp: 999,
      triggerDistanceDp: 999,
      edgeOffsetDp: 999,
      handleVisible: true,
      handleOpacity: 9.99,
      dimInFullscreen: true,
    },
    panel: {
      widthDp: 999,
      heightPercent: 999,
      cornerRadiusDp: 999,
      themeId: 'modern-saas-dark',
      colorMode: 'dark',
      nativeBlurEnabled: true,
      blurRadiusPx: 999,
      scrimAlpha: 9.99,
    },
  });

  assert.equal(clampedMax.version, 4);
  assert.equal(clampedMax.edge.topPercent, 85);
  assert.equal(clampedMax.edge.heightDp, 360);
  assert.equal(clampedMax.edge.hotzoneWidthDp, 48);
  assert.equal(clampedMax.edge.triggerDistanceDp, 96);
  assert.equal(clampedMax.edge.edgeOffsetDp, 24);
  assert.equal(clampedMax.edge.handleOpacity, 1.0);

  assert.equal(clampedMax.panel.widthDp, 480);
  assert.equal(clampedMax.panel.heightPercent, 92);
  assert.equal(clampedMax.panel.cornerRadiusDp, 36);
  assert.equal(clampedMax.panel.themeId, 'modern-saas-dark');
  assert.equal(clampedMax.panel.colorMode, 'dark');
  assert.equal(clampedMax.panel.blurRadiusPx, 300);
  assert.equal(clampedMax.panel.scrimAlpha, 0.75);
});

test('normalizeSideveilConfig sanitizes discrete enums and package lists', () => {
  const sanitized = normalizeSideveilConfig({
    edge: {
      side: /** @type {any} */ ('invalid-side'),
    },
    panel: {
      themeId: /** @type {any} */ ('invalid-theme'),
      colorMode: /** @type {any} */ ('invalid-mode'),
    },
    scope: {
      mode: /** @type {any} */ ('invalid-scope-mode'),
      allowlist: ['  com.foo  ', 'com.bar', 'com.foo', '', '   '],
      denylist: ['com.android.systemui', 'com.android.systemui', '  '],
    },
  });

  assert.equal(sanitized.edge.side, 'right');
  assert.equal(sanitized.panel.themeId, 'coloros-glass');
  assert.equal(sanitized.panel.colorMode, 'system');
  assert.equal(sanitized.scope.mode, 'allowlist');
  assert.deepEqual(sanitized.scope.allowlist, ['com.foo', 'com.bar']);
  assert.deepEqual(sanitized.scope.denylist, ['com.android.systemui']);
});

test('normalizeSideveilConfig handles NaN and non-number fallback values cleanly', () => {
  const result = normalizeSideveilConfig({
    edge: {
      topPercent: NaN,
      heightDp: Infinity,
      handleOpacity: -Infinity,
    },
    panel: {
      widthDp: undefined,
      scrimAlpha: NaN,
    },
  });

  assert.equal(result.edge.topPercent, DEFAULT_CONFIG.edge.topPercent);
  assert.equal(result.edge.heightDp, DEFAULT_CONFIG.edge.heightDp);
  assert.equal(result.edge.handleOpacity, DEFAULT_CONFIG.edge.handleOpacity);
  assert.equal(result.panel.widthDp, DEFAULT_CONFIG.panel.widthDp);
  assert.equal(result.panel.scrimAlpha, DEFAULT_CONFIG.panel.scrimAlpha);
});
