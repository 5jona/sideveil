# SideVeil Architecture Decisions & Platform Findings

This document records empirical findings on Android 16+ (API 36+), reference observations from system sidebar implementations, AOSP rendering constraints, and the resulting architectural decisions in SideVeil.

---

## 1. Android 16+ (`UID 1000` `app_process`) Runtime Boundaries

Verification on Android 16+ (tested up to API 37 / ColorOS 17 on Linux GKI 6.12) established the exact boundary between operations that work inside a standalone `UID 1000` `app_process64` daemon and operations that require an AMS-managed application process:

1. **`WindowManager.addView` and `SurfaceControlViewHost` fail in standalone `app_process`**
   - In `com.android.server.wm.Session.openSession()`, WindowManagerService looks up the caller PID in `ActivityTaskManagerService.mProcessMap.getProcess(mPid)`.
   - A process started directly via `app_process` is not spawned by Zygote/AMS and has no `WindowProcessController` entry in `mProcessMap`. Any attempt to open a window session throws `IllegalStateException: Unknown pid=<pid> uid=1000`.
2. **`WebView` is blocked in privileged UIDs**
   - `android.webkit.WebViewFactory` checks `UserHandle.getAppId(Process.myUid())` and throws `UnsupportedOperationException: For security reasons, WebView is not allowed in privileged processes` for `uid 0` and `uid 1000`.
   - Consequently, WebView instances must run inside an ordinary application UID managed by AMS.
3. **`ApplicationSharedMemory` initialization is required on Android 16+**
   - When `Flags.currentAnimatorScaleUsesSharedMemory()` is enabled, `WindowManagerGlobal` reads `ApplicationSharedMemory.getInstance().getCurrentAnimatorScale()` during class initialization.
   - Standard apps receive the shared memory file descriptor during `ActivityThread.bindApplication()`. A standalone `app_process` calling `ActivityThread.systemMain()` skips `bindApplication()` and crashes with a null instance unless `ApplicationSharedMemory.setInstance(ApplicationSharedMemory.create())` is invoked at startup.
4. **Global gesture monitoring does not require an AMS `WindowSession`**
   - `InputManagerService.monitorGestureInput()` only checks `android.permission.MONITOR_INPUT` (granted to `UID 1000`) and creates a gesture monitor input channel directly without querying `ATMS.mProcessMap`.
   - The returned `InputMonitor` can call `pilferPointers()` once a swipe gesture matches edge thresholds, transferring the active touch stream away from the underlying app.
5. **Overlay window type and permission grant**
   - Privileged window types above `TYPE_APPLICATION_OVERLAY` (such as `2314`) require `android.permission.INTERNAL_SYSTEM_WINDOW` (platform signature).
   - An AMS-managed app can use `TYPE_APPLICATION_OVERLAY` (`2038`) without platform signing once `OP_SYSTEM_ALERT_WINDOW` (`appOp 24`) is set to `MODE_ALLOWED` (`0`). The `UID 1000` sensor process grants this silently via `IAppOpsService` and adds the host package to the device idle allowlist via `IDeviceIdleController`.
6. **Privilege transition on kernels with execve root guards**
   - On ColorOS kernels with `secureguard_new`, invoking `/data/adb/ksud` directly from a non-zero UID logs an abnormal `execve_block` warning.
   - To avoid triggering kernel root-check hooks, `sideveild` runs as `uid=0` and spawns the `UID 1000` `app_process64` child by dropping privileges in `pre_exec` via `setresgid(1000, 1000, 1000)` and `setresuid(1000, 1000, 1000)`.

---

## 2. Reference Observations from System Sidebars

Analysis of system sidebar implementations (`com.coloros.smartsidebar` v17.9.8 and `com.oplus.games` v10.44.6) informed several window management and interaction patterns:

1. **Warm-attached `1x1 INVISIBLE` window residency**
   - Rather than adding and removing windows on every open/close cycle, the panel window remains attached to `WindowManager`.
   - When collapsed, the window is set to `1x1` pixels, `View.INVISIBLE`, and `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`, with `PRIVATE_FLAG_NO_MOVE_ANIMATION` (`0x00000040`) enabled to suppress system window move animations.
   - `HardwareRenderer.preload()` is called when the host service starts so the HWUI render thread is initialized prior to the first gesture.
2. **Click-through visual handle paired with input monitor interception**
   - The visible edge indicator window carries `FLAG_NOT_TOUCHABLE` at all times so it never blocks taps or vertical scrolling along the screen edge.
   - Touch interception is handled exclusively by the `UID 1000` gesture monitor channel (`monitorGestureInput` + `pilferPointers`).
