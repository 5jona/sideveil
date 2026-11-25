#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SEMVER_RE =
  /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$/;

const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const write = (rel, text) => fs.writeFileSync(path.join(ROOT, rel), text, 'utf8');

function fail(msg) {
  console.error(`[bump-version] Error: ${msg}`);
  process.exit(1);
}

function parseSemver(raw, label) {
  const m = raw.match(SEMVER_RE);
  if (!m) fail(`${label} "${raw}" is not valid SemVer`);
  const [major, minor, patch] = [Number(m[1]), Number(m[2]), Number(m[3])];
  if (minor >= 100 || patch >= 100) {
    fail(`${label} "${raw}" requires minor < 100 and patch < 100 for versionCode`);
  }
  return { version: raw, versionCode: major * 10000 + minor * 100 + patch };
}

function parseArgs(argv) {
  let check = false;
  let tag = null;
  let version = null;
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--check') check = true;
    else if (arg === '--tag') tag = argv[++i] ?? fail('--tag requires a value');
    else if (arg.startsWith('--tag=')) tag = arg.slice(6);
    else if (arg === '-h' || arg === '--help') {
      console.log('Usage: node scripts/bump-version.mjs [<X.Y.Z>] [--check] [--tag vX.Y.Z]');
      process.exit(0);
    } else if (arg.startsWith('-')) fail(`Unknown option: ${arg}`);
    else if (!version) version = arg;
    else fail(`Unexpected argument: ${arg}`);
  }
  if (!check && !version && !tag) fail('Specify <X.Y.Z> or --check');
  if ((check || tag) && version) fail('Cannot combine <X.Y.Z> with --check or --tag');
  return { check: check || Boolean(tag), tag, version };
}

function replaceOnce(rel, regex, replacement, label) {
  const src = read(rel);
  if (!regex.test(src)) fail(`Missing ${label} pattern in ${rel}`);
  write(rel, src.replace(regex, replacement));
}

function matchGroup(rel, regex, label) {
  const m = read(rel).match(regex);
  if (!m) fail(`Missing ${label} in ${rel}`);
  return m[1];
}

