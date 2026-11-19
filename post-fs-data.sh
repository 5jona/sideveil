#!/system/bin/sh
#
# Early-boot stage script for KernelSU.
# Prepares /data/system/sideveil ownership so UID 1000 can operate without accessing /data/adb.
#

STATE_DIR="/data/system/sideveil"
RUN_DIR="${STATE_DIR}/run"
ROOT_RUN_DIR="/data/adb/sideveil/run"

mkdir -p "$STATE_DIR" "$RUN_DIR" "$ROOT_RUN_DIR"
chown 0:1000 "$STATE_DIR"
chmod 1775 "$STATE_DIR"
chown 1000:1000 "$RUN_DIR"
chmod 0700 "$RUN_DIR"
chown 0:0 "/data/adb/sideveil" "$ROOT_RUN_DIR"
chmod 0700 "/data/adb/sideveil" "$ROOT_RUN_DIR"
rm -f "${RUN_DIR}/daemon.env"
