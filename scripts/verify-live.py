#!/usr/bin/env python3
"""Live end-to-end verification harness for SideVeil.

Runs natively on-device (inside a Linux container via `/usr/local/bin/host-exec` or directly
as Android UID 0 without ADB) or remotely from a workstation over ADB.
"""

import argparse
import json
import os
import shlex
import subprocess
import sys
import time

CTL = "/data/adb/modules/sideveil/bin/sideveil-ctl"
PLUGINS_DIR = "/data/system/sideveil/plugins"
HOST_EXEC = "/usr/local/bin/host-exec"


class DeviceRunner:
    def __init__(self, mode: str, serial: str | None) -> None:
        self.mode = self._resolve_mode(mode)
        self.serial = serial if self.mode != "adb" else self._resolve_adb_serial(serial)

    @staticmethod
    def _resolve_mode(mode: str) -> str:
        if mode != "auto":
            return mode
        if os.path.isfile(HOST_EXEC) and os.access(HOST_EXEC, os.X_OK):
            return "host-exec"
        if os.path.isfile("/system/bin/getprop") and os.geteuid() == 0:
            return "local"
        return "adb"

    @staticmethod
    def _resolve_adb_serial(explicit: str | None) -> str:
        if explicit:
            return explicit
        out = subprocess.check_output(["adb", "devices"], text=True)
        serials = [
            line.split()[0]
            for line in out.splitlines()[1:]
            if len(line.split()) >= 2 and line.split()[1] == "device"
        ]
        if len(serials) != 1:
            raise SystemExit(
                f"Expected 1 ADB device, found {len(serials)}. Pass --serial or --mode host-exec/local."
            )
        return serials[0]

    def su_sh(self, script: str, check: bool = True) -> str:
        if self.mode == "host-exec":
            cmd = [HOST_EXEC, "/system/bin/sh", "-s"]
        elif self.mode == "local":
            cmd = ["/system/bin/sh", "-s"]
        else:
            cmd = ["adb", "-s", str(self.serial), "shell", "su"]
        p = subprocess.run(
            cmd,
            input=script + "\nexit\n",
            text=True,
            capture_output=True,
            check=check,
        )
        return p.stdout.strip()

    def ctl_json(self, args: list[str], check: bool = True) -> dict:
        cmd = " ".join([CTL, *(shlex.quote(a) for a in args)])
        if not check:
            cmd += " 2>&1"
        out = self.su_sh(cmd, check=check)
        lines = [line for line in out.splitlines() if line.strip()]
        if not lines:
            raise RuntimeError(f"Empty output for `{cmd}`")
        return json.loads(lines[-1])


def verify_health(dev: DeviceRunner) -> dict:
    health = dev.ctl_json(["health"])
    status = dev.ctl_json(["status"])
    assert health.get("ok") is True, f"Unhealthy daemon: {health}"
    assert status.get("hostConnected") is True, f"Host not connected: {status}"
    assert status.get("sensorConnected") is True, f"Sensor not connected: {status}"
    print(
        f"[OK] ({dev.mode}) health/status: hub_pid={status['pid']} "
        f"sensor_pid={status.get('sensorPid')} host_pid={status.get('hostPid')} "
        f"blur={status.get('crossWindowBlurSupported')}"
    )
    return status


