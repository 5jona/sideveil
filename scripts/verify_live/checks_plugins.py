"""End-to-end plugin lifecycle, inotify, asset isolation, and audit verification."""

import json
import shlex
import time

from .runner import CTL, PLUGINS_DIR, DeviceRunner


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

    orig_edge: dict | None = None
    body_succeeded = False

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
        print("[OK] subdirectory inotify detected in-place manifest update -> v0.1.1")

        # 2b. Loopback-only dev URL validation BEFORE mounting/selecting test_id so no warm
        # WebView attempts to connect to a closed :5175 dev port and emit synthetic 502 errors.
        dev_snap = dev.ctl_json(["plugins", "dev", test_id, "http://127.0.0.1:5175"])["result"]
        assert (
            next(p for p in dev_snap["plugins"] if p["id"] == test_id).get("devServerUrl")
            == "http://127.0.0.1:5175"
        )
        bad_dev = dev.ctl_json(["plugins", "dev", test_id, "http://198.51.100.10:5175"], check=False)
        assert "error" in bad_dev, f"Expected non-loopback rejection: {bad_dev}"
        cleared_dev_snap = dev.ctl_json(["plugins", "dev", test_id, "--clear"])["result"]
        assert (
            next(p for p in cleared_dev_snap["plugins"] if p["id"] == test_id).get("devServerUrl")
            is None
        )

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

        # 5. Edge patch, audit diagnostics, and scope reset
        orig_edge = dev.ctl_json(["rpc", "config.get"])["result"]["edge"]
        patched_edge = dev.ctl_json(["rpc", "config.patchEdge", json.dumps({"topPercent": 44})])[
            "result"
        ]["edge"]
        assert patched_edge["topPercent"] == 44

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
        body_succeeded = True
    finally:
        if orig_edge is not None:
            dev.ctl_json(
                [
                    "rpc",
                    "config.patchEdge",
                    json.dumps(
                        {"side": orig_edge["side"], "topPercent": orig_edge["topPercent"]}
                    ),
                ],
                check=False,
            )
        dev.su_sh(
            f"{CTL} plugins uninstall {shlex.quote(test_id)} >/dev/null 2>&1 || true; "
            f"rm -rf {shlex.quote(plugin_root)} {shlex.quote(stage_root)} "
            f"/data/system/sideveil/cache/icons/{test_id}_*; "
            f"{CTL} close >/dev/null 2>&1 || true",
            check=False,
        )
        time.sleep(0.7)
        if body_succeeded:
            final_snap = dev.ctl_json(["plugins", "list"])["result"]
            assert all(p["id"] != test_id for p in final_snap["plugins"]), (
                "Temp plugin still present!"
            )
            overrides_raw = dev.su_sh(
                "cat /data/system/sideveil/plugins.json 2>/dev/null || true", check=False
            )
            assert test_id not in overrides_raw, (
                f"Temp plugin residue '{test_id}' still present in plugins.json!"
            )
            print("[OK] cleaned up temp plugin, overrides, and closed panel")
