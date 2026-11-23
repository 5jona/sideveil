# SideVeil Architecture & Navigation Map

SideVeil is a generic Android 16+ (API 36+, arm64, Linux GKI 6.12+) sidebar microkernel packaged as a KernelSU module.
Use this document as the primary codebase navigation map; empirical Android 16+ platform findings and ADRs live in [`docs/architecture-decisions.md`](docs/architecture-decisions.md), and subsystem-specific coding rules live in [`AGENTS.md`](AGENTS.md), [`hub/AGENTS.md`](hub/AGENTS.md), [`android/AGENTS.md`](android/AGENTS.md), and [`webui/AGENTS.md`](webui/AGENTS.md).

## 1. Four-Layer System Topology

SideVeil separates privileges, hidden Android APIs, overlay window rendering, and plugin code across four strictly isolated layers backed by a shared Scheme C contract registry (`contracts/`):

```text
+------------------------------------------------------------------------------+
| Layer 4: WebUI & Plugins  (webui/, examples/)                                |
|   Control Plane (webroot/) | Built-in Diagnostic | 3rd-party / Example UIs   |
+---------------------+--------------------------------------+-----------------+
                      | sideveil-ctl (hub.sock)              | WebMessagePort (JSON-RPC 2.0)
                      v                                      v
+--------------------------------------+    +----------------------------------+
| Layer 1: Core Hub (sideveild, UID 0) |<-->| Layer 3: Render Host (priv-app)  |
|   - pidfd + epoll supervisor         | WS |   pochita.sideveil.host          |
|   - config & plugin inotify registry |HTTP|   - Overlay Dialogs + Blur/AGSL  |
|   - SO_PEERCRED UDS gateway & ACLs   |    |   - Warm LRU WebView sandboxes   |
|   - sideveil-gfx (SVG/Oklch/NEON)    |    |   - libsideveil_hud.so (JNI HUD) |
+---------------------+----------------+    +----------------------------------+
                      | hub.sock (NDJSON, SO_PEERCRED UID 1000)
                      v
+------------------------------------------------------------------------------+
| Layer 2: System Sensor (:sensor, UID 1000 app_process64, single classes.dex) |
|   - Foreground Activity tracker (ITaskStackListener + ATM.getTasks)          |
|   - Edge gesture monitor (monitorGestureInput + pilferPointers)              |
|   - AppOps & Doze allowlister (IAppOpsService / IDeviceIdleController)       |
|   - Zero native libs, zero UI views, zero disk config authority              |
+------------------------------------------------------------------------------+
```

- **Core Hub (`hub/`)**: Sole authority for `/data/system/sideveil/config.json`, plugin manifests and persistent overrides (`plugins.json`), capability ACLs, zero-polling process supervision, and authenticated loopback HTTP/WebSocket serving.
- **System Sensor (`android/sensor/`)**: Spawned directly by `sideveild` via `pre_exec` UID 1000 drop (`setgroups`/`setgid`/`setuid`) to avoid kernel root-guard warnings. Restricted strictly to hidden framework APIs requiring `UID 1000`.
- **Render Host (`android/host-app/`)**: AMS-managed `/system/priv-app/SideVeilHost/SideVeilHost.apk` owning `TYPE_APPLICATION_OVERLAY` `Dialog` windows, spring choreography, cross-window blur + AGSL backdrops, isolated plugin WebViews, and zero-WebView native HUD rendering. Never embeds plugin business logic or config disk writes.
- **WebUI & Plugins (`webui/`, `examples/`)**: First-party UIs and bundled examples use React + TypeScript + Tailwind CSS v4 + `@pochita/*`. Third-party plugins remain framework-agnostic and bind only to `manifest.json` + JSON-RPC 2.0.

## 2. Repository Navigation Map

### Cross-Language Contracts & Scheme C Codegen

| Path | Generator / Gate | Generated Targets & Role |
| --- | --- | --- |
| `contracts/*.contract.json` | `@pochita/contracts-gen` (`mise run contracts:check`) | Canonical wire enums, RPC method/event/error constants, config bounds, and theme/HUD contracts. |
| `contracts/schemas/*.schema.json` | JSON Schema 2020-12 | Machine-validated config, status, event, and theme/HUD schemas (`contracts/fixtures/` golden tests). |
| Generated outputs (do not hand-edit) | `mise run contracts:gen` | Rust `hub/crates/sideveil-protocol/src/generated/`, Kotlin `android/protocol/.../generated/`, TS `@pochita/bridge` + `@pochita/sideveil-sdk`. |

