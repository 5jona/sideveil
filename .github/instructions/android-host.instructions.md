---
description: "Use when modifying Android Kotlin code in android/host-app, android/sensor, android/protocol, or Gradle configs."
applyTo: "android/**"
---
# Android Host, Sensor & Protocol Standards

Follow `sideveil/.agent/rules.md` and `docs/architecture-decisions.md` for all Android modules.

- **Role Boundaries**: `android/sensor` (`UID 1000` single DEX) must never include native libraries, UI views, or config persistence. `android/host-app` must never embed plugin business logic or direct disk config authority.
- **Blur vs. Backdrop Separation**: Any overlay window supporting `native-glass` (`SidebarPanelWindow`, `PluginRailWindow`, `PinnedContainerWindow`, `NativeHudWindow`) must use a `Dialog` whose `Window` background is an immutable `BlurMaskDrawable(cornerPx)` and whose content container binds `GlassBackdropDrawable`. Never set a stateful drawable directly on `Window.setBackgroundDrawable`.
- **Adaptive Uncapped Refresh Rate**: Apply `OverlayWindowFlags.applyPreferredMaxRefreshRate(...)` when overlay windows become visible and `clearPreferredRefreshRate(...)` when collapsed or hidden. Never hardcode refresh-rate ceilings. Keep active pinned plugin WebViews at `WebView.RENDERER_PRIORITY_IMPORTANT` while visible.
- **Single-Layer Pinned Chrome**: `PinnedContainerWindow` must only expose a minimal centered bottom drag handle bar (hidden in click-through mode) and a themed unlock chip. Do not add native close or unpin buttons that duplicate plugin controls. Always synchronize click-through and presentation changes back to the plugin via `SideVeilEvents` constants in `Protocol.kt`.
- **Modularity & Quality Gates**: Keep source files `<= 300` lines (hard limit `400`), reuse shared `OverlayWindowFlags` / density helpers rather than duplicating `dp()`, and verify with `mise run android:check` (`spotlessCheck`, `detekt`, `lintDebug`, unit tests, sensor DEX, release APK).
