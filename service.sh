#!/system/bin/sh
#
# Late-start service executed by KernelSU after boot completion.
# Prepares shared UID 1000 state under /data/system/sideveil, supervises sideveil-daemon,
# and keeps HostService launched from uid=0 context to satisfy ColorOS startup policy.
#

MODDIR="${0%/*}"
LOG_TAG="SideveilSvc"
STATE_DIR="/data/system/sideveil"
RUN_DIR="${STATE_DIR}/run"
RUN_ENV="${RUN_DIR}/daemon.env"
LIVE_CONFIG="${STATE_DIR}/config.json"
LIVE_DEX="${STATE_DIR}/sideveil-daemon.dex"
MODULE_CONFIG="${MODDIR}/config.json"
MODULE_DEX="${MODDIR}/bin/sideveil-daemon.dex"
CTL_BIN="${MODDIR}/bin/sideveil-ctl"
DAEMON_PROC_NAME="sideveil-daemon"
MAIN_CLASS="pochita.sideveil.daemon.DaemonMain"

log_info() {
    log -t "$LOG_TAG" -p i "$1"
}

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done

log_info "Boot completed. Initializing Sideveil daemon supervisor..."

mkdir -p "$STATE_DIR" "$RUN_DIR"
chown 1000:1000 "$STATE_DIR" "$RUN_DIR"
chmod 0755 "$STATE_DIR"
chmod 0700 "$RUN_DIR"

echo "$$" > "${RUN_DIR}/supervisor.pid"
chmod 0644 "${RUN_DIR}/supervisor.pid"

if [ ! -f "$LIVE_CONFIG" ] && [ -f "$MODULE_CONFIG" ]; then
    cp "$MODULE_CONFIG" "$LIVE_CONFIG"
    chown 1000:1000 "$LIVE_CONFIG"
    chmod 0644 "$LIVE_CONFIG"
fi

if [ ! -f "$MODULE_DEX" ]; then
    log_info "Error: $MODULE_DEX not found."
    exit 1
fi

pkill -9 -x "$DAEMON_PROC_NAME" 2>/dev/null || true

if [ -f "${RUN_DIR}/hostwatch.pid" ]; then
    OLD_HW=$(cat "${RUN_DIR}/hostwatch.pid" 2>/dev/null || true)
    [ -n "$OLD_HW" ] && kill -9 "$OLD_HW" 2>/dev/null || true
fi

start_host_if_needed() {
    [ -r "$RUN_ENV" ] || return 1
    # shellcheck disable=SC1090
    . "$RUN_ENV" 2>/dev/null || return 1
    [ -n "${SIDEVEIL_PORT:-}" ] && [ -n "${SIDEVEIL_TOKEN:-}" ] && [ -n "${SIDEVEIL_PID:-}" ] || return 1
    kill -0 "$SIDEVEIL_PID" 2>/dev/null || return 1

    STATUS_JSON=$("$CTL_BIN" status 2>/dev/null || true)
    case "$STATUS_JSON" in
        *'"hostConnected":true'*)
            return 0
            ;;
        *'"hostConnected":false'*)
            am start-foreground-service --user 0 \
                -n "pochita.sideveil.host/.HostService" \
                --ei port "$SIDEVEIL_PORT" \
                --es token "$SIDEVEIL_TOKEN" >/dev/null 2>&1 || true
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
        if [ ! -f "${MODDIR}/disable" ]; then
            start_host_if_needed || true
        fi
        sleep 3
    done
) >/dev/null 2>&1 &
HOSTWATCH_PID=$!
echo "$HOSTWATCH_PID" > "${RUN_DIR}/hostwatch.pid"
chmod 0644 "${RUN_DIR}/hostwatch.pid"

while true; do
    if [ -f "${MODDIR}/remove" ]; then
        log_info "Module marked for removal. Exiting supervisor."
        break
    fi

    if [ -f "${MODDIR}/disable" ]; then
        sleep 5
        continue
    fi

    cp "$MODULE_DEX" "$LIVE_DEX"
    chown 1000:1000 "$LIVE_DEX"
    chmod 0644 "$LIVE_DEX"

    log_info "Launching UID 1000 daemon ($MAIN_CLASS)..."
    su 1000 -c "CLASSPATH=${LIVE_DEX} /system/bin/app_process64 /system/bin --nice-name=${DAEMON_PROC_NAME} ${MAIN_CLASS} --config ${LIVE_CONFIG} --run-dir ${RUN_DIR}" >/dev/null 2>&1 || true

    sleep 3
done

kill -9 "$HOSTWATCH_PID" 2>/dev/null || true
