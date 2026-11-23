#!/usr/bin/env bash
#
# Shared staging, readiness polling, and CDP forwarding helpers for reload.sh.
#

# shellcheck source=scripts/reload-transport.sh
source "${SCRIPT_DIR}/scripts/reload-transport.sh"

run_in_env() {
    if command -v mise >/dev/null 2>&1; then
        mise exec -- "$@"
    else
        "$@"
    fi
}

wait_for_ready() {
    local max_wait_ms="${1:-12000}"
    local step_ms=500
    local elapsed_ms=0
    local status_json=""

    echo "[*] Waiting for SideVeil readiness (hostConnected + sensorConnected)..."
    while [ "$elapsed_ms" -lt "$max_wait_ms" ]; do
        status_json="$(run_remote_ctl status 2>/dev/null || true)"
        if printf '%s' "$status_json" | grep -q '"hostConnected":true' \
            && printf '%s' "$status_json" | grep -q '"sensorConnected":true'; then
            local secs
            secs="$(awk -v ms="$elapsed_ms" 'BEGIN { printf "%.1f", ms / 1000.0 }')"
            echo "[+] SideVeil ready in ${secs}s"
            printf '%s\n' "$status_json"
            return 0
        fi
        sleep 0.5
        elapsed_ms=$((elapsed_ms + step_ms))
    done

    echo "[!] Warning: Timed out waiting for full readiness after $((max_wait_ms / 1000))s." >&2
    if [ -n "$status_json" ]; then
        printf '%s\n' "$status_json"
    else
        run_remote_ctl status || true
    fi
    return 1
}

setup_cdp_forward() {
    local port="$1"
    local exec_mode="${2:-0}"
    echo "[*] Enabling WebView CDP debugging and resolving hostPid..."
    local cdp_json
    cdp_json="$(run_remote_ctl debug cdp on)"
    printf '%s\n' "$cdp_json"

    local parsed host_pid socket_name
    parsed="$(printf '%s' "$cdp_json" | python3 -c '
import json, sys
d = json.load(sys.stdin)
r = d.get("result", d) if isinstance(d, dict) else {}
pid = r.get("hostPid") or ""
sock = r.get("cdpSocket") or (f"webview_devtools_remote_{pid}" if pid else "")
print(f"{pid}\t{sock}")
' 2>/dev/null || true)"
    IFS=$'\t' read -r host_pid socket_name <<< "$parsed"
    if [ -z "$host_pid" ]; then
        echo "[!] Error: Unable to determine SideVeilHost PID for CDP forwarding." >&2
        return 1
    fi
    socket_name="${socket_name:-webview_devtools_remote_${host_pid}}"
    echo "[*] CDP abstract socket: @${socket_name}"

    local attempt=0
    local socket_ready=0
    while [ "$attempt" -lt 6 ]; do
        if printf 'grep -q "@%s" /proc/net/unix\n' "${socket_name}" | run_remote_sh >/dev/null 2>&1; then
            socket_ready=1
            break
        fi
        sleep 0.5
        attempt=$((attempt + 1))
    done
    if [ "$socket_ready" -eq 1 ]; then
        echo "[+] Verified @${socket_name} is listening in /proc/net/unix."
    else
        echo "[!] Warning: @${socket_name} not yet in /proc/net/unix (no WebView initialized yet)." >&2
    fi

    if [ "${ADB_FALLBACK:-0}" -eq 1 ]; then
        echo "[*] Bridge CDP to http://127.0.0.1:${port}/json via ADB:"
        echo "    adb -s ${ADB_SERIAL} forward tcp:${port} localabstract:${socket_name}"
        if [ "$exec_mode" -eq 1 ]; then
            adb -s "${ADB_SERIAL}" forward "tcp:${port}" "localabstract:${socket_name}"
        fi
    elif [ -n "$SSH_TARGET" ]; then
        echo "[*] Bridge CDP to http://127.0.0.1:${port}/json via Strata SSH:"
        echo "    ssh -p ${SSH_PORT} -L ${port}:127.0.0.1:${port} ${SSH_TARGET} \"strata-debug cdp serve --port ${port} --pid ${host_pid}\""
        if [ "$exec_mode" -eq 1 ]; then
            if [ "${ADB_SSH_ISOLATED:-0}" -eq 1 ]; then
                ssh "${SSH_OPTS[@]}" -L "${port}:127.0.0.1:${port}" "${SSH_TARGET}" \
                    "strata-debug cdp serve --port ${port} --pid ${host_pid}"
            else
                exec ssh -p "${SSH_PORT}" -L "${port}:127.0.0.1:${port}" "${SSH_TARGET}" \
                    "strata-debug cdp serve --port ${port} --pid ${host_pid}"
            fi
        fi
    else
        echo "[*] Serve CDP locally on http://127.0.0.1:${port}/json:"
        echo "    strata-debug cdp serve --port ${port} --pid ${host_pid}"
        if [ "$exec_mode" -eq 1 ]; then
            exec strata-debug cdp serve --port "${port}" --pid "${host_pid}"
        fi
    fi
}

