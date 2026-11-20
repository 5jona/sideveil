# Sideveil — Android & Kotlin Engineering Standards (`sideveil/android/`)

## 1. Module Boundaries & Responsibilities
- **`:hidden-stubs`**: Minimal `compileOnly` Java stubs for Android 16–17 hidden APIs. No runtime logic.
- **`:protocol`**: Pure Kotlin JVM wire DTOs (`kotlinx.serialization`) matching `sideveil-protocol`.
- **`:sensor` (`UID 1000` `app_process64`)**:
  - Must remain minimal: foreground package tracking (`ForegroundMonitor`), global edge gesture interception (`EdgeGestureMonitor` + pure JVM `EdgeGestureStateMachine`), and AppOps/power-save allowlisting (`AppOpsManagerBridge`).
  - Communicate with `sideveild` exclusively via `hub.sock` (UDS).
  - Keep `sideveil-sensor.dex` strictly single-DEX (`classes.dex` only, no `classes2.dex`).
- **`:host-app` (`SideveilHost.apk`, `priv-app`)**:
  - Owns only UI windows, animations, hardware blur/backdrop rendering, and WebView sandboxes.
  - Never implements plugin business logic or direct disk config authority.

## 2. Native Glass & Window Rendering Invariants
Align strictly with `pochita-webui-kit/.agents/rules/webui-standards.md` Section 4:
- **Window Blur vs. Optical Backdrop Separation**:
  - Never bind stateful custom `Drawable`s (`RuntimeShader`, dynamic tints/rims) to `Window.setBackgroundDrawable(...)` on windows using `Window.setBackgroundBlurRadius(...)`, because AOSP `DecorView` wraps it in a `LayerDrawable` and clears `Drawable.Callback`.
  - Bind an immutable, zero-draw `BlurMaskDrawable` (implementing only `Outline.setRoundRect`) to `Window.setBackgroundDrawable(...)`.
  - Attach `GlassBackdropDrawable` to `contentContainer.background`, rebind `RuntimeShader` to `Paint` after uniform updates, and propagate `updateDensity()` on `onConfigurationChanged`.
- **Hot-resident lifecycle**:
  - Preload `HardwareRenderer.preload()` and keep the overlay window warm-attached (`1x1` `INVISIBLE` when closed) with `PRIVATE_FLAG_NO_MOVE_ANIMATION` to guarantee zero-jank expansion.
  - Guard `onConfigurationChanged` against redundant double-layout / double-redraw passes.
- **Motion & Spring Physics**:
  - Target ColorOS-grade five-channel coupled springs (`transX`, `transY`, `width`, `height`, `cornerRadius`) batched via `postOnAnimation`, revealing from the edge handle `sourceRect` via center-anchored clip bounds (`scale = 1.0f`) rather than stretching content.
  - Support edge reverse-drag cancellation (`240px` zone) and clean post-dismiss fade to `1x1 INVISIBLE`.

## 3. Multi-Plugin WebView Sandbox & Plugin Rail
- **Per-plugin Origin isolation**: Assign each plugin an isolated virtual origin (`https://<pluginId>.plugins.sideveil.local/`) via `WebViewAssetLoader` / custom request interceptor so plugins cannot read each other's `localStorage` or caches. Disable `allowFileAccess` and `allowContentAccess`.
- **Render process crash recovery**: Always override `WebViewClient.onRenderProcessGone` to tear down and recreate the crashed WebView cleanly without killing `HostService`.
- **LRU warm cache**: Retain at most 2–3 recently used plugin WebViews warm in memory; evict least-recently-used instances to bound memory.
- **Plugin Rail (Multi-plugin switching)**:
  - When a foreground app matches **1 plugin**, render zero extra chrome—expand the plugin WebView directly.
  - When a foreground app matches **>= 2 plugins**, attach a compact native glass **Plugin Rail** (`44–48dp` width, `6dp` gap on the screen-edge side) outside the plugin WebView, defaulting to the MRU plugin for that app.

## 4. Code Style, File Size & Quality Gates
- **File size**: Target <= 300 lines per Kotlin file (hard limit 400 lines). Split large UI/window classes into focused collaborators (e.g., backdrop shader, optical palette, blur mask, window flags, gesture controller).
- **Formatting & Static Analysis**:
  - Enforce Spotless (`ktlint_official`) + **Detekt** + `:host-app:lint` in Gradle and `build.sh`.
  - Share Gradle helper logic (such as SDK/android.jar resolution) instead of duplicating it across subprojects.
- **Testing**:
  - Keep gesture state machines, spring math, and protocol codecs pure JVM with unit tests.
  - Never swallow exceptions silently in IPC or window lifecycle paths; log structured diagnostics.
