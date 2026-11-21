---
description: "Use when editing Rust crates in hub/ or bundled Rust plugin daemons under examples/."
applyTo: "hub/**,examples/**/*.rs"
---
# Rust Hub & Plugin Daemon Standards

Follow `sideveil/.agent/rules.md` for all Rust code.

- **Structure & Lints**: Inherit `[lints] workspace = true` inside `hub/`. Never use `mod.rs` (prefer `foo.rs` + `foo/bar.rs`). Keep source files `<= 300` lines (hard limit `400`).
- **Safety & Reliability**: Document every `unsafe` block with `// SAFETY:`. Never use `.unwrap()` or `.expect()` in runtime code paths after startup.
- **Security & Persistence**: Authenticate UDS peers via kernel `SO_PEERCRED`. Canonicalize all static asset and jailed plugin storage paths against traversal and symlink escapes. Persist configuration and override files atomically (`temp -> fsync -> rename`).
- **Theme-Aware Native HUDs**: Bundled native HUD daemons (`examples/native-perf-hud`) must support both light and dark surface palettes so text and vector tracks remain legible over light and dark glass backdrops.
- **Verification**: Run `mise run hub:check` (`cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test`) across both `hub/` and bundled example crates.