3. **Edge ergonomics and damping**
   - After an idle interval (`idle_auto_dim_ms`, default `5000ms`), the edge indicator lowers its opacity and shifts partially toward the screen edge.
   - Dragging past the target panel width applies logarithmic damping (`ln(1 + x)`), and pulling back toward the screen edge before release cancels the expansion.
4. **Two-column rail and card layout**
   - When multiple plugins match the current foreground activity, a separate vertical navigation rail (`46dp` wide) is positioned beside the main panel card with a `6dp` gap, matching the separated rail-and-card layout used in `com.oplus.games`.

---

## 3. AOSP Rendering & Animation Constraints

1. **`Window.setBackgroundBlurRadius` clears `Drawable.Callback` on the window background**
   - In AOSP `DecorView.updateBackgroundDrawable()`, enabling `window.setBackgroundBlurRadius(radiusPx)` wraps the drawable passed to `window.setBackgroundDrawable(drawable)` inside a new `LayerDrawable([BackgroundBlurDrawable, drawable])` and then calls `super.setBackgroundDrawable(layerDrawable)`.
   - Inside `View.setBackgroundDrawable()`, Android calls `mBackground.setCallback(null)` on the old background reference (which is `drawable` itself), leaving `drawable.getCallback() == null`.
   - If a stateful drawable (such as an AGSL `RuntimeShader` backdrop) is bound directly to `window.setBackgroundDrawable()`, subsequent `invalidateSelf()` calls during live theme or color-mode switches do not trigger a redraw.
   - **Resolution**: Bind an immutable, zero-draw `BlurMaskDrawable` (which only provides the rounded-rect `Outline` for `BackgroundBlurDrawable`) to `window.setBackgroundDrawable()`, and bind the stateful `GlassBackdropDrawable` to `contentContainer.background`.
2. **Avoiding `WindowManager.LayoutParams` resizing during spring animations**
   - Updating `LayoutParams.width` or `LayoutParams.height` via `updateViewLayout()` on every animation frame reallocates window buffers in SurfaceFlinger and triggers repeated CSS viewport reflows inside embedded WebViews.
   - **Resolution**: Keep `LayoutParams.width` and `LayoutParams.height` strictly locked to the target container geometry while the panel is visible (translating the fixed-size window with `PRIVATE_FLAG_NO_MOVE_ANIMATION` and driving content/backdrop alpha, quantized blur, and AGSL specular shift from a single normalized spring `progress` in `[0, 1]`).
   - **Uncapped adaptive refresh rate**: While any overlay window is visible, dynamically apply the highest supported `Display.Mode` refresh rate matching the active physical resolution (`preferredRefreshRate` / `preferredDisplayModeId` and `View.setRequestedFrameRate`) without imposing hardcoded FPS ceilings, and clear votes when windows collapse to `1x1 INVISIBLE` or hide.

---

## 4. Architecture Evolution Summary

### Stage 1: Single-APK Two-Process Concept (Discarded)
- **Design**: One APK containing a configuration Activity and a `UID 1000` `app_process` daemon that would directly create overlay windows and embed a `WebView`.
- **Outcome**: Discarded after on-device testing showed that standalone `UID 1000` processes cannot open a `WindowSession` (`ATMS.mProcessMap` check) and cannot instantiate `WebView` (`WebViewFactory` UID check).

### Stage 2: Three-Role Process Separation
- **Design**: Split responsibilities across (1) a control plane UI, (2) a `UID 1000` `app_process` sensor handling `ITaskStackListener`, `monitorGestureInput`, and `IAppOpsService`, and (3) an AMS-managed `priv-app` service (`SideVeilHost.apk`) owning `TYPE_APPLICATION_OVERLAY` windows and `WebView`.
- **Limitation**: Early iterations relied on shell scripts for process supervision, single-panel asset bundling, and lacked per-plugin isolation.

### Stage 3: Rust Core Hub (`sideveild`) & Open Plugin Sandbox
- **Design**: Introduced `sideveild` as the central supervisor and IPC broker:
  - Supervises `:sensor` (`pre_exec` UID 1000 drop) and `SideVeilHost` via Linux `pidfd_open` + `epoll` (`AsyncFd`) instead of polling loops.
  - Watches `/data/system/sideveil/config.json` and `/data/system/sideveil/plugins/<id>/manifest.json` via `inotify`.
  - Exposes dual Unix Domain Sockets (`hub.sock` for privileged components and `uds-native` plugins; `app.sock` for `uds-app` plugins) verified via kernel `SO_PEERCRED`.
  - Isolates each WebView plugin under its own virtual origin (`https://<pluginId>.plugins.sideveil.local/`), enforces manifest capability ACLs, and separates high-frequency ephemeral frames (`gesture.dragMove`, `hud.sceneUpdated`) from control/RPC frames using dual-priority outbound queues.

