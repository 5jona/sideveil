# SideVeil

Generic Android sidebar microkernel host packaged as a KernelSU module.
Target baseline: Android 16+ (API 36+, arm64) on Linux GKI 6.12+. Built around a **Rust Core Hub (`sideveild`)**, a **thin `UID 1000` Kotlin System Sensor (`:sensor`)**, and an AMS-managed headless `priv-app` render host (`SideVeilHost.apk`).

## Architecture

For the full repository navigation map, runtime data flows, ABI-pinned FQCNs, and 7 protocol trust boundaries, see [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md). Empirical Android 16+ platform findings and ADRs live in [`docs/architecture-decisions.md`](./docs/architecture-decisions.md).

1. **Rust Core Hub (`hub/crates/sideveild`, `sideveil-gfx`, `sideveil-hud-jni`, `sideveil-ctl`)**
   - Supervises `sideveil-sensor` directly (`UID 1000` drop via `pre_exec`) and `SideVeilHost` via Linux `pidfd_open` + Tokio `AsyncFd` (`epoll`) with zero shell/curl polling.
   - Manages `/data/system/sideveil/config.json` with atomic persistence and `inotify` hot-reload.
   - Hosts the open **Plugin Registry** (`inotify`-watched `/data/system/sideveil/plugins/<plugin_id>/manifest.json`, persistent overrides & per-app MRU in `plugins.json`, package + Activity-level triggers, capability ACLs for `storage`/`toast`/`system-exec`/`native-hud`/`clipboard`/`haptics`/`telemetry`/`inter-plugin-bus`, structured audit ring, authenticated loopback static asset serving with on-demand COOP/COEP headers, per-plugin loopback Dev Mode URLs, and dynamic `uds-native` / `uds-app` backend RPC multiplexing).
   - Integrates **`sideveil-gfx`** (Linebender `color` `Oklch`, `kurbo`, `peniko`, `vello_cpu` NEON + `usvg`): powers on-disk cached SVG-to-PNG icon rasterization (`GET /plugins/<id>/__icon?size=&theme=`), WCAG-verified Material You `Oklch` palette synthesis (`theme.paletteUpdated`), and zero-WebView `NativeHudScene` rasterization via **`sideveil-hud-jni`** (`libsideveil_hud.so` + NDK `libjnigraphics.so`).
   - Exposes a dual-channel Unix Domain Socket gateway (`hub.sock` & `app.sock`) gated by kernel `SO_PEERCRED`, paired with an authenticated loopback WebSocket (`127.0.0.1:0/ws`) and dual-priority outbound queues (coalescing ephemeral gesture moves and high-rate HUD frames while preserving critical control/RPC frames).
   - Provides the native Rust CLI `bin/sideveil-ctl` communicating directly over `hub.sock` (including `plugins list|reload|enable|disable|priority|scope|dev|audit|select` and `hud demo|dismiss`).
2. **Thin System Sensor (`android/sensor`, `UID 1000` `app_process64`)**
   - Initializes `ApplicationSharedMemory` and monitors foreground packages and Activities via `ITaskStackListener` + 4-argument `ActivityTaskManager.getTasks`.
   - Intercepts edge drags using `InputManagerGlobal.monitorGestureInput` + `InputMonitor.pilferPointers` backed by a pure JVM `EdgeGestureStateMachine` (`240px` reverse-pull cancel, optional fullscreen double-swipe anti-accidental trigger, and `450ms` long-press handle repositioning).
   - Grants `OP_SYSTEM_ALERT_WINDOW` and Doze exemption to `pochita.sideveil.host` via `IAppOpsService` and `IDeviceIdleController`.
   - Connects persistently to `sideveild` via `hub.sock` NDJSON framing.
