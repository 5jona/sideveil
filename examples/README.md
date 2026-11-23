# SideVeil Reference Example Plugins

This directory contains two bundled reference plugins demonstrating the two complementary Vello / Linebender tracks in SideVeil. Their frontends are authored in `webui/src/plugins/` using React 19 + TypeScript + Tailwind CSS v4 + `@gojona/ui` + `@gojona/sideveil-sdk` and compiled via Vite (`mise run webui:check` / `bash build.sh`) into each plugin's `web/` directory:

1. **`vello-vector-scope/`** (`example.vello-scope`):
   - Demonstrates **WebGPU + COOP/COEP (`crossOriginIsolated: true`) + live `native-glass` / `transparent` backdrop switching** inside SideVeil's isolated plugin WebView (`https://example.vello-scope.plugins.sideveil.local/`).
   - Verifies uncapped native display refresh-rate rendering (`navigator.gpu` WebGPU WGSL pipeline with automatic Canvas 2D fallback), `telemetry.snapshot`, `haptics.trigger`, live daemon theme + Material You Oklch CSS token sync, pinned floating mode (`usePresentationMode`), and bidirectional click-through synchronization.
   - Frontend sources live in `webui/src/plugins/vello-scope/` and build to `examples/vello-vector-scope/web/` via `pnpm build:vello-scope`.

2. **`native-perf-hud/`** (`example.native-perf-hud`):
   - Demonstrates **zero-WebView native vector HUD rendering** powered by a standalone Rust `uds-native` daemon connecting over `/data/system/sideveil/run/hub.sock`.
   - Samples Linux `/proc/stat`, `/proc/meminfo`, and `/sys/class/thermal/` and pushes theme-aware declarative `NativeHudScene` frames (Bezier `Sparkline` + `ProgressBar` + `RadialGauge` + HWUI `tnum` text) directly to `libsideveil_hud.so` (`vello_cpu` NEON + `libjnigraphics.so`) over the ephemeral WS channel.
   - Frontend sources live in `webui/src/plugins/native-perf-hud/` and build to `examples/native-perf-hud/web/` via `pnpm build:native-perf-hud`; build the arm64 daemon binary via `mise run examples:build`.
