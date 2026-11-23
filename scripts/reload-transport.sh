#!/usr/bin/env bash
#
# Transport resolution, SSH/ADB-forward/ADB-su execution, and cleanup helpers for reload.sh.
#

cleanup_reload() {
    [ -n "${TMP_STAGE:-}" ] && rm -rf "${TMP_STAGE}" 2>/dev/null || true
    [ -n "${TMP_STAGE_TAR:-}" ] && rm -f "${TMP_STAGE_TAR}" 2>/dev/null || true
    if [ "${ADB_SSH_ISOLATED:-0}" -eq 1 ] && [ -n "${SSH_TARGET:-}" ]; then
        ssh "${SSH_OPTS[@]}" -O exit "${SSH_TARGET}" >/dev/null 2>&1 || true
    fi
    if [ -n "${ADB_SERIAL:-}" ] && [ -n "${ADB_LOCAL_PORT:-}" ]; then
        adb -s "${ADB_SERIAL}" forward --remove "tcp:${ADB_LOCAL_PORT}" >/dev/null 2>&1 || true
    fi
}

resolve_transport_target() {
    if [ "$LOCAL_MODE" -eq 0 ] && [ -z "$SSH_TARGET" ] && [ -z "$ADB_SERIAL" ]; then
        if [ -n "${STRATA_ADB_SERIAL:-}" ]; then
            ADB_SERIAL="${STRATA_ADB_SERIAL}"
        elif [ -x /usr/local/bin/host-exec ]; then
            LOCAL_MODE=1
            LOCAL_TRANSPORT="host-exec"
        elif [ -x /system/bin/getprop ] && [ "$(id -u)" -eq 0 ]; then
            LOCAL_MODE=1
            LOCAL_TRANSPORT="local"
        else
            echo "[!] Error: Specify --ssh <host|user@host>, --adb <serial> (or set STRATA_SSH_HOST / STRATA_ADB_SERIAL), or run on-device." >&2
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
}

setup_transport() {
    SSH_CONTROL_DIR="${XDG_RUNTIME_DIR:-/tmp}/sideveil-ssh-$(id -u)"
    mkdir -p "${SSH_CONTROL_DIR}"
    chmod 0700 "${SSH_CONTROL_DIR}"

    if [ -n "$ADB_SERIAL" ]; then
        ADB_LOCAL_PORT="$(adb -s "${ADB_SERIAL}" forward tcp:0 "tcp:${SSH_PORT}" 2>/dev/null | tr -d '\r\n' || true)"
        if [[ "${ADB_LOCAL_PORT}" =~ ^[0-9]+$ ]] && ssh \
            -p "${ADB_LOCAL_PORT}" \
            -o BatchMode=yes \
            -o ConnectTimeout=4 \
            -o StrictHostKeyChecking=no \
            -o UserKnownHostsFile=/dev/null \
            -o LogLevel=ERROR \
            root@127.0.0.1 true >/dev/null 2>&1; then
            echo "[*] Using isolated ADB forward (${ADB_SERIAL} -> 127.0.0.1:${ADB_LOCAL_PORT})..."
            SSH_TARGET="root@127.0.0.1"
            SSH_PORT="${ADB_LOCAL_PORT}"
            ADB_SSH_ISOLATED=1
        else
            if [[ "${ADB_LOCAL_PORT}" =~ ^[0-9]+$ ]]; then
                adb -s "${ADB_SERIAL}" forward --remove "tcp:${ADB_LOCAL_PORT}" >/dev/null 2>&1 || true
                ADB_LOCAL_PORT=""
            fi
            echo "[*] Container SSH unavailable over ADB forward; falling back to adb -s ${ADB_SERIAL} su..."
            ADB_FALLBACK=1
        fi
    fi

    if [ "${ADB_SSH_ISOLATED}" -eq 1 ]; then
        local safe_serial="${ADB_SERIAL//[^A-Za-z0-9._-]/_}"
        SSH_OPTS=(
            -p "${SSH_PORT}"
            -o BatchMode=yes
            -o ConnectTimeout=6
            -o StrictHostKeyChecking=no
            -o UserKnownHostsFile=/dev/null
            -o LogLevel=ERROR
            -o ControlMaster=auto
            -o ControlPersist=60
            -o "ControlPath=${SSH_CONTROL_DIR}/adb-${safe_serial}-${SSH_PORT}-%r"
        )
    else
        SSH_OPTS=(
            -p "${SSH_PORT}"
            -o BatchMode=yes
            -o ConnectTimeout=6
            -o StrictHostKeyChecking=accept-new
            -o ControlMaster=auto
            -o ControlPersist=60
            -o "ControlPath=${SSH_CONTROL_DIR}/reload-%r@%h:%p"
        )
    fi

    if [ "${ADB_SSH_ISOLATED}" -eq 1 ]; then
        TRANSPORT_LABEL="ADB+SSH (${ADB_SERIAL} -> 127.0.0.1:${SSH_PORT})"
    elif [ "${ADB_FALLBACK}" -eq 1 ]; then
        TRANSPORT_LABEL="ADB su (${ADB_SERIAL})"
    elif [ -n "$SSH_TARGET" ]; then
        TRANSPORT_LABEL="SSH (${SSH_TARGET}:${SSH_PORT})"
    else
        TRANSPORT_LABEL="local (${LOCAL_TRANSPORT})"
    fi
}