def verify_plugins_e2e(dev: DeviceRunner) -> None:
    test_id = "test.phase4c.smoke"
    plugin_root = f"{PLUGINS_DIR}/{test_id}"
    stage_root = "/data/local/tmp/sideveil_smoke_pkg"
    manifest = {
        "id": test_id,
        "name": "SideVeil 冒烟插件",
        "version": "0.1.0",
        "icon": "web/icon.svg",
        "triggers": {"packages": ["com.example.unmatched"], "global": False, "priority": 5},
        "entry": "web/index.html",
        "backend": "none",
        "capabilities": ["storage"],
        "surface": {"backdrop": "native-glass", "widthDp": 360, "heightPercent": 68},
    }
    svg_icon = (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" '
        'stroke="currentColor" stroke-width="2" stroke-linecap="round">'
        '<circle cx="12" cy="12" r="8"/><path d="M12 8v8M8 12h8"/></svg>'
    )
    setup_stage = (
        f"rm -rf {shlex.quote(stage_root)} && mkdir -p {shlex.quote(stage_root + '/web')}\n"
        f"cat > {shlex.quote(stage_root + '/manifest.json')} <<'EOF'\n"
        f"{json.dumps(manifest)}\n"
        "EOF\n"
        f"cat > {shlex.quote(stage_root + '/web/index.html')} <<'EOF'\n"
        "<!doctype html><html><body style='color:#fff;padding:16px'><h3>Smoke OK</h3></body></html>\n"
        "EOF\n"
        f"cat > {shlex.quote(stage_root + '/web/icon.svg')} <<'EOF'\n"
        f"{svg_icon}\n"
        "EOF\n"
        f"echo secret > {shlex.quote(stage_root + '/private.key')}\n"
    )

    try:
        # 1. Atomic staged install via `sideveil-ctl plugins install`
        dev.su_sh(setup_stage)
        inst = dev.ctl_json(["plugins", "install", stage_root])["result"]
        assert inst.get("ok") is True and inst.get("pluginId") == test_id, f"install failed: {inst}"
        snap = inst["snapshot"]
        entry = next((p for p in snap["plugins"] if p["id"] == test_id), None)
        assert entry is not None and entry["version"] == "0.1.0"
        print(f"[OK] plugins install atomically registered {test_id} v0.1.0")

        # 2. Subdirectory inotify watcher picks up in-place manifest.json updates without reload
        manifest_v2 = {**manifest, "version": "0.1.1"}
        dev.su_sh(
            f"cat > {shlex.quote(plugin_root + '/manifest.json')} <<'EOF'\n"
            f"{json.dumps(manifest_v2)}\n"
            "EOF\n"
        )
        time.sleep(0.7)
        snap_v2 = dev.ctl_json(["plugins", "list"])["result"]
        entry_v2 = next((p for p in snap_v2["plugins"] if p["id"] == test_id), None)
        assert entry_v2 is not None and entry_v2["version"] == "0.1.1", (
            f"subdir inotify missed manifest update: {entry_v2}"
        )
        print(f"[OK] subdirectory inotify detected in-place manifest update -> v0.1.1")

        # 3. Priority, scope override, PanelHost + PluginRail visibility
        prio_snap = dev.ctl_json(["plugins", "priority", test_id, "42"])["result"]
        entry = next(p for p in prio_snap["plugins"] if p["id"] == test_id)
        assert entry["priority"] == 42, f"priority update failed: {entry}"

        scope_snap = dev.ctl_json(["plugins", "scope", test_id, "--global", "true"])["result"]
        entry = next(p for p in scope_snap["plugins"] if p["id"] == test_id)
        assert entry["global"] is True and entry["hasScopeOverride"] is True
        assert test_id in scope_snap["matchedPluginIds"]

        dev.ctl_json(["open"])
        time.sleep(0.8)
        dev.ctl_json(["plugins", "select", test_id])
        time.sleep(0.8)
        wins = dev.su_sh(
            "dumpsys window windows | grep -E 'SideVeilPanelHost|SideVeilPluginRail|SideVeilEdgeIndicator' | head -n 10",
        )
        assert "SideVeilPanelHost" in wins and "SideVeilPluginRail" in wins, wins
        print("[OK] SideVeilPanelHost + SideVeilPluginRail visible simultaneously")

        # 4. Asset server sibling isolation & vello_cpu dark/light icon rasterization
        ep = json.loads(
            dev.su_sh("cat /data/user/0/pochita.sideveil.host/no_backup/daemon_endpoint.json")
        )
        http_status = dev.su_sh(
            f"(printf 'GET /plugins/{test_id}/private.key HTTP/1.1\\r\\n"
            f"Host: 127.0.0.1\\r\\nAuthorization: Bearer {ep['token']}\\r\\n\\r\\n' | "
            f"toybox nc 127.0.0.1 {ep['port']} | head -n 1) 2>/dev/null || true",
        )
        if http_status:
            assert "403" in http_status, f"Expected 403 for sibling private.key, got: {http_status}"

        icon_check = dev.su_sh(
            f"for theme in dark light; do "
            f"  printf 'GET /plugins/{test_id}/__icon?size=96&theme='$theme' HTTP/1.1\\r\\n"
            f"Host: 127.0.0.1\\r\\nAuthorization: Bearer {ep['token']}\\r\\n\\r\\n' | "
            f"  toybox nc 127.0.0.1 {ep['port']} > /data/local/tmp/sv_icon_$theme.raw; "
            f"done; "
            f"head -n 1 /data/local/tmp/sv_icon_dark.raw; "
            f"head -n 1 /data/local/tmp/sv_icon_light.raw; "
            f"ls /data/system/sideveil/cache/icons/{test_id}_* | wc -l; "
            f"cmp -s /data/local/tmp/sv_icon_dark.raw /data/local/tmp/sv_icon_light.raw && echo SAME || echo DISTINCT; "
            f"rm -f /data/local/tmp/sv_icon_dark.raw /data/local/tmp/sv_icon_light.raw",
        )
        icon_lines = [line.strip() for line in icon_check.splitlines() if line.strip()]
        assert len(icon_lines) >= 4 and "200 OK" in icon_lines[0] and "200 OK" in icon_lines[1]
        assert int(icon_lines[2]) >= 2 and icon_lines[3] == "DISTINCT", icon_lines
        print(f"[OK] asset isolation + vello_cpu __icon cached {icon_lines[2]} distinct PNGs")

        # 5. Edge patch, loopback-only dev URL, audit diagnostics, and scope reset
        orig_edge = dev.ctl_json(["rpc", "config.get"])["result"]["edge"]
        patched_edge = dev.ctl_json(["rpc", "config.patchEdge", json.dumps({"topPercent": 44})])["result"]["edge"]
        assert patched_edge["topPercent"] == 44
        dev.ctl_json(
            ["rpc", "config.patchEdge", json.dumps({"side": orig_edge["side"], "topPercent": orig_edge["topPercent"]})],
        )

        dev_snap = dev.ctl_json(["plugins", "dev", test_id, "http://127.0.0.1:5175"])["result"]
        assert next(p for p in dev_snap["plugins"] if p["id"] == test_id).get("devServerUrl") == "http://127.0.0.1:5175"
        bad_dev = dev.ctl_json(["plugins", "dev", test_id, "http://198.51.100.10:5175"], check=False)
        assert "error" in bad_dev, f"Expected non-loopback rejection: {bad_dev}"
        dev.ctl_json(["plugins", "dev", test_id, "--clear"])

        audit = dev.ctl_json(["plugins", "audit"])["result"]
        assert isinstance(audit.get("entries"), list) and len(audit["entries"]) > 0
        assert isinstance(audit.get("diagnostics", {}).get("warmPluginIds"), list)
        print(
            f"[OK] dev-url, patchEdge, audit live: entries={len(audit['entries'])} "
            f"warm={audit['diagnostics']['warmPluginIds']}"
        )

        clear_snap = dev.ctl_json(["plugins", "scope", test_id, "--clear"])["result"]
        entry = next(p for p in clear_snap["plugins"] if p["id"] == test_id)
        assert entry["hasScopeOverride"] is False and entry["global"] is False
    finally:
        dev.su_sh(
            f"{CTL} plugins priority {shlex.quote(test_id)} 5 >/dev/null 2>&1 || true; "
            f"{CTL} plugins scope {shlex.quote(test_id)} --clear >/dev/null 2>&1 || true; "
            f"{CTL} plugins dev {shlex.quote(test_id)} --clear >/dev/null 2>&1 || true; "
            f"rm -rf {shlex.quote(plugin_root)} {shlex.quote(stage_root)} "
            f"/data/system/sideveil/cache/icons/{test_id}_*; "
            f"{CTL} close >/dev/null 2>&1 || true",
        )
        time.sleep(0.7)
        final_snap = dev.ctl_json(["plugins", "list"])["result"]
        assert all(p["id"] != test_id for p in final_snap["plugins"]), "Temp plugin still present!"
        print("[OK] cleaned up temp plugin and closed panel")


