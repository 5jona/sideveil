# WebView Wire Protocol & Frontend SDK

SideVeil's WebView bridge is a plain JSON-RPC 2.0 + JSON Event protocol over `window.__POCHITA_HOST__`. Third-party plugins may talk to the bridge directly with zero dependencies, or use the framework-agnostic `@gojona/bridge/core` and `@gojona/sideveil-sdk/core` packages (or React hooks in `@gojona/sideveil-sdk`).

## 1. Handshake & Transport Lifecycle

When a plugin WebView loads at `https://<pluginId>.plugins.sideveil.local/`:

1. Chromium exposes the origin-pinned `window.__POCHITA_HOST__` object (created by `WebViewCompat.addWebMessageListener`) with `postMessage(string)` and `addEventListener('message', handler)`.
2. On startup, the plugin registers a `'message'` listener and sends the deterministic attach frame:
   ```json
   { "event": "host.attach", "channel": "listener", "protocolVersion": 1 }
   ```
3. Render Host binds its `JavaScriptReplyProxy` and immediately replies with a `host.ready` event:
   ```json
   {
     "event": "host.ready",
     "payload": {
       "protocolVersion": 1,
       "apiVersion": 1,
       "pluginId": "example.vanilla-hello",
       "grantedCapabilities": ["storage", "toast"]
     }
   }
   ```
4. Legacy hosts without `addEventListener` fall back to `window.addEventListener('message', ...)` and transferred `WebMessagePort` channels automatically when using `@gojona/bridge/core`.

## 2. JSON-RPC 2.0 Envelope & Error Codes

### Request & Response
```json
// WebView -> Host
{ "jsonrpc": "2.0", "id": "rpc_1", "method": "fs.read", "params": { "path": "note.txt" } }

// Host -> WebView (success)
{ "jsonrpc": "2.0", "id": "rpc_1", "result": { "content": "Hello" } }

// Host -> WebView (error)
{ "jsonrpc": "2.0", "id": "rpc_1", "error": { "code": -32003, "message": "Capability 'storage' not granted" } }
```

### Canonical Error Codes (`SIDEVEIL_ERROR_CODES`)

| Code | Constant | Meaning |
| --- | --- | --- |
| `-32700` | `PARSE_ERROR` | Malformed JSON payload |
| `-32600` | `INVALID_REQUEST` | Invalid JSON-RPC envelope |
| `-32601` | `METHOD_NOT_FOUND` | Unknown RPC method |
| `-32602` | `INVALID_PARAMS` | Parameter validation failed |
| `-32000` | `INTERNAL_ERROR` | Unhandled daemon/host error |
| `-32001` | `DAEMON_DISCONNECTED` | Host WebSocket to `sideveild` is temporarily offline |
| `-32002` | `RPC_TIMEOUT` | Host/Hub RPC timed out |
| `-32003` | `PERMISSION_DENIED` | Capability not declared or not yet granted by user |
| `-32004` | `RATE_LIMITED` | Per-plugin RPC rate limit exceeded |
| `-32005` | `VERSION_MISMATCH` | Protocol or plugin API version incompatible |
| `-32006` | `QUOTA_EXCEEDED` | Jailed storage file or total directory quota exceeded |
| `-32010` | `BACKEND_OFFLINE` | Plugin's `uds-native` / `uds-app` backend is not connected |
| `-32011` | `BACKEND_TIMEOUT` | Backend RPC exceeded timeout (`1000`–`60000` ms) |

## 3. Plugin-Accessible RPC Methods

