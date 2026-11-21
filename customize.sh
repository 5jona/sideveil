#!/system/bin/sh
#
# Module installation and permission setup script for KernelSU / Magisk.
#

SKIPUNZIP=0

ui_print "- Installing SideVeil..."

if [ "$ARCH" != "arm64" ]; then
    abort "! Unsupported architecture: $ARCH. SideVeil requires arm64."
fi

ui_print "- Setting module file permissions..."
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/bin/sideveild" 0 0 0755
set_perm "$MODPATH/bin/sideveil-ctl" 0 0 0755
if [ -f "$MODPATH/bin/sideveil-example-perf-hud" ]; then
    set_perm "$MODPATH/bin/sideveil-example-perf-hud" 0 0 0755
fi
set_perm "$MODPATH/bin/sideveil-sensor.dex" 0 0 0644
set_perm "$MODPATH/module.prop" 0 0 0644
set_perm "$MODPATH/config.json" 0 0 0644

set_perm_recursive "$MODPATH/system" 0 0 0755 0644
set_perm_recursive "$MODPATH/webroot" 0 0 0755 0644
if [ -d "$MODPATH/plugins" ]; then
    set_perm_recursive "$MODPATH/plugins" 0 0 0755 0644
fi

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

if [ ! -f "${STATE_DIR}/config.json" ] && [ -f "${MODPATH}/config.json" ]; then
    cp "${MODPATH}/config.json" "${STATE_DIR}/config.json"
    chown 0:1000 "${STATE_DIR}/config.json"
    chmod 0644 "${STATE_DIR}/config.json"
    ui_print "- Seeded default config to ${STATE_DIR}/config.json"
else
    ui_print "- Preserved existing ${STATE_DIR}/config.json"
fi

if [ -d "${MODPATH}/plugins" ]; then
    for pdir in "${MODPATH}/plugins"/*; do
        [ -d "$pdir" ] || continue
        pname="${pdir##*/}"
        rm -rf "${PLUGINS_DIR:?}/${pname}"
        cp -r "$pdir" "${PLUGINS_DIR}/${pname}"
        chown -R 0:1000 "${PLUGINS_DIR}/${pname}"
        find "${PLUGINS_DIR}/${pname}" -type d -exec chmod 0755 {} +
        find "${PLUGINS_DIR}/${pname}" -type f -exec chmod 0644 {} +
    done
    ui_print "- Seeded bundled template plugins to ${PLUGINS_DIR}"
fi

ui_print "- SideVeilHost APK will mount under /system/priv-app after reboot."
ui_print "- Installation completed successfully."
