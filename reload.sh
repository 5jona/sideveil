#!/usr/bin/env bash
#
# Zero-reboot development deploy & live reload script for SideVeil.
# Supports full and incremental (--hub-only/--host-only/--sensor-only/--webui-only/--plugin)
# deployment over Strata container SSH (--ssh <host|user@host> or STRATA_SSH_HOST / SSH_TARGET)
# or locally on-device (host-exec / Android root), plus --cdp-forward.
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
LOCAL_MODE=0
LOCAL_TRANSPORT=""
DEPLOY_MODE="full"
TARGET_PLUGIN=""
CDP_FORWARD=0
CDP_FORWARD_EXEC=0
CDP_FORWARD_PORT="9222"
SSH_TARGET="${SSH_TARGET:-${STRATA_SSH_HOST:-}}"
SSH_PORT="${SSH_PORT:-${STRATA_SSH_PORT:-2222}}"

# shellcheck source=scripts/reload-helpers.sh
source "${SCRIPT_DIR}/scripts/reload-helpers.sh"

usage() {
    cat <<'EOF'
Usage: ./reload.sh [options] [ssh-target]

Options:
  --ssh <host|user@host>     Deploy and reload over Strata container SSH (host-exec)
  --ssh-port <port>          SSH port (default: $STRATA_SSH_PORT / $SSH_PORT / 2222)
  --local                    Deploy locally on-device via host-exec or Android UID 0
  --skip-build               Skip compilation and quality gates
  --reset-config             Overwrite /data/system/sideveil/config.json with repo defaults
  --hub-only                 Incremental: deploy only sideveild + sideveil-ctl + example daemon
  --host-only                Incremental: reinstall only SideVeilHost.apk and restart host
  --sensor-only              Incremental: deploy only sideveil-sensor.dex and respawn sensor
  --webui-only               Incremental: sync only KernelSU Control Plane webroot/
  --plugin <id>              Incremental: sync only one plugin directory and reload plugins
  --cdp-forward [port]       Enable WebView CDP and print strata-debug bridge command (default: 9222)
  --cdp-forward-exec [port]  Enable WebView CDP and directly launch strata-debug bridge (default: 9222)
  -h, --help                 Show this help message
EOF
}

set_deploy_mode() {
    local next="$1"
    if [ "$DEPLOY_MODE" != "full" ] && [ "$DEPLOY_MODE" != "$next" ]; then
        echo "[!] Error: Cannot combine multiple incremental modes (${DEPLOY_MODE} vs ${next})." >&2
        exit 2
    fi
    DEPLOY_MODE="$next"
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --skip-build) SKIP_BUILD=1; shift ;;
        --reset-config) RESET_CONFIG=1; shift ;;
        --local) LOCAL_MODE=1; SSH_TARGET=""; shift ;;
        --ssh) SSH_TARGET="$2"; shift 2 ;;
        --ssh-port) SSH_PORT="$2"; shift 2 ;;
        --hub-only) set_deploy_mode "hub"; shift ;;
        --host-only) set_deploy_mode "host"; shift ;;
        --sensor-only) set_deploy_mode "sensor"; shift ;;
        --webui-only) set_deploy_mode "webui"; shift ;;
        --plugin) set_deploy_mode "plugin"; TARGET_PLUGIN="$2"; shift 2 ;;
        --cdp-forward|--cdp-forward-exec)
            CDP_FORWARD=1
            [ "$1" = "--cdp-forward-exec" ] && CDP_FORWARD_EXEC=1
            if [ "$#" -ge 2 ] && [[ "$2" =~ ^[0-9]+$ ]]; then
                CDP_FORWARD_PORT="$2"
                shift 2
            else
                shift
            fi
            ;;
        -h|--help) usage; exit 0 ;;
        *) SSH_TARGET="$1"; shift ;;
    esac
done

