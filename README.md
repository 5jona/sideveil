# Sideveil

Generic Android sidebar microkernel host packaged as a KernelSU module.
Target baseline: Android 16–17 (API 36–37, arm64) on Linux GKI 6.12+. Built around a **Rust Core Hub (`sideveild`)**, a **thin `UID 1000` Kotlin System Sensor (`:sensor`)**, and an AMS-managed headless `priv-app` render host (`SideveilHost.apk`).

## Architecture

1. **Rust Core Hub (`hub/crates/sideveild` & `hub/crates/sideveil-ctl`)**
   - Supervises `sideveil-sensor` directly (`UID 1000` drop via `pre_exec`) and `SideveilHost` via Linux `pidfd_open` + Tokio `AsyncFd` (`epoll`) with zero shell/curl polling.
   - Manages `/data/system/sideveil/config.json` with atomic persistence and `inotify` hot-reload.
   - Hosts the open **Plugin Registry** (`inotify`-watched `/data/system/sideveil/plugins/<plugin_id>/manifest.json`, persistent overrides & per-app MRU in `plugins.json`, capability ACLs for `storage`/`toast`/`system-exec`, authenticated loopback static asset serving, and dynamic `uds-native` / `uds-app` backend RPC multiplexing).
   - Exposes a dual-channel Unix Domain Socket gateway (`hub.sock` & `app.sock`) gated by kernel `SO_PEERCRED`, paired with an authenticated loopback WebSocket (`127.0.0.1:0/ws`) and dual-priority outbound queues (coalescing ephemeral gesture moves while preserving critical control/RPC frames).
   - Provides the native Rust CLI `bin/sideveil-ctl` communicating directly over `hub.sock` (including `plugins list|reload|enable|disable|priority|scope|select`).
2. **Thin System Sensor (`android/sensor`, `UID 1000` `app_process64`)**
   - Initializes `ApplicationSharedMemory` and monitors foreground apps via `ITaskStackListener` + 4-argument `ActivityTaskManager.getTasks`.
   - Intercepts edge drags using `InputManagerGlobal.monitorGestureInput` + `InputMonitor.pilferPointers` backed by a pure JVM `EdgeGestureStateMachine`.
   - Grants `OP_SYSTEM_ALERT_WINDOW` and Doze exemption to `pochita.sideveil.host` via `IAppOpsService` and `IDeviceIdleController`.
   - Connects persistently to `sideveild` via `hub.sock` NDJSON framing.
3. **Headless Render Host APK (`android/host-app`, `pochita.sideveil.host`)**
   - Packaged into `/system/priv-app/SideveilHost/SideveilHost.apk` with `privapp-permissions` and `sysconfig` allowlists.
   - Maintains a click-through `TYPE_APPLICATION_OVERLAY` edge indicator capsule, a warm `1x1` `INVISIBLE` overlay container with `SpringAnimation` physics, hardware cross-window blur (`BlurMaskDrawable` + AGSL `GlassBackdropDrawable` with per-plugin `native-glass`/`transparent`/`opaque` backdrop overrides), IME focus handoff, and an automatic `46dp` frosted-glass **Plugin Rail** (`PluginRailWindow`) shown only when 2+ plugins match the foreground app.
   - Isolates each plugin in its own warm LRU-cached WebView sandbox under `https://<pluginId>.plugins.sideveil.local/` with `WebMessagePort`, server-stamped `pluginId` identity, Android 16–17 `OnBackInvokedCallback` back interception, and `onRenderProcessGone` crash self-healing.
4. **KernelSU WebUI Control Plane & Built-in Diagnostic Plugin (`webui/`)**
   - Built with `@pochita/ui`, `@pochita/bridge`, `@pochita/sideveil-sdk`, and `@pochita/config` from `pochita-webui-kit`.
   - Control Plane (`webui/control` -> `webroot/`) edits `/data/system/sideveil/config.json` atomically (`inotify` hot-reload), manages registered plugins (enable/disable, priority, trigger scope overrides, rescan, quick switch), and controls the live sidebar via `/data/adb/modules/sideveil/bin/sideveil-ctl`.
   - Built-in Diagnostic Plugin (`sideveil.diagnostic`, `webui/panel` -> `assets/panel/`) adheres to the standard `@pochita/sideveil-sdk` plugin contract.

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
├── hub/
│   └── crates/
│       ├── sideveil-protocol/  # Shared Rust Manifest, JSON-RPC, Sensor & UDS wire types
│       ├── sideveild/          # Rust core hub daemon -> bin/sideveild
│       └── sideveil-ctl/       # Native Rust CLI -> bin/sideveil-ctl
├── android/
│   ├── hidden-stubs/           # compileOnly Android 16–17 @hide stubs
│   ├── protocol/               # Minimal Kotlin wire DTOs & RFC 6455 client codec
│   ├── sensor/                 # Thin UID 1000 app_process sensor -> bin/sideveil-sensor.dex
│   └── host-app/               # Headless priv-app overlay host -> SideveilHost.apk
├── system/                     # KernelSU Magic Mount priv-app & sysconfig XMLs
├── webui/                      # React + Vite + Tailwind v4 Control Plane & Panel apps
├── build.sh                    # Full Rust + WebUI + Gradle + KernelSU ZIP packager
├── reload.sh                   # Zero-reboot ADB push/install/restart helper
├── config.json                 # Default configuration template
├── module.prop
├── post-fs-data.sh
├── service.sh
├── customize.sh
└── uninstall.sh
```

## Tooling & Build Commands

Pinned tool versions live in [`mise.toml`](./mise.toml) (`java = 25`, `android-sdk = 23.0`, `pnpm = 12.22.0`) alongside Rust Edition 2024 (`aarch64-linux-android` via NDK 30).

```bash
# Run all Rust, WebUI, and Android quality gates
mise run check

# Build everything and generate out/sideveil.zip
bash build.sh

# Build + live-reload onto ADB device without rebooting
bash reload.sh <device-serial>
```

## Runtime Notes

- `/data/adb` is `0700 root:root` on Android/KernelSU; shared runtime state (`config.json`, `run/`, `plugins/`, `data/`, staged `sideveil-sensor.dex`) lives under `/data/system/sideveil/`.
- `reload.sh` installs `SideveilHost.apk` via `pm install -r -d` for immediate zero-reboot testing; flashing `out/sideveil.zip` mounts the `priv-app` and whitelist XMLs systemlessly after a reboot.
