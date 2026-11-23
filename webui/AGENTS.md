# SideVeil WebUI & Bundled Plugin Frontend Guidelines

Applies to `webui/` and bundled plugin frontends compiled into `examples/*/web/`. Also follow the shared design system invariants in `pochita-webui-kit/AGENTS.md`.

- **No Hand-Coded Bundles**: Never write standalone inline HTML/CSS/JS files for built-in or bundled plugins. Author all plugin frontends as React + TypeScript + Tailwind CSS v4 modules inside `webui/src/` with Vite configs using `createPochitaViteConfig({ surface: 'native-glass', ... })`, and compile outputs into `examples/<plugin>/web/`.
- **UI & SDK Discipline**: Wrap plugin views in `<AppShell variant="drawer">` with `<SideVeilPluginProvider>` + `<ThemeProvider>`. Reuse `webui/src/shared/useSyncedPluginTheme.ts` so every plugin stays synchronized with daemon theme presets, light/dark color modes, and Material You dynamic palette tokens.
- **Semantic Tokens Only**: Never hardcode surface, border, or text hex colors in UI components. Bind semantic CSS tokens (`--surface-*`, `--text-*`, `--border-hairline`, `--glass-divider`, `--accent`, `--radius-*`). For Canvas 2D or WebGPU colors, read tokens dynamically via `readThemeToken()` and `colorWithAlpha()`.
- **Bridge Abstraction**: Never access `window.__POCHITA_HOST__`, `window.ksu`, or raw `window.postMessage` in feature or plugin code. Route all RPCs, container actions, haptics, telemetry, and presentation state through `@pochita/bridge` and `@pochita/sideveil-sdk`.
- **Uncapped Frame Measurement**: Never clamp `requestAnimationFrame` FPS counters or rendering loops to arbitrary fixed ceilings.
- **Accessibility & File Size**: Every interactive control must have an accessible name (`aria-label` or `aria-labelledby`). Keep every source file `<= 300` lines (hard limit `400`).
- **Verification**: Run `mise run webui:check` (Biome, `tsc --noEmit`, tests including `test/standards.test.mjs`, and all Vite builds) with zero warnings.
