# SideVeil Plugin Developer Handbook

SideVeil is a generic, framework-agnostic Android 16+ sidebar microkernel host. Third-party plugins are packaged as self-contained directories (or `.svp` ZIP archives) containing a `manifest.json`, optional static web assets, and an optional native or companion-app backend.

The host runtime enforces only **`manifest.json`** and **JSON-RPC 2.0** over origin-pinned `WebViewCompat.addWebMessageListener` — you can author plugin UIs in plain HTML/CSS/JavaScript, React, Vue, Svelte, Solid, or any web stack.

## Plugin Archetypes

Choose the lowest-privilege archetype that satisfies your use case:

| Archetype | `origin` / `backend` | Use Cases | Isolation & Privileges |
| --- | --- | --- | --- |
| **Web Embed** | `web-embed` / `none` | Home Assistant, OpenClash, LuCI, Zashboard, LAN/WAN dashboards | Zero privileged host bridge; isolated WebView profile; optional LAN/WAN dual URL & Wi-Fi SSID switching. |
| **Static WebUI Plugin** | `installed` / `none` | Calculators, scratchpads, timers, quick-action launchers, WebGPU visualizers | Isolated `https://<id>.plugins.sideveil.local/` origin + Host-computed CSP; opt-in sandbox capabilities (`storage`, `toast`, `clipboard`, `haptics`, `telemetry`, etc.). |
| **WebUI + Native Daemon** | `installed` / `uds-native` | System/performance monitors, root utilities, custom hardware controllers | Supervised native binary (`daemon` spec in `manifest.json`) connecting over `hub.sock` (`SO_PEERCRED` UID 0/1000); requires explicit user approval before launch. |
| **WebUI + Android Companion App** | `installed` / `uds-app` | Media lyrics/controls, app-specific overlays, Android API bridges | Ordinary Android app connecting over `app.sock` (`0666`), authenticated via `SO_PEERCRED` UID -> `/data/system/packages.list` against `backendPackages`. |
| **Zero-WebView Native HUD** | `uds-native` or WebUI + `native-hud` | Always-on FPS/CPU/temp telemetry badges, crosshairs, status pills | Renders vector primitives via `libsideveil_hud.so` (`vello_cpu` NEON) + HWUI text overlays with zero WebView overhead. |

## Standard Package Directory Layout

```text
<plugin_id>/
├── manifest.json          # Required: schemaVersion 1, id must match directory name
├── web/
│   ├── index.html         # Static UI entry (referenced by manifest.entry)
│   ├── icon.svg           # Optional SVG icon rasterized by Hub (sideveil-gfx)
│   └── ...                # JS/CSS/WASM/image assets
└── bin/
    └── daemon             # Optional arm64-v8a binary (referenced by manifest.daemon.binary)
```

Runtime paths on device:
- Installed plugins: `/data/system/sideveil/plugins/<plugin_id>/`
- Per-plugin jailed storage: `/data/system/sideveil/data/<plugin_id>/`
- Persistent overrides, grants & MRU: `/data/system/sideveil/plugins.json`

## Quick-Start Paths

- **Zero-framework HTML/JS template**: See [`examples/vanilla-hello/`](../../examples/vanilla-hello/) for a complete zero-build, zero-dependency plugin using raw `window.__POCHITA_HOST__` (`host.attach` / `host.ready`), Oklch theme token sync, jailed `fs.*` persistence, and `ui.toast`.
- **Framework-agnostic TypeScript client**: Use `@gojona/bridge/core` + `@gojona/sideveil-sdk/core` (`createSideVeilClient`) with zero React or UI dependencies.
- **React + Vite reference plugins**: See [`examples/vello-vector-scope/`](../../examples/vello-vector-scope/) (WebGPU + COOP/COEP + pinned click-through) and [`examples/native-perf-hud/`](../../examples/native-perf-hud/) (Rust `uds-native` daemon + zero-WebView `NativeHudScene`).

## Documentation Index

1. [**Manifest & Capabilities Reference**](./manifest-and-capabilities.md) — `manifest.json` v1 schema, provenance (`origin`), triggers, surface preferences, `daemon` supervision spec, and all 12 risk-tiered capabilities.
2. [**Security Model & Trust Boundaries**](./security-model.md) — Process isolation, `WebMessageListener` origin pinning, Host-computed CSP, network rules, `fs.*` sandboxing, `app.sock` peer verification, and unified audit trail.
3. [**WebView Protocol & Frontend SDK**](./webview-protocol-and-sdk.md) — Wire protocol (`host.attach` -> `host.ready`, JSON-RPC 2.0, error codes, RPC methods, events, Oklch CSS tokens) and `@gojona/bridge/core` + `@gojona/sideveil-sdk/core` usage.
4. [**Native Backends & Zero-WebView HUD**](./native-backends-and-hud.md) — `uds-native` (`hub.sock`), `uds-app` (`app.sock`), Rust `sideveil-plugin-sdk`, crash circuit breaker, and `NativeHudScene` rendering.
5. [**Web Embeds**](./web-embeds.md) — Isolated LAN/WAN dashboard panels, SSID auto-switching, RFC1918 cleartext rules, and quick toolbar.
6. [**Debugging, CLI & `.svp` Packaging**](./debugging-and-packaging.md) — Desktop mock preview, loopback HMR, WebView CDP, daemon logs, `sideveil-ctl plugins` commands, and `.svp` release checklist.