function topChangelogVersion() {
  for (const line of read('docs/CHANGELOG.md').split(/\r?\n/)) {
    const m = line.match(/^## \[([^\]]+)\](?:\s+-\s+\d{4}-\d{2}-\d{2})?$/);
    if (m) return m[1];
  }
  return null;
}

function runCheck(tag) {
  const ver = matchGroup('module/module.prop', /^version=v(.+)$/m, 'version');
  const code = Number(matchGroup('module/module.prop', /^versionCode=(\d+)$/m, 'versionCode'));
  const parsed = parseSemver(ver, 'module/module.prop version');
  if (code !== parsed.versionCode) {
    fail(`module/module.prop versionCode ${code} != expected ${parsed.versionCode}`);
  }
  const checks = [
    ['hub/Cargo.toml', matchGroup('hub/Cargo.toml', /\[workspace\.package\][\s\S]*?^version\s*=\s*"([^"]+)"/m, 'version')],
    ['webui/package.json', JSON.parse(read('webui/package.json')).version],
    ['SensorMain.kt', matchGroup('android/sensor/src/main/kotlin/pochita/sideveil/sensor/SensorMain.kt', /SENSOR_VERSION\s*=\s*"([^"]+)"/, 'SENSOR_VERSION')],
    ['contracts/protocol-constants.contract.json', JSON.parse(read('contracts/protocol-constants.contract.json')).constants.builtin_diagnostic.items.VERSION.value],
    ['examples/native-perf-hud/Cargo.toml', matchGroup('examples/native-perf-hud/Cargo.toml', /\[package\][\s\S]*?^version\s*=\s*"([^"]+)"/m, 'version')],
    ['examples/native-perf-hud/manifest.json', JSON.parse(read('examples/native-perf-hud/manifest.json')).version],
    ['webui/src/plugins/native-perf-hud/manifest.ts', matchGroup('webui/src/plugins/native-perf-hud/manifest.ts', /version:\s*'([^']+)'/, 'version')],
    ['examples/vello-vector-scope/manifest.json', JSON.parse(read('examples/vello-vector-scope/manifest.json')).version],
    ['webui/src/plugins/vello-scope/manifest.ts', matchGroup('webui/src/plugins/vello-scope/manifest.ts', /version:\s*'([^']+)'/, 'version')],
    ['docs/CHANGELOG.md', topChangelogVersion()],
  ];
  for (const [name, actual] of checks) {
    if (actual !== ver) fail(`${name} version "${actual}" != "${ver}"`);
  }
  if (tag !== null) {
    if (!tag.startsWith('v')) fail(`Tag "${tag}" must start with "v"`);
    if (tag.slice(1) !== ver) fail(`Tag "${tag}" does not match repository version "${ver}"`);
  }
  console.log(`[bump-version] OK: all targets and docs/CHANGELOG.md match v${ver} (versionCode=${code})`);
}

function runBump(rawVersion) {
  const { version: v, versionCode: code } = parseSemver(rawVersion, 'Target version');
  replaceOnce('module/module.prop', /^version=v.+$/m, `version=v${v}`, 'version');
  replaceOnce('module/module.prop', /^versionCode=\d+$/m, `versionCode=${code}`, 'versionCode');
  replaceOnce('hub/Cargo.toml', /(\[workspace\.package\][\s\S]*?^version\s*=\s*)"[^"]+"/m, `$1"${v}"`, 'workspace.package.version');
  for (const rel of ['webui/package.json', 'examples/native-perf-hud/manifest.json', 'examples/vello-vector-scope/manifest.json']) {
    const json = JSON.parse(read(rel));
    json.version = v;
    write(rel, `${JSON.stringify(json, null, 2)}\n`);
  }
  replaceOnce('android/sensor/src/main/kotlin/pochita/sideveil/sensor/SensorMain.kt', /(SENSOR_VERSION\s*=\s*)"[^"]+"/, `$1"${v}"`, 'SENSOR_VERSION');
  const contract = JSON.parse(read('contracts/protocol-constants.contract.json'));
  contract.constants.builtin_diagnostic.items.VERSION.value = v;
  write('contracts/protocol-constants.contract.json', `${JSON.stringify(contract, null, 2)}\n`);
  replaceOnce('examples/native-perf-hud/Cargo.toml', /(\[package\][\s\S]*?^version\s*=\s*)"[^"]+"/m, `$1"${v}"`, 'package.version');
  replaceOnce('webui/src/plugins/native-perf-hud/manifest.ts', /(version:\s*)'[^']+'/, `$1'${v}'`, 'version');
  replaceOnce('webui/src/plugins/vello-scope/manifest.ts', /(version:\s*)'[^']+'/, `$1'${v}'`, 'version');

  if (topChangelogVersion() !== v) {
    const raw = read('docs/CHANGELOG.md');
    const heading = `## [${v}] - ${new Date().toISOString().slice(0, 10)}\n\n### Changed\n\n- Release v${v}.\n\n`;
    const idx = raw.search(/^## \[/m);
    write('docs/CHANGELOG.md', idx === -1 ? `${raw.trimEnd()}\n\n${heading}` : `${raw.slice(0, idx)}${heading}${raw.slice(idx)}`);
  }

  const cli = path.join(ROOT, 'webui/node_modules/.bin/pochita-contracts-gen');
  if (fs.existsSync(cli)) {
    execFileSync(cli, ['generate', 'contracts/protocol-constants.contract.json'], { cwd: ROOT, stdio: 'inherit' });
  }
  console.log(`[bump-version] Updated all targets and docs/CHANGELOG.md to v${v} (versionCode=${code})`);
}

const { check, tag, version } = parseArgs(process.argv.slice(2));
if (check) runCheck(tag);
else runBump(version);