### Rust Workspace & Example Daemons

| Crate / Path | Binary / Artifact | Core Responsibilities & Key Modules |
| --- | --- | --- |
| `hub/crates/sideveil-protocol/` | rlib | Shared wire types: `generated/`, `config/{patches,sections}`, `plugin/{rpc_params,url_validation,validation}`, `rpc/`, `sensor.rs`, `telemetry.rs`, `theme.rs`, `hud.rs`, `uds.rs`. |
| `hub/crates/sideveil-gfx/` | rlib | Pure-Rust Linebender core: `svg_icon.rs` + `svg_icon/paint.rs` (`usvg` + `vello_cpu` NEON), `palette.rs` (WCAG Oklch), `hud_renderer/{gauges,path_parser,primitives,sparkline}`. |
| `hub/crates/sideveil-hud-jni/` | `libsideveil_hud.so` | arm64 JNI `cdylib` rasterizing `NativeHudScene` JSON into locked `ARGB_8888` `Bitmap`s via `libjnigraphics`. |
| `hub/crates/sideveild/` | `bin/sideveild` | Hub daemon: `supervisor/{sensor,host_wake,plugin_daemons}` (`pidfd`), `config_store.rs`, `inotify.rs`, `plugin_registry/{dev_url,embed_override,error,installer,overrides,query,store,web_embed}`, `plugin_acl.rs`, `plugin_backends.rs`, `peercred.rs`, `uds_gateway/{framing,backend_session}`, `host_ws.rs`, `asset_server/{http,icon_cache,path}`, `outbound_queue.rs`, `rpc/`. |
| `hub/crates/sideveil-ctl/` | `bin/sideveil-ctl` | Native CLI talking JSON-RPC 2.0 over `hub.sock` (`cli/{plugins_cmd,plugins_embed_cmd,hud_cmd,debug_cmd,logs_cmd}`). |
| `examples/native-perf-hud/` | `bin/sideveil-example-perf-hud` | Reference `uds-native` daemon sampling `/proc` & thermal metrics and streaming `NativeHudScene` frames. |

### Android Gradle Modules & Sub-Packages

| Module | Output | Sub-Packages & Key Responsibilities |
| --- | --- | --- |
| `android/hidden-stubs/` | compileOnly JAR | Android 16+ `@hide` stubs (`ActivityThread`, `InputManagerGlobal`, `InputMonitor`, `ApplicationSharedMemory`, `ServiceManager`). |
| `android/protocol/` | JVM JAR | Contract-generated (`generated/`) + hand-authored wire DTOs (`Protocol.kt`, `PluginProtocol.kt`, `SensorProtocol.kt`, `HudProtocol.kt`, `ThemeProtocol.kt`, `DebugProtocol.kt`, `SideVeilConfig.kt`) and RFC 6455 codec (`WebSocketCodec.kt`, `WebSocketHandshake.kt`, `WebSocketModels.kt`). |
| `android/sensor/` | `bin/sideveil-sensor.dex` | Root `SensorMain.kt` + `SensorLog.kt`, `gesture/` (`EdgeGestureMonitor`, `EdgeGestureStateMachine`, `EdgeGestureMath`, `EdgeRepositionTracker`, `GestureDiagnosticsTracker`), `system/` (`ForegroundMonitor`, `DisplayGeometryWatcher`, `NetworkContextWatcher`, `AppOpsManagerBridge`), `ipc/` (`HubUdsClient`). |
| `android/host-app/` | `SideVeilHost.apk` | Root `HostService.kt` + `NativeHudRenderer.kt`, plus 9 domain sub-packages: `service/` (`DaemonClient`, `HostOverlayCoordinator`, `HostGestureCoordinator`, `HostEventDispatcher`, `HostServiceBridge`), `overlay/` (`BlurMaskDrawable`, `GlassBackdropDrawable`, `DynamicThemeExtractor`, `OverlayWindowFlags`, `EdgeHapticsController`), `edge/` (`EdgeIndicatorWindow`, `EdgeIndicatorAppearance`), `panel/` (`SidebarPanelWindow`, `PanelMotionChoreographer`, `PanelSpringController`, `PanelDragStateMachine`, `PanelInsetsCoordinator`), `rail/` (`PluginRailWindow`, `PluginRailSurfaceController`, `RailSlotFactory`), `pinned/` (`PinnedContainerWindow`, `PinnedChromeViews`, `PinnedWindowLayout`), `hud/` (`NativeHudWindow`, `NativeHudView`, `NativeHudBackdropController`), `plugin/` (`PluginWebViewManager`, `PluginWebViewPool`, `PluginWebMessageBridge`, `PluginAssetFetcher`, `PluginLocalRpcHandler`, `EmbedUrlRouter`, `PluginNetworkPolicy`), `debug/` (`HostDebugInspector`, `OverlayDebugSnapshots`, `WindowFrameMetricsTracker`). |

