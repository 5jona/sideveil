#!/system/bin/sh
#
# Shared runtime helpers sourced by service.sh and bin/sideveil-ctl.
# Provides safe daemon.env parsing, PID verification, and atomic HostService endpoint sync.
#

STATE_DIR="/data/system/sideveil"
RUN_DIR="${STATE_DIR}/run"
ROOT_RUN_DIR="/data/adb/sideveil/run"
RUN_ENV="${RUN_DIR}/daemon.env"
DAEMON_PROC_NAME="sideveil-daemon"
MAIN_CLASS="pochita.sideveil.daemon.DaemonMain"
SIDEVEIL_LAST_ERROR=""

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

load_daemon_env() {
    SIDEVEIL_LAST_ERROR=""
    if [ ! -f "$RUN_ENV" ] || [ -L "$RUN_ENV" ] || [ ! -r "$RUN_ENV" ]; then
        SIDEVEIL_LAST_ERROR="Daemon runtime state not found or invalid ($RUN_ENV)"
        return 1
    fi

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

    if [ -z "$SIDEVEIL_PORT" ] || [ -z "$SIDEVEIL_TOKEN" ] || [ -z "$SIDEVEIL_PID" ]; then
        SIDEVEIL_LAST_ERROR="Incomplete daemon environment in $RUN_ENV"
        return 1
    fi

    case "$SIDEVEIL_PORT" in
        ''|*[!0-9]*)
            SIDEVEIL_LAST_ERROR="Invalid daemon port in $RUN_ENV"
            return 1
            ;;
    esac
    if [ "$SIDEVEIL_PORT" -lt 1 ] || [ "$SIDEVEIL_PORT" -gt 65535 ]; then
        SIDEVEIL_LAST_ERROR="Out-of-range daemon port in $RUN_ENV"
        return 1
    fi

    case "$SIDEVEIL_TOKEN" in
        ''|*[!0-9a-f]*)
            SIDEVEIL_LAST_ERROR="Invalid daemon token format in $RUN_ENV"
            return 1
            ;;
    esac
    if [ "${#SIDEVEIL_TOKEN}" -ne 64 ]; then
        SIDEVEIL_LAST_ERROR="Invalid daemon token length in $RUN_ENV"
        return 1
    fi

    case "$SIDEVEIL_PID" in
        ''|*[!0-9]*)
            SIDEVEIL_LAST_ERROR="Invalid daemon PID in $RUN_ENV"
            return 1
            ;;
    esac

    if ! kill -0 "$SIDEVEIL_PID" 2>/dev/null; then
        SIDEVEIL_LAST_ERROR="Sideveil daemon pid $SIDEVEIL_PID is not alive"
        return 1
    fi

    if [ -r "/proc/${SIDEVEIL_PID}/cmdline" ]; then
        CMDLINE=$(tr '\000' ' ' < "/proc/${SIDEVEIL_PID}/cmdline" 2>/dev/null || true)
        case "$CMDLINE" in
            *sideveil-daemon*|*pochita.sideveil.daemon.DaemonMain*) ;;
            *)
                SIDEVEIL_LAST_ERROR="PID $SIDEVEIL_PID does not match sideveil-daemon process"
                return 1
                ;;
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

wake_host_service() {
    sync_host_endpoint || true
    am start-foreground-service --user 0 \
        -n "pochita.sideveil.host/.HostService" \
        -a "pochita.sideveil.host.ACTION_SYNC_ENDPOINT" >/dev/null 2>&1 || true
}
