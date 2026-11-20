#!/system/bin/sh
#
# Early-boot stage script for KernelSU.
# Prepares /data/system/sideveil directories and cleans stale sockets from prior boots.
#

STATE_DIR="/data/system/sideveil"
RUN_DIR="${STATE_DIR}/run"
PLUGINS_DIR="${STATE_DIR}/plugins"
DATA_DIR="${STATE_DIR}/data"
ROOT_RUN_DIR="/data/adb/sideveil/run"

mkdir -p "$STATE_DIR" "$RUN_DIR" "$PLUGINS_DIR" "$DATA_DIR" "$ROOT_RUN_DIR"
chown 0:1000 "$STATE_DIR" "$RUN_DIR" "$PLUGINS_DIR"
chmod 1775 "$STATE_DIR"
chmod 0711 "$RUN_DIR"
chmod 0755 "$PLUGINS_DIR"
chown 0:0 "$DATA_DIR" "/data/adb/sideveil" "$ROOT_RUN_DIR"
chmod 0700 "$DATA_DIR" "/data/adb/sideveil" "$ROOT_RUN_DIR"
rm -f "${RUN_DIR}/hub.sock" "${RUN_DIR}/app.sock" "${RUN_DIR}/daemon.env" "${RUN_DIR}/hostwatch.pid" "${RUN_DIR}/supervisor.pid"
