# SideVeil Plugin Security Model

SideVeil separates system privileges, window rendering, and untrusted third-party plugin code across four isolated layers.

## Four-Layer Trust Boundaries

1. **Core Hub (`sideveild`, `uid 0`)**: Sole authority for `/data/system/sideveil/config.json`, plugin manifests, capability grants (`plugins.json`), loopback HTTP asset serving, jailed plugin storage, and supervised `uds-native` daemons.
2. **System Sensor (`sideveil-sensor`, `UID 1000`)**: Single-DEX `app_process64` handling foreground task monitoring and edge gesture interception; never runs plugin code or exposes sockets to plugins.
3. **Render Host (`pochita.sideveil.host`, `priv-app`)**: Owns Android overlay windows, WebView sandboxes, and host-local ACL enforcement (`ui.toast`, `clipboard.*`, `haptics.trigger`). Never executes root commands or writes config directly.
4. **Plugin Sandboxes (Isolated WebViews & Backends)**: Each plugin runs under a dedicated virtual origin `https://<pluginId>.plugins.sideveil.local/` with strict origin isolation, computed CSP, and explicit capability grants.

## WebView Origin Isolation & `WebMessageListener`

- **Per-plugin virtual origin**: Local plugin assets are served via Android `WebViewAssetLoader` / authenticated loopback proxy under `https://<pluginId>.plugins.sideveil.local/`. Separate plugins never share cookies, `localStorage`, IndexedDB, or `WebView` storage origins.
- **Origin-pinned messaging (`WebViewCompat.addWebMessageListener`)**: Render Host binds `window.__POCHITA_HOST__` strictly to the plugin's exact virtual origin (`setOf("https://<pluginId>.plugins.sideveil.local")`). Unlike `addJavascriptInterface`, Chromium guarantees that no third-party iframe or navigated origin can access `__POCHITA_HOST__`.
- **Web Embeds receive zero host bridge**: `origin: "web-embed"` plugins never bind `__POCHITA_HOST__` and cannot invoke any JSON-RPC method.
- **On-demand Cross-Origin Isolation**: Only plugins explicitly declaring `surface.crossOriginIsolated: true` receive `COOP: same-origin` and `COEP: require-corp` headers (for WebGPU / `SharedArrayBuffer`).

## Host-Computed Content Security Policy (CSP)

Render Host computes and injects a strict CSP response header for every plugin HTML document based on the plugin's **granted** capabilities and `network.allowedOrigins`:
- Baseline policy locks `default-src 'self'`, `object-src 'none'`, `base-uri 'none'`, `frame-ancestors 'none'`.
- `connect-src` / `img-src` / `media-src` expand **only** for origins validated against the plugin's currently granted network capabilities:
  - `network-loopback`: `127.0.0.1` / `localhost` / `[::1]`
  - `network-lan`: RFC1918 private IPs (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) and `.local`
  - `network-internet`: HTTPS public origins explicitly listed in `network.allowedOrigins` (public cleartext HTTP is always rejected).
- Dev Mode (`devServerUrl`) is restricted strictly to loopback (`127.0.0.1` / `localhost`) with no userinfo, no redirects, and bounded response sizes.

## Filesystem & Storage Jailing (`fs.*`)

- Every plugin requesting `storage` is jailed to `/data/system/sideveil/data/<plugin_id>/` (`0700`).
- Hub canonicalizes all paths (`fs_util.rs` / `plugin_acl.rs`), rejects `..` traversal and symlink escapes pointing outside the jail, enforces per-file and per-plugin storage quotas (returning `-32006 QUOTA_EXCEEDED`), and persists writes atomically via temp-file + `fsync` + `rename`.

## Backend Socket Authentication (`hub.sock` & `app.sock`)

- **`hub.sock` (`uds-native`)**: Restricted via Linux `SO_PEERCRED` to `uid 0` or `uid 1000`. Supervised plugin binaries live inside the installed plugin directory, cannot escape via symlinks, and require explicit user approval (`daemonApproved: true`) before `sideveild` spawns them.
- **`app.sock` (`0666`, `uds-app`)**: Verified via `SO_PEERCRED` by mapping the peer's appId (`uid % 100_000`) against `/data/system/packages.list` and checking membership in the plugin's manifest-declared `backendPackages` allowlist. Unlisted packages are rejected immediately.

## Unified Audit Trail

Every capability check — both Hub-side (`fs.*`, `system.exec`, `telemetry.snapshot`, `network.context`, `hud.*`, `plugin.busPublish`) and Host-local (`ui.toast`, `clipboard.get/set`, `haptics.trigger` forwarded via `host.localAudit`) — is recorded in the Hub's bounded audit ring (`plugins.audit`), capturing `timestampMs`, `pluginId`, `action`, `allowed`, and `detail`.