### Stage 4: Hybrid Graphics Pipeline (`HWUI` + `vello_cpu` JNI + Optional `WebGPU`)
- **Host Shell (`:host-app`)**: Uses Android HWUI, `Window.setBackgroundBlurRadius`, and AGSL `RuntimeShader`. Rendering the main host shell with `SurfaceView` + Vulkan/`wgpu` was rejected because `SurfaceView` punches a separate surface layer that conflicts with `DecorView` cross-window blur and increases baseline GPU memory usage in an always-resident process.
- **Shared Vector & Color Core (`sideveil-gfx` & `sideveil-hud-jni`)**:
  - `sideveild` uses `usvg` + `vello_cpu` (AArch64 NEON) to rasterize plugin SVG icons into cached PNGs (`/plugins/<id>/__icon`) and uses `color` (`Oklch`) to synthesize light/dark glass tokens from Android wallpaper seed colors.
  - For lightweight status overlays (`native-hud` presentation mode), `libsideveil_hud.so` rasterizes vector primitives (`NativeHudScene`) directly into double-buffered Android `Bitmap`s via `libjnigraphics.so`, while Android HWUI `Canvas.drawText` renders CJK/Emoji/tabular text (`tnum`) using system fonts without bundling font assets in Rust or spawning a WebView process.
- **WebView Plugin Sandbox**: Standard web plugins run inside LRU-cached WebViews; plugins declaring `surface.crossOriginIsolated: true` receive `COOP: same-origin` and `COEP: require-corp` headers to enable `SharedArrayBuffer` and WASM/WebGPU rendering, with render processes reclaimed on LRU eviction.

---

## 5. WebView Sandbox, Bridge & Packaging Findings

1. **Chromium `WebViewCompat.setProfile` resets WebView background to opaque white**
   - Calling `WebViewCompat.setProfile(...)` recreates Chromium's underlying `AwContents`, resetting the view background to opaque white and masking SurfaceFlinger `blurRegions` on the floating panel even while the Plugin Rail remains translucent.
   - **Resolution**: Always bind the isolated WebView profile first and invoke `webView.setBackgroundColor(Color.TRANSPARENT)` strictly afterwards (`PluginWebViewSettings.kt`).
2. **Origin-bound `WebMessageListener` supersedes `MessagePort` during handshake**
   - `addJavascriptInterface` is banned in favor of `WebViewCompat.addWebMessageListener` gated on exact plugin origin and main-frame checks (`PluginWebMessageBridge.kt`).
   - Once the web page sends `host.attach(channel: 'listener')`, the Android bridge closes any legacy `MessagePort`. Client transports (`@gojona/bridge` `HostTransport`) must therefore prioritize the active listener and close late-transferred ports so `plugin.invoke` requests are never dropped into a closed port.
3. **Full-response CSP & canonical network origin validation**
   - CSP headers are attached to every `2xx` response served from virtual plugin origins (including JavaScript resources loaded by Web Workers) to prevent zero-CSP worker escape, and main-frame navigation out of trusted plugin origins is blocked.
   - Both Rust Hub and Android Host unwrap IPv4-mapped IPv6 literals (`[::ffff:...]`) and reject unspecified addresses (`0.0.0.0`, `[::]`) when evaluating loopback/LAN network capabilities.
4. **KernelSU `.svp` uploads and plugin ID namespace rules**
   - KernelSU WebUI shell commands hit Linux's `MAX_ARG_STRLEN` (128 KiB `E2BIG`) if large `.svp` payloads are passed inline; the Control Plane stages uploads in 40 KiB chunks to a temporary file before invoking `sideveil-ctl plugins install <path>` (and `sideveil-ctl rpc -` accepts stdin payloads up to 24 MiB for `uid 0`).
   - Reserved-ID guards protect `builtin` (`sideveil.diagnostic`) and `bundled` (`example.*`) plugin IDs against third-party hijacking without blocking legitimate installed first-party modules such as `sideveil.proxy`. Keep `/data/system/sideveil/plugins/` free of stale shadowed `example.*` directories that belong under `/data/adb/modules/sideveil/plugins/`.
