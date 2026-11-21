#!/usr/bin/env bash
#
# Zero-reboot development deploy & live reload script for SideVeil.
# Supports both ADB deployment and ADB-free container SSH deployment (--ssh <host|user@host>).
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_ID="sideveil"
REMOTE_MOD_DIR="/data/adb/modules/${MODULE_ID}"
REMOTE_STATE_DIR="/data/system/${MODULE_ID}"
REMOTE_ROOT_RUN_DIR="/data/adb/${MODULE_ID}/run"
STAGE_DIR="/data/local/tmp/sideveil_stage"

SKIP_BUILD=0
RESET_CONFIG=0
TARGET_SERIAL="${TARGET_SERIAL:-}"
SSH_TARGET="${SSH_TARGET:-}"
SSH_PORT="${SSH_PORT:-2222}"

usage() {
    cat <<'EOF'
Usage: ./reload.sh [options] [adb-serial]

Options:
  --ssh <host|user@host>  Deploy and reload over on-device container SSH (host-exec, no ADB)
  --ssh-port <port>       SSH port for --ssh mode (default: 2222)
  --skip-build            Skip build.sh quality gates and compilation
  --reset-config          Overwrite /data/system/sideveil/config.json with repo defaults
  -h, --help              Show this help message
EOF
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --skip-build) SKIP_BUILD=1; shift ;;
        --reset-config) RESET_CONFIG=1; shift ;;
        --ssh) SSH_TARGET="$2"; shift 2 ;;
        --ssh-port) SSH_PORT="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) TARGET_SERIAL="$1"; shift ;;
    esac
done

if [ -n "$SSH_TARGET" ] && [[ "$SSH_TARGET" != *@* ]]; then
    SSH_TARGET="root@${SSH_TARGET}"
fi

if [ "$SKIP_BUILD" -eq 0 ]; then
    bash "${SCRIPT_DIR}/build.sh"
fi

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

for pidfile in ${REMOTE_ROOT_RUN_DIR}/supervisor.pid ${REMOTE_ROOT_RUN_DIR}/sideveild.pid ${REMOTE_ROOT_RUN_DIR}/hostwatch.pid ${REMOTE_STATE_DIR}/run/hostwatch.pid ${REMOTE_STATE_DIR}/run/supervisor.pid; do
    if [ -f "\$pidfile" ] && [ ! -L "\$pidfile" ]; then
        P=\$(cat "\$pidfile" 2>/dev/null || true)
        if [ -n "\$P" ]; then
            kill -TERM "\$P" 2>/dev/null || true
            sleep 0.2
            kill -9 "\$P" 2>/dev/null || true
        fi
    fi
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
am force-stop pochita.sideveil.host 2>/dev/null || true
rm -f ${REMOTE_STATE_DIR}/run/hub.sock ${REMOTE_STATE_DIR}/run/app.sock ${REMOTE_STATE_DIR}/run/daemon.env ${REMOTE_STATE_DIR}/run/hostwatch.pid ${REMOTE_STATE_DIR}/run/supervisor.pid
EOF
}

