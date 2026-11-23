# SideVeil — Agent Guidelines

For the full repository navigation map, runtime data flows, ABI-pinned FQCNs, and protocol trust boundaries, see `ARCHITECTURE.md`. For empirical Android 16+ (API 36+, arm64) platform constraints and rendering decisions, see `docs/architecture-decisions.md`. Subsystem-specific rules live in `hub/AGENTS.md`, `android/AGENTS.md`, and `webui/AGENTS.md`.

## Architecture & Role Boundaries
SideVeil enforces strict separation across four layers:
- **Core Hub (`hub/`)**: Rust `uid 0` supervisor (`pidfd` + `epoll`, zero polling), global configuration authority (`inotify` + atomic persistence), plugin registry & capability ACLs, UDS IPC routing (`SO_PEERCRED`), SVG/Oklch graphics (`sideveil-gfx`), and loopback HTTP asset serving.
- **System Sensor (`android/sensor/`)**: `UID 1000` single-DEX (`classes.dex` only) `app_process64` strictly for foreground task tracking, global edge gesture interception (`monitorGestureInput` + `pilferPointers`), and AppOps/Doze allowlisting. Zero native libraries, zero UI, zero config authority.
- **Render Host (`android/host-app/`)**: AMS-managed `priv-app` service owning overlay `Dialog` windows, physical motion, cross-window blur + AGSL backdrops, lightweight native vector HUDs (`libsideveil_hud.so`), and isolated per-plugin WebView sandboxes (`https://<pluginId>.plugins.sideveil.local/`). Never embeds plugin business logic or direct disk config authority.
- **WebUI & Bundled Plugins (`webui/`, `examples/`)**: Keep the host runtime agnostic of third-party plugin UI frameworks (enforce only `manifest.json` and JSON-RPC 2.0). All first-party and bundled example plugin UIs are authored in `webui/` with React + TypeScript + Tailwind CSS v4 + `@gojona/*` + `createPochitaViteConfig`, never hand-coded inline HTML/CSS/JS bundles. Scope plugin storage to `/data/system/sideveil/data/<plugin_id>/`.

## Non-Negotiable Invariants
- **File Size & Modularity**: Keep every source file `<= 300` lines (hard limit `400`). Split by cohesion before approaching the limit. Extract pure logic (state machines, spring kinematics, protocol codecs, palette/vector synthesis) into host-unit-tested modules without Android Framework or live socket dependencies.
- **Single Source of Truth**: Never duplicate protocol event/method constants, schema definitions, or theme allowlists across modules.
- **Uncapped Adaptive Refresh Rate**: Never hardcode FPS or refresh-rate ceilings in native or web code. Visible overlay windows dynamically request the display's maximum supported refresh rate at the current physical resolution and clear votes when hidden.
- **Language & Privacy**: Objective English for all code, comments, logs, commit messages, and engineering docs; Simplified Chinese (`zh-CN`) for user-facing UI copy. Never commit device serials, hardware-specific specs, private IPs, or personal paths.
- **Commit Messages**: Conventional Commits (`<type>(<scope>): <imperative summary>`, `<= 72` chars). Single-line for small focused changes; 2–5 concise bullets explaining key changes and rationale for multi-file or architectural commits.

## Build & Verification Commands
- Full quality gate: `mise run check` (`hub:check`, `webui:check`, `android:check`) with zero warnings or errors.
- Full module archive build: `bash build.sh` (outputs `out/sideveil.zip`).
- Live device hot-reload & verification (when requested): `bash reload.sh --skip-build --ssh <host|user@host>` (or `STRATA_SSH_HOST` / `--local`) followed by `python3 scripts/verify-live.py`.