if [ "$LOCAL_MODE" -eq 0 ] && [ -z "$SSH_TARGET" ]; then
    if [ -x /usr/local/bin/host-exec ]; then
        LOCAL_MODE=1
        LOCAL_TRANSPORT="host-exec"
    elif [ -x /system/bin/getprop ] && [ "$(id -u)" -eq 0 ]; then
        LOCAL_MODE=1
        LOCAL_TRANSPORT="local"
    else
        echo "[!] Error: Specify --ssh <host|user@host> (or set STRATA_SSH_HOST / SSH_TARGET), or run on-device." >&2
        exit 1
    fi
elif [ "$LOCAL_MODE" -eq 1 ]; then
    if [ -x /usr/local/bin/host-exec ]; then
        LOCAL_TRANSPORT="host-exec"
    elif [ -x /system/bin/getprop ] && [ "$(id -u)" -eq 0 ]; then
        LOCAL_TRANSPORT="local"
    else
        echo "[!] Error: --local requires on-device host-exec or Android UID 0." >&2
        exit 1
    fi
fi

if [ -n "$SSH_TARGET" ] && [[ "$SSH_TARGET" != *@* ]]; then
    SSH_TARGET="root@${SSH_TARGET}"
fi

SSH_CONTROL_DIR="${XDG_RUNTIME_DIR:-/tmp}/sideveil-ssh-$(id -u)"
mkdir -p "${SSH_CONTROL_DIR}"
chmod 0700 "${SSH_CONTROL_DIR}"

SSH_OPTS=(
    -p "${SSH_PORT}"
    -o BatchMode=yes
    -o ConnectTimeout=6
    -o StrictHostKeyChecking=accept-new
    -o ControlMaster=auto
    -o ControlPersist=60
    -o "ControlPath=${SSH_CONTROL_DIR}/reload-%r@%h:%p"
)

if [ "$SKIP_BUILD" -eq 0 ]; then
    case "$DEPLOY_MODE" in
        full)
            bash "${SCRIPT_DIR}/build.sh"
            ;;
        hub)
            echo "[*] Incremental build: Rust hub + example HUD binaries..."
            (cd "${SCRIPT_DIR}/hub" && run_in_env cargo build --release --target aarch64-linux-android -p sideveild -p sideveil-ctl)
            (cd "${SCRIPT_DIR}/examples/native-perf-hud" && run_in_env cargo build --release --target aarch64-linux-android)
            cp "${SCRIPT_DIR}/hub/target/aarch64-linux-android/release/sideveild" "${SCRIPT_DIR}/bin/sideveild"
            cp "${SCRIPT_DIR}/hub/target/aarch64-linux-android/release/sideveil-ctl" "${SCRIPT_DIR}/bin/sideveil-ctl"
            cp "${SCRIPT_DIR}/examples/native-perf-hud/target/aarch64-linux-android/release/sideveil-example-perf-hud" "${SCRIPT_DIR}/bin/sideveil-example-perf-hud"
            chmod 0755 "${SCRIPT_DIR}/bin/sideveild" "${SCRIPT_DIR}/bin/sideveil-ctl" "${SCRIPT_DIR}/bin/sideveil-example-perf-hud"
            ;;
        host)
            echo "[*] Incremental build: panel WebUI + sideveil-hud-jni + SideVeilHost APK..."
            (cd "${SCRIPT_DIR}/webui" && run_in_env pnpm build:panel)
            (cd "${SCRIPT_DIR}/hub" && run_in_env cargo build --release --target aarch64-linux-android -p sideveil-hud-jni)
            mkdir -p "${SCRIPT_DIR}/android/host-app/src/main/jniLibs/arm64-v8a"
            cp "${SCRIPT_DIR}/hub/target/aarch64-linux-android/release/libsideveil_hud.so" \
               "${SCRIPT_DIR}/android/host-app/src/main/jniLibs/arm64-v8a/libsideveil_hud.so"
            chmod 0644 "${SCRIPT_DIR}/android/host-app/src/main/jniLibs/arm64-v8a/libsideveil_hud.so"
            (cd "${SCRIPT_DIR}/android" && run_in_env ./gradlew :host-app:assembleRelease)
            cp "${SCRIPT_DIR}/android/host-app/build/outputs/apk/release/host-app-release.apk" \
               "${SCRIPT_DIR}/system/priv-app/SideVeilHost/SideVeilHost.apk"
            chmod 0644 "${SCRIPT_DIR}/system/priv-app/SideVeilHost/SideVeilHost.apk"
            ;;
        sensor)
            echo "[*] Incremental build: sideveil-sensor.dex..."
            (cd "${SCRIPT_DIR}/android" && run_in_env ./gradlew :sensor:buildDex)
            ;;
        webui)
            echo "[*] Incremental build: Control Plane WebUI..."
            (cd "${SCRIPT_DIR}/webui" && run_in_env pnpm build:control)
            ;;
        plugin)
            case "$TARGET_PLUGIN" in
                example.vello-scope|vello-vector-scope)
                    echo "[*] Incremental build: bundled plugin vello-scope..."
                    (cd "${SCRIPT_DIR}/webui" && run_in_env pnpm build:vello-scope)
                    ;;
                example.native-perf-hud|native-perf-hud)
                    echo "[*] Incremental build: bundled plugin native-perf-hud..."
                    (cd "${SCRIPT_DIR}/webui" && run_in_env pnpm build:native-perf-hud)
                    ;;
                *)
                    echo "[*] Skipping bundled WebUI rebuild for external plugin '${TARGET_PLUGIN}'."
                    ;;
            esac
            ;;
    esac
