# SideVeil — Engineering Standards

For empirical platform constraints, AOSP rendering details, and architectural background, see [`docs/architecture-decisions.md`](../docs/architecture-decisions.md).

## 1. Role Boundaries
SideVeil targets **Android 16+ (API 36+, arm64)** and enforces strict separation across three runtime roles:
- **Core Hub (`hub/`)**: Owns process supervision (`pidfd` + `epoll`, zero polling), global configuration authority (`inotify` + atomic persistence), plugin registry & capability ACLs, UDS IPC routing (`SO_PEERCRED`), shared vector/color synthesis, and static asset serving.
- **System Sensor (`android/sensor/`, `UID 1000` `app_process64`)**: Restricted strictly to operations requiring `UID 1000` framework access (foreground task tracking, global edge gesture interception, AppOps/Doze allowlisting). Must remain a single DEX (`classes.dex` only) with zero native libraries, zero UI, and zero config authority.
- **Render Host (`android/host-app/`, `SideVeilHost.apk`)**: AMS-managed `priv-app` service owning overlay windows, physical motion, cross-window blur + AGSL backdrops, lightweight native vector HUDs, and isolated plugin WebView sandboxes. Never embeds plugin business logic or direct disk config authority.

## 2. Modularity & Testability
- **File Size Limits**: Keep every source file at or below **300 lines** (hard limit **400 lines**). Split modules by cohesion before crossing 400 lines.
- **Pure Logic Separation**: Extract state machines, spring kinematics, protocol codecs, and palette/vector rendering into platform-agnostic modules covered by host-side unit tests without Android Framework or live socket dependencies.
- **Single Source of Truth**: Never duplicate protocol constants, schema definitions, or theme allowlists across modules.

## 3. Runtime, Security & Rendering Invariants
- **Rust (`hub/`)**: Inherit workspace lints (`[lints] workspace = true`). Never use `mod.rs` (use `foo.rs` + `foo/bar.rs`). Document every `unsafe` block with `// SAFETY:`. Never use `.unwrap()` or `.expect()` in runtime paths after startup. Authenticate all UDS peers via kernel `SO_PEERCRED`. Separate high-frequency ephemeral frames from control/RPC frames via dual-priority queues. Persist files atomically (`temp -> fsync -> rename`) and canonicalize static asset paths against traversal.
- **Android (`android/`)**:
  - *Blur vs. Backdrop Separation*: Never attach stateful `Drawable`s (`RuntimeShader`) to `Window.setBackgroundDrawable(...)` on windows using `Window.setBackgroundBlurRadius(...)`; bind an immutable outline-only mask (`BlurMaskDrawable`) to the Window background and attach the stateful glass drawable (`GlassBackdropDrawable`) to the content container. This applies to every translucent overlay window supporting `native-glass`, including `PinnedContainerWindow` (must use `Dialog` rather than raw `WindowManager.addView`).
  - *Zero-Reflow Motion*: Keep overlay windows warm-attached (`1x1` `INVISIBLE` when closed) with `PRIVATE_FLAG_NO_MOVE_ANIMATION`. Lock `WindowManager.LayoutParams` dimensions during animations so embedded WebViews never suffer viewport reflows.
  - *Uncapped Adaptive Refresh Rate*: Never hardcode arbitrary FPS or refresh-rate ceilings anywhere in native or web code. While an overlay window is visible, dynamically request the highest supported `Display.Mode.refreshRate` matching the current physical display resolution (`preferredRefreshRate` / `preferredDisplayModeId` and `View.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH)`). Clear refresh-rate votes (`0f` / `0`) whenever a window collapses to warm-invisible or hides to preserve LTPO power efficiency. Keep active pinned plugin WebViews at `RENDERER_PRIORITY_IMPORTANT` while visible.
  - *Single-Layer Pinned Chrome*: `PinnedContainerWindow` must not duplicate plugin controls (such as close or unpin buttons) in a native title bar. Provide only a minimal centered bottom drag handle bar (hidden during click-through) and a themed unlock chip for exiting click-through, keeping click-through state synchronized with the plugin via protocol events.
  - *WebView Sandbox*: Isolate each plugin under its own virtual origin (`https://<pluginId>.plugins.sideveil.local/`) with `allowFileAccess = false`, bounded LRU caching, loopback-only Dev Mode, and `onRenderProcessGone` crash recovery.
- **Plugins & WebUI (`webui/`, `examples/`)**:
  - Keep the host runtime agnostic of third-party plugin UI frameworks (enforce only `manifest.json` and JSON-RPC 2.0).
  - *Built-In & Bundled Plugins Standard*: Every first-party, built-in, or bundled example plugin UI shipped by this repository MUST be authored in `webui/` using React + TypeScript + Tailwind CSS v4 + `@pochita/ui` + `@pochita/bridge` + `@pochita/sideveil-sdk` + `createPochitaViteConfig`, compiled into its target asset directory, and covered by `pnpm check` (Biome, TypeScript, tests, Vite build). Never hand-code standalone inline HTML/CSS/JS bundles for first-party or bundled plugins.
  - Scope plugin storage to `/data/system/sideveil/data/<plugin_id>/`. Adhere to `pochita-webui-kit/.agent/rules.md` for all frontend code.

## 4. Language, Privacy, Commits & Verification
- **Language & Privacy**: Write all code, comments, logs, commit messages, and documentation in objective English. Write user-facing UI copy in Simplified Chinese (`zh-CN`). Never commit device serials, device-specific hardware specs, or personal paths.
- **Commit Messages**: Use Conventional Commits (`<type>(<scope>): <imperative summary>`, <= 72 chars). Small single-purpose changes may stay single-line; multi-file, cross-module, or architectural/bug-fix commits must include 2–5 concise bullet points summarizing key changes and non-obvious rationale (Why).
- **Verification**: Every change must pass `mise run check` (covering Rust workspace + bundled Rust plugins, WebUI + bundled plugin bundles + standards guard tests, and Android checks) and `bash build.sh` when packaging changes, with zero warnings or errors.

