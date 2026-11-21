#!/system/bin/sh
#
# Cleanup script executed when the SideVeil KernelSU module is removed.
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
    /data/adb/sideveil/run/supervisor.pid \
    /data/adb/sideveil/run/sideveild.pid \
    /data/adb/sideveil/run/hostwatch.pid \
    /data/system/sideveil/run/hostwatch.pid \
    /data/system/sideveil/run/supervisor.pid; do
    if [ -f "$pidfile" ] && [ ! -L "$pidfile" ]; then
        P=$(cat "$pidfile" 2>/dev/null || true)
        terminate_pid "$P"
    fi
done

pkill -l TERM -x "sideveild" 2>/dev/null || true
pkill -l TERM -U 1000 -f "sideveil-sensor|pochita\.sideveil\.sensor\.SensorMain" 2>/dev/null || true
pkill -l TERM -U 1000 -f "sideveil-daemon|pochita\.sideveil\.daemon\.DaemonMain" 2>/dev/null || true
sleep 0.5
pkill -l KILL -x "sideveild" 2>/dev/null || true
pkill -l KILL -U 1000 -f "sideveil-sensor|pochita\.sideveil\.sensor\.SensorMain" 2>/dev/null || true
pkill -l KILL -U 1000 -f "sideveil-daemon|pochita\.sideveil\.daemon\.DaemonMain" 2>/dev/null || true
pm uninstall pochita.sideveil.host >/dev/null 2>&1 || pm uninstall-system-updates pochita.sideveil.host >/dev/null 2>&1 || true
rm -rf /data/system/sideveil /data/adb/sideveil