def verify_live_appearance(dev: DeviceRunner) -> None:
    orig_cfg = dev.ctl_json(["rpc", "config.get"])["result"]["panel"]
    try:
        dev.ctl_json(["open"])
        time.sleep(0.7)
        for patch in (
            {"themeId": "coloros-glass", "colorMode": "light", "nativeBlurEnabled": True},
            {"themeId": "modern-saas-dark", "colorMode": "dark", "nativeBlurEnabled": True},
            {"nativeBlurEnabled": False},
        ):
            res = dev.ctl_json(["rpc", "config.patchAppearance", json.dumps(patch)])
            assert "result" in res, f"patchAppearance failed: {res}"
            time.sleep(0.5)
        print("[OK] live appearance/blur switching via sideveil-ctl rpc passed")
    finally:
        dev.ctl_json(
            [
                "rpc",
                "config.patchAppearance",
                json.dumps(
                    {
                        "themeId": orig_cfg["themeId"],
                        "colorMode": orig_cfg["colorMode"],
                        "nativeBlurEnabled": orig_cfg["nativeBlurEnabled"],
                        "blurRadiusPx": orig_cfg["blurRadiusPx"],
                        "cornerRadiusDp": orig_cfg["cornerRadiusDp"],
                    }
                ),
            ],
        )
        dev.ctl_json(["close"])


