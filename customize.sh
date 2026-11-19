#!/system/bin/sh
#
# Module installation and permission setup script for KernelSU / Magisk.
#

SKIPUNZIP=0

ui_print "- Installing Sideveil..."

if [ "$ARCH" != "arm64" ]; then
    abort "! Unsupported architecture: $ARCH. Sideveil requires arm64."
fi

ui_print "- Setting module file permissions..."
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/bin/sideveil-ctl" 0 0 0755
set_perm "$MODPATH/bin/sideveil-common.sh" 0 0 0644
set_perm "$MODPATH/bin/sideveil-daemon.dex" 0 0 0644
set_perm "$MODPATH/module.prop" 0 0 0644
set_perm "$MODPATH/config.json" 0 0 0644

set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm_recursive "$MODPATH/webroot" 0 0 0755 0644

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

if [ ! -f "${STATE_DIR}/config.json" ] && [ -f "${MODPATH}/config.json" ]; then
    cp "${MODPATH}/config.json" "${STATE_DIR}/config.json"
    chown 1000:1000 "${STATE_DIR}/config.json"
    chmod 0644 "${STATE_DIR}/config.json"
    ui_print "- Seeded default config to ${STATE_DIR}/config.json"
else
    ui_print "- Preserved existing ${STATE_DIR}/config.json"
fi

ui_print "- SideveilHost APK will mount under /system/priv-app after reboot."
ui_print "- Installation completed successfully."
