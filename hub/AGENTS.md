# SideVeil Rust Hub & Plugin Daemon Guidelines

Applies to `hub/` and bundled Rust plugin daemons under `examples/`.

- **Workspace & Module Layout**: Inherit `[lints] workspace = true` inside `hub/`. Never use `mod.rs` (prefer `foo.rs` + `foo/bar.rs`). Keep source files `<= 300` lines (hard limit `400`).
- **Safety & Reliability**: Document every `unsafe` block with `// SAFETY:`. Never use `.unwrap()` or `.expect()` in runtime code paths after startup.
- **IPC, Security & Persistence**:
  - Authenticate all UDS peers via kernel `SO_PEERCRED`.
  - Separate high-frequency ephemeral frames from control/RPC frames via dual-priority queues.
  - Canonicalize all static asset and jailed plugin storage paths against traversal and symlink escapes.
  - Persist configuration and override files atomically (`temp -> fsync -> rename`).
- **Theme-Aware Native HUDs**: Bundled native HUD daemons (`examples/native-perf-hud`) must support both light and dark surface palettes so text and vector tracks remain legible over light and dark glass backdrops.
- **Verification**: Run `mise run hub:check` (`cargo fmt --check`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo test --workspace`, plus bundled example crate checks).
