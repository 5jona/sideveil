# SideVeil Reference Example Plugins

This directory contains two independent, self-contained reference plugins demonstrating the two complementary Vello / Linebender tracks in SideVeil:

1. **`vello-vector-scope/`** (`example.vello-scope`):
   - Demonstrates **WebGPU + COOP/COEP (`crossOriginIsolated: true`) + transparent backdrop (`backdrop: "transparent"`)** inside SideVeil's isolated plugin WebView (`https://example.vello-scope.plugins.sideveil.local/`).
   - Verifies live `window.crossOriginIsolated`, `SharedArrayBuffer`, `navigator.gpu` WebGPU rendering (with automatic Canvas 2D fallback), `telemetry.snapshot`, `haptics.trigger`, `theme.paletteUpdated` Oklch CSS token sync, and `setPresentationMode('pinned')`.
   - Ready to drop directly into `/data/system/sideveil/plugins/example.vello-scope/` with zero build step, or replace the inline WGSL shader with a `wasm32-unknown-unknown` Rust `vello` WebGPU bundle targeting the same `canvas.getContext('webgpu')` surface.

2. **`native-perf-hud/`** (`example.native-perf-hud`):
   - Demonstrates **zero-WebView native vector HUD rendering** powered by a standalone Rust `uds-native` daemon connecting over `/data/system/sideveil/run/hub.sock`.
   - Samples Linux `/proc/stat`, `/proc/meminfo`, and `/sys/class/thermal/` and pushes declarative `NativeHudScene` frames (Bezier `Sparkline` + `ProgressBar` + `RadialGauge` + HWUI `tnum` text) directly to `libsideveil_hud.so` (`vello_cpu` NEON + `libjnigraphics.so`) over the ephemeral WS channel.
   - Build the arm64 daemon binary via `mise run examples:build`.
