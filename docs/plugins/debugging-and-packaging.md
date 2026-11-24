# Debugging, CLI Workflows & `.svp` Packaging

This guide covers the complete plugin development lifecycle: local browser preview, live device HMR, WebView CDP remote debugging, CLI scaffolding/packaging, and pre-release verification.

## 1. Desktop Browser Mock Preview

Plugins built with `@gojona/bridge/core` or `@gojona/sideveil-sdk/core` automatically detect desktop browsers (`bridge.env === 'mock'`) and route `fs.*`, `invoke()`, `invokePlugin()`, and host events through `MockTransport` backed by `localStorage`:

```ts
import { createBridge, MockTransport } from '@gojona/bridge/core';
import { createSideVeilClient } from '@gojona/sideveil-sdk/core';

const mock = new MockTransport({
  namespace: 'my-plugin-dev',
  handlers: {
    'telemetry.snapshot': () => ({ cpuPercent: 18.4, memUsedMb: 4200, memTotalMb: 12288, socTempC: 36, uptimeSec: 900 }),
  },
});
const client = createSideVeilClient(createBridge({ pluginId: 'com.example.demo', mockTransport: mock }));
```

In browser preview mode, `window.__POCHITA_DEBUG__.emitHostEvent(event, payload)` lets you simulate `host.ready`, `theme.paletteUpdated`, `panel.visibility`, and `scope.changed` directly from DevTools.

## 2. Live Device Hot-Reload (`devServerUrl`) & CDP

1. Forward your local dev server (e.g., Vite on port `5179`) to the device over ADB or SSH reverse tunnel:
   ```bash
   adb reverse tcp:5179 tcp:5179
   ```
2. Point your plugin at the loopback dev server (via Control Plane `管理 -> 开发与后台` or CLI):
   ```bash
   sideveil-ctl plugins dev com.example.demo http://127.0.0.1:5179
   ```
   Render Host automatically enables WebView DevTools (CDP) whenever a loopback `devServerUrl` is active.
3. To inspect DOM, console logs, and network traffic from Chrome DevTools (`chrome://inspect`), enable **开发者选项 -> WebView 远程调试 (CDP)** in the Control Plane or run:
   ```bash
   sideveil-ctl debug cdp on
   sideveil-ctl debug cdp status
   ```
4. Exit Dev Mode when finished:
   ```bash
   sideveil-ctl plugins dev com.example.demo --clear
   ```

## 3. Auditing & Daemon Troubleshooting

- **Inspect capability ACL audit log & host memory**:
  ```bash
  sideveil-ctl plugins audit
  ```
- **Inspect supervised `uds-native` daemon stderr output**:
  ```bash
  sideveil-ctl plugins daemon-logs com.example.demo --limit 100
  ```
- **Force reload a plugin WebView instance**:
  ```bash
  sideveil-ctl rpc plugins.reloadWebView '{"pluginId":"com.example.demo"}'
  ```
- **Grant or revoke capabilities from CLI**:
  ```bash
  sideveil-ctl plugins grant com.example.demo --all-pending --approve-daemon
  ```

## 4. `.svp` Archive Specification & `sideveil-ctl` Packaging Workflow

A `.svp` package is a standard ZIP archive containing `manifest.json` at archive root (or inside a single top-level `<plugin_id>/` directory):

```text
my-plugin-1.0.0.svp (ZIP)
├── manifest.json
├── web/
│   ├── index.html
│   ├── icon.svg
│   └── ...
└── bin/                 # Optional uds-native arm64 binary
    └── my-daemon
```

End-to-end CLI workflow:

```bash
# 1. Scaffold a new plugin directory
sideveil-ctl plugins init com.example.demo --dir ./my-plugin

# 2. Validate manifest.json against schema and path safety rules
sideveil-ctl plugins validate ./my-plugin

# 3. Pack into a distributable .svp archive
sideveil-ctl plugins pack ./my-plugin --out ./com.example.demo-1.0.0.svp

# 4. Inspect archive metadata & capability diff before installing
sideveil-ctl rpc plugins.inspectArchive '{"path":"/sdcard/Download/com.example.demo-1.0.0.svp"}'

# 5. Install on device (or import via Control Plane UI: 插件管理 -> 已安装插件 -> 导入插件)
sideveil-ctl plugins install /sdcard/Download/com.example.demo-1.0.0.svp
```

## 5. Pre-Release Checklist

- [ ] `manifest.json` sets `"schemaVersion": 1`, `"apiVersion": 1`, concise `description`, `author`, and valid SemVer `version`.
- [ ] Only the minimal required `capabilities` are declared (avoid `system-exec` unless strictly necessary).
- [ ] Plugin degrades gracefully if a medium/high capability returns `-32003` (`PERMISSION_DENIED`) before user approval.
- [ ] Animations and polling pause when `panel.visibility` reports `visible: false` or `document.hidden === true`.
- [ ] UI renders cleanly in both light and dark themes using `theme.paletteUpdated` CSS tokens (no hardcoded surface/text hex colors).
- [ ] Tested on device with zero uncaught WebView console errors (`sideveil-ctl logs`).
