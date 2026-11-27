# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] - 2026-11-27

### Changed

- Unified module (`v0.3.0`, `versionCode=300`), Rust hub crates, Kotlin sensor, SideVeilHost APK, built-in diagnostic contract, WebUI, and bundled example plugins to `v0.3.0`.
- Upgraded `@gojona/*` dependencies to `^0.3.1`, redesigned the Control Plane layout with full-screen bottom navigation, and split control header, daemon status, and panel shell components.
- Optimized hub UDS framing, bounded LRU asset memory cache (`AssetMemoryCache`), JNI HUD rendering, edge gesture tracking, and host window blur/layout commit deduplication (`WindowCommitTrackers`).

### Fixed

- Hardened SideVeilHost background keep-alive (AppOps, sysconfig allowlist, foreground promotion), `/data/system/sideveil/data/*` permissions, endpoint token permissions, loopback `Host` header validation, and `PR_SET_PDEATHSIG` ordering after `setuid`.
- Guarded `libsideveil_hud.so` allocation bounds with `catch_unwind`, deferred boot-time host wake until user-0 CE storage unlocks, eliminated `SideVeilEdgeIndicator` resize churn, and purged orphaned `plugins.json` overrides on uninstall.

## [0.2.0] - 2026-11-25

### Changed

- Unified module, Rust hub, Kotlin sensor, SideVeilHost APK (`versionCode=200`), built-in diagnostic contract, WebUI, and bundled example plugins to `v0.2.0` (`versionCode = MAJOR * 10000 + MINOR * 100 + PATCH`).
- Updated `webui/src/panel/manifest.ts` to read `SIDEVEIL_BUILTIN_DIAGNOSTIC` directly from in-repo generated contracts and added a cross-stack version parity test in `webui/test/contracts.test.mjs`.
- Rebuilt Control Plane, built-in diagnostic panel, and bundled plugin WebUIs with `@gojona/ui` rapid tab-swipe fixes and `@gojona/bridge` `WebMessageListener` channel prioritization fixes.

## [0.1.0] - 2026-11-24

Initial public release of **SideVeil**, a generic Android 16+ (API 36+, arm64, Linux GKI 6.12+) sidebar microkernel host packaged as a KernelSU module.

### Added

- **Core Hub (`hub/`)**:
  - `sideveild` Rust `UID 0` supervisor using Linux `pidfd_open` + Tokio `AsyncFd` (`epoll`) with kernel `flock` singleton enforcement and zero shell/curl polling.
  - Authoritative atomic configuration store (`/data/system/sideveil/config.json`) with `inotify` hot-reload and schema-validated clamping.
  - Open Plugin Registry (`inotify`-watched `/data/system/sideveil/plugins/`, persistent overrides and per-app MRU in `plugins.json`, package + Activity triggers, web-embed dual LAN/remote URL routing, loopback Dev Mode proxying, manifest capability ACLs, and structured audit ring).
  - Dual-channel Unix Domain Socket gateway (`hub.sock` & `app.sock`) authenticated via kernel `SO_PEERCRED`, paired with an authenticated loopback WebSocket (`127.0.0.1:0/ws`), loopback HTTP asset server with on-demand COOP/COEP headers, and dual-priority outbound queues.
  - `sideveil-gfx` (Linebender `usvg` + `vello_cpu` NEON + WCAG-verified `Oklch` palette synthesis) and `sideveil-hud-jni` (`libsideveil_hud.so`) for cached SVG icon rasterization, dynamic Material You glass tinting, and zero-WebView vector HUDs.
  - `sideveil-plugin-sdk` async Rust SDK for `uds-native` / `uds-app` plugin daemons with bounded NDJSON framing, reconnect backoff, dual-priority queues, and structured cancellation.
  - `sideveil-ctl` native Rust CLI communicating directly over `hub.sock`.
- **Thin System Sensor (`android/sensor/`)**:
  - Single-DEX (`classes.dex`) `UID 1000` `app_process64` sensor dropped directly by `sideveild` via `pre_exec`.
  - Foreground package/Activity monitoring (`ITaskStackListener` + `ActivityTaskManager.getTasks`), network context tracking, global edge gesture interception (`monitorGestureInput` + `pilferPointers` with reverse-pull cancel, fullscreen double-swipe hint, and long-press handle repositioning), and `OP_SYSTEM_ALERT_WINDOW` / Doze allowlisting.
- **Headless Render Host (`android/host-app/`)**:
  - AMS-managed `/system/priv-app/SideVeilHost/SideVeilHost.apk` owning click-through edge capsule, zero-reflow origin-anchored spring panel (`drawer`/`peek`/`wide`), pinned floating container with click-through unlock chip, hardware cross-window blur + AGSL `GlassBackdropDrawable`, uncapped adaptive refresh-rate voting, stepped haptics, Android 16+ predictive back preview, frosted-glass Plugin Rail, and zero-WebView `NativeHudWindow`.
  - Isolated per-plugin WebView sandboxes (`https://<pluginId>.plugins.sideveil.local/`) with `WebMessagePort` JSON-RPC 2.0, LRU eviction, WebView console/error/crash telemetry, and `onRenderProcessGone` self-healing.
- **Cross-Language Platform Contracts (`contracts/`)**:
  - Canonical `contracts/*.contract.json` specifications compiling deterministically via `@gojona/contracts-gen` (`pochita-contracts-gen`) to Rust, Kotlin, in-repo TypeScript (`contracts/generated/ts/`), and JSON Schema 2020-12 with 25 golden wire fixtures, plus `scripts/sync-kit-contracts.sh` for syncing contract updates upstream to `gojona-kit`.
- **WebUI Control Plane, Built-in Diagnostic Plugin & Reference Examples (`webui/`, `examples/`)**:
  - React 19 + TypeScript + Tailwind CSS v4 Control Plane (`webroot/`), built-in diagnostic panel (`sideveil.diagnostic`), and reference plugins (`example.vello-scope` WebGPU + `example.native-perf-hud` native HUD) built against published `@gojona/*` npm packages.
  - Dual `release` / `debug` runtime modes (`debug.setRuntimeMode`) and build modes (`build.sh --mode release|debug`) gating bundled source-level test plugins.
- **Build, Signing, Licensing & CI/CD**:
  - Formal `GPL-3.0-only` licensing (`LICENSE` and Cargo workspace metadata).
  - Configurable Android Release APK signing via `android/keystore.properties` or `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` with strict `REQUIRE_RELEASE_KEYSTORE=true` enforcement.
  - Self-contained GitHub Actions CI (`.github/workflows/ci.yml`) and tagged release automation (`.github/workflows/release.yml`) producing release/debug KernelSU archives and `SHA256SUMS.txt`.
