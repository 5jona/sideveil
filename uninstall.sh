#!/system/bin/sh
#
# Cleanup script executed when the Sideveil KernelSU module is removed.
#

terminate_pid() {
    TARGET_PID="$1"
    case "$TARGET_PID" in
        ''|*[!0-9]*) return 0 ;;
    esac
    if kill -0 "$TARGET_PID" 2>/dev/null; then
        kill -TERM "$TARGET_PID" 2>/dev/null || true
        sleep 0.3
        kill -9 "$TARGET_PID" 2>/dev/null || true
    fi
}

for pidfile in \
    /data/adb/sideveil/run/hostwatch.pid \
    /data/adb/sideveil/run/supervisor.pid \
    /data/system/sideveil/run/hostwatch.pid \
    /data/system/sideveil/run/supervisor.pid; do
    if [ -f "$pidfile" ] && [ ! -L "$pidfile" ]; then
        P=$(cat "$pidfile" 2>/dev/null || true)
        terminate_pid "$P"
    fi
done

if [ -f /data/system/sideveil/run/daemon.env ] && [ ! -L /data/system/sideveil/run/daemon.env ]; then
    D_PID=$(sed -n 's/^SIDEVEIL_PID=//p' /data/system/sideveil/run/daemon.env 2>/dev/null || true)
    terminate_pid "$D_PID"
fi

pkill -TERM -x "sideveil-daemon" 2>/dev/null || true
sleep 0.5
pkill -9 -x "sideveil-daemon" 2>/dev/null || true
pm uninstall pochita.sideveil.host >/dev/null 2>&1 || pm uninstall-system-updates pochita.sideveil.host >/dev/null 2>&1 || true
rm -rf /data/system/sideveil /data/adb/sideveil