3. **Headless Render Host APK (`android/host-app`, `pochita.sideveil.host`)**
   - Packaged into `/system/priv-app/SideVeilHost/SideVeilHost.apk` (with `arm64-v8a/libsideveil_hud.so`) alongside `privapp-permissions` and `sysconfig` allowlists.
   - Maintains a click-through `TYPE_APPLICATION_OVERLAY` edge indicator capsule (idle auto-dim/tuck, fullscreen double-swipe pulse, live reposition preview, live status badges), zero-reflow origin-anchored spring panel (`drawer`/`peek`/`wide` + `PinnedContainerWindow` with click-through unlock chip), hardware cross-window blur (`BlurMaskDrawable` + dynamic `Oklch`-tinted AGSL `GlassBackdropDrawable`), IME/cutout/tablet `WindowInsets` avoidance, stepped `VibrationEffect.Composition` haptics, Android 16+ `OnBackAnimationCallback` predictive back preview, synchronized `46dp` frosted-glass **Plugin Rail** (`PluginRailWindow` with SVG icon loading, blind scrubbing, and quick menu), and zero-WebView **`NativeHudWindow`** (`vello_cpu` NEON Bitmap + HWUI `tnum` text).
   - Isolates each plugin in its own warm LRU-cached WebView sandbox under `https://<pluginId>.plugins.sideveil.local/` with `WebMessagePort`, COOP/COEP WebGPU support, loopback Dev Mode proxying, `PluginWebChromeClient` console forwarding, WebGPU VRAM-aware LRU eviction, and `onRenderProcessGone` crash self-healing.
