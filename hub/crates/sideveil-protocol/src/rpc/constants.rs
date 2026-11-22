//! JSON-RPC 2.0 event names, method names, and error code constants.

pub mod events {
    pub const CONFIG_UPDATED: &str = "config.updated";
    pub const SCOPE_CHANGED: &str = "scope.changed";
    pub const GESTURE_DRAG_START: &str = "gesture.dragStart";
    pub const GESTURE_DRAG_MOVE: &str = "gesture.dragMove";
    pub const GESTURE_DRAG_END: &str = "gesture.dragEnd";
    pub const GESTURE_FULLSCREEN_HINT: &str = "gesture.fullscreenHint";
    pub const EDGE_REPOSITION: &str = "edge.reposition";
    pub const PANEL_COMMAND: &str = "panel.command";
    pub const HOST_HELLO: &str = "host.hello";
    pub const HOST_FULLSCREEN_CHANGED: &str = "host.fullscreenChanged";
    pub const HOST_DYNAMIC_COLORS: &str = "host.dynamicColors";
    pub const HOST_DIAGNOSTICS: &str = "host.diagnostics";
    pub const HOST_WEBVIEW_CONSOLE: &str = "host.webviewConsole";
    pub const HOST_SELECT_PLUGIN: &str = "host.selectPlugin";
    pub const PANEL_STATE_CHANGED: &str = "panel.stateChanged";
    pub const PANEL_VISIBILITY: &str = "panel.visibility";
    pub const PANEL_BACK_PRESSED: &str = "panel.backPressed";
    pub const PLUGINS_UPDATED: &str = "plugins.updated";
    pub const PLUGINS_ACTIVE_CHANGED: &str = "plugins.activeChanged";
    pub const PLUGIN_BACKEND_STATE: &str = "plugin.backendState";
    pub const PLUGIN_EVENT: &str = "plugin.event";
    pub const PLUGIN_BUS_MESSAGE: &str = "plugin.busMessage";
    pub const THEME_PALETTE_UPDATED: &str = "theme.paletteUpdated";
    pub const HUD_SCENE_UPDATED: &str = "hud.sceneUpdated";
    pub const HUD_DISMISSED: &str = "hud.dismissed";
    pub const NETWORK_CONTEXT_CHANGED: &str = "network.contextChanged";
    pub const PANEL_VISIBILITY_CHANGED: &str = "panel.visibilityChanged";
    pub const APPEARANCE_CHANGED: &str = "appearance.changed";
}

pub mod methods {
    pub const DAEMON_PING: &str = "daemon.ping";
    pub const DAEMON_STATUS: &str = "daemon.status";
    pub const DAEMON_HEALTH: &str = "daemon.health";
    pub const DAEMON_LOGS: &str = "daemon.logs";
    pub const CONFIG_GET: &str = "config.get";
    pub const CONFIG_PATCH_APPEARANCE: &str = "config.patchAppearance";
    pub const CONFIG_PATCH_EDGE: &str = "config.patchEdge";
    pub const CONFIG_RELOAD: &str = "config.reload";
    pub const PANEL_OPEN: &str = "panel.open";
    pub const PANEL_CLOSE: &str = "panel.close";
    pub const PANEL_TOGGLE: &str = "panel.toggle";
    pub const HOST_RESTART: &str = "host.restart";
    pub const CONTAINER_ACTION: &str = "container.action";
    pub const PLUGINS_LIST: &str = "plugins.list";
    pub const PLUGINS_RELOAD: &str = "plugins.reload";
    pub const PLUGINS_INSTALL: &str = "plugins.install";
    pub const PLUGINS_SET_ENABLED: &str = "plugins.setEnabled";
    pub const PLUGINS_SET_PRIORITY: &str = "plugins.setPriority";
    pub const PLUGINS_SET_SCOPE_OVERRIDE: &str = "plugins.setScopeOverride";
    pub const PLUGINS_SET_DEV_URL: &str = "plugins.setDevUrl";
    pub const PLUGINS_SET_EMBED_CONFIG: &str = "plugins.setEmbedConfig";
    pub const PLUGINS_CREATE_WEB_EMBED: &str = "plugins.createWebEmbed";
    pub const PLUGINS_UNINSTALL: &str = "plugins.uninstall";
    pub const PLUGINS_AUDIT: &str = "plugins.audit";
    pub const PLUGINS_SELECT: &str = "plugins.select";
    pub const NETWORK_CONTEXT: &str = "network.context";
    pub const PLUGIN_INVOKE: &str = "plugin.invoke";
    pub const PLUGIN_BUS_PUBLISH: &str = "plugin.busPublish";
    pub const FS_READ: &str = "fs.read";
    pub const FS_WRITE_ATOMIC: &str = "fs.writeAtomic";
    pub const SYSTEM_EXEC: &str = "system.exec";
    pub const TELEMETRY_SNAPSHOT: &str = "telemetry.snapshot";
    pub const CLIPBOARD_GET: &str = "clipboard.get";
    pub const CLIPBOARD_SET: &str = "clipboard.set";
    pub const HAPTICS_TRIGGER: &str = "haptics.trigger";
    pub const UI_TOAST: &str = "ui.toast";
    pub const HUD_UPDATE_SCENE: &str = "hud.updateScene";
    pub const HUD_DISMISS: &str = "hud.dismiss";
    pub const DEBUG_INSPECT_UI: &str = "debug.inspectUi";
    pub const DEBUG_SET_WEBVIEW_DEBUGGING: &str = "debug.setWebViewDebugging";
}

pub mod error_codes {
    pub const PARSE_ERROR: i32 = -32700;
    pub const INVALID_REQUEST: i32 = -32600;
    pub const METHOD_NOT_FOUND: i32 = -32601;
    pub const INVALID_PARAMS: i32 = -32602;
    pub const INTERNAL_ERROR: i32 = -32000;
    pub const PERMISSION_DENIED: i32 = -32003;
    pub const BACKEND_OFFLINE: i32 = -32010;
    pub const BACKEND_TIMEOUT: i32 = -32011;
}
