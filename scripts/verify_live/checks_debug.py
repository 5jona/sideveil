"""Live debug RPC checks: debug.inspectUi, CDP toggle/status, and WebView console errors."""

import re
import time

from .runner import DeviceRunner

WEBVIEW_ERROR_RE = re.compile(r"\[WebView:([^\]]+)\]\[ERROR\]", re.IGNORECASE)
WEBVIEW_TRANSPORT_ERROR_RE = re.compile(
    r"\[WebView:([^\]]+)\]\[error:(loopback|http|network)\](?:\s+code=(\d+))?",
    re.IGNORECASE,
)


def _untrusted_embed_plugin_ids(dev: DeviceRunner) -> set[str]:
    try:
        resp = dev.ctl_json(["plugins", "list"], check=False)
        payload = _unwrap_result(resp)
        plugins = payload.get("plugins", [])
        if isinstance(plugins, list):
            return {
                p["id"]
                for p in plugins
                if isinstance(p, dict) and isinstance(p.get("id"), str) and p.get("embed") is not None
            }
    except Exception:  # noqa: BLE001
        pass
    return set()


def _is_forbidden_webview_error_line(line: str, untrusted_embed_ids: set[str]) -> bool:
    if WEBVIEW_ERROR_RE.search(line):
        return True
    match = WEBVIEW_TRANSPORT_ERROR_RE.search(line)
    if not match:
        return False
    plugin_id = match.group(1)
    if plugin_id in untrusted_embed_ids:
        return False
    kind = match.group(2).lower()
    if kind == "http":
        code = int(match.group(3)) if match.group(3) else None
        return code is None or code >= 400
    return True


def _unwrap_result(payload: dict) -> dict:
    inner = payload.get("result")
    return inner if isinstance(inner, dict) else payload


def verify_debug_inspect_ui(dev: DeviceRunner) -> dict:
    """Validates `sideveil-ctl debug inspect-ui` and `debug reset-frames`."""
    try:
        reset_resp = _unwrap_result(dev.ctl_json(["debug", "reset-frames"]))
        assert reset_resp.get("ok") is True, f"debug reset-frames failed: {reset_resp}"

        dev.ctl_json(["open"])
        time.sleep(0.5)
        envelope = dev.ctl_json(["debug", "inspect-ui", "--timeout-ms", "8000"])
        result = _unwrap_result(envelope)
        assert isinstance(result, dict), f"Missing result in debug.inspectUi: {envelope}"
        for required_key in ("display", "panel", "agsl", "frameMetrics"):
            assert required_key in result, (
                f"debug.inspectUi missing '{required_key}' key: {sorted(result.keys())}"
            )
        display = result["display"]
        assert isinstance(display, dict), f"Invalid display field: {display}"
        extras = []
        if "dormant" in result:
            extras.append(f"dormant={result.get('dormant')}")
        if "gestureStats" in result:
            extras.append("gestureStats=present")
        extra_str = f" {' '.join(extras)}" if extras else ""
        print(
            f"[OK] debug.inspectUi + reset-frames: display={display.get('widthPx')}x{display.get('heightPx')} "
            f"refreshHz={display.get('refreshRateHz') or display.get('refreshHz')}{extra_str} "
            f"keys={sorted(result.keys())}"
        )
        return result
    finally:
        dev.ctl_json(["close"], check=False)
        time.sleep(0.3)


def verify_debug_cdp(dev: DeviceRunner) -> dict:
    """Validates `sideveil-ctl debug cdp status` and `on`/`off` toggle smoke."""
    initial = _unwrap_result(dev.ctl_json(["debug", "cdp", "status"]))
    assert "webviewDebuggingEnabled" in initial, f"Bad cdp status: {initial}"
    orig_enabled = bool(initial["webviewDebuggingEnabled"])
    try:
        on_resp = _unwrap_result(dev.ctl_json(["debug", "cdp", "on"]))
        assert on_resp.get("ok") is True, f"debug cdp on failed: {on_resp}"
        assert on_resp.get("webviewDebuggingEnabled") is True, on_resp
        host_pid = on_resp.get("hostPid")
        if host_pid is not None:
            assert on_resp.get("cdpSocket") == f"webview_devtools_remote_{host_pid}", on_resp

        status_on = _unwrap_result(dev.ctl_json(["debug", "cdp", "status"]))
        assert status_on.get("webviewDebuggingEnabled") is True, status_on
        print(
            f"[OK] debug cdp toggle smoke passed: hostPid={status_on.get('hostPid')} "
            f"effectiveEnabled={status_on.get('effectiveEnabled')} "
            f"devServerActive={status_on.get('devServerActive')} "
            f"cdpSocket={status_on.get('cdpSocket')}"
        )
        return status_on
    finally:
        restore_cmd = "on" if orig_enabled else "off"
        dev.ctl_json(["debug", "cdp", restore_cmd], check=False)


def verify_webview_console_zero_errors(
    dev: DeviceRunner, since_seq: int | None = None
) -> dict:
    """Asserts that `sideveil-ctl logs` contains zero WebView console or trusted transport errors."""
    logs_resp: dict
    if since_seq is not None:
        try:
            logs_resp = dev.ctl_json(["logs", "--since-seq", str(since_seq)])
        except Exception:  # noqa: BLE001
            logs_resp = dev.ctl_json(["logs"])
    else:
        logs_resp = dev.ctl_json(["logs"])
    payload = _unwrap_result(logs_resp)
    lines = payload.get("lines", [])
    assert isinstance(lines, list), f"Unexpected logs response: {logs_resp}"
    untrusted_embed_ids = _untrusted_embed_plugin_ids(dev)
    errors = [
        line
        for line in lines
        if isinstance(line, str) and _is_forbidden_webview_error_line(line, untrusted_embed_ids)
    ]
    assert not errors, "Found WebView console/transport errors in daemon logs:\n" + "\n".join(errors)
    seq_info = f", since_seq={since_seq}" if since_seq is not None else ""
    print(f"[OK] WebView zero-error assertion passed ({len(lines)} log lines scanned{seq_info})")
    return {"scannedLines": len(lines), "webviewErrors": 0, "sinceSeq": since_seq}
