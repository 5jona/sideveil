#!/usr/bin/env bash
#
# SideVeil KernelSU Module Builder
# Builds Rust core hub (sideveild + sideveil-ctl), Control Plane & Panel WebUIs,
# thin Kotlin sensor DEX, and SideVeilHost APK, then packages out/sideveil.zip.
#
# Options:
#   --mode <debug|release>  Build mode (default: debug).
#                           debug:   packages all bundled source-level test plugins
#                                    (toggleable at runtime via debug.setRuntimeMode).
#                           release: builds a lean archive skipping example.vello-scope
#                                    and example.native-perf-hud binaries/assets.
#

set -euo pipefail

BUILD_MODE="debug"

while [ "$#" -gt 0 ]; do
    case "$1" in
        --mode)
            if [ "$#" -lt 2 ]; then
                echo "[!] Error: --mode requires a value (debug|release)" >&2
                exit 2
            fi
            BUILD_MODE="$2"
            shift 2
            ;;
        --mode=*)
            BUILD_MODE="${1#--mode=}"
            shift
            ;;
        -h|--help)
            echo "Usage: bash build.sh [--mode <debug|release>]"
            exit 0
            ;;
        *)
            echo "[!] Error: Unknown option '$1'. Usage: bash build.sh [--mode <debug|release>]" >&2
            exit 2
            ;;
    esac
done

case "${BUILD_MODE}" in
    debug|release) ;;
    *)
        echo "[!] Error: Invalid --mode '${BUILD_MODE}'. Expected 'debug' or 'release'." >&2
        exit 2
        ;;
esac

echo "[*] Build mode: ${BUILD_MODE}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HUB_DIR="${SCRIPT_DIR}/hub"
WEBUI_DIR="${SCRIPT_DIR}/webui"
ANDROID_DIR="${SCRIPT_DIR}/android"
HUB_TARGET_DIR="${HUB_DIR}/target/aarch64-linux-android/release"
APK_SRC="${ANDROID_DIR}/host-app/build/outputs/apk/release/host-app-release.apk"
APK_DST="${SCRIPT_DIR}/system/priv-app/SideVeilHost/SideVeilHost.apk"
HUB_DST="${SCRIPT_DIR}/bin/sideveild"
CTL_DST="${SCRIPT_DIR}/bin/sideveil-ctl"
PERF_HUD_DST="${SCRIPT_DIR}/bin/sideveil-example-perf-hud"
HUD_JNI_DST="${ANDROID_DIR}/host-app/src/main/jniLibs/arm64-v8a/libsideveil_hud.so"
DEX_DST="${SCRIPT_DIR}/bin/sideveil-sensor.dex"
OUTPUT_DIR="${SCRIPT_DIR}/out"
PACKAGE_NAME="sideveil.zip"

# shellcheck source=scripts/lib/module-env.sh
source "${SCRIPT_DIR}/scripts/lib/module-env.sh"

echo "[*] Checking & cross-compiling Rust hub + HUD JNI (sideveild + sideveil-ctl + libsideveil_hud.so for aarch64-linux-android)..."
(
    cd "${HUB_DIR}"
    run_in_env cargo fmt --check
    run_in_env cargo clippy --workspace --all-targets -- -D warnings
    run_in_env cargo test --workspace
    run_in_env cargo build --release --target aarch64-linux-android -p sideveild -p sideveil-ctl -p sideveil-hud-jni
)

mkdir -p "${SCRIPT_DIR}/bin" "$(dirname "${HUD_JNI_DST}")"
cp "${HUB_TARGET_DIR}/sideveild" "${HUB_DST}"
cp "${HUB_TARGET_DIR}/sideveil-ctl" "${CTL_DST}"
cp "${HUB_TARGET_DIR}/libsideveil_hud.so" "${HUD_JNI_DST}"
chmod 0755 "${HUB_DST}" "${CTL_DST}"
chmod 0644 "${HUD_JNI_DST}"

if [ "${BUILD_MODE}" = "debug" ]; then
    echo "[*] Cross-compiling bundled example plugin daemon (sideveil-example-perf-hud for aarch64-linux-android)..."
    (
        cd "${SCRIPT_DIR}/examples/native-perf-hud"
        run_in_env cargo fmt --check
        run_in_env cargo clippy --all-targets -- -D warnings
        run_in_env cargo build --release --target aarch64-linux-android
    )
    cp "${SCRIPT_DIR}/examples/native-perf-hud/target/aarch64-linux-android/release/sideveil-example-perf-hud" "${PERF_HUD_DST}"
    chmod 0755 "${PERF_HUD_DST}"
else
    echo "[*] Release mode: skipping bundled example plugin daemon (sideveil-example-perf-hud)."
