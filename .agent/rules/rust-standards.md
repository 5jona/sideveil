# Sideveil — Rust Engineering Standards (`sideveil/hub/`)

## 1. Toolchain, Edition & Workspace Configuration
Follow modern Rust idioms consistent with workspace sibling projects (`pencursor`, `oplus-hardware-hub/daemon`):
- **Workspace**: `resolver = "3"`, `edition = "2024"`, `rust-version = "1.89"`.
- **Release Profile**: `lto = "thin"`, `codegen-units = 1`, `strip = "symbols"`, `panic = "abort"`, `opt-level = 3`.
- **Cross-Compilation (`.cargo/config.toml`)**:
  - Target `aarch64-linux-android` using NDK 30 `aarch64-linux-android37-clang` linker and `-C target-feature=+neon`.
- **Formatting (`rustfmt.toml`)**:
  - `edition = "2024"`, `style_edition = "2024"`, `max_width = 100`, `newline_style = "Unix"`, `use_field_init_shorthand = true`, `use_try_shorthand = true`.

## 2. Mandatory Workspace Lints
All crates must inherit `[lints] workspace = true` with at least:
- `[workspace.lints.rust]`:
  - `unsafe_op_in_unsafe_fn = "deny"`
  - `unreachable_pub = "warn"`
  - `missing_debug_implementations = "warn"`
  - `rust_2018_idioms = { level = "warn", priority = -1 }`
- `[workspace.lints.clippy]`:
  - `all = { level = "warn", priority = -1 }`
  - `mod_module_files = "warn"` (**forbid `mod.rs`**; always use `foo.rs` + `foo/bar.rs`)
  - `undocumented_unsafe_blocks = "warn"` (every `unsafe` block requires a `// SAFETY:` comment)
  - `semicolon_if_nothing_returned = "warn"`, `uninlined_format_args = "warn"`, `manual_let_else = "warn"`, `explicit_iter_loop = "warn"`, `redundant_closure_for_method_calls = "warn"`, `cloned_instead_of_copied = "warn"`, `dbg_macro = "warn"`, `todo = "warn"`, `unimplemented = "warn"`.

## 3. Async Runtime & Dependency Discipline
- **Runtime**: Use `tokio` (prefer minimal feature set: `rt`, `net`, `sync`, `time`, `signal`, `io-util`, `macros`) for concurrent UDS multiplexing, `inotify` watching, `pidfd` supervision, and static asset serving.
- **Lean dependencies**: Prefer focused, well-maintained crates (`serde`, `serde_json`, `thiserror`, `tracing`, `rustix` or `libc`). Avoid heavy frameworks or transitive bloat in a system daemon.
- **Error handling**:
  - Use `thiserror` for structured domain/protocol errors in library crates (`sideveil-protocol`).
  - Never use `.unwrap()` or `.expect()` in production paths after startup initialization; propagate errors with `?` and handle transient I/O failures gracefully.

## 4. Security, IPC & Linux 6.12 Primitives
- **Zero-polling supervision**: Use Linux `pidfd_open` + Tokio `AsyncFd` (`epoll`) to detect `:sensor` and `SideveilHost` exits immediately without periodic shell/curl polling.
- **Mandatory `SO_PEERCRED` verification**:
  - Every UDS connection must authenticate peer `uid`, `pid`, and `gid` via kernel `SO_PEERCRED`.
  - `hub.sock` accepts only privileged peers (`root`, `UID 1000`, `SideveilHost` UID, or authorized native plugins).
  - `app.sock` (`0666`) only allows a connecting app process to register/communicate as a plugin whose `manifest.json` explicitly authorizes that app's package/UID.
- **Dual-priority outbound queues**:
  - Separate high-frequency ephemeral frames (`gesture.dragMove`) from critical control/RPC frames (`gesture.dragStart`, `gesture.dragEnd`, `config.updated`, JSON-RPC responses).
  - Under backpressure, drop only stale ephemeral frames; never drop critical control or RPC frames.
- **Safe file persistence & asset serving**:
  - Always write configs/state atomically (`write temp -> fsync -> rename`) with explicit POSIX permissions.
  - Canonicalize and prefix-verify all plugin static asset paths to prevent directory traversal (`..` / symlink escapes outside the plugin root).

## 5. Module Layout & Testing
- Keep each `.rs` file <= 300 lines (hard limit 400 lines).
- Put unit tests alongside pure logic (`#[cfg(test)] mod tests`) and integration tests for UDS `SO_PEERCRED`, `PluginRegistry`, and dual-priority queues under `tests/`.