### WebUI Entries, Examples & Tooling Scripts

| Area | Path | Output / Role |
| --- | --- | --- |
| Control Plane WebUI | `webui/control/` + `webui/src/control/` | Builds via `vite.control.config.mjs` -> `webroot/` (KernelSU WebUI manager). |
| Diagnostic Panel | `webui/panel/` + `webui/src/panel/` | Builds via `vite.panel.config.mjs` -> `android/host-app/src/main/assets/panel/` (`sideveil.diagnostic`). |
| Vello Vector Scope | `webui/plugins/vello-scope/` + `webui/src/plugins/vello-scope/` | Builds via `vite.vello-scope.config.mjs` -> `examples/vello-vector-scope/web/` (COOP/COEP WebGPU + Canvas 2D renderer). |
| Native Perf HUD UI | `webui/plugins/native-perf-hud/` + `webui/src/plugins/native-perf-hud/` | Builds via `vite.native-perf-hud.config.mjs` -> `examples/native-perf-hud/web/`. |
| Module Lifecycle | `service.sh`, `post-fs-data.sh`, `customize.sh`, `uninstall.sh`, `system/` | KernelSU Magic Mount, privapp-permissions, sysconfig allowlist, boot launch. |
| Build & Verification | `build.sh`, `reload.sh`, `scripts/lib/module-env.sh`, `scripts/reload-*.sh`, `scripts/verify-live.py`, `scripts/verify_live/` | Shared `run_in_env` + `BUNDLED_PLUGINS`, full module archive build, zero-reboot incremental deploy, and live E2E checks. |

### ABI-Pinned Fully Qualified Class Names

Do not rename or move these three classes without coordinated updates across manifests, Rust supervisor code, and JNI exports:

1. `pochita.sideveil.host.HostService` — Pinned by `android/host-app/src/main/AndroidManifest.xml`, `hub/crates/sideveild/src/supervisor/host_wake.rs`, and `reload.sh`.
2. `pochita.sideveil.host.NativeHudRenderer` — Pinned by JNI C symbol names `Java_pochita_sideveil_host_NativeHudRenderer_nativeRenderScene` and `nativeResetCache` in `hub/crates/sideveil-hud-jni/src/lib.rs`.
3. `pochita.sideveil.sensor.SensorMain` — Pinned by `SENSOR_MAIN_CLASS` in `hub/crates/sideveild/src/supervisor/sensor.rs` for `/system/bin/app_process64` startup.

### Task-to-File Quick Lookup

| If you are changing... | Start here |
| --- | --- |
| Wire contracts, config bounds, or RPC/event constants | `contracts/*.contract.json`, `contracts/schemas/`, run `mise run contracts:gen && mise run contracts:check` |
| Edge trigger geometry, cancel threshold, or double-swipe | `android/sensor/.../gesture/EdgeGestureStateMachine.kt`, `gesture/EdgeGestureMonitor.kt` |
| Foreground app / Activity scope matching | `android/sensor/.../system/ForegroundMonitor.kt`, `hub/crates/sideveild/src/plugin_registry/query.rs` |
| Overlay window blur, AGSL glass shader, or dynamic Oklch palette | `android/host-app/.../overlay/BlurMaskDrawable.kt`, `overlay/GlassBackdropDrawable.kt`, `hub/crates/sideveil-gfx/src/palette.rs` |
| Panel spring physics, drag FSM, or insets/IME avoidance | `android/host-app/.../panel/PanelDragStateMachine.kt`, `panel/PanelSpringController.kt`, `panel/PanelInsetsCoordinator.kt` |
| Plugin manifest schema, overrides, or capability ACLs | `hub/crates/sideveil-protocol/src/plugin.rs`, `hub/crates/sideveild/src/plugin_registry/`, `plugin_acl.rs` |
| WebView sandbox, WebMessagePort RPC, or Dev Mode fetching | `android/host-app/.../plugin/PluginWebViewManager.kt`, `plugin/PluginWebMessageBridge.kt`, `plugin/PluginLocalRpcHandler.kt`, `plugin/PluginAssetFetcher.kt` |
| Native HUD vector primitives or JNI bitmap rendering | `hub/crates/sideveil-gfx/src/hud_renderer.rs`, `hub/crates/sideveil-hud-jni/src/lib.rs`, `android/host-app/.../hud/NativeHudWindow.kt` |
| Daemon JSON-RPC handlers or CLI subcommands | `hub/crates/sideveild/src/rpc/`, `hub/crates/sideveil-ctl/src/cli/` |

