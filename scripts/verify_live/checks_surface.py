"""Live appearance, native HUD, and bundled example plugin verification checks."""

import json
import time

from .runner import CTL, DeviceRunner


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
        print(
            f"[OK] example.vello-scope isolated + mounted (warm={audit_scope['diagnostics']['warmPluginIds']})"
        )
    finally:
        dev.su_sh(
            f"{CTL} plugins select sideveil.diagnostic >/dev/null 2>&1 || true; "
            f"{CTL} hud dismiss >/dev/null 2>&1 || true; "
            f"{CTL} close >/dev/null 2>&1 || true",
        )
        time.sleep(0.4)