resolve_plugin_source_dir() {
    local target="$1"
    if [ -d "$target" ] && [ -f "${target}/manifest.json" ]; then
        printf '%s\n' "$target"
        return 0
    fi
    local candidate
    for candidate in "${SCRIPT_DIR}"/examples/*; do
        [ -f "${candidate}/manifest.json" ] || continue
        local mid
        mid="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "${candidate}/manifest.json" 2>/dev/null || true)"
        if [ "$mid" = "$target" ] || [ "$(basename "$candidate")" = "$target" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

stop_hub_processes_script() {
    cat <<EOF
for pidfile in ${REMOTE_ROOT_RUN_DIR}/supervisor.pid ${REMOTE_ROOT_RUN_DIR}/sideveild.pid ${REMOTE_ROOT_RUN_DIR}/hostwatch.pid ${REMOTE_STATE_DIR}/run/hostwatch.pid ${REMOTE_STATE_DIR}/run/supervisor.pid; do
    if [ -f "\$pidfile" ] && [ ! -L "\$pidfile" ]; then
        P=\$(cat "\$pidfile" 2>/dev/null || true)
        if [ -n "\$P" ] && [ "\$P" != "\$\$" ] && [ "\$P" != "\$PPID" ]; then
            kill -TERM "\$P" 2>/dev/null || true
            sleep 0.2
            kill -9 "\$P" 2>/dev/null || true
        fi
    fi
done
for P in \$(ps -A -o PID,ARGS 2>/dev/null | grep -F "${REMOTE_MOD_DIR}/service.sh" | grep -v grep | while read -r pid _rest; do echo "\$pid"; done); do
    case "\$P" in
        ''|*[!0-9]*) continue ;;
    esac
    if [ "\$P" = "\$\$" ] || [ "\$P" = "\$PPID" ]; then
        continue
    fi
    kill -TERM "\$P" 2>/dev/null || true
    WAIT_STEPS=0
    while [ "\$WAIT_STEPS" -lt 10 ] && kill -0 "\$P" 2>/dev/null; do
        sleep 0.2
        WAIT_STEPS=\$((WAIT_STEPS + 1))
    done
    kill -9 "\$P" 2>/dev/null || true
done
pkill -l TERM -x "sideveild" 2>/dev/null || true
pkill -l TERM -x "sideveil-example-perf-hud" 2>/dev/null || true
pkill -l TERM -U 1000 -f "sideveil-sensor|pochita\\.sideveil\\.sensor\\.SensorMain" 2>/dev/null || true
pkill -l TERM -U 1000 -f "sideveil-daemon|pochita\\.sideveil\\.daemon\\.DaemonMain" 2>/dev/null || true
sleep 0.3
pkill -l KILL -x "sideveild" 2>/dev/null || true
pkill -l KILL -x "sideveil-example-perf-hud" 2>/dev/null || true
pkill -l KILL -U 1000 -f "sideveil-sensor|pochita\\.sideveil\\.sensor\\.SensorMain" 2>/dev/null || true
pkill -l KILL -U 1000 -f "sideveil-daemon|pochita\\.sideveil\\.daemon\\.DaemonMain" 2>/dev/null || true
EOF
}

stop_and_prepare_script() {
    cat <<EOF
set -e
rm -rf ${REMOTE_MOD_DIR}/system/priv-app
mkdir -p ${REMOTE_MOD_DIR}/bin \
         ${REMOTE_MOD_DIR}/system/priv-app/SideVeilHost \
         ${REMOTE_MOD_DIR}/system/etc/permissions \
         ${REMOTE_MOD_DIR}/system/etc/sysconfig \
         ${REMOTE_STATE_DIR}/run \
         ${REMOTE_STATE_DIR}/plugins \
         ${REMOTE_STATE_DIR}/data \
         ${REMOTE_ROOT_RUN_DIR}
chown 0:1000 ${REMOTE_STATE_DIR} ${REMOTE_STATE_DIR}/run ${REMOTE_STATE_DIR}/plugins
chmod 1775 ${REMOTE_STATE_DIR}
chmod 0711 ${REMOTE_STATE_DIR}/run
chmod 0755 ${REMOTE_STATE_DIR}/plugins
chown 0:0 ${REMOTE_STATE_DIR}/data /data/adb/${MODULE_ID} ${REMOTE_ROOT_RUN_DIR}
chmod 0700 ${REMOTE_STATE_DIR}/data /data/adb/${MODULE_ID} ${REMOTE_ROOT_RUN_DIR}
$(stop_hub_processes_script)
am force-stop pochita.sideveil.host 2>/dev/null || true
rm -f ${REMOTE_STATE_DIR}/run/hub.sock ${REMOTE_STATE_DIR}/run/app.sock ${REMOTE_STATE_DIR}/run/daemon.env ${REMOTE_STATE_DIR}/run/hostwatch.pid ${REMOTE_STATE_DIR}/run/supervisor.pid
EOF
}

install_full_staged_script() {
    cat <<EOF
set -e
pm install -r -d ${STAGE_DIR}/SideVeilHost.apk
cp ${STAGE_DIR}/module.prop ${REMOTE_MOD_DIR}/module.prop
cp ${STAGE_DIR}/config.json ${REMOTE_MOD_DIR}/config.json
cp ${STAGE_DIR}/service.sh ${REMOTE_MOD_DIR}/service.sh
cp ${STAGE_DIR}/post-fs-data.sh ${REMOTE_MOD_DIR}/post-fs-data.sh
cp ${STAGE_DIR}/customize.sh ${REMOTE_MOD_DIR}/customize.sh
cp ${STAGE_DIR}/uninstall.sh ${REMOTE_MOD_DIR}/uninstall.sh
for b in sideveild sideveil-ctl sideveil-example-perf-hud; do
    cp ${STAGE_DIR}/\$b ${REMOTE_MOD_DIR}/bin/\$b.new
    chmod 0755 ${REMOTE_MOD_DIR}/bin/\$b.new
    mv -f ${REMOTE_MOD_DIR}/bin/\$b.new ${REMOTE_MOD_DIR}/bin/\$b
done
cp ${STAGE_DIR}/sideveil-sensor.dex ${REMOTE_MOD_DIR}/bin/sideveil-sensor.dex.new
mv -f ${REMOTE_MOD_DIR}/bin/sideveil-sensor.dex.new ${REMOTE_MOD_DIR}/bin/sideveil-sensor.dex
rm -f ${REMOTE_MOD_DIR}/bin/sideveil-common.sh ${REMOTE_MOD_DIR}/bin/sideveil-daemon.dex ${REMOTE_STATE_DIR}/sideveil-daemon.dex
cp ${STAGE_DIR}/SideVeilHost.apk ${REMOTE_MOD_DIR}/system/priv-app/SideVeilHost/SideVeilHost.apk
cp ${STAGE_DIR}/privapp.xml ${REMOTE_MOD_DIR}/system/etc/permissions/privapp-permissions-pochita-sideveil.xml
cp ${STAGE_DIR}/sysconfig.xml ${REMOTE_MOD_DIR}/system/etc/sysconfig/sideveil-allowlist.xml
rm -rf ${REMOTE_MOD_DIR}/webroot
cp -r ${STAGE_DIR}/webroot ${REMOTE_MOD_DIR}/webroot
rm -rf ${REMOTE_MOD_DIR}/plugins
cp -r ${STAGE_DIR}/plugins ${REMOTE_MOD_DIR}/plugins
for pdir in ${STAGE_DIR}/plugins/*; do
    [ -d "\$pdir" ] || continue
    pname="\${pdir##*/}"
    rm -rf ${REMOTE_STATE_DIR}/plugins/"\$pname"
    cp -r "\$pdir" ${REMOTE_STATE_DIR}/plugins/"\$pname"
    chown -R 0:1000 ${REMOTE_STATE_DIR}/plugins/"\$pname"
    find ${REMOTE_STATE_DIR}/plugins/"\$pname" -type d -exec chmod 0755 {} +
    find ${REMOTE_STATE_DIR}/plugins/"\$pname" -type f -exec chmod 0644 {} +