### Host-Testable Pure Logic Modules

Keep Android Framework and live-socket dependencies out of pure logic modules:
- Sensor kinematics & diagnostics: `gesture/EdgeGestureStateMachine.kt`, `gesture/EdgeGestureMath.kt`, `gesture/GestureDiagnosticsTracker.kt`.
- Host motion, layout & policy: `panel/PanelDragStateMachine.kt`, `panel/PanelSpringController.kt`, `panel/PanelInsetsCoordinator.kt`, `panel/PanelOutsideTouchPolicy.kt`, `plugin/PluginWebViewPool.kt`, `plugin/WebViewDormancyController.kt`, `plugin/PluginNetworkPolicy.kt`, `plugin/EmbedUrlRouter.kt`.
- Hub protocol & graphics: `sideveil-protocol` (wire codecs/validation), `sideveil-gfx` (palette, SVG raster, HUD rendering), `sideveild/src/outbound_queue.rs`, `sideveild/src/uds_gateway/framing.rs`, `sideveild/src/peercred.rs`.

## 3. Core Runtime Data Flows

```text
Edge Swipe Flow:
  [:sensor] gesture/EdgeGestureMonitor -> EdgeGestureStateMachine -> pilferPointers()
     |-- hub.sock (NDJSON: GestureDragStart / DragMove / DragEnd) -->
  [sideveild] OutboundQueue (ephemeral coalescing lane for DragMove)
     |-- 127.0.0.1:0/ws (Bearer token) -->
  [SideVeilHost] service/HostGestureCoordinator -> panel/PanelMotionChoreographer -> PanelSpringController
     -> panel/SidebarPanelWindow + rail/PluginRailWindow (locked LayoutParams, zero WebView reflow)

Plugin RPC & Asset Flow:
  [Plugin WebView] https://<id>.plugins.sideveil.local/*
     |-- plugin/PluginWebViewClient -> PluginAssetFetcher --(HTTP Bearer / loopback devUrl)--> [sideveild asset_server]
     |-- WebMessagePort (JSON-RPC 2.0) -> plugin/PluginWebMessageBridge
         +--> plugin/PluginLocalRpcHandler (container/haptics/toast/clipboard)
         +--> service/DaemonClient --(WS)--> [sideveild plugin_acl] (capability check + audit ring + jailed fs/exec/backend)

Native HUD Flow:
  [uds-native daemon] --(hub.sock: hud.sceneUpdated)--> [sideveild OutboundQueue ephemeral lane]
     --(WS)--> [SideVeilHost hud/NativeHudWindow + NativeHudView] -> NativeHudRenderer (JNI)
     -> [libsideveil_hud.so] UTF-16 decode -> vello_cpu NEON -> ARGB_8888 Bitmap -> HWUI tnum text overlay
```

1. **Boot & Zero-Polling Supervision**:
   - `service.sh` launches `sideveild`, which acquires an exclusive kernel `flock` singleton lock (`/data/adb/sideveil/run/sideveild.lock`).
   - `sideveild` stages `sideveil-sensor.dex` (`0640 0:1000`), spawns `sideveil-sensor` (`UID 1000` via `pre_exec`), starts bundled `uds-native` daemons, and wakes `SideVeilHost` via `am start-foreground-service`.
   - `supervisor.rs` + `supervisor/sensor.rs` monitor child and host PIDs via Linux `pidfd_open` + Tokio `AsyncFd` (`epoll`) with exponential backoff and zero polling loops; `inotify.rs` watches `config.json` and `plugins/` for hot-reload.
