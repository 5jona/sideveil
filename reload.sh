#!/usr/bin/env bash
#
# Zero-reboot development deploy & live reload script for Sideveil.
# Builds artifacts (unless --skip-build), installs/updates SideveilHost APK, pushes
# module files and UID 1000 DEX via ADB, restarts service.sh, and verifies health.
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

for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=1 ;;
        --reset-config) RESET_CONFIG=1 ;;
        *) TARGET_SERIAL="$arg" ;;
    esac
done

if [ -z "$TARGET_SERIAL" ]; then
    DETECTED_SERIALS=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    SERIAL_COUNT=$(printf '%s\n' "$DETECTED_SERIALS" | sed '/^$/d' | wc -l | tr -d ' ')
    if [ "$SERIAL_COUNT" -eq 1 ]; then
        TARGET_SERIAL="$DETECTED_SERIALS"
    else
        echo "[!] Error: Please specify a target ADB serial via TARGET_SERIAL=<serial> or positional argument." >&2
        echo "    Usage: ./reload.sh [--skip-build] [--reset-config] <device-serial>" >&2
        exit 1
    fi
fi

echo "[*] Target device: ${TARGET_SERIAL}"

if [ "$SKIP_BUILD" -eq 0 ]; then
    bash "${SCRIPT_DIR}/build.sh"
fi

echo "[*] Preparing staging and module directories..."
adb -s "${TARGET_SERIAL}" shell "rm -rf ${STAGE_DIR} && mkdir -p ${STAGE_DIR}"
adb -s "${TARGET_SERIAL}" shell "su -c '
    mkdir -p ${REMOTE_MOD_DIR}/bin \
             ${REMOTE_MOD_DIR}/system/priv-app/SideveilHost \
             ${REMOTE_MOD_DIR}/system/etc/permissions \
             ${REMOTE_MOD_DIR}/system/etc/sysconfig \
             ${REMOTE_STATE_DIR}/run \
             ${REMOTE_ROOT_RUN_DIR}
    chown 0:1000 ${REMOTE_STATE_DIR}
    chmod 1775 ${REMOTE_STATE_DIR}
    chown 1000:1000 ${REMOTE_STATE_DIR}/run
    chmod 0700 ${REMOTE_STATE_DIR}/run
    chown 0:0 /data/adb/${MODULE_ID} ${REMOTE_ROOT_RUN_DIR}
    chmod 0700 /data/adb/${MODULE_ID} ${REMOTE_ROOT_RUN_DIR}
'"

