#!/system/bin/sh
#
# Cleanup script executed when the Sideveil KernelSU module is removed.
#

for pidfile in /data/system/sideveil/run/hostwatch.pid /data/system/sideveil/run/supervisor.pid; do
    if [ -f "$pidfile" ]; then
        P=$(cat "$pidfile" 2>/dev/null || true)
        [ -n "$P" ] && kill -9 "$P" 2>/dev/null || true
    fi
done

pkill -9 -x "sideveil-daemon" 2>/dev/null || true
pm uninstall pochita.sideveil.host >/dev/null 2>&1 || pm uninstall-system-updates pochita.sideveil.host >/dev/null 2>&1 || true
rm -rf /data/system/sideveil