fi

TRANSPORT_LABEL="${SSH_TARGET:+SSH (${SSH_TARGET}:${SSH_PORT})}"
TRANSPORT_LABEL="${TRANSPORT_LABEL:-local (${LOCAL_TRANSPORT})}"
echo "[*] Target: ${TRANSPORT_LABEL} | Mode: ${DEPLOY_MODE}"

TMP_STAGE="$(mktemp -d)"
trap 'rm -rf "${TMP_STAGE}"' EXIT

case "$DEPLOY_MODE" in
    full)
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

        stop_and_prepare_script | run_remote_sh
        push_stage_dir "${TMP_STAGE}"
        install_full_staged_script | run_remote_sh
        start_service_detached
        wait_for_ready 12000
        ;;
    hub)
        cp "${SCRIPT_DIR}/bin/sideveild" "${SCRIPT_DIR}/bin/sideveil-ctl" \
           "${SCRIPT_DIR}/bin/sideveil-example-perf-hud" "${TMP_STAGE}/"
        push_stage_dir "${TMP_STAGE}"
        run_remote_sh <<EOF
set -e
$(stop_hub_processes_script)
cp ${STAGE_DIR}/sideveild ${REMOTE_MOD_DIR}/bin/sideveild
cp ${STAGE_DIR}/sideveil-ctl ${REMOTE_MOD_DIR}/bin/sideveil-ctl
cp ${STAGE_DIR}/sideveil-example-perf-hud ${REMOTE_MOD_DIR}/bin/sideveil-example-perf-hud
chmod 0755 ${REMOTE_MOD_DIR}/bin/sideveild ${REMOTE_MOD_DIR}/bin/sideveil-ctl ${REMOTE_MOD_DIR}/bin/sideveil-example-perf-hud
rm -f ${REMOTE_STATE_DIR}/run/hub.sock ${REMOTE_STATE_DIR}/run/app.sock ${REMOTE_STATE_DIR}/run/daemon.env
rm -rf ${STAGE_DIR}
EOF
        start_service_detached
        wait_for_ready 12000
        ;;
    host)
        cp "${SCRIPT_DIR}/system/priv-app/SideVeilHost/SideVeilHost.apk" "${TMP_STAGE}/SideVeilHost.apk"
        push_stage_dir "${TMP_STAGE}"
        run_remote_sh <<EOF