echo "[*] Stopping running supervisor, daemon, and host service..."
adb -s "${TARGET_SERIAL}" shell "su -c '
    for pidfile in ${REMOTE_ROOT_RUN_DIR}/hostwatch.pid ${REMOTE_ROOT_RUN_DIR}/supervisor.pid ${REMOTE_STATE_DIR}/run/hostwatch.pid ${REMOTE_STATE_DIR}/run/supervisor.pid; do
        if [ -f \"\$pidfile\" ] && [ ! -L \"\$pidfile\" ]; then
            P=\$(cat \"\$pidfile\" 2>/dev/null || true)
            if [ -n \"\$P\" ]; then
                kill -TERM \"\$P\" 2>/dev/null || true
                sleep 0.2
                kill -9 \"\$P\" 2>/dev/null || true
            fi
        fi
    done
    if [ -f ${REMOTE_STATE_DIR}/run/daemon.env ] && [ ! -L ${REMOTE_STATE_DIR}/run/daemon.env ]; then
        D_PID=\$(sed -n \"s/^SIDEVEIL_PID=//p\" ${REMOTE_STATE_DIR}/run/daemon.env 2>/dev/null || true)
        if [ -n \"\$D_PID\" ]; then
            kill -TERM \"\$D_PID\" 2>/dev/null || true
            sleep 0.3
            kill -9 \"\$D_PID\" 2>/dev/null || true
        fi
    fi
    pkill -TERM -x \"sideveil-daemon\" 2>/dev/null || true
    sleep 0.3
    pkill -9 -x \"sideveil-daemon\" 2>/dev/null || true
    am force-stop pochita.sideveil.host 2>/dev/null || true
'"

echo "[*] Pushing build outputs to staging directory..."
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/module.prop" "${STAGE_DIR}/module.prop"
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/config.json" "${STAGE_DIR}/config.json"
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/service.sh" "${STAGE_DIR}/service.sh"
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/post-fs-data.sh" "${STAGE_DIR}/post-fs-data.sh"
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/customize.sh" "${STAGE_DIR}/customize.sh"
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/uninstall.sh" "${STAGE_DIR}/uninstall.sh"
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/bin/sideveil-ctl" "${STAGE_DIR}/sideveil-ctl"
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/bin/sideveil-daemon.dex" "${STAGE_DIR}/sideveil-daemon.dex"
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/system/priv-app/SideveilHost/SideveilHost.apk" "${STAGE_DIR}/SideveilHost.apk"
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/system/etc/permissions/privapp-permissions-pochita-sideveil.xml" "${STAGE_DIR}/privapp.xml"
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/system/etc/sysconfig/sideveil-allowlist.xml" "${STAGE_DIR}/sysconfig.xml"
adb -s "${TARGET_SERIAL}" push "${SCRIPT_DIR}/webroot" "${STAGE_DIR}/webroot"

echo "[*] Installing/updating SideveilHost APK for immediate testing..."
adb -s "${TARGET_SERIAL}" shell "su -c 'pm install -r -d ${STAGE_DIR}/SideveilHost.apk'"

echo "[*] Syncing module files to ${REMOTE_MOD_DIR} and cleaning staging directory..."
adb -s "${TARGET_SERIAL}" shell "su -c '
    cp ${STAGE_DIR}/module.prop ${REMOTE_MOD_DIR}/module.prop
    cp ${STAGE_DIR}/config.json ${REMOTE_MOD_DIR}/config.json
    cp ${STAGE_DIR}/service.sh ${REMOTE_MOD_DIR}/service.sh
    cp ${STAGE_DIR}/post-fs-data.sh ${REMOTE_MOD_DIR}/post-fs-data.sh
    cp ${STAGE_DIR}/customize.sh ${REMOTE_MOD_DIR}/customize.sh
    cp ${STAGE_DIR}/uninstall.sh ${REMOTE_MOD_DIR}/uninstall.sh
    cp ${STAGE_DIR}/sideveil-ctl ${REMOTE_MOD_DIR}/bin/sideveil-ctl
    cp ${STAGE_DIR}/sideveil-daemon.dex ${REMOTE_MOD_DIR}/bin/sideveil-daemon.dex
    cp ${STAGE_DIR}/SideveilHost.apk ${REMOTE_MOD_DIR}/system/priv-app/SideveilHost/SideveilHost.apk
    cp ${STAGE_DIR}/privapp.xml ${REMOTE_MOD_DIR}/system/etc/permissions/privapp-permissions-pochita-sideveil.xml
    cp ${STAGE_DIR}/sysconfig.xml ${REMOTE_MOD_DIR}/system/etc/sysconfig/sideveil-allowlist.xml
    rm -rf ${REMOTE_MOD_DIR}/webroot
    cp -r ${STAGE_DIR}/webroot ${REMOTE_MOD_DIR}/webroot

    if [ \"${RESET_CONFIG}\" -eq 1 ] || [ ! -f ${REMOTE_STATE_DIR}/config.json ]; then
        cp ${STAGE_DIR}/config.json ${REMOTE_STATE_DIR}/config.json
        chown 1000:1000 ${REMOTE_STATE_DIR}/config.json
        chmod 0644 ${REMOTE_STATE_DIR}/config.json
    fi

    chmod 0755 ${REMOTE_MOD_DIR}/service.sh ${REMOTE_MOD_DIR}/post-fs-data.sh ${REMOTE_MOD_DIR}/customize.sh ${REMOTE_MOD_DIR}/uninstall.sh ${REMOTE_MOD_DIR}/bin/sideveil-ctl
    chmod 0644 ${REMOTE_MOD_DIR}/module.prop ${REMOTE_MOD_DIR}/config.json ${REMOTE_MOD_DIR}/bin/sideveil-daemon.dex ${REMOTE_MOD_DIR}/system/priv-app/SideveilHost/SideveilHost.apk ${REMOTE_MOD_DIR}/system/etc/permissions/privapp-permissions-pochita-sideveil.xml ${REMOTE_MOD_DIR}/system/etc/sysconfig/sideveil-allowlist.xml
    find ${REMOTE_MOD_DIR}/webroot -type d -exec chmod 0755 {} +
    find ${REMOTE_MOD_DIR}/webroot -type f -exec chmod 0644 {} +
    rm -rf ${STAGE_DIR}
'"

echo "[*] Starting Sideveil supervisor detached..."
adb -s "${TARGET_SERIAL}" shell "su -c 'nohup ${REMOTE_MOD_DIR}/service.sh >/dev/null 2>&1 &'"

sleep 5

echo "[*] Querying live daemon status..."
adb -s "${TARGET_SERIAL}" shell "su -c '${REMOTE_MOD_DIR}/bin/sideveil-ctl status'"
echo ""
echo "[+] Live reload completed on ${TARGET_SERIAL}."
