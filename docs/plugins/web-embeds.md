# Web Embeds (`web-embed`)

Web Embeds pin existing LAN or WAN web dashboards (such as Home Assistant, OpenClash, OpenWrt LuCI, or Zashboard) directly into the SideVeil sidebar with **zero privileged host bridge**.

## Security Guarantees

- `origin` is always `"web-embed"` and `backend` is always `"none"`.
- Render Host **never** attaches `window.__POCHITA_HOST__` or any `WebMessageListener` to Web Embed WebViews.
- Navigation is strictly locked to `embed.allowedOrigins` (defaulting to the origins of `embed.url` and optional `embed.remoteUrl`).
- Cleartext HTTP (`allowCleartext: true`) is permitted **only** for loopback (`127.0.0.1`, `localhost`, `[::1]`) and RFC1918 / `.local` private hosts; public addresses always require HTTPS.

## Creating & Configuring Web Embeds

Users can create Web Embeds directly in the Control Plane (**`插件管理 -> 网页面板 -> 添加网页面板`**) using built-in presets or custom URLs, or programmatically via `sideveil-ctl`:

```bash
sideveil-ctl rpc plugins.createWebEmbed '{
  "id": "lan.homeassistant",
  "name": "Home Assistant",
  "url": "http://homeassistant.local:8123",
  "remoteUrl": "https://ha.example.com:8123",
  "lanSsids": ["Home-5G", "Home-WiFi"],
  "allowCleartext": true,
  "keepAlive": true,
  "keepAliveGraceSec": 180,
  "showToolbar": true,
  "webHistoryBack": true,
  "wideViewport": true,
  "textZoom": 95,
  "widthDp": 400,
  "heightPercent": 78,
  "global": true
}'
```

## Field Reference (`PluginEmbedPrefs`)

| Field | Default | Purpose |
| --- | --- | --- |
| `url` | *(required)* | Primary (LAN or HTTPS) URL loaded in the sidebar. |
| `remoteUrl` | `undefined` | Optional fallback/WAN HTTPS URL for automatic LAN/WAN switching. |
| `lanSsids` | `[]` | Wi-Fi SSID allowlist: when connected to a listed SSID (without VPN overriding LAN routing), Host prefers `url`; otherwise it switches to `remoteUrl`. |
| `allowedOrigins` | `[origin(url), origin(remoteUrl)]` | Explicit navigation origin allowlist. |
| `allowCleartext` | `false` | Allows HTTP on loopback/RFC1918/`.local` private hosts only. |
| `showToolbar` | `true` | Displays the compact native header bar (connection badge, LAN/WAN toggle, reload, open in external browser). |
| `webHistoryBack` | `true` | Android back gesture navigates browser history before collapsing the sidebar. |
| `wideViewport` | `true` | Enables responsive desktop/dashboard wide viewport layout. |
| `allowZoom` | `false` | Enables pinch-to-zoom inside the embedded webpage. |
| `textZoom` | `100` | WebView text zoom percentage (`50`–`200`). |
| `keepAlive` (`surface`) | `false` | Keeps WebView warm across panel collapses to preserve login state and live WebSockets. |
| `keepAliveGraceSec` | `180` | Idle grace period (`0`–`1800`s) before reclaiming a background keep-alive WebView. |
