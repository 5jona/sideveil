import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { NATIVE_PERF_HUD_MANIFEST } from '../src/plugins/native-perf-hud/manifest.ts';
import { VELLO_SCOPE_MANIFEST } from '../src/plugins/vello-scope/manifest.ts';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const WEBUI_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(WEBUI_ROOT, '..');

function walkFiles(dir, extensions, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (
      entry.name.startsWith('.') ||
      entry.name === 'node_modules' ||
      entry.name === 'target' ||
      entry.name === 'build' ||
      entry.name === 'dist' ||
      entry.name === 'assets'
    ) {
      continue;
    }
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walkFiles(full, extensions, out);
    } else if (extensions.some((ext) => entry.name.endsWith(ext))) {
      out.push(full);
    }
  }
  return out;
}

test('webui source files never bypass @gojona/bridge or hardcode FPS ceilings', () => {
  const files = walkFiles(path.join(WEBUI_ROOT, 'src'), ['.ts', '.tsx', '.css']);
  assert.ok(files.length > 0, 'Expected webui/src source files to exist');

  const forbiddenPatterns = [
    { re: /window\.__POCHITA_HOST__/g, label: 'direct window.__POCHITA_HOST__ access' },
    { re: /window\.ksu\b/g, label: 'direct window.ksu access' },
    { re: /window\.postMessage\s*\(/g, label: 'raw window.postMessage call' },
    {
      re: /\b(?:fps|measuredFps|refreshRate)\s*=\s*Math\.min\s*\(\s*\d+\s*,|Math\.min\s*\(\s*(?:60|90|120)\s*,/gi,
      label: 'hardcoded FPS/refresh ceiling',
    },
  ];

  for (const file of files) {
    const rel = path.relative(REPO_ROOT, file);
    const content = fs.readFileSync(file, 'utf8');
    for (const { re, label } of forbiddenPatterns) {
      assert.equal(re.test(content), false, `${rel} contains forbidden ${label}`);
    }
  }
});

test('bundled example plugin web outputs are compiled Vite + Pochita bundles with aligned manifests', () => {
  const bundledPlugins = [
    {
      dir: path.join(REPO_ROOT, 'examples/vello-vector-scope'),
      expectedManifest: VELLO_SCOPE_MANIFEST,
    },
    {
      dir: path.join(REPO_ROOT, 'examples/native-perf-hud'),
      expectedManifest: NATIVE_PERF_HUD_MANIFEST,
    },
  ];

  for (const { dir, expectedManifest } of bundledPlugins) {
    const manifestPath = path.join(dir, 'manifest.json');
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    assert.equal(manifest.id, expectedManifest.id);
    assert.equal(manifest.entry, expectedManifest.entry);
    assert.equal(manifest.backend, expectedManifest.backend);
    assert.equal(manifest.surface?.backdrop, expectedManifest.surface?.backdrop);
    assert.deepEqual(manifest.capabilities, expectedManifest.capabilities);

    const webDir = path.join(dir, 'web');
    const htmlPath = path.join(webDir, 'index.html');
    const iconPath = path.join(webDir, 'icon.svg');
    assert.ok(fs.existsSync(htmlPath), `Missing compiled bundle: ${htmlPath}`);
    assert.ok(fs.existsSync(iconPath), `Missing plugin icon: ${iconPath}`);

    const html = fs.readFileSync(htmlPath, 'utf8');
    assert.match(html, /<meta\s+name="color-scheme"\s+content="light dark"/i);
    assert.ok(html.includes('pochita:color-scheme'), `${htmlPath} missing theme bootstrap script`);
    assert.match(
      html,
      /<script\s+type="module"\s+crossorigin\s+src="\.\/assets\/[^"]+\.js"><\/script>/,
      `${htmlPath} must reference compiled Vite module JS asset`,
    );
    assert.match(
      html,
      /<link\s+rel="stylesheet"\s+crossorigin\s+href="\.\/assets\/[^"]+\.css">/,
      `${htmlPath} must reference compiled Vite CSS asset`,
    );

    // Only the single synchronous Pochita theme bootstrap inline script is permitted.
    const inlineScripts = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)].filter(
      (match) => !/\bsrc=/i.test(match[0]) && match[1].trim().length > 0,
    );
    assert.equal(
      inlineScripts.length,
      1,
      `${htmlPath} must not contain hand-coded inline business scripts`,
    );
    assert.ok(inlineScripts[0][1].includes('pochita:color-scheme'));
  }
});

test('all managed source files stay within the 400-line hard limit', () => {
  const roots = [
    path.join(WEBUI_ROOT, 'src'),
    path.join(WEBUI_ROOT, 'test'),
    path.join(REPO_ROOT, 'android/host-app/src/main'),
    path.join(REPO_ROOT, 'android/sensor/src/main'),
    path.join(REPO_ROOT, 'android/protocol/src/main'),
    path.join(REPO_ROOT, 'hub/crates'),
    path.join(REPO_ROOT, 'examples/native-perf-hud/src'),
  ];
  const extensions = ['.ts', '.tsx', '.css', '.kt', '.rs', '.mjs'];
  const violations = [];

  for (const root of roots) {
    for (const file of walkFiles(root, extensions)) {
      const content = fs.readFileSync(file, 'utf8');
      if (content.startsWith('// @generated')) continue;
      const lines = content.split('\n').length;
      if (lines > 400) {
        violations.push(`${path.relative(REPO_ROOT, file)} (${lines} lines)`);
      }
    }
  }

  assert.deepEqual(
    violations,
    [],
    `Source files exceeding 400-line hard limit:\n${violations.join('\n')}`,
  );
});