2. **Edge Gesture -> `pilferPointers` -> Drag Move -> Spring Panel**:
   - `:sensor` listens on `InputManagerGlobal.monitorGestureInput`. `gesture/EdgeGestureStateMachine` enforces edge bands, optional fullscreen double-swipe, and `240px` reverse-pull cancel; once triggered, it calls `InputMonitor.pilferPointers()`.
   - `:sensor` streams `GestureDragStart` / `GestureDragMove` / `GestureDragEnd` NDJSON frames over `hub.sock` (`ipc/HubUdsClient`).
   - `sideveild` routes high-rate `dragMove` frames through the coalescing ephemeral lane of `OutboundQueue` (preserving ordered control/RPC frames in the critical lane) over loopback WebSocket to `HostService`.
   - `service/HostGestureCoordinator` + `panel/PanelMotionChoreographer` + `panel/PanelSpringController` animate warm-attached, locked-geometry `SidebarPanelWindow` and `PluginRailWindow` without triggering WebView viewport reflows.
3. **Plugin Asset/Icon Serving & WebMessagePort ACL RPC**:
   - Each web plugin runs inside an isolated WebView origin `https://<pluginId>.plugins.sideveil.local/` with `allowFileAccess = false` and LRU pool eviction (`plugin/PluginWebViewPool`).
   - `plugin/PluginWebViewClient` intercepts asset requests and delegates to `plugin/PluginAssetFetcher`, fetching from `sideveild`'s authenticated loopback HTTP server (`/plugins/<id>/*`, bounded line/header reads) or a strictly validated loopback Dev Mode URL.
   - Plugin icons (`/plugins/<id>/__icon?size=&theme=`) are rasterized on demand by `sideveil-gfx` (`usvg` + `vello_cpu` NEON) and persisted atomically in `asset_server/icon_cache.rs`.
   - Runtime RPCs travel from `@pochita/sideveil-sdk` over `WebMessagePort` -> `plugin/PluginWebMessageBridge` -> `plugin/PluginLocalRpcHandler` (container/haptics/toast/clipboard) or `service/DaemonClient` -> `sideveild` `plugin_acl.rs` (manifest capability check, audit ring recording, jailed storage/exec, or backend multiplexing).
4. **Native HUD (`vello_cpu` NEON JNI) Pipeline**:
   - A `uds-native` plugin daemon streams `hud.sceneUpdated` JSON frames over `hub.sock`. `sideveild` coalesces frames in the ephemeral outbound lane and forwards them to `HostService`.
   - `hud/NativeHudWindow` / `hud/NativeHudView` submits the scene JSON and a double-buffered `ARGB_8888` `Bitmap` to `NativeHudRenderer` -> `libsideveil_hud.so`.
   - `sideveil-hud-jni` decodes UTF-16 `jstring` safely (preserving emoji/supplementary Unicode), renders vector paths/sparklines/gauges into the locked pixel buffer via `sideveil-gfx` (`vello_cpu` NEON), and HWUI draws crisp CJK/Emoji/tabular (`tnum`) text overlays on top.

## 4. Protocol & Security Trust Boundaries

SideVeil enforces **7 explicit protocol boundaries**:

| # | Boundary | Transport & Framing | Authentication & Trust Controls |
| --- | --- | --- | --- |
| 1 | Sensor <-> Hub | `hub.sock` (`0660 0:1000`), bounded NDJSON | Kernel `SO_PEERCRED` (`uid == 1000` or `0`); cancellation-safe framing. |
| 2 | CLI / Control Plane <-> Hub | `hub.sock` (`0660 0:1000`), JSON-RPC 2.0 NDJSON | Kernel `SO_PEERCRED` (`uid == 0`). |
| 3 | Render Host <-> Hub Control | `127.0.0.1:0/ws`, RFC 6455 + JSON-RPC/Events | Loopback-only + Bearer token synced atomically (`0600`) to `no_backup/daemon_endpoint.json`. |
| 4 | Render Host <-> Hub Assets | `127.0.0.1:0/plugins/<id>/*`, HTTP/1.1 | Loopback-only + Bearer token; bounded request/header line reads; canonicalized paths; on-demand COOP/COEP. |
| 5 | Plugin WebView <-> Render Host | `WebMessagePort`, JSON-RPC 2.0 + Events | Virtual origin isolation, strict loopback Dev URLs (no userinfo/redirects, bounded body), hub-side manifest ACLs. |
| 6 | Plugin Backends <-> Hub | `hub.sock` (`uds-native`) / `app.sock` (`0666`, `uds-app`), NDJSON | `SO_PEERCRED` (`uid 0/1000` for native; `uid % 100_000` appId matching against `/data/system/packages.list` for apps). |
| 7 | Render Host <-> Native HUD | In-process JNI (`libsideveil_hud.so`) | Pure CPU vector rasterizer on validated JSON; zero filesystem or network access. |

### Runtime Filesystem Trust Zones