run_remote_sh() {
    if [ -n "$SSH_TARGET" ]; then
        ssh "${SSH_OPTS[@]}" "${SSH_TARGET}" "host-exec /system/bin/sh -s"
    elif [ "${ADB_FALLBACK}" -eq 1 ]; then
        adb -s "${ADB_SERIAL}" shell "su -c '/system/bin/sh -s'"
    elif [ "$LOCAL_TRANSPORT" = "host-exec" ]; then
        /usr/local/bin/host-exec /system/bin/sh -s
    else
        /system/bin/sh -s
    fi
}

run_remote_ctl() {
    local args=""
    local arg
    for arg in "$@"; do
        args="${args} $(printf '%q' "$arg")"
    done
    if [ -n "$SSH_TARGET" ]; then
        ssh "${SSH_OPTS[@]}" "${SSH_TARGET}" "host-exec ${REMOTE_MOD_DIR}/bin/sideveil-ctl${args}"
    elif [ "${ADB_FALLBACK}" -eq 1 ]; then
        printf 'exec %s%s\n' "${REMOTE_MOD_DIR}/bin/sideveil-ctl" "${args}" \
            | adb -s "${ADB_SERIAL}" shell "su -c '/system/bin/sh -s'"
    elif [ "$LOCAL_TRANSPORT" = "host-exec" ]; then
        /usr/local/bin/host-exec "${REMOTE_MOD_DIR}/bin/sideveil-ctl" "$@"
    else
        "${REMOTE_MOD_DIR}/bin/sideveil-ctl" "$@"
    fi
}

push_stage_dir() {
    local local_stage="$1"
    if [ -n "$SSH_TARGET" ]; then
        tar -C "${local_stage}" -cf - . \
            | ssh "${SSH_OPTS[@]}" "${SSH_TARGET}" \
                "rm -rf ${STAGE_DIR} && mkdir -p ${STAGE_DIR} && tar -xf - -C ${STAGE_DIR}"
    elif [ "${ADB_FALLBACK}" -eq 1 ]; then
        local stage_tar="${STAGE_DIR}.tar"
        TMP_STAGE_TAR="$(mktemp)"
        tar -C "${local_stage}" -cf "${TMP_STAGE_TAR}" .
        adb -s "${ADB_SERIAL}" push "${TMP_STAGE_TAR}" "${stage_tar}" >/dev/null
        adb -s "${ADB_SERIAL}" shell \
            "su -c 'set -e; rm -rf ${STAGE_DIR}; mkdir -p ${STAGE_DIR}; tar -xf ${stage_tar} -C ${STAGE_DIR}; rm -f ${stage_tar}'"
        rm -f "${TMP_STAGE_TAR}"
        TMP_STAGE_TAR=""
    else
        rm -rf "${STAGE_DIR}" && mkdir -p "${STAGE_DIR}"
        cp -r "${local_stage}"/. "${STAGE_DIR}/"
    fi
}

start_service_detached() {
    local launch_cmd
    launch_cmd='[ -w /sys/fs/cgroup/cgroup.procs ] && printf "%s\n" "$$" > /sys/fs/cgroup/cgroup.procs 2>/dev/null || true
for cg in /dev/cpuctl/cgroup.procs /dev/cpuset/cgroup.procs; do
    [ -w "$cg" ] && printf "%s\n" "$$" > "$cg" 2>/dev/null || true
done
nohup /system/bin/sh '"${REMOTE_MOD_DIR}"'/service.sh >/dev/null 2>&1 </dev/null &'

    if [ -n "$SSH_TARGET" ]; then
        echo "[*] Starting SideVeil supervisor detached in root cgroup..."
        ssh "${SSH_OPTS[@]}" "${SSH_TARGET}" "host-exec /system/bin/sh -c '${launch_cmd}'"
    elif [ "${ADB_FALLBACK}" -eq 1 ]; then
        echo "[*] Starting SideVeil supervisor detached in root cgroup via ADB (${ADB_SERIAL})..."
        adb -s "${ADB_SERIAL}" shell "su -c '${launch_cmd}'"
    elif [ "$LOCAL_TRANSPORT" = "host-exec" ]; then
        echo "[*] Starting SideVeil supervisor detached in root cgroup via host-exec..."
        /usr/local/bin/host-exec /system/bin/sh -c "${launch_cmd}"
    else
        echo "[*] Starting SideVeil supervisor detached in root cgroup..."
        /system/bin/sh -c "${launch_cmd}"
    fi
}
