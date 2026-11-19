#!/usr/bin/env bash
#
# Sideveil KernelSU Module Builder
# Builds Control Plane & Panel WebUIs, compiles Kotlin daemon DEX and host APK,
# and packages a flashable KernelSU ZIP archive into out/sideveil.zip.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEBUI_DIR="${SCRIPT_DIR}/webui"
ANDROID_DIR="${SCRIPT_DIR}/android"
APK_SRC="${ANDROID_DIR}/host-app/build/outputs/apk/release/host-app-release.apk"
APK_DST="${SCRIPT_DIR}/system/priv-app/SideveilHost/SideveilHost.apk"
DEX_DST="${SCRIPT_DIR}/bin/sideveil-daemon.dex"
OUTPUT_DIR="${SCRIPT_DIR}/out"
PACKAGE_NAME="sideveil.zip"

run_in_env() {
    if command -v mise >/dev/null 2>&1; then
        mise exec -- "$@"
    else
        "$@"
    fi
}

echo "[*] Building WebUI bundles (Control Plane + Built-in Panel)..."
(
    cd "${WEBUI_DIR}"
    run_in_env pnpm install --frozen-lockfile
    run_in_env pnpm typecheck
    run_in_env pnpm test
    run_in_env pnpm build
)

echo "[*] Compiling Android modules (spotlessCheck, unit tests, DEX, release APK)..."
(
    cd "${ANDROID_DIR}"
    run_in_env ./gradlew spotlessCheck :shared:test :daemon:test :host-app:testDebugUnitTest :daemon:buildDex :host-app:assembleRelease
)

if [ ! -f "${DEX_DST}" ]; then
    echo "[!] Error: Daemon DEX not found at ${DEX_DST}" >&2
    exit 1
fi

if [ ! -f "${APK_SRC}" ]; then
    echo "[!] Error: Host APK not found at ${APK_SRC}" >&2
    exit 1
fi

mkdir -p "$(dirname "${APK_DST}")" "${OUTPUT_DIR}"
cp "${APK_SRC}" "${APK_DST}"
chmod 0644 "${APK_DST}" "${DEX_DST}"

echo "[*] Packaging KernelSU archive -> ${OUTPUT_DIR}/${PACKAGE_NAME}..."
rm -f "${OUTPUT_DIR}/${PACKAGE_NAME}"

python3 - <<EOF
import os
import stat
import zipfile

script_dir = "${SCRIPT_DIR}"
output_zip = os.path.join("${OUTPUT_DIR}", "${PACKAGE_NAME}")

explicit_files = [
    ("module.prop", 0o644),
    ("config.json", 0o644),
    ("service.sh", 0o755),
    ("post-fs-data.sh", 0o755),
    ("customize.sh", 0o755),
    ("uninstall.sh", 0o755),
    ("bin/sideveil-ctl", 0o755),
    ("bin/sideveil-common.sh", 0o644),
    ("bin/sideveil-daemon.dex", 0o644),
]

def write_file(zf, src_path, arcname, mode):
    with open(src_path, "rb") as f:
        data = f.read()
    info = zipfile.ZipInfo(arcname)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = (stat.S_IFREG | mode) << 16
    zf.writestr(info, data)

with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
    for rel_path, mode in explicit_files:
        full_path = os.path.join(script_dir, rel_path)
        if not os.path.isfile(full_path):
            raise FileNotFoundError(f"Required module file missing: {full_path}")
        write_file(zf, full_path, rel_path, mode)

    for folder in ("system", "webroot"):
        base = os.path.join(script_dir, folder)
        if not os.path.isdir(base):
            raise FileNotFoundError(f"Required module directory missing: {base}")
        for root, dirs, files in os.walk(base):
            dirs[:] = sorted(d for d in dirs if not d.startswith("."))
            for fname in sorted(files):
                if fname.startswith(".") or fname.endswith((".tmp", ".swp", "~")):
                    continue
                full_path = os.path.join(root, fname)
                arcname = os.path.relpath(full_path, script_dir).replace(os.sep, "/")
                write_file(zf, full_path, arcname, 0o644)

print("[+] Packaged module archive:", output_zip)
EOF
