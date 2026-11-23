# SideVeil Rust Hub & Plugin Daemon Guidelines

Applies to `hub/` and bundled Rust plugin daemons under `examples/`.

- **Workspace & Module Layout**: Inherit `[lints] workspace = true` inside `hub/`. Never use `mod.rs` (prefer `foo.rs` + `foo/bar.rs`). Keep source files `<= 300` lines (hard limit `400`).
- **Native Plugin SDK**: Author all native Rust (`uds-native` / `uds-app`) plugin daemons on top of `sideveil-plugin-sdk` instead of hand-rolling raw Unix socket loops, NDJSON framing, or reconnect timers.
- **Safety & Reliability**: Document every `unsafe` block with `// SAFETY:`. Enforce the workspace Clippy iron cage (`hub/clippy.toml`, `unwrap_used`, `expect_used`, `await_holding_lock`, `redundant_clone`, `undocumented_unsafe_blocks`).
- **Rust Negative Patterns -> Required Replacements**:
  - `.unwrap()` / `.expect()` in non-test paths -> propagate typed `thiserror` errors (`?`) or use explicit fallbacks (`unwrap_or_else`, `let ... else`).
  - Holding `std::sync::Mutex` / `RwLock` guards across `.await` -> drop the guard before awaiting or use `tokio::sync` primitives when cross-await coordination is required.
  - Multi-`Option` / multi-`bool` implicit state machines -> model valid lifecycle transitions explicitly with a Rust `enum`.
  - Silent `let _ = ...` on fallible persistence or cleanup I/O -> propagate the error, or log/handle the failure explicitly when cleanup is best-effort.
  - Broad `pub` on internal items inside binary crates (`sideveild`, `sideveil-ctl`, example daemons) -> default to private or `pub(crate)`.
- **Test-Baseline Protection SOP**:
  - Never weaken, relax, or delete existing test assertions to make CI pass; fix the underlying implementation or investigate regressions first.
  - Review changes to core public `struct`, `enum`, or `trait` signatures (especially in `sideveil-protocol` and `sideveil-plugin-sdk`) before editing downstream callers.
- **IPC, Security & Persistence**:
  - Authenticate all UDS peers via kernel `SO_PEERCRED`.
  - Separate high-frequency ephemeral frames from control/RPC frames via dual-priority queues.
  - Canonicalize all static asset and jailed plugin storage paths against traversal and symlink escapes.
  - Persist configuration and override files atomically (`temp -> fsync -> rename`).
- **Theme-Aware Native HUDs**: Bundled native HUD daemons (`examples/native-perf-hud`) must support both light and dark surface palettes so text and vector tracks remain legible over light and dark glass backdrops.
- **Verification**: Run `mise run hub:check` (`cargo fmt --check`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo test --workspace`, plus bundled example crate checks) and `RUSTDOCFLAGS="-D warnings" cargo doc --no-deps --workspace`.
