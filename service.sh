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
SUPERVISOR_PID_FILE="${ROOT_RUN_DIR}/supervisor.pid"
SUPERVISOR_SCRIPT="/data/adb/modules/sideveil/service.sh"
LIVE_CONFIG="${STATE_DIR}/config.json"
MODULE_CONFIG="${MODDIR}/config.json"
HUB_BIN="${MODDIR}/bin/sideveild"
HOST_PKG="pochita.sideveil.host"
MODULE_HOST_APK="${MODDIR}/system/priv-app/SideVeilHost/SideVeilHost.apk"
HUB_PID=""

log_info() {
    log -t "$LOG_TAG" -p i "$1"
}

sync_host_apk_if_overridden() {
    [ -f "$MODULE_HOST_APK" ] || return 0
    INSTALLED_PATH=$(pm path "$HOST_PKG" 2>/dev/null | head -n 1)
    INSTALLED_PATH="${INSTALLED_PATH#package:}"
    case "$INSTALLED_PATH" in
        /data/app/*) ;;
        *) return 0 ;;
    esac
    [ -f "$INSTALLED_PATH" ] || return 0
    MOD_SHA=$(sha256sum "$MODULE_HOST_APK" 2>/dev/null || true)
    MOD_SHA="${MOD_SHA%% *}"
    INS_SHA=$(sha256sum "$INSTALLED_PATH" 2>/dev/null || true)
    INS_SHA="${INS_SHA%% *}"
    if [ -n "$MOD_SHA" ] && [ -n "$INS_SHA" ] && [ "$MOD_SHA" != "$INS_SHA" ]; then
        log_info "Detected stale /data/app override for ${HOST_PKG}; syncing module APK..."
        if pm install -r -d "$MODULE_HOST_APK" >/dev/null 2>&1; then
            log_info "Synchronized ${HOST_PKG} from ${MODULE_HOST_APK}."
        else
            log_info "Warning: failed to sync ${HOST_PKG} from ${MODULE_HOST_APK}."
        fi
    fi
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

remove_own_supervisor_pid() {
    if [ -f "$SUPERVISOR_PID_FILE" ] && [ ! -L "$SUPERVISOR_PID_FILE" ]; then
        CURRENT_SUP_PID=$(cat "$SUPERVISOR_PID_FILE" 2>/dev/null || true)
        if [ "$CURRENT_SUP_PID" = "$$" ]; then
            rm -f "$SUPERVISOR_PID_FILE"
        fi
    fi
}

stop_existing_supervisors() {
    if [ -f "$SUPERVISOR_PID_FILE" ] && [ ! -L "$SUPERVISOR_PID_FILE" ]; then
        OLD_SUP_PID=$(cat "$SUPERVISOR_PID_FILE" 2>/dev/null || true)
        if [ -n "$OLD_SUP_PID" ] && [ "$OLD_SUP_PID" != "$$" ] && [ "$OLD_SUP_PID" != "$PPID" ]; then
            log_info "Stopping previous supervisor pid=${OLD_SUP_PID} from pidfile..."
            terminate_pid_gracefully "$OLD_SUP_PID"
        fi
    fi
    for PROC_PID in $(ps -A -o PID,ARGS 2>/dev/null | grep -F "$SUPERVISOR_SCRIPT" | grep -v grep | while read -r p _rest; do echo "$p"; done); do
        case "$PROC_PID" in
            ''|*[!0-9]*) continue ;;
        esac
        if [ "$PROC_PID" = "$$" ] || [ "$PROC_PID" = "$PPID" ]; then
            continue
        fi
        log_info "Stopping stale supervisor pid=${PROC_PID}..."
        terminate_pid_gracefully "$PROC_PID"
    done
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
    pkill -l TERM -x "sideveil-example-perf-hud" 2>/dev/null || true
    pkill -l TERM -U 1000 -f "sideveil-sensor|pochita\.sideveil\.sensor\.SensorMain" 2>/dev/null || true
    pkill -l TERM -U 1000 -f "sideveil-daemon|pochita\.sideveil\.daemon\.DaemonMain" 2>/dev/null || true
    sleep 0.4
    pkill -l KILL -x "sideveild" 2>/dev/null || true
    pkill -l KILL -x "sideveil-example-perf-hud" 2>/dev/null || true
    pkill -l KILL -U 1000 -f "sideveil-sensor|pochita\.sideveil\.sensor\.SensorMain" 2>/dev/null || true
    pkill -l KILL -U 1000 -f "sideveil-daemon|pochita\.sideveil\.daemon\.DaemonMain" 2>/dev/null || true
}

cleanup_and_exit() {
    terminate_pid_gracefully "$HUB_PID"
    remove_own_supervisor_pid
    exit 0
}
trap cleanup_and_exit TERM INT

# Escape container or transient cgroups into root cgroup (0::/) before spawning daemons.
[ -w /sys/fs/cgroup/cgroup.procs ] && printf '%s\n' "$$" > /sys/fs/cgroup/cgroup.procs 2>/dev/null || true
for cg in /dev/cpuctl/cgroup.procs /dev/cpuset/cgroup.procs; do
    [ -w "$cg" ] && printf '%s\n' "$$" > "$cg" 2>/dev/null || true
done

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

stop_existing_supervisors

rm -f "$SUPERVISOR_PID_FILE"
echo "$$" > "$SUPERVISOR_PID_FILE"
chmod 0600 "$SUPERVISOR_PID_FILE"

if [ ! -f "$LIVE_CONFIG" ] && [ -f "$MODULE_CONFIG" ]; then
    cp "$MODULE_CONFIG" "$LIVE_CONFIG"
    chown 0:1000 "$LIVE_CONFIG"
    chmod 0644 "$LIVE_CONFIG"
fi

if [ ! -x "$HUB_BIN" ]; then
    log_info "Error: $HUB_BIN not found or not executable."
    exit 1
fi

sync_host_apk_if_overridden
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

remove_own_supervisor_pid