4. **KernelSU WebUI Control Plane, Built-in Diagnostic Plugin (`webui/`), & Reference Examples (`examples/`)**
   - Built with published [`@gojona/*`](https://github.com/5jona/gojona-kit) npm packages (`@gojona/ui`, `@gojona/bridge`, `@gojona/sideveil-sdk`, `@gojona/config`, and `@gojona/contracts-gen`).
   - Control Plane (`webui/control` -> `webroot/`) edits `/data/system/sideveil/config.json` atomically (`inotify` hot-reload), manages registered plugins (enable/disable, priority, scope overrides, loopback Dev Mode, Host memory & ACL audit drawer, quick switch), and controls the live sidebar via `/data/adb/modules/sideveil/bin/sideveil-ctl`.
   - Built-in Diagnostic Plugin (`sideveil.diagnostic`, `webui/panel` -> `assets/panel/`) adheres to the standard `@gojona/sideveil-sdk` plugin contract with live dynamic `Oklch` CSS token sync.
   - Dual reference example plugins live in `examples/` (`vello-vector-scope` for COOP/COEP WebGPU rendering with live glass/transparent toggling and `native-perf-hud` for zero-WebView `uds-native` + `vello_cpu` NEON HUD rendering), with frontends compiled from `webui/src/plugins/`.

## Security & Runtime Separation

- **Root-only supervisor PIDs** (`supervisor.pid`, `sideveild.pid`) live in `/data/adb/sideveil/run` (`0:0`, `0700`).
- **Dual-channel UDS sockets** live in `/data/system/sideveil/run` (`0:1000`, `0711`):
  - `hub.sock` (`0:1000`, `0660`) — privileged peers (`root` CLI, `UID 1000` sensor, native KernelSU daemon plugins) verified via `SO_PEERCRED`.
  - `app.sock` (`0666`) — restricted channel for LSPosed target-app plugins verified via `SO_PEERCRED` UID-to-package matching against `/data/system/packages.list`.
- **Sensor DEX** (`sideveil-sensor.dex`) is staged atomically to `/data/system/sideveil/sideveil-sensor.dex` (`0:1000`, `0640`).
- **Host endpoint credentials** (`port`, `token`) are synced atomically with SELinux context preservation to `/data/user/0/pochita.sideveil.host/no_backup/daemon_endpoint.json` (`0600`).
- **Plugin data isolation** jails each plugin's `fs.read` / `fs.writeAtomic` calls under `/data/system/sideveil/data/<plugin_id>/` (owned by `root`, `0700`) and enforces manifest-declared capability ACLs (`storage`, `toast`, `system-exec`).

## Repository Layout

```text
sideveil/
├── contracts/                  # Canonical IDL specs, JSON Schemas, golden fixtures & generated/ts/
├── hub/
│   └── crates/
│       ├── sideveil-protocol/  # Shared Rust Manifest, JSON-RPC, HUD, Theme, Sensor & UDS types
│       ├── sideveil-plugin-sdk/# Async Rust SDK for uds-native / uds-app plugin daemons
│       ├── sideveil-gfx/       # Pure-Rust Linebender core (usvg + vello_cpu NEON + Oklch color)
│       ├── sideveil-hud-jni/   # arm64 JNI cdylib -> libsideveil_hud.so (zero-WebView HUD)
│       ├── sideveild/          # Rust core hub daemon -> bin/sideveild
│       └── sideveil-ctl/       # Native Rust CLI -> bin/sideveil-ctl
├── android/
│   ├── hidden-stubs/           # compileOnly Android 16+ @hide stubs
│   ├── protocol/               # Minimal Kotlin wire DTOs & RFC 6455 client codec
│   ├── sensor/                 # Thin UID 1000 app_process sensor -> bin/sideveil-sensor.dex
│   └── host-app/               # Headless priv-app overlay host -> SideVeilHost.apk
├── docs/
│   ├── ARCHITECTURE.md           # Full navigation map, data flows & protocol trust boundaries
│   ├── CHANGELOG.md              # Release history
│   └── architecture-decisions.md # Android 16+ UID 1000 boundaries, rendering notes & ADRs
├── examples/                   # Official reference plugins (vello-vector-scope, native-perf-hud)
├── module/                     # KernelSU static files (module.prop, config.json, *.sh, system/)
├── out/                        # Gitignored staging outputs (out/stage/) & KernelSU ZIPs
├── scripts/                    # Build, hot-reload, contract sync, version bump & E2E harness
├── webui/                      # React + Vite + Tailwind v4 Control Plane & Panel apps
├── AGENTS.md
└── LICENSE                     # GNU General Public License v3.0 (GPL-3.0-only)
```

## Tooling & Build Commands

Pinned tool versions live in [`mise.toml`](./mise.toml) (`java = 25`, `android-sdk = 23.0`, `pnpm = 12.22.0`) alongside Rust Edition 2024 (`aarch64-linux-android` via NDK 30).

```bash
# Run all version, contract, Rust, WebUI, and Android quality gates
mise run check

# Build a lean release archive (skips bundled example test plugins)
bash scripts/build.sh --mode release

# Build a debug archive (packages bundled example.vello-scope & example.native-perf-hud)
bash scripts/build.sh --mode debug

# Build both clean and debug archives in a single pass
bash scripts/build.sh --mode both

# Regenerate cross-language contract artifacts and optionally sync TS outputs upstream
mise run contracts:gen
bash scripts/sync-kit-contracts.sh [path/to/gojona-kit]

# Build + live-reload onto Android device via Strata container SSH (or on-device --local)
bash scripts/reload.sh --ssh <host|user@host>
```

### Build Modes (`--mode release|debug|both`)

- `--mode release`: Builds a lean KernelSU archive (`out/sideveil.zip`) containing only the Core Hub, System Sensor, Render Host APK, Control Plane WebUI, and built-in diagnostic panel, skipping the source-level reference plugins (`example.vello-scope` and `example.native-perf-hud`).
- `--mode debug` (default): Also compiles and bundles `example.vello-scope` and `example.native-perf-hud` (toggleable at runtime via `debug.setRuntimeMode`).
- `--mode both`: Compiles once and emits both `out/sideveil.zip` (lean) and `out/sideveil-debug.zip` (with bundled examples).

### Android Release Signing

`android/host-app` supports formal release APK signing via either an untracked local `android/keystore.properties` file or environment variables:

```properties
# android/keystore.properties (gitignored)
storeFile=/path/to/sideveil-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

For CI or environment-driven builds, export `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`. Set `REQUIRE_RELEASE_KEYSTORE=true` to fail the Gradle build if no release keystore is configured instead of falling back to the debug signing config.

## Runtime Notes

- `/data/adb` is `0700 root:root` on Android/KernelSU; shared runtime state (`config.json`, `run/`, `plugins/`, `data/`, staged `sideveil-sensor.dex`) lives under `/data/system/sideveil/`.
- `scripts/reload.sh` installs `SideVeilHost.apk` via `pm install -r -d` for immediate zero-reboot testing; flashing `out/sideveil.zip` mounts the `priv-app` and whitelist XMLs systemlessly after a reboot.

## License

SideVeil is licensed under the **GNU General Public License v3.0 only (`GPL-3.0-only`)**. See [`LICENSE`](./LICENSE) for the full license text.
