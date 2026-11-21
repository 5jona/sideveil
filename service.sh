#!/system/bin/sh
#
# Late-start service executed by KernelSU after boot completion.
# Prepares runtime directories and supervises Rust `sideveild` with zero polling;
# `sideveild` directly supervises UID 1000 `sideveil-sensor` and `SideVeilHost` via pidfd.
#

MODDIR="${0%/*}"
LOG_TAG="SideVeilSvc"
STATE_DIR="/data/system/sideveil"
RUN_DIR="${STATE_DIR}/run"
PLUGINS_DIR="${STATE_DIR}/plugins"
DATA_DIR="${STATE_DIR}/data"
ROOT_RUN_DIR="/data/adb/sideveil/run"
LIVE_CONFIG="${STATE_DIR}/config.json"
MODULE_CONFIG="${MODDIR}/config.json"
HUB_BIN="${MODDIR}/bin/sideveild"
HUB_PID=""

log_info() {
    log -t "$LOG_TAG" -p i "$1"
}

terminate_pid_gracefully() {
    TARGET_PID="$1"
    case "$TARGET_PID" in
        ''|*[!0-9]*) return 0 ;;
    esac
    if kill -0 "$TARGET_PID" 2>/dev/null; then
        kill -TERM "$TARGET_PID" 2>/dev/null || true
        WAIT_STEPS=0
        while [ "$WAIT_STEPS" -lt 10 ] && kill -0 "$TARGET_PID" 2>/dev/null; do
            sleep 0.2
            WAIT_STEPS=$((WAIT_STEPS + 1))
        done
        kill -9 "$TARGET_PID" 2>/dev/null || true
    fi
}

stop_existing_processes() {
    for pidfile in "${ROOT_RUN_DIR}/sideveild.pid" "${ROOT_RUN_DIR}/hostwatch.pid"; do
        if [ -f "$pidfile" ] && [ ! -L "$pidfile" ]; then
            P=$(cat "$pidfile" 2>/dev/null || true)
            terminate_pid_gracefully "$P"
            rm -f "$pidfile"
        fi
    done
    pkill -l TERM -x "sideveild" 2>/dev/null || true
    pkill -l TERM -U 1000 -f "sideveil-sensor|pochita\.sideveil\.sensor\.SensorMain" 2>/dev/null || true
    pkill -l TERM -U 1000 -f "sideveil-daemon|pochita\.sideveil\.daemon\.DaemonMain" 2>/dev/null || true
    sleep 0.4
    pkill -l KILL -x "sideveild" 2>/dev/null || true
    pkill -l KILL -U 1000 -f "sideveil-sensor|pochita\.sideveil\.sensor\.SensorMain" 2>/dev/null || true
    pkill -l KILL -U 1000 -f "sideveil-daemon|pochita\.sideveil\.daemon\.DaemonMain" 2>/dev/null || true
}

cleanup_and_exit() {
    terminate_pid_gracefully "$HUB_PID"
    exit 0
}
trap cleanup_and_exit TERM INT

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done

log_info "Boot completed. Initializing SideVeil Rust hub supervisor..."

mkdir -p "$STATE_DIR" "$RUN_DIR" "$PLUGINS_DIR" "$DATA_DIR" "$ROOT_RUN_DIR"
chown 0:1000 "$STATE_DIR" "$RUN_DIR" "$PLUGINS_DIR"
chmod 1775 "$STATE_DIR"
chmod 0711 "$RUN_DIR"
chmod 0755 "$PLUGINS_DIR"
chown 0:0 "$DATA_DIR" "/data/adb/sideveil" "$ROOT_RUN_DIR"
chmod 0700 "$DATA_DIR" "/data/adb/sideveil" "$ROOT_RUN_DIR"

rm -f "${ROOT_RUN_DIR}/supervisor.pid"
echo "$$" > "${ROOT_RUN_DIR}/supervisor.pid"
chmod 0600 "${ROOT_RUN_DIR}/supervisor.pid"

if [ ! -f "$LIVE_CONFIG" ] && [ -f "$MODULE_CONFIG" ]; then
    cp "$MODULE_CONFIG" "$LIVE_CONFIG"
    chown 0:1000 "$LIVE_CONFIG"
    chmod 0644 "$LIVE_CONFIG"
fi

if [ ! -x "$HUB_BIN" ]; then
    log_info "Error: $HUB_BIN not found or not executable."
    exit 1
fi

stop_existing_processes

while true; do
    if [ -f "${MODDIR}/remove" ]; then
        log_info "Module marked for removal. Exiting supervisor."
        stop_existing_processes
        break
    fi

    if [ -f "${MODDIR}/disable" ]; then
        stop_existing_processes
        am force-stop pochita.sideveil.host >/dev/null 2>&1 || true
        sleep 5
        continue
    fi

    log_info "Launching sideveild..."
    "$HUB_BIN" \
        --config "$LIVE_CONFIG" \
        --run-dir "$RUN_DIR" \
        --module-dir "$MODDIR" \
        --state-dir "$STATE_DIR" >/dev/null 2>&1 &
    HUB_PID=$!
    rm -f "${ROOT_RUN_DIR}/sideveild.pid"
    echo "$HUB_PID" > "${ROOT_RUN_DIR}/sideveild.pid"
    chmod 0600 "${ROOT_RUN_DIR}/sideveild.pid"

    wait "$HUB_PID" || true
    HUB_PID=""
    sleep 2
done
