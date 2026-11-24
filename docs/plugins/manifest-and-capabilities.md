# Manifest & Capabilities Reference (`manifest.json` v1)

Every SideVeil plugin is defined by a `manifest.json` file validated against [`contracts/schemas/plugin-manifest.schema.json`](../../contracts/schemas/plugin-manifest.schema.json).

## Complete Schema Overview

```json
{
  "schemaVersion": 1,
  "apiVersion": 1,
  "minHostVersion": "0.1.0",
  "id": "com.example.toolkit",
  "name": "Toolkit",
  "version": "1.0.0",
  "description": "Concise summary shown in the Control Plane",
  "author": "Example Author",
  "homepage": "https://example.com/toolkit",
  "icon": "web/icon.svg",
  "defaultEnabled": true,
  "entry": "web/index.html",
  "backend": "none",
  "capabilities": ["storage", "toast"],
  "triggers": {
    "packages": ["com.example.targetapp"],
    "activities": [],
    "global": false,
    "priority": 10
  },
  "surface": {
    "backdrop": "native-glass",
    "presentation": "drawer",
    "crossOriginIsolated": false,
    "keepAlive": false,
    "widthDp": 360,
    "heightPercent": 72
  },
  "network": {
    "allowedOrigins": [],
    "allowCleartext": false,
    "spaFallback": false
  }
}
```

### Top-Level Fields

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `schemaVersion` | `integer` | No (default `1`) | Manifest schema version (`SIDEVEIL_PROTOCOL_VERSIONS.MANIFEST_SCHEMA_VERSION`). |
| `apiVersion` | `integer` | No (default `1`) | Target SideVeil plugin API version (`SIDEVEIL_PROTOCOL_VERSIONS.PLUGIN_API_VERSION`). |
| `minHostVersion` | `string` | No | Optional minimum host SemVer version string. |
| `id` | `string` | **Yes** | Reverse-DNS identifier (`[a-z0-9._-]+`); **must match** the parent directory name. |
| `name` | `string` | **Yes** | Human-readable display name shown in the Plugin Rail and Control Plane. |
| `version` | `string` | **Yes** | SemVer plugin version string (e.g., `1.0.0`). |
| `description` | `string` | No | Short summary displayed in Plugin Manager cards and the detail sheet. |
| `author` | `string` | No | Plugin author or organization name. |
| `homepage` | `string` | No | Documentation or repository URL. |
| `icon` | `string` | No | Relative path to an SVG icon rasterized and cached by Hub (`sideveil-gfx`). |
| `defaultEnabled` | `boolean` | No (default `true`) | Initial enabled state before user overrides in `plugins.json`. Bundled examples use `false`. |
| `entry` | `string` | Conditional | Relative HTML entry path (required for non-embed WebView plugins; must stay inside plugin dir). |
| `embed` | `object` | Conditional | Web Embed configuration (`url` required). Mutually exclusive with privileged `entry` plugins. |
| `backend` | `enum` | No (`"none"`) | `"none"`, `"uds-native"` (`hub.sock`), or `"uds-app"` (`app.sock`). |
| `backendPackages` | `string[]` | Conditional | Explicit Android package allowlist permitted to connect via `app.sock` for `uds-app` plugins. |
| `daemon` | `object` | No | Declarative supervision spec for `uds-native` daemons (`binary`, `args`, `env`, `restartPolicy`, `maxRestarts`, `backoffMs`). |
| `capabilities` | `string[]` | No (`[]`) | Capability ACL tokens requested by the plugin. |
| `triggers` | `object` | No | Foreground matching rules: `packages`, `activities`, `global`, `priority` (higher integer wins). |
| `surface` | `object` | No | `backdrop` (`native-glass`/`transparent`/`opaque`), `presentation` (`drawer`/`peek`/`pinned`/`wide`/`native-hud`), `crossOriginIsolated`, `keepAlive`, `widthDp`, `heightPercent`. |
| `network` | `object` | No | `allowedOrigins` (HTTPS/private allowlist), `allowCleartext` (loopback/RFC1918 only), `spaFallback`. |

### Provenance Classification (`PluginOrigin`)

The Hub assigns an immutable `origin` to every registered plugin in `PluginRuntimeDescriptor`:
- `builtin`: System-resident panel (`sideveil.diagnostic`), non-uninstallable.
- `bundled`: Official module-packaged examples (`example.*`), defaulting to `defaultEnabled: false`.
- `installed`: User-installed third-party plugins under `/data/system/sideveil/plugins/<id>/`.
- `web-embed`: Zero-privilege user-created or manifest-declared isolated web embeds.

## Capabilities & Risk Tiers

All 12 capabilities are grouped into three security tiers enforced by Hub (`sideveild`) and Render Host (`SideVeilHost`):

| Capability | Risk Tier | Grant Policy | Scope & Behavior |
| --- | --- | --- | --- |
| `system-exec` | **High** | Explicit user grant required | Executes shell commands via `system.exec`. Blocked by default (`-32003`) until approved. |
| `storage` | **Medium** | Explicit user grant required | Reads/writes jailed files under `/data/system/sideveil/data/<id>/` (`fs.read`, `fs.writeAtomic`). |
| `clipboard` | **Medium** | Explicit user grant required | Host-backed `clipboard.get` / `clipboard.set` for unfocused overlay WebViews. |
| `native-hud` | **Medium** | Explicit user grant required | Projects zero-WebView `NativeHudScene` overlays (`hud.updateScene`, `hud.dismiss`). |
| `inter-plugin-bus` | **Medium** | Explicit user grant required | Publishes and receives topic events on `plugin.busPublish` / `plugin.busMessage`. |
| `network-loopback` | **Medium** | Explicit user grant required | Permits WebView CSP/requests to `127.0.0.1` / `localhost` origins listed in `network.allowedOrigins`. |
| `network-lan` | **Medium** | Explicit user grant required | Permits requests to RFC1918 private IPs and `.local` origins listed in `network.allowedOrigins`. |
| `network-internet` | **Medium** | Explicit user grant required | Permits HTTPS requests to public origins explicitly listed in `network.allowedOrigins`. |
| `network-context` | **Medium** | Explicit user grant required | Queries `network.context` and receives `network.contextChanged` (transport, SSID, VPN). |
| `toast` | **Low** | Auto-granted on install | Displays short Android Toast messages via `ui.toast`. |
| `haptics` | **Low** | Auto-granted on install | Triggers stepped host haptic feedback via `haptics.trigger`. |
| `telemetry` | **Low** | Auto-granted on install | Reads zero-subprocess Linux CPU, memory, and SoC temperature snapshots (`telemetry.snapshot`). |

## Explicit Grant & Upgrade Diff Semantics

- **Fresh Install**: Low-risk capabilities may be granted automatically; medium- and high-risk capabilities remain in `pendingCapabilities` (and `approvalStatus: "pending-review"`) until explicitly approved in the Control Plane or via `sideveil-ctl plugins grant`.
- **Upgrades**: When a plugin is updated to a newer version that adds **new** medium/high-risk capabilities or adds a `daemon` spec, previously granted capabilities remain active while the newly added capabilities enter `pendingCapabilities` and `approvalStatus` transitions to `"needs-reapproval"`.
- **Daemon Approval**: Any `installed` plugin declaring a `daemon` spec starts with `daemonApproved: false` and `daemonStatus: "awaiting-approval"` until explicitly approved.
- **Runtime Enforcement**: Unapproved capability calls fail deterministically with JSON-RPC error `-32003` (`PERMISSION_DENIED`) and are recorded in the Hub audit ring.