done
if [ "${RESET_CONFIG}" -eq 1 ] || [ ! -f ${REMOTE_STATE_DIR}/config.json ]; then
    cp ${STAGE_DIR}/config.json ${REMOTE_STATE_DIR}/config.json
    chown 0:1000 ${REMOTE_STATE_DIR}/config.json
    chmod 0644 ${REMOTE_STATE_DIR}/config.json
fi
chmod 0755 ${REMOTE_MOD_DIR}/service.sh ${REMOTE_MOD_DIR}/post-fs-data.sh ${REMOTE_MOD_DIR}/customize.sh ${REMOTE_MOD_DIR}/uninstall.sh ${REMOTE_MOD_DIR}/bin/sideveild ${REMOTE_MOD_DIR}/bin/sideveil-ctl ${REMOTE_MOD_DIR}/bin/sideveil-example-perf-hud
chmod 0644 ${REMOTE_MOD_DIR}/module.prop ${REMOTE_MOD_DIR}/config.json ${REMOTE_MOD_DIR}/bin/sideveil-sensor.dex ${REMOTE_MOD_DIR}/system/priv-app/SideVeilHost/SideVeilHost.apk ${REMOTE_MOD_DIR}/system/etc/permissions/privapp-permissions-pochita-sideveil.xml ${REMOTE_MOD_DIR}/system/etc/sysconfig/sideveil-allowlist.xml
find ${REMOTE_MOD_DIR}/webroot ${REMOTE_MOD_DIR}/plugins -type d -exec chmod 0755 {} +
find ${REMOTE_MOD_DIR}/webroot ${REMOTE_MOD_DIR}/plugins -type f -exec chmod 0644 {} +
rm -rf ${STAGE_DIR}
EOF
}
