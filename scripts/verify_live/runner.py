"""Device command transport and daemon health checks for SideVeil live verification."""

import json
import os
import shlex
import subprocess

CTL = "/data/adb/modules/sideveil/bin/sideveil-ctl"
PLUGINS_DIR = "/data/system/sideveil/plugins"
HOST_EXEC = "/usr/local/bin/host-exec"


def default_ssh_target(explicit: str | None = None) -> str | None:
    if explicit and explicit.strip():
        return explicit.strip()
    for key in ("STRATA_SSH_HOST", "SSH_TARGET"):
        val = os.environ.get(key, "").strip()
        if val:
            return val
    return None


def default_ssh_port(explicit: int | None = None) -> int:
    if explicit is not None:
        return explicit
    for key in ("STRATA_SSH_PORT", "SSH_PORT"):
        raw = os.environ.get(key, "").strip()
        if raw.isdigit():
            return int(raw)
    return 2222


class DeviceRunner:
    def __init__(
        self,
        mode: str = "auto",
        ssh: str | None = None,
        ssh_port: int | None = None,
    ) -> None:
        resolved_ssh = default_ssh_target(ssh)
        self.ssh_target = self._normalize_ssh_target(resolved_ssh) if resolved_ssh else None
        self.ssh_port = default_ssh_port(ssh_port)
        self.mode = self._resolve_mode(mode, self.ssh_target)
        if self.mode == "ssh" and not self.ssh_target:
            raise SystemExit(
                "Mode 'ssh' requires --ssh <host|user@host> or STRATA_SSH_HOST / SSH_TARGET."
            )

    @staticmethod
    def _normalize_ssh_target(target: str) -> str:
        return target if "@" in target else f"root@{target}"

    @staticmethod
    def _resolve_mode(mode: str, ssh_target: str | None) -> str:
        if mode not in {"auto", "ssh", "host-exec", "local"}:
            raise SystemExit(f"Unsupported mode '{mode}'. Use auto, ssh, host-exec, or local.")
        if mode != "auto":
            return mode
        if ssh_target:
            return "ssh"
        if os.path.isfile(HOST_EXEC) and os.access(HOST_EXEC, os.X_OK):
            return "host-exec"
        if os.path.isfile("/system/bin/getprop") and os.geteuid() == 0:
            return "local"
        raise SystemExit(
            "Unable to resolve on-device runtime. Pass --ssh <host|user@host> "
            "(or set STRATA_SSH_HOST / SSH_TARGET), or run on-device."
        )

    def su_sh(self, script: str, check: bool = True) -> str:
        if self.mode == "ssh":
            cmd = [
                "ssh",
                "-p",
                str(self.ssh_port),
                "-o",
                "BatchMode=yes",
                "-o",
                "ConnectTimeout=8",
                "-o",
                "StrictHostKeyChecking=accept-new",
                "-o",
                "ControlMaster=auto",
                "-o",
                "ControlPersist=120",
                "-o",
                "ControlPath=/tmp/sideveil-verify-%r@%h:%p",
                str(self.ssh_target),
                "host-exec /system/bin/sh -s",
            ]
        elif self.mode == "host-exec":
            cmd = [HOST_EXEC, "/system/bin/sh", "-s"]
        else:
            cmd = ["/system/bin/sh", "-s"]
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
