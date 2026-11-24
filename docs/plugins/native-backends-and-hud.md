# Native Backends (`uds-native` / `uds-app`) & Zero-WebView HUD

Plugins that need background sampling, persistent system integration, or Android app APIs can attach a Unix Domain Socket backend to `sideveild` and optionally project zero-WebView vector HUD overlays.

## 1. `uds-native` Daemons (`hub.sock`)

Native binaries run on Android arm64-v8a and connect to `/data/system/sideveil/run/hub.sock` (NDJSON framing, verified via kernel `SO_PEERCRED` for `uid 0` / `uid 1000`).

### Declarative Daemon Supervision (`manifest.json`)

```json
{
  "backend": "uds-native",
  "daemon": {
    "binary": "bin/my-daemon",
    "args": ["--interval-ms", "500"],
    "env": { "RUST_LOG": "info" },
    "restartPolicy": "on-failure",
    "maxRestarts": 5,
    "backoffMs": 1000
  }
}
```

- **Approval Gate**: Installed third-party daemons start in `daemonStatus: "awaiting-approval"` (`daemonApproved: false`) until explicitly approved in the Control Plane (`插件管理 -> 管理 -> 开发与后台`) or via `sideveil-ctl plugins grant <id> --approve-daemon`.
- **Crash Circuit Breaker**: `sideveild` supervises daemons via `pidfd` + `epoll` with exponential backoff (`backoffMs`). If a daemon exceeds `maxRestarts` within its crash window, supervision trips into `daemonStatus: "circuit-open"` with `daemonLastError` populated and stderr ring logs accessible via `plugins.daemonLogs`.

### Rust Backend via `sideveil-plugin-sdk`

Use [`hub/crates/sideveil-plugin-sdk`](../../hub/crates/sideveil-plugin-sdk/) rather than hand-rolling socket loops (see [`examples/native-perf-hud/`](../../examples/native-perf-hud/) for a complete production example):

```rust
use serde_json::{Value, json};
use sideveil_plugin_sdk::{
    PluginBackendConfig, PluginBackendHandle, PluginRpcContext, PluginRpcError,
    PluginRpcHandler, run_plugin_backend,
};

struct MyHandler;

impl PluginRpcHandler for MyHandler {
    async fn handle_rpc(
        &self,
        ctx: PluginRpcContext,
        method: &str,
        params: Option<Value>,
    ) -> Result<Value, PluginRpcError> {
        match method {
            "ping" => Ok(json!({ "ok": true, "plugin": ctx.plugin_id })),
            other => Err(PluginRpcError::method_not_found(other)),
        }
    }

    async fn on_connected(&self, handle: PluginBackendHandle) {
        let _ = handle.emit_event("ready", json!({ "ts": 1 })).await;
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let cfg = PluginBackendConfig::for_native("com.example.my-daemon");
    run_plugin_backend(cfg, MyHandler).await?;
    Ok(())
}
```

The SDK automatically handles `UdsClientHello::PluginBackend`, reconnect backoff, dual-priority queues (reliable RPC/control vs coalesced ephemeral HUD/telemetry frames), and Hub-pushed system events (`appearance.changed`, `panel.visibility`, `scope.changed`).

## 2. `uds-app` Android Companion Backends (`app.sock`)

Ordinary Android apps can act as plugin backends without root privileges by connecting to `/data/system/sideveil/run/app.sock` (`0666`) using `android.net.LocalSocket`.

1. Declare your companion package(s) in `manifest.json`:
   ```json
   {
     "backend": "uds-app",
     "backendPackages": ["com.example.companion"]
   }
   ```
2. On connection, send one NDJSON hello line:
   ```json
   {"role":"pluginBackend","pluginId":"com.example.music","version":"1.0.0"}
   ```
3. `sideveild` authenticates the socket peer via `SO_PEERCRED`, maps `uid % 100_000` through `/data/system/packages.list`, and verifies the package is listed in `backendPackages`.
4. Read newline-delimited JSON-RPC requests (`{"jsonrpc":"2.0","id":"...","method":"...","params":...}`) and write matching JSON-RPC responses or plugin events (`{"event":"plugin.event","payload":{"pluginId":"...","event":"...","payload":...}}`).

## 3. Zero-WebView Native Vector HUD (`NativeHudScene`)

Plugins granted the `native-hud` capability can push declarative vector scenes either from a `uds-native` backend (via `handle.push_hud_scene(scene)`) or from a WebView (`hud.updateScene` / `client.hud.updateScene(scene)`). Render Host rasterizes primitives with `libsideveil_hud.so` (`vello_cpu` NEON) and composites CJK/tabular text with Android HWUI — zero WebView memory overhead.

```json
{
  "pluginId": "example.native-perf-hud",
  "widthDp": 200,
  "heightDp": 88,
  "cornerRadiusDp": 18,
  "backdrop": "native-glass",
  "clickThrough": false,
  "anchorXPercent": 4,
  "anchorYPercent": 12,
  "primitives": [
    {
      "kind": "sparkline",
      "x": 12, "y": 40, "w": 176, "h": 34,
      "values": [18, 24, 21, 35, 29],
      "min": 0, "max": 100,
      "strokeArgb": 4281915647,
      "strokeWidth": 1.5,
      "smooth": true
    }
  ],
  "texts": [
    {
      "x": 12, "y": 14,
      "text": "CPU 29%",
      "sizeSp": 12,
      "colorArgb": 4294047225,
      "align": "start",
      "bold": true,
      "tabularNums": true
    }
  ]
}
```

Supported primitive kinds (max `64` primitives, max `32` text items per scene): `roundedPill`, `progressBar`, `sparkline`, `radialGauge`, `crosshair`, and SVG `path`. Always handle `appearance.changed` (`isDark`) to keep vector and text contrast legible across both light and dark glass surfaces.