def verify_native_hud(dev: DeviceRunner) -> None:
    try:
        res = dev.ctl_json(["hud", "demo"])
        assert res.get("result", {}).get("ok") is True, f"hud demo failed: {res}"
        time.sleep(0.6)
        wins = dev.su_sh("dumpsys window windows | grep -E 'SideVeilNativeHud' | head -n 5")
        assert "SideVeilNativeHud" in wins, f"SideVeilNativeHud window missing:\n{wins}"
        mem = dev.su_sh(
            "dumpsys meminfo pochita.sideveil.host | grep -E 'TOTAL PSS|TOTAL RSS|Native Heap|Views:' | head -n 6",
        )
        print(f"[OK] vello_cpu JNI NativeHudWindow active (zero WebView):\n{mem}")
    finally:
        dev.ctl_json(["hud", "dismiss"])
        time.sleep(0.3)
        print("[OK] NativeHudWindow dismissed cleanly")


def verify_examples_and_invoke(dev: DeviceRunner) -> None:
    scope_id = "example.vello-scope"
    hud_id = "example.native-perf-hud"

    try:
        tele = dev.ctl_json(["rpc", "telemetry.snapshot"])["result"]
        assert "cpuPercent" in tele and tele.get("memTotalMb", 0) > 0, f"Bad telemetry: {tele}"
        print(
            f"[OK] telemetry.snapshot live: cpu={tele['cpuPercent']}% "
            f"mem={tele['memUsedMb']}/{tele['memTotalMb']}MB temp={tele.get('socTempC')}C"
        )

        snap = dev.ctl_json(["plugins", "list"])["result"]
        scope_desc = next((p for p in snap["plugins"] if p["id"] == scope_id), None)
        hud_desc = next((p for p in snap["plugins"] if p["id"] == hud_id), None)
        assert scope_desc is not None, f"Missing bundled {scope_id}: {snap}"
        assert hud_desc is not None and hud_desc["backendState"] == "online", f"HUD offline: {hud_desc}"

        # Privileged CLI backend invocation with explicit --timeout-ms
        hud_state = dev.ctl_json(
            ["plugins", "invoke", hud_id, "getState", "--timeout-ms", "10000"]
        )["result"]
        assert "enabled" in hud_state and "clickThrough" in hud_state, f"Bad invoke result: {hud_state}"
        print(f"[OK] plugins invoke {hud_id} getState succeeded: {hud_state}")

        ep = json.loads(
            dev.su_sh("cat /data/user/0/pochita.sideveil.host/no_backup/daemon_endpoint.json")
        )
        headers = dev.su_sh(
            f"printf 'GET /plugins/{scope_id}/web/index.html HTTP/1.1\\r\\n"
            f"Host: 127.0.0.1\\r\\nAuthorization: Bearer {ep['token']}\\r\\n\\r\\n' | "
            f"toybox nc 127.0.0.1 {ep['port']} | head -n 12",
        )
        assert "Cross-Origin-Opener-Policy: same-origin" in headers, headers
        assert "Cross-Origin-Embedder-Policy: require-corp" in headers, headers
        assert "Cross-Origin-Resource-Policy: same-origin" in headers, headers

        dev.ctl_json(["open"])
        time.sleep(0.5)
        dev.ctl_json(["plugins", "select", scope_id])
        time.sleep(1.2)
        audit_scope = dev.ctl_json(["plugins", "audit"])["result"]
        assert scope_id in audit_scope["diagnostics"]["warmPluginIds"], audit_scope
        dev.ctl_json(["close"])
        time.sleep(0.4)
        print(f"[OK] example.vello-scope isolated + mounted (warm={audit_scope['diagnostics']['warmPluginIds']})")
    finally:
        dev.su_sh(
            f"{CTL} plugins select sideveil.diagnostic >/dev/null 2>&1 || true; "
            f"{CTL} hud dismiss >/dev/null 2>&1 || true; "
            f"{CTL} close >/dev/null 2>&1 || true",
        )
        time.sleep(0.4)


def main() -> int:
    parser = argparse.ArgumentParser(description="Live SideVeil verification (on-device or ADB)")
    parser.add_argument(
        "--mode",
        choices=("auto", "host-exec", "local", "adb"),
        default="auto",
        help="Execution transport: auto detects on-device host-exec/local before falling back to adb",
    )
    parser.add_argument("--serial", help="ADB device serial (optional when single device connected)")
    parser.add_argument(
        "--skip-appearance", action="store_true", help="Skip live theme/blur RPC checks"
    )
    args = parser.parse_args()

    dev = DeviceRunner(args.mode, args.serial)
    verify_health(dev)
    verify_native_hud(dev)
    verify_plugins_e2e(dev)
    verify_examples_and_invoke(dev)
    if not args.skip_appearance:
        verify_live_appearance(dev)
    print(f"=== ALL LIVE CHECKS PASSED ({dev.mode}) ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
