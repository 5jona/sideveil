# Sideveil

Generic Android sidebar micro-frontend host packaged as a KernelSU module.
Target baseline: Android 16–17 (API 36–37). Built around a `UID 1000` privileged input/task daemon paired with an AMS-managed headless `priv-app` overlay host running a warm-resident transparent WebView.

## Architecture

1. **Privileged Daemon (`android/daemon`, `UID 1000` `app_process64`)**
   - Initializes `ApplicationSharedMemory` and monitors foreground apps via `ITaskStackListener` + 4-argument `ActivityTaskManager.getTasks` + periodic reconciliation.
   - Intercepts edge drags using `InputManagerGlobal.monitorGestureInput` + `InputMonitor.pilferPointers` so normal clicks and vertical scrolls pass straight through.
   - Grants `OP_SYSTEM_ALERT_WINDOW` and Doze exemption to `pochita.sideveil.host` via `IAppOpsService` and `IDeviceIdleController`.
   - Serves an authenticated loopback HTTP/1.1 + full-duplex RFC 6455 WebSocket server (`127.0.0.1:0` with cryptographic session token under `/data/system/sideveil/run/daemon.env`).
2. **Headless Render Host APK (`android/host-app`, `pochita.sideveil.host`)**
   - Packaged into `/system/priv-app/SideveilHost/SideveilHost.apk` with `privapp-permissions` and `sysconfig` allowlists.
   - Maintains a click-through `TYPE_APPLICATION_OVERLAY` edge indicator capsule and a warm `1x1` `INVISIBLE` overlay container with `SpringAnimation` physics, hardware-accelerated overlay rendering using `Window.setBackgroundBlurRadius()` paired with the AGSL `RuntimeShader` optical frosted-glass backdrop (`GlassBackdropDrawable`), and IME focus handoff.
   - Preloads a transparent WebView rendering the built-in diagnostic panel (`assets/panel/`) over `@pochita/bridge` (`WebMessagePort` + `window.__POCHITA_HOST__`).
3. **KernelSU WebUI Control Plane (`webui/control` -> `webroot/`)**
   - Built with `@pochita/ui`, `@pochita/bridge`, and `@pochita/config` from `pochita-webui-kit`.
   - Edits `/data/system/sideveil/config.json` atomically (`FileObserver` hot-reload) and controls the live sidebar via `/data/adb/modules/sideveil/bin/sideveil-ctl`.

## Security & Runtime Separation

The runtime enforces strict two-tier privilege and filesystem isolation:
- **Root-only supervisor PIDs** (`supervisor.pid`, `hostwatch.pid`) live in `/data/adb/sideveil/run` (`0:0`, `0700`).
- **Daemon runtime files** live in `/data/system/sideveil/run` (`1000:1000`, `0700`), with `sideveil-daemon.dex` deployed atomically as `0:1000` (`0640`, read-only to `UID 1000`).
- `daemon.env` is parsed line-by-line with strict regex validation (never sourced directly by the root shell).
- **Endpoint credentials** (`port`, `token`) are synced atomically to `/data/user/0/pochita.sideveil.host/no_backup/daemon_endpoint.json` (`0600`) instead of being exposed via `am` CLI intent extras.

## Repository Layout

```text
sideveil/
├── android/
│   ├── hidden-stubs/    # compileOnly Android 16–17 @hide stubs
│   ├── shared/          # Config models, JSON-RPC 2.0 protocol, RFC 6455 codec
│   ├── daemon/          # UID 1000 app_process daemon -> bin/sideveil-daemon.dex
│   └── host-app/        # Headless priv-app overlay host -> SideveilHost.apk
├── bin/                 # sideveil-ctl root CLI + compiled daemon dex
├── system/              # KernelSU Magic Mount priv-app & sysconfig XMLs
├── webui/               # React + Vite + Tailwind v4 Control Plane & Panel apps
├── build.sh             # Full WebUI + Gradle + KernelSU ZIP packager
├── reload.sh            # Zero-reboot ADB push/install/restart helper
├── config.json          # Default configuration template
├── module.prop
├── post-fs-data.sh
├── service.sh
├── customize.sh
└── uninstall.sh
```

## Tooling & Build Commands

Pinned tool versions live in [`mise.toml`](./mise.toml) (`java = 25`, `android-sdk = 23.0`, `pnpm = 12.22.0`).

```bash
# Build everything and generate out/sideveil.zip
bash build.sh

# Build + live-reload onto ADB device without rebooting
bash reload.sh 10.0.0.12:5555

# Verify Kotlin formatting, unit tests, DEX, and release APK
cd android && mise exec -- ./gradlew spotlessCheck :shared:test :daemon:test :host-app:testDebugUnitTest :daemon:buildDex :host-app:assembleRelease

# Typecheck and build Control Plane + Built-in Panel WebUIs
cd webui && mise exec -- pnpm typecheck && mise exec -- pnpm build
```

## Runtime Notes

- `/data/adb` is `0700 root:root` on Android/KernelSU; runtime state (`config.json`, `run/daemon.env`, copied `sideveil-daemon.dex`) is maintained in `/data/system/sideveil/` (`1000:1000`), accessible to both root (`sideveil-ctl`, KSU WebUI) and the `UID 1000` daemon.
- `reload.sh` installs `SideveilHost.apk` via `pm install -r -d` for immediate zero-reboot testing; flashing `out/sideveil.zip` mounts the `priv-app` and whitelist XMLs systemlessly after a reboot.