| Method | Capability | Parameters | Result |
| --- | --- | --- | --- |
| `config.get` | None | `{}` | Authoritative `SideVeilConfig` snapshot (for theme sync) |
| `container.action` | None | `{ type, ... }` (see below) | `{ ok: true }` |
| `fs.read` | `storage` | `{ path: string }` | `{ content: string \| null }` |
| `fs.writeAtomic` | `storage` | `{ path: string, content: string }` | `{ errno, stdout, stderr }` |
| `ui.toast` | `toast` | `{ message: string }` | `{ ok: true }` |
| `clipboard.get` | `clipboard` | `{}` | `{ text: string }` |
| `clipboard.set` | `clipboard` | `{ text: string }` | `{ ok: true }` |
| `haptics.trigger` | `haptics` | `{ kind: "tick"\|"click"\|"heavy"\|"cancel" }` | `{ ok: true }` |
| `telemetry.snapshot` | `telemetry` | `{}` | `{ cpuPercent, memUsedMb, memTotalMb, socTempC, uptimeSec }` |
| `network.context` | `network-context` | `{}` | `{ transport, vpnActive, validated, metered, ssid?, gateway?, localIpv4? }` |
| `hud.updateScene` | `native-hud` | `NativeHudScene` | `{ ok: true }` |
| `hud.dismiss` | `native-hud` | `{ pluginId? }` | `{ ok: true }` |
| `plugin.busPublish` | `inter-plugin-bus` | `{ topic: string, payload?: unknown }` | `{ ok: true }` |
| `plugin.invoke` | Backend attached | `{ method: string, params?: unknown, timeoutMs?: number }` | Backend result |
| `system.exec` | `system-exec` | `{ cmd: string, options?: ExecOptions }` | `{ errno, stdout, stderr }` |

### `container.action` Types
- `{ type: "dismiss" }` — Collapse the sidebar panel.
- `{ type: "requestImeFocus", focused: boolean }` — Toggle soft keyboard focusability.
- `{ type: "setPreferredSize", widthDp?, heightDp?, heightPercent? }` — Adjust panel geometry.
- `{ type: "setBackIntercept", enabled: boolean }` — Receive `panel.backPressed` instead of dismissing.
- `{ type: "setSurfaceBackdrop", backdrop: "native-glass" \| "transparent" \| "opaque" }`
- `{ type: "setPresentationMode", mode: "drawer" \| "peek" \| "pinned" \| "wide" \| "native-hud" }`
- `{ type: "setPinnedBounds", widthDp?, heightDp?, xPercent?, yPercent?, clickThrough? }`
- `{ type: "setEdgeStatus", status: { badgeText?, accentColor?, pulse?, progress?, clear? } \| null }`

## 4. Host Events & Oklch Theme Tokens

Events arrive as `{ "event": "<name>", "payload": ... }`:
- `host.ready` — Protocol/API versions, `pluginId`, and `grantedCapabilities`.
- `theme.paletteUpdated` — Material You Oklch palette `{ themeId, isDark, seedArgb, lumaHint, cssTokens }`. Apply `cssTokens` (e.g., `--accent`, `--accent-soft`, `--surface-card`, `--text-primary`, `--border-hairline`) to `document.documentElement.style` to match the native glass backdrop seamlessly.
- `panel.visibility` — `{ visible: boolean, state: "closed" \| "dragging" \| "open" }` (pause animations/polling when `visible === false`).
- `scope.changed` — `{ foregroundPackage, foregroundActivity, active }`.
- `panel.backPressed` — Delivered when `setBackIntercept(true)` is active.
- `container.presentationChanged` / `container.clickThroughChanged` — Bidirectional pinned-mode sync.
- `network.contextChanged` — Live network state updates (`network-context` capability).
- `plugin.backendState` — `{ pluginId, state: "none" \| "online" \| "offline" }`.
- `plugin.event` — `{ pluginId, event, payload }` (includes `plugin.eventLagged` with `{ dropped }` on backpressure).
- `plugin.busMessage` — `{ fromPluginId, topic, payload }` (`inter-plugin-bus` capability).

## 5. Using the TypeScript SDK (Framework-Agnostic vs React)

### Vanilla JS / Vue / Svelte / Solid (`@gojona/sideveil-sdk/core`)
Zero React and zero `@gojona/ui` dependencies:

```ts
import { createSideVeilClient } from '@gojona/sideveil-sdk/core';

const client = createSideVeilClient();
const ready = await client.waitForReady();
console.log('Granted capabilities:', ready.grantedCapabilities);

const note = await client.storage.read('note.txt');
await client.storage.writeJson('state.json', { updatedAt: Date.now() });
await client.ui.toast('Saved');

const unsub = client.events.onThemePalette((palette) => {
  for (const [k, v] of Object.entries(palette.cssTokens)) {
    document.documentElement.style.setProperty(k, v);
  }
});
```

### React 19 (`@gojona/sideveil-sdk`)
Wrap your root component in `<SideVeilPluginProvider>` and use reactive hooks (`usePluginStorage`, `usePluginRpc`, `useTypedPluginClient`, `useTelemetry`, `useNetworkContext`, `usePresentationMode`, `useHaptics`, `useClipboard`, `useNativeHud`, `useSyncedPluginTheme`).
