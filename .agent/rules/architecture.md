# Sideveil — Architecture, Naming & Engineering Standards

## 1. System Topology & Responsibility Boundaries
Sideveil is an open microkernel sidebar host targeting **Android 16–17 (API 36–37, arm64)** on **Linux GKI 6.12+**. It enforces strict privilege and responsibility separation across three runtime roles:

1. **Rust Core Hub (`sideveild`, `sideveil/hub/`)**:
   - Owns process supervision (`pidfd_open` + `epoll`, zero shell polling), global configuration (`inotify` + atomic writes), plugin registry & lifecycle, dual-channel UDS IPC routing (`SO_PEERCRED`), permission ACLs, and isolated plugin static asset serving.
   - Companion native CLI `sideveil-ctl` is compiled from the same Cargo workspace and talks directly over UDS.
2. **Thin Kotlin System Sensor (`:sensor`, `UID 1000` `app_process64`)**:
   - Restricted strictly to Android Framework operations that require `UID 1000` Java/Hidden APIs: foreground app detection (`ITaskStackListener` + fallback), global edge gesture interception (`InputManagerGlobal.monitorGestureInput` + `pilferPointers`), and AppOps / power-save allowlisting.
   - Must never host HTTP/WS servers, config persistence, plugin registries, or UI windows.
3. **Headless Render Host (`:host-app` / `SideveilHost.apk`, `priv-app`)**:
   - AMS-managed foreground service (`HostService`) owning native overlay windows: edge indicator, multi-plugin **Plugin Rail**, sidebar container with five-channel spring physics, hardware cross-window blur + AGSL optical glass backdrop, and isolated WebView sandboxes.
   - Never contains plugin business logic or global config authority.

## 2. Repository & Runtime Naming Conventions
- **Rust Workspace (`sideveil/hub/`)**:
  - `crates/sideveil-protocol` — Shared Manifest, JSON-RPC 2.0, HUD, Theme, event, and ACL wire types.
  - `crates/sideveil-gfx` — Shared pure-Rust Linebender (`vello_cpu` NEON, `kurbo`, `peniko`, `color`, optional `usvg`) SVG icon rasterizer, `Oklch` palette synthesizer, and `HudCanvas` renderer.
  - `crates/sideveil-hud-jni` — Lightweight `arm64-v8a` `cdylib` (`libsideveil_hud.so`) linking `libjnigraphics.so` for zero-WebView native vector HUDs.
  - `crates/sideveild` — Core hub daemon binary.
  - `crates/sideveil-ctl` — Native CLI binary (kept decoupled from `sideveil-gfx`).
- **Android Modules (`sideveil/android/`)**:
  - `:hidden-stubs` — Compile-only Android hidden API stubs.
  - `:protocol` — Minimal wire DTOs shared by `:sensor` and `:host-app`.
  - `:sensor` — Thin `UID 1000` sensor DEX (`bin/sideveil-sensor.dex`).
  - `:host-app` — `pochita.sideveil.host` (`SideveilHost.apk`).
- **WebUI (`sideveil/webui/`)**:
  - `control` — KernelSU WebUI control plane (global settings + plugin manager).
  - `builtin-diag` — Built-in diagnostic plugin adhering to the exact same contract as external plugins.
- **Device Runtime Paths**:
  - `/data/system/sideveil/config.json` — Authoritative host configuration.
  - `/data/system/sideveil/plugins/<plugin_id>/` — Plugin directory (or symlink) containing `manifest.json` and optional `web/`.
  - `/data/system/sideveil/data/<plugin_id>/` — Per-plugin isolated persistent storage.
  - `/data/system/sideveil/run/hub.sock` — Privileged UDS (Sensor, Host, CLI, Native Daemon plugins).
  - `/data/system/sideveil/run/app.sock` — Restricted UDS (`0666`) for LSPosed target-app processes, strictly gated by `SO_PEERCRED` UID-to-package verification.

## 3. File Size & Modularity Limits
- **Soft limit**: **<= 300 lines** per source file.
- **Hard limit**: **400 lines** per source file (exceptions require explicit justification, e.g. generated code). Split by cohesion into focused submodules before crossing the hard limit.
- **Pure logic vs. platform I/O**: Extract state machines, kinematics, protocol codecs, and config validators into pure, platform-agnostic modules so they can be unit-tested without Android Framework or live sockets.
- **Single source of truth**: Never duplicate version strings, theme allowlists, or protocol constants across modules or repositories.

## 4. Language & Privacy Policy
- **Engineering internals (English only)**: All source code, identifiers, comments, docstrings, developer logs/errors, commit messages, and `.agent/` rules must be written in clear, objective English.
- **User-facing UI copy (Simplified Chinese)**: All visible web/Android UI copy (titles, labels, descriptions, toasts, status badges) must use concise, natural Simplified Chinese (`zh-CN`).
- **Privacy**: Keep shared examples and tests free of personal paths, device serials, or private credentials.

## 5. Verification Gates
Every change must pass the relevant toolchain gates before completion:
- **Rust**: `cargo fmt --check`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo test --workspace`.
- **Android**: `./android/gradlew spotlessCheck detekt :host-app:lint test`.
- **WebUI**: `pnpm check` (Biome lint/format + TypeScript `typecheck` + unit `test` + `build`).
- **Packaging**: `./build.sh` produces a clean KernelSU module archive; live device changes are verified via `./reload.sh` and `tmp/verify_live.py`.
