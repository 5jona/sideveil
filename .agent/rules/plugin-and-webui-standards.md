# Sideveil — Open Plugin Contract & WebUI Standards

## 1. Open Host Philosophy
- **Host stays minimal; Plugins own their UI**: Sideveil Host never dictates how a plugin's expanded panel looks or which frontend framework it uses. Any web stack (React, Vue, Solid, Canvas, Vanilla HTML/JS) is valid as long as it speaks the standard `MessagePort` / JSON-RPC 2.0 host contract.
- **Zero-boilerplate option**: `@pochita/sideveil-sdk` (published from `pochita-webui-kit`) provides optional React hooks and theme synchronization for rapid development, while `@pochita/ui` and `@pochita/bridge` remain strictly generic and Sideveil-agnostic.

## 2. Plugin Discovery & Manifest Contract
- **Registration directory**: `/data/system/sideveil/plugins/<plugin_id>/` (real directory or symlink), watched live via `inotify` by `sideveild`.
- **Required `manifest.json` fields**:
  - `id`: Reverse-DNS or kebab-case unique identifier (e.g. `com.example.foo`).
  - `name`, `version`, `icon`: Display metadata (used by Control WebUI and Plugin Rail).
  - `triggers`: Matching rules (target package list, optional Activity suffix list, global mode, priority).
  - `entry`: Relative path under `web/` (e.g. `web/index.html`).
  - `backend` (optional): `none` | `uds-native` (KernelSU daemon via `hub.sock`) | `uds-app` (LSPosed hook in target app via `app.sock` + `SO_PEERCRED` UID match).
  - `capabilities` (optional): Least-privilege permission requests (`storage`, `toast`, `system-exec`, `native-hud`, `clipboard`, `haptics`, `telemetry`, `inter-plugin-bus`).
  - `surface` (optional): Default backdrop mode (`native-glass` | `transparent` | `opaque`), presentation mode (`drawer` | `peek` | `pinned` | `wide` | `native-hud`), `crossOriginIsolated` (on-demand COOP/COEP/CORP for WASM/WebGPU), and preferred panel size hints.
- **Dynamic backend lifecycle**: Backends connect over UDS and register with `plugin.register`. If a backend disconnects, the plugin remains discoverable and the frontend receives a `plugin.backendState` event (`online` / `offline`).

## 3. Host Container & Plugin RPC Contract
- **Container actions (`container.action`)**:
  - `dismiss`: Close the sidebar panel.
  - `setPreferredSize`: Request panel `widthDp` / `heightPercent` (clamped by Host safety bounds).
  - `requestImeFocus`: Toggle window focusability for soft keyboard input.
  - `setBackIntercept`: Opt in/out of receiving `panel.backPressed` events for in-plugin navigation before Host dismisses the panel.
  - `setSurfaceBackdrop`: Switch between native glass backdrop and full transparent custom-drawn canvas.
  - `syncTheme`: Propagate active `themeId` and `colorScheme` to native backdrop/indicator.
- **Plugin backend RPC (`plugin.invoke`)**:
  - Frontend calls `plugin.invoke` with `{ method, params }`; `sideveild` routes it exclusively to the active plugin's registered backend and enforces capability ACLs.
  - Standard `@pochita/bridge` methods (`fs.read`, `fs.writeAtomic`, `ui.toast`, `system.exec`) are scoped to the plugin's isolated `/data/system/sideveil/data/<plugin_id>/` directory and manifest ACLs.

## 4. Frontend & `pochita-webui-kit` Standards
All Sideveil WebUI code (`control`, `panel` built-in diagnostic plugin `sideveil.diagnostic`) and `pochita-webui-kit` packages must adhere to `pochita-webui-kit/.agents/rules/webui-standards.md`, plus:
- **Unified Linter & Formatter**: Enforce **Biome** across both `pochita-webui-kit` and `sideveil/webui` (2-space indent, consistent imports, zero lint warnings).
- **Theme Bootstrap Timing**: Inject the theme bootstrap inline script with `injectTo: 'head-prepend'` (immediately after `<meta name="color-scheme">`) so first paint never flashes.
- **CSS & Token Hygiene**:
  - Use `color-mix(in oklab, ...)` consistently (never `srgb` drift).
  - Keep `data-surface="native-glass"` overrides isolated in a dedicated stylesheet.
  - Maintain a single source of truth for registered theme IDs across kit, webui, and Android.
- **Package Consumption**:
  - Support clean build/export entry points in `@pochita/ui`, `@pochita/bridge`, `@pochita/config`, and `@pochita/sideveil-sdk` so external plugins can consume them without brittle `file:../../../../` relative paths.
- **File Size**: Keep `.ts` / `.tsx` / `.css` files <= 300 lines (hard limit 400 lines).
