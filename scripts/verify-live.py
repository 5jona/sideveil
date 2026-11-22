#!/usr/bin/env python3
"""Live end-to-end verification harness for SideVeil.

Runs natively on-device (inside Strata via `/usr/local/bin/host-exec` or directly
as Android UID 0) or remotely from a workstation over Strata container SSH.
"""

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Any, Callable

from verify_live import (
    DeviceRunner,
    verify_debug_cdp,
    verify_debug_inspect_ui,
    verify_examples_and_invoke,
    verify_health,
    verify_live_appearance,
    verify_native_hud,
    verify_plugins_e2e,
    verify_webview_console_zero_errors,
)


def run_check(
    results: list[dict[str, Any]],
    name: str,
    fn: Callable[[], Any],
) -> bool:
    started = time.monotonic()
    try:
        detail = fn()
        results.append(
            {
                "name": name,
                "ok": True,
                "durationMs": int((time.monotonic() - started) * 1000),
                "detail": detail if isinstance(detail, dict) else None,
            }
        )
        return True
    except Exception as exc:  # noqa: BLE001
        results.append(
            {
                "name": name,
                "ok": False,
                "durationMs": int((time.monotonic() - started) * 1000),
                "error": str(exc),
            }
        )
        print(f"[FAIL] {name}: {exc}", file=sys.stderr)
        return False


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Live SideVeil verification (on-device host-exec/local or container SSH)"
    )
    parser.add_argument(
        "--mode",
        choices=("auto", "ssh", "host-exec", "local"),
        default="auto",
        help="Execution transport: auto checks STRATA_SSH_HOST / SSH_TARGET, host-exec, and local",
    )
    parser.add_argument(
        "--ssh",
        help="Container SSH target (<host|user@host>, default: $STRATA_SSH_HOST / $SSH_TARGET)",
    )
    parser.add_argument(
        "--ssh-port",
        type=int,
        default=None,
        help="SSH port for --ssh mode (default: $STRATA_SSH_PORT / $SSH_PORT / 2222)",
    )
    parser.add_argument(
        "--skip-appearance", action="store_true", help="Skip live theme/blur RPC checks"
    )
    parser.add_argument(
        "--skip-debug",
        action="store_true",
        help="Skip debug.inspectUi, CDP toggle, and WebView console checks",
    )
    parser.add_argument(
        "--report-json",
        help="Optional path to write a machine-readable JSON verification summary",
    )
    args = parser.parse_args()

    dev = DeviceRunner(args.mode, ssh=args.ssh, ssh_port=args.ssh_port)
    checks: list[tuple[str, Callable[[], Any]]] = [
        ("health", lambda: verify_health(dev)),
        ("native_hud", lambda: verify_native_hud(dev)),
        ("plugins_e2e", lambda: verify_plugins_e2e(dev)),
        ("examples_and_invoke", lambda: verify_examples_and_invoke(dev)),
    ]
    if not args.skip_appearance:
        checks.append(("live_appearance", lambda: verify_live_appearance(dev)))
    if not args.skip_debug:
        checks.extend(
            [
                ("debug_inspect_ui", lambda: verify_debug_inspect_ui(dev)),
                ("debug_cdp", lambda: verify_debug_cdp(dev)),
                ("webview_console_zero_errors", lambda: verify_webview_console_zero_errors(dev)),
            ]
        )

    results: list[dict[str, Any]] = []
    all_ok = True
    for name, fn in checks:
        if not run_check(results, name, fn):
            all_ok = False
            break

    if args.report_json:
        report = {
            "ok": all_ok,
            "mode": dev.mode,
            "target": dev.ssh_target or dev.mode,
            "checks": results,
        }
        report_path = Path(args.report_json)
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    if not all_ok:
        return 1
    print(f"=== ALL LIVE CHECKS PASSED ({dev.mode}) ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