fi

if [ "${BUILD_MODE}" = "debug" ]; then
    echo "[*] Building WebUI bundles (Control Plane + Built-in Panel + Bundled Plugins)..."
    (
        cd "${WEBUI_DIR}"
        run_in_env pnpm install --frozen-lockfile
        run_in_env pnpm check
    )
else
    echo "[*] Building WebUI bundles (Release lean mode: Control Plane + Built-in Panel)..."
    (
        cd "${WEBUI_DIR}"
        run_in_env pnpm install --frozen-lockfile
        run_in_env pnpm lint
        run_in_env pnpm typecheck
        run_in_env pnpm build:control
        run_in_env pnpm build:panel
    )
fi

echo "[*] Compiling Android modules (spotlessCheck, detekt, lintDebug, unit tests, sensor DEX, release APK)..."
(
    cd "${ANDROID_DIR}"
    run_in_env ./gradlew spotlessCheck detekt :host-app:lintDebug :protocol:test :sensor:test :host-app:testDebugUnitTest :sensor:buildDex :host-app:assembleRelease
)

REQUIRED_ARTIFACTS=("${HUB_DST}" "${CTL_DST}" "${DEX_DST}" "${APK_SRC}")
if [ "${BUILD_MODE}" = "debug" ]; then
    REQUIRED_ARTIFACTS+=("${PERF_HUD_DST}")
fi

for required in "${REQUIRED_ARTIFACTS[@]}"; do
    if [ ! -f "${required}" ]; then
        echo "[!] Error: Required artifact not found at ${required}" >&2
        exit 1
    fi
done

mkdir -p "$(dirname "${APK_DST}")" "${OUTPUT_DIR}"
cp "${APK_SRC}" "${APK_DST}"
chmod 0644 "${APK_DST}" "${DEX_DST}"

echo "[*] Packaging KernelSU archive (mode=${BUILD_MODE}) -> ${OUTPUT_DIR}/${PACKAGE_NAME}..."
rm -f "${OUTPUT_DIR}/${PACKAGE_NAME}"

PACKAGED_PLUGINS_SPEC=""
if [ "${BUILD_MODE}" = "debug" ]; then
    PACKAGED_PLUGINS_SPEC="$(printf '%s\n' "${BUNDLED_PLUGINS[@]}")"
fi

BUILD_MODE="${BUILD_MODE}" BUNDLED_PLUGINS_SPEC="${PACKAGED_PLUGINS_SPEC}" python3 - <<EOF
import os
import stat
import zipfile

script_dir = "${SCRIPT_DIR}"
output_zip = os.path.join("${OUTPUT_DIR}", "${PACKAGE_NAME}")
build_mode = os.environ.get("BUILD_MODE", "debug")

explicit_files = [
    ("module.prop", 0o644),
    ("config.json", 0o644),
    ("service.sh", 0o755),
    ("post-fs-data.sh", 0o755),
    ("customize.sh", 0o755),
    ("uninstall.sh", 0o755),
    ("bin/sideveild", 0o755),
    ("bin/sideveil-ctl", 0o755),
    ("bin/sideveil-sensor.dex", 0o644),
]
if build_mode == "debug":
    explicit_files.append(("bin/sideveil-example-perf-hud", 0o755))

bundled_plugins = []
for raw_entry in os.environ.get("BUNDLED_PLUGINS_SPEC", "").splitlines():
    entry = raw_entry.strip()
    if not entry:
        continue
    src_rel, plugin_id = entry.split(":", 1)
    bundled_plugins.append((src_rel, f"plugins/{plugin_id}"))

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

    for src_rel, dst_prefix in bundled_plugins:
        src_base = os.path.join(script_dir, src_rel)
        manifest_src = os.path.join(src_base, "manifest.json")
        if not os.path.isfile(manifest_src):
            raise FileNotFoundError(f"Bundled plugin manifest missing: {manifest_src}")
        write_file(zf, manifest_src, f"{dst_prefix}/manifest.json", 0o644)
        web_dir = os.path.join(src_base, "web")
        if os.path.isdir(web_dir):
            for root, dirs, files in os.walk(web_dir):
                dirs[:] = sorted(d for d in dirs if not d.startswith("."))
                for fname in sorted(files):
                    if fname.startswith(".") or fname.endswith((".tmp", ".swp", "~")):
                        continue
                    full_path = os.path.join(root, fname)
                    rel_web = os.path.relpath(full_path, src_base).replace(os.sep, "/")
                    write_file(zf, full_path, f"{dst_prefix}/{rel_web}", 0o644)

print("[+] Packaged module archive:", output_zip)
EOF