- `/data/adb/sideveil/run/` (`0700 0:0`): Root-only lockfile and supervisor PIDs (`sideveild.lock`, `sideveild.pid`).
- `/data/system/sideveil/` (`0711 0:1000`): Shared runtime state (`config.json`, `plugins.json`, `plugins/`, `run/hub.sock`, `run/app.sock`, `sideveil-sensor.dex`).
- `/data/system/sideveil/data/<plugin_id>/` (`0700 0:0`): Jailed per-plugin storage enforced by `plugin_acl.rs` with path canonicalization, bounded reads, and atomic temp-file writes (`fs_util.rs`).
- `/data/user/0/pochita.sideveil.host/no_backup/daemon_endpoint.json` (`0600`): Host-private WS/HTTP port and Bearer token synced atomically with SELinux context preservation.

## 5. Non-Negotiable Platform Invariants

Full empirical rationale and Android 16+ test evidence live in [`docs/architecture-decisions.md`](docs/architecture-decisions.md):

- **Dialog + Blur/Backdrop Split**: All translucent `native-glass` overlay windows (`panel/SidebarPanelWindow`, `rail/PluginRailWindow`, `pinned/PinnedContainerWindow`, `hud/NativeHudWindow`) must use a `Dialog` (never raw `WindowManager.addView`), binding an immutable `overlay/BlurMaskDrawable` to `Window.setBackgroundDrawable(...)` and stateful `overlay/GlassBackdropDrawable` to the content container.
- **Zero-Reflow Warm Residency**: Keep overlay windows warm-attached (`1x1 INVISIBLE` when collapsed) with `PRIVATE_FLAG_NO_MOVE_ANIMATION`, and lock `LayoutParams` width/height during spring motion so embedded WebViews never reflow.
- **Uncapped Adaptive Refresh Rate**: Request the display's maximum supported refresh rate matching active physical resolution (`overlay/OverlayWindowFlags.applyPreferredMaxRefreshRate` + `REQUESTED_FRAME_RATE_CATEGORY_HIGH`) while visible and clear votes (`0f` / `0`) when collapsed/hidden. Never hardcode FPS ceilings.
- **UID 1000 Constraints**: Standalone `app_process64` cannot open a `WindowSession` or instantiate `WebView`, and must initialize `ApplicationSharedMemory` before touching `WindowManagerGlobal`.
- **Single-Layer Pinned Chrome**: `pinned/PinnedContainerWindow` exposes only a minimal bottom drag bar (hidden during click-through) and themed unlock chip; no duplicate native close/unpin chrome.
- **Code & Privacy Hygiene**: Keep source files `<= 300` lines (hard limit `400`), use objective English for code/comments/logs/docs and `zh-CN` only for user-facing UI copy, and never commit device serials, private IPs, or personal paths.

## 6. Verification & Hot-Reload Matrix

| Goal | Command | What It Verifies / Builds |
| --- | --- | --- |
| Full local quality gate | `mise run check` | Runs `contracts:check`, `hub:check`, `webui:check`, and `android:check` with zero warnings or errors. |
| Cross-language contracts | `mise run contracts:check` | Verifies `@pochita/contracts-gen` outputs (Rust/Kotlin/TS) and golden JSON Schema fixtures are up to date. |
| Rust hub & example daemon | `mise run hub:check` | `cargo fmt --check`, `cargo clippy --all-targets -D warnings`, `cargo test` for `hub/` and `examples/native-perf-hud`. |
| WebUI & bundled plugins | `mise run webui:check` | Biome lint, `tsc --noEmit`, unit & `standards.test.mjs` checks, all 4 Vite builds. |
| Android protocol/sensor/host | `mise run android:check` | Spotless, Detekt, Android Lint, JVM unit tests (`:protocol`, `:sensor`, `:host-app`), `:sensor:buildDex`, `:host-app:assembleRelease`. |
| Full KernelSU module ZIP | `bash build.sh` | Cross-compiles arm64 Rust/JNI, builds WebUI + DEX + APK, packages `out/sideveil.zip`. |
| Incremental device hot-reload | `bash reload.sh --skip-build --ssh <host>` | Pushes artifacts over `--ssh`, `--adb <serial>`, or `--local` (supports `--hub-only`, `--host-only`, `--sensor-only`, `--webui-only`, `--plugin <id>`, `--cdp-forward`). |
| Live E2E device harness | `python3 scripts/verify-live.py` | Verifies daemon/sensor/host health, live appearance, plugins E2E, native HUD, UI inspector, CDP, and zero WebView console errors. |