set -e
am force-stop pochita.sideveil.host 2>/dev/null || true
pm install -r -d ${STAGE_DIR}/SideVeilHost.apk
cp ${STAGE_DIR}/SideVeilHost.apk ${REMOTE_MOD_DIR}/system/priv-app/SideVeilHost/SideVeilHost.apk
chmod 0644 ${REMOTE_MOD_DIR}/system/priv-app/SideVeilHost/SideVeilHost.apk
rm -rf ${STAGE_DIR}
EOF
        run_remote_ctl restart-host >/dev/null 2>&1 || true
        wait_for_ready 12000
        ;;
    sensor)
        cp "${SCRIPT_DIR}/bin/sideveil-sensor.dex" "${TMP_STAGE}/sideveil-sensor.dex"
        push_stage_dir "${TMP_STAGE}"
        run_remote_sh <<EOF
set -e
cp ${STAGE_DIR}/sideveil-sensor.dex ${REMOTE_MOD_DIR}/bin/sideveil-sensor.dex
chmod 0644 ${REMOTE_MOD_DIR}/bin/sideveil-sensor.dex
pkill -l TERM -U 1000 -f "sideveil-sensor|pochita\\.sideveil\\.sensor\\.SensorMain" 2>/dev/null || true
rm -rf ${STAGE_DIR}
EOF
        wait_for_ready 12000
        ;;
    webui)
        cp -r "${SCRIPT_DIR}/webroot" "${TMP_STAGE}/webroot"
        push_stage_dir "${TMP_STAGE}"
        run_remote_sh <<EOF
set -e
rm -rf ${REMOTE_MOD_DIR}/webroot
cp -r ${STAGE_DIR}/webroot ${REMOTE_MOD_DIR}/webroot
find ${REMOTE_MOD_DIR}/webroot -type d -exec chmod 0755 {} +
find ${REMOTE_MOD_DIR}/webroot -type f -exec chmod 0644 {} +
rm -rf ${STAGE_DIR}
EOF
        echo "[+] Control Plane webroot synced."
        ;;
    plugin)
        SRC_PLUGIN_DIR="$(resolve_plugin_source_dir "${TARGET_PLUGIN}")" || {
            echo "[!] Error: Plugin '${TARGET_PLUGIN}' not found under examples/ or as a directory." >&2
            exit 1
        }
        PLUGIN_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "${SRC_PLUGIN_DIR}/manifest.json")"
        mkdir -p "${TMP_STAGE}/${PLUGIN_ID}"
        cp "${SRC_PLUGIN_DIR}/manifest.json" "${TMP_STAGE}/${PLUGIN_ID}/"
        if [ -d "${SRC_PLUGIN_DIR}/web" ]; then
            cp -r "${SRC_PLUGIN_DIR}/web" "${TMP_STAGE}/${PLUGIN_ID}/web"
        fi
        push_stage_dir "${TMP_STAGE}"
        run_remote_sh <<EOF
set -e
rm -rf ${REMOTE_STATE_DIR}/plugins/${PLUGIN_ID} ${REMOTE_MOD_DIR}/plugins/${PLUGIN_ID}
cp -r ${STAGE_DIR}/${PLUGIN_ID} ${REMOTE_STATE_DIR}/plugins/${PLUGIN_ID}
cp -r ${STAGE_DIR}/${PLUGIN_ID} ${REMOTE_MOD_DIR}/plugins/${PLUGIN_ID}
chown -R 0:1000 ${REMOTE_STATE_DIR}/plugins/${PLUGIN_ID}
find ${REMOTE_STATE_DIR}/plugins/${PLUGIN_ID} ${REMOTE_MOD_DIR}/plugins/${PLUGIN_ID} -type d -exec chmod 0755 {} +
find ${REMOTE_STATE_DIR}/plugins/${PLUGIN_ID} ${REMOTE_MOD_DIR}/plugins/${PLUGIN_ID} -type f -exec chmod 0644 {} +
rm -rf ${STAGE_DIR}
EOF
        run_remote_ctl plugins reload >/dev/null 2>&1 || true
        echo "[+] Plugin '${PLUGIN_ID}' synced and reloaded."
        ;;
esac

if [ "$CDP_FORWARD" -eq 1 ]; then
    setup_cdp_forward "${CDP_FORWARD_PORT}" "${CDP_FORWARD_EXEC}"
fi

echo "[+] Live reload (${DEPLOY_MODE}) completed on ${TRANSPORT_LABEL}."
