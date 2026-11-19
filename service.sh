#!/system/bin/sh
#
# Late-start service executed by KernelSU after boot completion.
# Prepares shared UID 1000 state under /data/system/sideveil, supervises sideveil-daemon,
# and keeps HostService launched from uid=0 context to satisfy ColorOS startup policy.
#

MODDIR="${0%/*}"
LOG_TAG="SideveilSvc"
. "${MODDIR}/bin/sideveil-common.sh"

LIVE_CONFIG="${STATE_DIR}/config.json"
LIVE_DEX="${STATE_DIR}/sideveil-daemon.dex"
MODULE_CONFIG="${MODDIR}/config.json"
MODULE_DEX="${MODDIR}/bin/sideveil-daemon.dex"
CTL_BIN="${MODDIR}/bin/sideveil-ctl"

log_info() {
    log -t "$LOG_TAG" -p i "$1"
}

stop_daemon_processes() {
    if [ -f "$RUN_ENV" ] && [ ! -L "$RUN_ENV" ]; then
        D_PID=$(sed -n 's/^SIDEVEIL_PID=//p' "$RUN_ENV" 2>/dev/null || true)
        terminate_pid_gracefully "$D_PID"
    fi
    pkill -TERM -x "$DAEMON_PROC_NAME" 2>/dev/null || true
    sleep 0.5
    pkill -9 -x "$DAEMON_PROC_NAME" 2>/dev/null || true
}

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done

log_info "Boot completed. Initializing Sideveil daemon supervisor..."

mkdir -p "$STATE_DIR" "$RUN_DIR" "$ROOT_RUN_DIR"
chown 0:1000 "$STATE_DIR"
chmod 1775 "$STATE_DIR"
chown 1000:1000 "$RUN_DIR"
chmod 0700 "$RUN_DIR"
chown 0:0 "/data/adb/sideveil" "$ROOT_RUN_DIR"
chmod 0700 "/data/adb/sideveil" "$ROOT_RUN_DIR"

rm -f "${ROOT_RUN_DIR}/supervisor.pid"
echo "$$" > "${ROOT_RUN_DIR}/supervisor.pid"
chmod 0600 "${ROOT_RUN_DIR}/supervisor.pid"

if [ ! -f "$LIVE_CONFIG" ] && [ -f "$MODULE_CONFIG" ]; then
    cp "$MODULE_CONFIG" "$LIVE_CONFIG"
    chown 1000:1000 "$LIVE_CONFIG"
    chmod 0644 "$LIVE_CONFIG"
fi

if [ ! -f "$MODULE_DEX" ]; then
    log_info "Error: $MODULE_DEX not found."
    exit 1
fi

stop_daemon_processes

if [ -f "${ROOT_RUN_DIR}/hostwatch.pid" ] && [ ! -L "${ROOT_RUN_DIR}/hostwatch.pid" ]; then
    OLD_HW=$(cat "${ROOT_RUN_DIR}/hostwatch.pid" 2>/dev/null || true)
    terminate_pid_gracefully "$OLD_HW"
fi

start_host_if_needed() {
    load_daemon_env || return 1

    STATUS_JSON=$("$CTL_BIN" status 2>/dev/null || true)
    case "$STATUS_JSON" in
        *'"hostConnected":true'*)
            return 0
            ;;
        *'"hostConnected":false'*)
            wake_host_service
            return 0
            ;;
    esac
    return 1
}

(
    while true; do
        if [ -f "${MODDIR}/remove" ]; then
            break
        fi
        if [ -f "${MODDIR}/disable" ]; then
            stop_daemon_processes
            am force-stop pochita.sideveil.host >/dev/null 2>&1 || true
        else
            start_host_if_needed || true
        fi
        sleep 3
    done
) >/dev/null 2>&1 &
HOSTWATCH_PID=$!
rm -f "${ROOT_RUN_DIR}/hostwatch.pid"
echo "$HOSTWATCH_PID" > "${ROOT_RUN_DIR}/hostwatch.pid"
chmod 0600 "${ROOT_RUN_DIR}/hostwatch.pid"

while true; do
    if [ -f "${MODDIR}/remove" ]; then
        log_info "Module marked for removal. Exiting supervisor."
        stop_daemon_processes
        break
    fi

    if [ -f "${MODDIR}/disable" ]; then
        stop_daemon_processes
        sleep 5
        continue
    fi

    LIVE_DEX_TMP="${LIVE_DEX}.tmp.$$"
    rm -f "$LIVE_DEX_TMP"
    cp "$MODULE_DEX" "$LIVE_DEX_TMP"
    chown 0:1000 "$LIVE_DEX_TMP"
    chmod 0640 "$LIVE_DEX_TMP"
    mv -f "$LIVE_DEX_TMP" "$LIVE_DEX"

    log_info "Launching UID 1000 daemon ($MAIN_CLASS)..."
    su 1000 -c "CLASSPATH=${LIVE_DEX} /system/bin/app_process64 /system/bin --nice-name=${DAEMON_PROC_NAME} ${MAIN_CLASS} --config ${LIVE_CONFIG} --run-dir ${RUN_DIR}" >/dev/null 2>&1 || true

    sleep 3
done

terminate_pid_gracefully "$HOSTWATCH_PID"
