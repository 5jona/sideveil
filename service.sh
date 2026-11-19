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
ROOT_RUN_DIR="/data/adb/sideveil/run"
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

stop_daemon_processes() {
    if [ -f "$RUN_ENV" ] && [ ! -L "$RUN_ENV" ]; then
        D_PID=$(sed -n 's/^SIDEVEIL_PID=//p' "$RUN_ENV" 2>/dev/null || true)
        terminate_pid_gracefully "$D_PID"
    fi
    pkill -TERM -x "$DAEMON_PROC_NAME" 2>/dev/null || true
    sleep 0.5
    pkill -9 -x "$DAEMON_PROC_NAME" 2>/dev/null || true
}

load_daemon_env() {
    [ -f "$RUN_ENV" ] && [ ! -L "$RUN_ENV" ] && [ -r "$RUN_ENV" ] || return 1
    SIDEVEIL_PORT=""
    SIDEVEIL_TOKEN=""
    SIDEVEIL_PID=""
    while IFS='=' read -r key val || [ -n "$key" ]; do
        case "$key" in
            SIDEVEIL_PORT) SIDEVEIL_PORT="$val" ;;
            SIDEVEIL_TOKEN) SIDEVEIL_TOKEN="$val" ;;
            SIDEVEIL_PID) SIDEVEIL_PID="$val" ;;
        esac
    done < "$RUN_ENV"

    case "$SIDEVEIL_PORT" in
        ''|*[!0-9]*) return 1 ;;
    esac
    if [ "$SIDEVEIL_PORT" -lt 1 ] || [ "$SIDEVEIL_PORT" -gt 65535 ]; then
        return 1
    fi

    case "$SIDEVEIL_TOKEN" in
        ''|*[!0-9a-f]*) return 1 ;;
    esac
    if [ "${#SIDEVEIL_TOKEN}" -ne 64 ]; then
        return 1
    fi

    case "$SIDEVEIL_PID" in
        ''|*[!0-9]*) return 1 ;;
    esac
    kill -0 "$SIDEVEIL_PID" 2>/dev/null || return 1
    if [ -r "/proc/${SIDEVEIL_PID}/cmdline" ]; then
        CMDLINE=$(tr '\000' ' ' < "/proc/${SIDEVEIL_PID}/cmdline" 2>/dev/null || true)
        case "$CMDLINE" in
            *sideveil-daemon*|*pochita.sideveil.daemon.DaemonMain*) ;;
            *) return 1 ;;
        esac
    fi
    return 0
}

sync_host_endpoint() {
    HOST_DATA_DIR="/data/user/0/pochita.sideveil.host"
    [ -d "$HOST_DATA_DIR" ] || HOST_DATA_DIR="/data/data/pochita.sideveil.host"
    [ -d "$HOST_DATA_DIR" ] || return 1
    HOST_NOBACKUP_DIR="${HOST_DATA_DIR}/no_backup"
    ENDPOINT_FILE="${HOST_NOBACKUP_DIR}/daemon_endpoint.json"
    ENDPOINT_TMP="${HOST_NOBACKUP_DIR}/daemon_endpoint.json.tmp.$$"

    HOST_UID=$(stat -c '%u:%g' "$HOST_DATA_DIR" 2>/dev/null || true)
    mkdir -p "$HOST_NOBACKUP_DIR"
    if [ -n "$HOST_UID" ]; then
        chown "$HOST_UID" "$HOST_NOBACKUP_DIR" 2>/dev/null || true
    fi
    chmod 0700 "$HOST_NOBACKUP_DIR" 2>/dev/null || true

    (
        umask 077
        printf '{"port":%s,"token":"%s"}\n' "$SIDEVEIL_PORT" "$SIDEVEIL_TOKEN" > "$ENDPOINT_TMP"
    )
    if [ -n "$HOST_UID" ]; then
        chown "$HOST_UID" "$ENDPOINT_TMP" 2>/dev/null || true
    fi
    chmod 0600 "$ENDPOINT_TMP" 2>/dev/null || true
    chcon --reference="$HOST_DATA_DIR" "$HOST_NOBACKUP_DIR" "$ENDPOINT_TMP" 2>/dev/null || restorecon -F "$HOST_NOBACKUP_DIR" "$ENDPOINT_TMP" 2>/dev/null || true
    mv -f "$ENDPOINT_TMP" "$ENDPOINT_FILE"
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
            sync_host_endpoint || true
            am start-foreground-service --user 0 \
                -n "pochita.sideveil.host/.HostService" \
                -a "pochita.sideveil.host.ACTION_SYNC_ENDPOINT" >/dev/null 2>&1 || true
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
