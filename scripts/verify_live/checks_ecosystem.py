"""Live verification of .svp packaging, tiered capability grants, and daemon supervision."""

import json
import shlex
import time

from .runner import CTL, DeviceRunner


def _find_plugin(snap: dict, plugin_id: str) -> dict | None:
    return next((p for p in snap.get("plugins", []) if p.get("id") == plugin_id), None)


def verify_svp_and_grants(dev: DeviceRunner) -> dict:
    """Verifies offline init/validate/pack, .svp inspect/install, upgrade diff, shadow defense, and uninstall."""
    test_id = "test.ecosystem.svp"
    base_tmp = "/data/local/tmp/sideveil_eco_test"
    v1_dir = f"{base_tmp}/v1"
    v2_dir = f"{base_tmp}/v2"
    v1_svp = f"{base_tmp}/v1.svp"
    v2_svp = f"{base_tmp}/v2.svp"
    shadow_dir = f"{base_tmp}/shadow"
    data_dir = f"/data/system/sideveil/data/{test_id}"

    try:
        dev.su_sh(f"rm -rf {shlex.quote(base_tmp)} && mkdir -p {shlex.quote(base_tmp)}")

        # 1. Offline scaffold + add medium-risk storage + validate + pack v1 (.svp)
        init_res = dev.ctl_json(["plugins", "init", test_id, "--template", "vanilla", "-o", v1_dir])
        assert init_res.get("ok") is True, f"plugins init failed: {init_res}"

        manifest_v1 = {
            "schemaVersion": 1,
            "apiVersion": 1,
            "id": test_id,
            "name": "Ecosystem SVP Test",
            "version": "1.0.0",
            "entry": "web/index.html",
            "backend": "none",
            "capabilities": ["toast", "storage"],
        }
        dev.su_sh(
            f"cat > {shlex.quote(v1_dir + '/manifest.json')} <<'EOF'\n"
            f"{json.dumps(manifest_v1)}\n"
            "EOF\n"
        )

        val_res = dev.ctl_json(["plugins", "validate", v1_dir])
        assert val_res.get("ok") is True, f"plugins validate failed: {val_res}"

        pack_res = dev.ctl_json(["plugins", "pack", v1_dir, "-o", v1_svp])
        assert pack_res.get("ok") is True, f"plugins pack failed: {pack_res}"

        # 2. Pre-install archive inspection via Hub RPC
        insp1 = dev.ctl_json(["plugins", "inspect", v1_svp])["result"]
        assert insp1.get("isUpgrade") is False, f"Expected fresh install inspection: {insp1}"
        caps1 = {c["capability"]: c for c in insp1.get("capabilities", [])}
        assert "storage" in caps1 and caps1["storage"]["requiresApproval"] is True, insp1
        assert caps1["storage"]["isNew"] is True and caps1["storage"]["alreadyGranted"] is False
        assert "toast" in caps1 and caps1["toast"]["requiresApproval"] is False, insp1

        # 3. Install v1 without explicit medium grants -> pending-review
        inst1 = dev.ctl_json(["plugins", "install", v1_svp])["result"]
        assert inst1.get("ok") is True and inst1.get("pluginId") == test_id, inst1
        desc1 = _find_plugin(inst1["snapshot"], test_id)
        assert desc1 is not None and desc1.get("origin") == "installed", desc1
        assert desc1.get("approvalStatus") == "pending-review", desc1
        assert "toast" in desc1.get("grantedCapabilities", []), desc1
        assert "storage" in desc1.get("pendingCapabilities", []), desc1
        assert "storage" not in desc1.get("grantedCapabilities", []), desc1

        # Seed a marker file in plugin data dir to verify uninstall cleans it up
        dev.su_sh(f"mkdir -p {shlex.quote(data_dir)} && echo marker > {shlex.quote(data_dir + '/note.txt')}")

        # 4. Approve pending capabilities -> approved
        grant_snap = dev.ctl_json(["plugins", "grant", test_id, "--all-pending"])["result"]
        desc_granted = _find_plugin(grant_snap, test_id)
        assert desc_granted is not None and desc_granted.get("approvalStatus") == "approved", desc_granted
        assert "storage" in desc_granted.get("grantedCapabilities", []), desc_granted
        assert desc_granted.get("pendingCapabilities") == [], desc_granted

        # 5. Build v2 adding high-risk system-exec -> inspect shows upgrade diff
        dev.su_sh(f"cp -r {shlex.quote(v1_dir)} {shlex.quote(v2_dir)}")
        manifest_v2 = {
            "schemaVersion": 1,
            "apiVersion": 1,
            "id": test_id,
            "name": "Ecosystem SVP Test",
            "version": "1.1.0",
            "entry": "web/index.html",
            "backend": "none",
            "capabilities": ["toast", "storage", "system-exec"],
        }
        dev.su_sh(
            f"cat > {shlex.quote(v2_dir + '/manifest.json')} <<'EOF'\n"
            f"{json.dumps(manifest_v2)}\n"
            "EOF\n"
        )
        dev.ctl_json(["plugins", "pack", v2_dir, "-o", v2_svp])

        insp2 = dev.ctl_json(["plugins", "inspect", v2_svp])["result"]
        assert insp2.get("isUpgrade") is True, insp2
        caps2 = {c["capability"]: c for c in insp2.get("capabilities", [])}
        assert caps2["storage"]["alreadyGranted"] is True and caps2["storage"]["isNew"] is False, caps2
        assert caps2["system-exec"]["alreadyGranted"] is False and caps2["system-exec"]["isNew"] is True, caps2

        # 6. Upgrade v2 without approving system-exec -> needs-reapproval, storage preserved
        inst2 = dev.ctl_json(["plugins", "install", v2_svp])["result"]
        desc2 = _find_plugin(inst2["snapshot"], test_id)
        assert desc2 is not None and desc2.get("version") == "1.1.0", desc2
        assert desc2.get("approvalStatus") == "needs-reapproval", desc2
        assert "storage" in desc2.get("grantedCapabilities", []), desc2
        assert "system-exec" in desc2.get("pendingCapabilities", []), desc2

        # 7. Revoke all medium/high grants
        revoked_snap = dev.ctl_json(["plugins", "grant", test_id, "--revoke-all"])["result"]
        desc_revoked = _find_plugin(revoked_snap, test_id)
        assert desc_revoked is not None, revoked_snap
        assert "storage" not in desc_revoked.get("grantedCapabilities", []), desc_revoked
        assert "toast" in desc_revoked.get("grantedCapabilities", []), desc_revoked

        # 8. Bundled ID shadowing defense (third-party cannot overwrite example.vello-scope)
        dev.su_sh(f"mkdir -p {shlex.quote(shadow_dir + '/web')}")
        shadow_manifest = {
            "schemaVersion": 1,
            "apiVersion": 1,
            "id": "example.vello-scope",
            "name": "Shadow Attempt",
            "version": "9.9.9",
            "entry": "web/index.html",
            "backend": "none",
            "capabilities": ["toast"],
        }
        dev.su_sh(
            f"cat > {shlex.quote(shadow_dir + '/manifest.json')} <<'EOF'\n"
            f"{json.dumps(shadow_manifest)}\n"
            "EOF\n"
            f"echo '<html></html>' > {shlex.quote(shadow_dir + '/web/index.html')}\n"
        )
        shadow_res = dev.ctl_json(["plugins", "install", shadow_dir], check=False)
        assert "error" in shadow_res, f"Bundled ID shadow should fail, got: {shadow_res}"

        # 9. Clean uninstall removes plugin AND its data directory
        uninst = dev.ctl_json(["plugins", "uninstall", test_id])
        assert "error" not in uninst, f"plugins uninstall failed: {uninst}"
        snap_after = dev.ctl_json(["plugins", "list"])["result"]
        assert _find_plugin(snap_after, test_id) is None, "Plugin still registered after uninstall"
        data_exists = dev.su_sh(f"[ -d {shlex.quote(data_dir)} ] && echo EXISTS || echo GONE")
        assert data_exists == "GONE", f"Plugin data dir survived uninstall: {data_dir}"

        print("[OK] .svp init/validate/pack/inspect/install/upgrade-diff/revoke/shadow-defense/uninstall passed")
        return {"pluginId": test_id, "upgradedVersion": "1.1.0", "shadowBlocked": True}
    finally:
        dev.su_sh(
            f"{CTL} plugins uninstall {shlex.quote(test_id)} >/dev/null 2>&1 || true; "
            f"rm -rf {shlex.quote(base_tmp)} {shlex.quote(data_dir)}"
        )