install_staged_script() {
    cat <<EOF
set -e
pm install -r -d ${STAGE_DIR}/SideVeilHost.apk
cp ${STAGE_DIR}/module.prop ${REMOTE_MOD_DIR}/module.prop
cp ${STAGE_DIR}/config.json ${REMOTE_MOD_DIR}/config.json
cp ${STAGE_DIR}/service.sh ${REMOTE_MOD_DIR}/service.sh
cp ${STAGE_DIR}/post-fs-data.sh ${REMOTE_MOD_DIR}/post-fs-data.sh
cp ${STAGE_DIR}/customize.sh ${REMOTE_MOD_DIR}/customize.sh
cp ${STAGE_DIR}/uninstall.sh ${REMOTE_MOD_DIR}/uninstall.sh
cp ${STAGE_DIR}/sideveild ${REMOTE_MOD_DIR}/bin/sideveild
cp ${STAGE_DIR}/sideveil-ctl ${REMOTE_MOD_DIR}/bin/sideveil-ctl
cp ${STAGE_DIR}/sideveil-example-perf-hud ${REMOTE_MOD_DIR}/bin/sideveil-example-perf-hud
cp ${STAGE_DIR}/sideveil-sensor.dex ${REMOTE_MOD_DIR}/bin/sideveil-sensor.dex
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

if [ -n "$SSH_TARGET" ]; then
    SSH_OPTS=(-p "${SSH_PORT}" -o BatchMode=yes -o ConnectTimeout=6 -o StrictHostKeyChecking=accept-new)
    echo "[*] Deploying SideVeil via ADB-free SSH (${SSH_TARGET}:${SSH_PORT})..."

    TMP_STAGE="$(mktemp -d)"
    trap 'rm -rf "${TMP_STAGE}"' EXIT
    cp "${SCRIPT_DIR}/module.prop" "${SCRIPT_DIR}/config.json" \
       "${SCRIPT_DIR}/service.sh" "${SCRIPT_DIR}/post-fs-data.sh" \
       "${SCRIPT_DIR}/customize.sh" "${SCRIPT_DIR}/uninstall.sh" "${TMP_STAGE}/"
    cp "${SCRIPT_DIR}/bin/sideveild" "${SCRIPT_DIR}/bin/sideveil-ctl" \
       "${SCRIPT_DIR}/bin/sideveil-example-perf-hud" "${SCRIPT_DIR}/bin/sideveil-sensor.dex" "${TMP_STAGE}/"
    cp "${SCRIPT_DIR}/system/priv-app/SideVeilHost/SideVeilHost.apk" "${TMP_STAGE}/SideVeilHost.apk"
    cp "${SCRIPT_DIR}/system/etc/permissions/privapp-permissions-pochita-sideveil.xml" "${TMP_STAGE}/privapp.xml"
    cp "${SCRIPT_DIR}/system/etc/sysconfig/sideveil-allowlist.xml" "${TMP_STAGE}/sysconfig.xml"
    cp -r "${SCRIPT_DIR}/webroot" "${TMP_STAGE}/webroot"
    mkdir -p "${TMP_STAGE}/plugins/example.vello-scope" "${TMP_STAGE}/plugins/example.native-perf-hud"
    cp "${SCRIPT_DIR}/examples/vello-vector-scope/manifest.json" "${TMP_STAGE}/plugins/example.vello-scope/"
    cp -r "${SCRIPT_DIR}/examples/vello-vector-scope/web" "${TMP_STAGE}/plugins/example.vello-scope/web"
    cp "${SCRIPT_DIR}/examples/native-perf-hud/manifest.json" "${TMP_STAGE}/plugins/example.native-perf-hud/"
    cp -r "${SCRIPT_DIR}/examples/native-perf-hud/web" "${TMP_STAGE}/plugins/example.native-perf-hud/web"

    stop_and_prepare_script | ssh "${SSH_OPTS[@]}" "${SSH_TARGET}" "host-exec /system/bin/sh -s"
    tar -C "${TMP_STAGE}" -cf - . \
        | ssh "${SSH_OPTS[@]}" "${SSH_TARGET}" \
            "rm -rf ${STAGE_DIR} && mkdir -p ${STAGE_DIR} && tar -xf - -C ${STAGE_DIR}"
    install_staged_script | ssh "${SSH_OPTS[@]}" "${SSH_TARGET}" "host-exec /system/bin/sh -s"

    echo "[*] Starting SideVeil supervisor detached in root cgroup..."
    ssh "${SSH_OPTS[@]}" "${SSH_TARGET}" "host-exec /system/bin/sh -c '
        for cg in /sys/fs/cgroup/cgroup.procs /dev/cpuctl/cgroup.procs /dev/cpuset/cgroup.procs; do
            [ -w \"\$cg\" ] && printf \"%s\\n\" \"\$\$\" > \"\$cg\" 2>/dev/null || true
        done
        nohup /system/bin/sh ${REMOTE_MOD_DIR}/service.sh >/dev/null 2>&1 </dev/null &
    '"

    sleep 5
    echo "[*] Querying live daemon status..."
    ssh "${SSH_OPTS[@]}" "${SSH_TARGET}" "host-exec ${REMOTE_MOD_DIR}/bin/sideveil-ctl status"
    echo ""
    echo "[+] ADB-free SSH live reload completed on ${SSH_TARGET}."
    exit 0
fi

if [ -z "$TARGET_SERIAL" ]; then
    DETECTED_SERIALS=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    SERIAL_COUNT=$(printf '%s\n' "$DETECTED_SERIALS" | sed '/^$/d' | wc -l | tr -d ' ')
    if [ "$SERIAL_COUNT" -eq 1 ]; then
        TARGET_SERIAL="$DETECTED_SERIALS"
    else
        echo "[!] Error: Specify --ssh <user@host> or an ADB serial via TARGET_SERIAL / positional arg." >&2
        exit 1
    fi
fi

echo "[*] Target device (ADB): ${TARGET_SERIAL}"
adb -s "${TARGET_SERIAL}" shell "rm -rf ${STAGE_DIR} && mkdir -p ${STAGE_DIR}"
stop_and_prepare_script | adb -s "${TARGET_SERIAL}" shell "su"

echo "[*] Pushing build outputs to staging directory..."
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/module.prop" "${STAGE_DIR}/module.prop" >/dev/null
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/config.json" "${STAGE_DIR}/config.json" >/dev/null
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/service.sh" "${STAGE_DIR}/service.sh" >/dev/null
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/post-fs-data.sh" "${STAGE_DIR}/post-fs-data.sh" >/dev/null
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/customize.sh" "${STAGE_DIR}/customize.sh" >/dev/null
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/uninstall.sh" "${STAGE_DIR}/uninstall.sh" >/dev/null
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/bin/sideveild" "${STAGE_DIR}/sideveild" >/dev/null
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/bin/sideveil-ctl" "${STAGE_DIR}/sideveil-ctl" >/dev/null
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/bin/sideveil-example-perf-hud" "${STAGE_DIR}/sideveil-example-perf-hud" >/dev/null
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/bin/sideveil-sensor.dex" "${STAGE_DIR}/sideveil-sensor.dex" >/dev/null
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/system/priv-app/SideVeilHost/SideVeilHost.apk" "${STAGE_DIR}/SideVeilHost.apk" >/dev/null
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/system/etc/permissions/privapp-permissions-pochita-sideveil.xml" "${STAGE_DIR}/privapp.xml" >/dev/null
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/system/etc/sysconfig/sideveil-allowlist.xml" "${STAGE_DIR}/sysconfig.xml" >/dev/null
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/webroot" "${STAGE_DIR}/webroot" >/dev/null

adb -s "${TARGET_SERIAL}" shell "mkdir -p ${STAGE_DIR}/plugins/example.vello-scope ${STAGE_DIR}/plugins/example.native-perf-hud"
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/examples/vello-vector-scope/manifest.json" "${STAGE_DIR}/plugins/example.vello-scope/manifest.json" >/dev/null
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/examples/vello-vector-scope/web" "${STAGE_DIR}/plugins/example.vello-scope/web" >/dev/null
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/examples/native-perf-hud/manifest.json" "${STAGE_DIR}/plugins/example.native-perf-hud/manifest.json" >/dev/null
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/examples/native-perf-hud/web" "${STAGE_DIR}/plugins/example.native-perf-hud/web" >/dev/null

echo "[*] Installing SideVeilHost APK and syncing module files..."
install_staged_script | adb -s "${TARGET_SERIAL}" shell "su"

echo "[*] Starting SideVeil supervisor detached..."
adb -s "${TARGET_SERIAL}" shell "su -c 'nohup ${REMOTE_MOD_DIR}/service.sh >/dev/null 2>&1 &'"

sleep 5

echo "[*] Querying live daemon status..."
adb -s "${TARGET_SERIAL}" shell "su -c '${REMOTE_MOD_DIR}/bin/sideveil-ctl status'"
echo ""
echo "[+] Live reload completed on ${TARGET_SERIAL}."
