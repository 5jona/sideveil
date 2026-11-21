# SideVeil — Agent Guidelines

Read `.agent/rules.md` for complete engineering standards and `docs/architecture-decisions.md` for Android 16+ platform constraints and rendering invariants.

## Architecture & Role Boundaries
- **Core Hub (`hub/`)**: Rust `uid 0` supervisor (`pidfd` + `epoll`, zero polling), authoritative config (`inotify` + atomic persistence), plugin registry & ACLs, UDS IPC (`SO_PEERCRED`), SVG/Oklch graphics (`sideveil-gfx`), and loopback HTTP asset server.
- **System Sensor (`android/sensor/`)**: `UID 1000` single-DEX `app_process64` strictly for framework task tracking, edge gesture interception (`monitorGestureInput` + `pilferPointers`), and AppOps/Doze allowlisting. Zero native libs, zero UI, zero config authority.
- **Render Host (`android/host-app/`)**: AMS-managed `priv-app` service owning overlay `Dialog` windows, cross-window blur + AGSL backdrops, native vector HUDs (`libsideveil_hud.so`), and isolated per-plugin WebViews (`https://<pluginId>.plugins.sideveil.local/`).
- **WebUI & Bundled Plugins (`webui/`, `examples/`)**: All first-party WebUIs and bundled example plugin frontends are authored in `webui/` with React + TypeScript + Tailwind v4 + `@pochita/ui` + `@pochita/bridge` + `@pochita/sideveil-sdk` + `createPochitaViteConfig`. Never hand-code standalone inline HTML/CSS/JS bundles for first-party or bundled plugins.

## Non-Negotiable Invariants
- **File Size**: Keep every source file at or below 300 lines (hard limit 400 lines). Split by cohesion before crossing the limit.
- **Single Source of Truth**: Never duplicate protocol event/method constants, schemas, or theme allowlists across modules.
- **Uncapped Refresh Rate**: Never hardcode FPS or refresh-rate ceilings anywhere. Visible overlay windows dynamically request the display's maximum supported refresh rate at the current physical resolution and clear votes when hidden.
- **Language & Privacy**: Objective English for all code, comments, logs, commit messages, and docs; Simplified Chinese (`zh-CN`) for user-facing UI copy. Never commit device serials, device-specific hardware specs, or personal paths.
- **Commit Messages**: Use Conventional Commits (`<type>(<scope>): <summary>`). Keep small focused changes single-line; include 2–5 concise bullet points explaining key changes and rationale for multi-file or architectural commits.

## Build & Verification Commands
- Full quality gate: `mise run check` (Rust workspace + bundled Rust plugins, WebUI lint/typecheck/tests/builds + standards guard tests, Android Spotless/Detekt/Lint/tests/DEX/APK).
- Full module archive build: `bash build.sh` (outputs `out/sideveil.zip`).
- Live device hot-reload (when requested): `bash reload.sh --skip-build` followed by `python3 tmp/verify_live.py`.