def verify_daemon_lifecycle(dev: DeviceRunner) -> dict:
    """Verifies generic PluginDaemonSupervisor awaiting-approval -> running -> daemon-logs -> revoke teardown."""
    hud_id = "example.native-perf-hud"
    snap_before = dev.ctl_json(["plugins", "list"])["result"]
    prev = _find_plugin(snap_before, hud_id)
    assert prev is not None, f"Missing bundled daemon plugin {hud_id}"
    assert prev.get("origin") == "bundled", f"Expected bundled origin: {prev}"
    assert prev.get("hasDaemon") is True, f"Expected hasDaemon=true: {prev}"

    prev_enabled = bool(prev.get("enabled"))
    prev_daemon_approved = bool(prev.get("daemonApproved"))
    prev_granted = list(prev.get("grantedCapabilities", []))

    try:
        # 1. Start revoked + enabled -> must stay awaiting-approval without spawning daemon
        dev.ctl_json(["plugins", "grant", hud_id, "--revoke-all"])
        dev.ctl_json(["plugins", "enable", hud_id])
        time.sleep(0.5)
        snap_wait = dev.ctl_json(["plugins", "list"])["result"]
        desc_wait = _find_plugin(snap_wait, hud_id)
        assert desc_wait is not None and desc_wait.get("daemonStatus") == "awaiting-approval", desc_wait
        assert desc_wait.get("daemonPid") is None, f"Daemon spawned before approval: {desc_wait}"

        # 2. Approve daemon + pending caps -> daemon runs and connects online
        dev.ctl_json(["plugins", "grant", hud_id, "--all-pending", "--approve-daemon"])
        desc_run = None
        for _ in range(20):
            snap_run = dev.ctl_json(["plugins", "list"])["result"]
            desc_run = _find_plugin(snap_run, hud_id)
            if (
                desc_run
                and desc_run.get("daemonStatus") == "running"
                and desc_run.get("backendState") == "online"
                and desc_run.get("daemonPid")
            ):
                break
            time.sleep(0.3)
        assert desc_run is not None and desc_run.get("daemonStatus") == "running", desc_run
        assert desc_run.get("backendState") == "online", desc_run
        daemon_pid = int(desc_run["daemonPid"])

        # 3. Query supervised daemon stderr ring buffer
        raw_logs = dev.ctl_json(["plugins", "daemon-logs", hud_id, "--limit", "20"])
        logs_res = raw_logs.get("result", raw_logs) if isinstance(raw_logs, dict) else raw_logs
        assert logs_res.get("pluginId") == hud_id, logs_res
        assert logs_res.get("status") == "running", logs_res
        assert isinstance(logs_res.get("lines"), list), logs_res

        # 4. Revoke approval -> daemon terminated promptly and backend goes offline
        dev.ctl_json(["plugins", "grant", hud_id, "--revoke-all"])
        desc_stopped = None
        for _ in range(15):
            snap_stop = dev.ctl_json(["plugins", "list"])["result"]
            desc_stopped = _find_plugin(snap_stop, hud_id)
            if desc_stopped and desc_stopped.get("daemonStatus") == "awaiting-approval":
                break
            time.sleep(0.25)
        assert desc_stopped is not None and desc_stopped.get("daemonStatus") == "awaiting-approval", desc_stopped
        assert desc_stopped.get("backendState") == "offline", desc_stopped
        pid_alive = dev.su_sh(f"kill -0 {daemon_pid} 2>/dev/null && echo ALIVE || echo DEAD")
        assert pid_alive == "DEAD", f"Revoked daemon pid={daemon_pid} survived teardown!"

        print(
            f"[OK] PluginDaemonSupervisor lifecycle verified: awaiting-approval -> running (pid={daemon_pid}) -> revoked/dead"
        )
        return {"pluginId": hud_id, "daemonPid": daemon_pid, "logLines": len(logs_res["lines"])}
    finally:
        if not prev_enabled:
            dev.ctl_json(["plugins", "disable", hud_id], check=False)
        if not prev_daemon_approved:
            dev.ctl_json(["plugins", "grant", hud_id, "--revoke-all"], check=False)
        else:
            params = {
                "pluginId": hud_id,
                "capabilities": prev_granted,
                "approveDaemon": True,
            }
            dev.ctl_json(["rpc", "plugins.grantCapabilities", json.dumps(params)], check=False)
        dev.ctl_json(["hud", "dismiss"], check=False)
