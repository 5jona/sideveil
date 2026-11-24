"""Modular live verification checks for SideVeil."""

from .checks_debug import (
    verify_debug_cdp,
    verify_debug_inspect_ui,
    verify_webview_console_zero_errors,
)
from .checks_ecosystem import (
    verify_daemon_lifecycle,
    verify_svp_and_grants,
)
from .checks_plugins import verify_plugins_e2e
from .checks_surface import (
    verify_examples_and_invoke,
    verify_live_appearance,
    verify_native_hud,
)
from .runner import CTL, HOST_EXEC, PLUGINS_DIR, DeviceRunner, verify_health

__all__ = [
    "CTL",
    "HOST_EXEC",
    "PLUGINS_DIR",
    "DeviceRunner",
    "verify_daemon_lifecycle",
    "verify_debug_cdp",
    "verify_debug_inspect_ui",
    "verify_examples_and_invoke",
    "verify_health",
    "verify_live_appearance",
    "verify_native_hud",
    "verify_plugins_e2e",
    "verify_svp_and_grants",
    "verify_webview_console_zero_errors",
]
