#!/usr/bin/env bash
#
# SideVeil KernelSU Module Builder
# Builds Rust core hub (sideveild + sideveil-ctl), Control Plane & Panel WebUIs,
# thin Kotlin sensor DEX, and SideVeilHost APK, then packages out/sideveil.zip
# (and out/sideveil-debug.zip when --mode both is specified).
#
# Options:
#   --mode <debug|release|both>  Build mode (default: debug).
#                                debug:   packages bundled example plugins into out/sideveil.zip.
#                                release: builds a lean archive without example plugins.
#                                both:    compiles once and packages out/sideveil.zip (lean)
#                                         and out/sideveil-debug.zip (with example plugins).
#

set -euo pipefail

BUILD_MODE="debug"

while [ "$#" -gt 0 ]; do
    case "$1" in
        --mode)
            if [ "$#" -lt 2 ]; then
                echo "[!] Error: --mode requires a value (debug|release|both)" >&2
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
            echo "Usage: bash scripts/build.sh [--mode <debug|release|both>]"
            exit 0
            ;;
        *)
            echo "[!] Error: Unknown option '$1'. Usage: bash scripts/build.sh [--mode <debug|release|both>]" >&2
            exit 2
            ;;
    esac
done

case "${BUILD_MODE}" in
    debug|release|both) ;;
    *)
        echo "[!] Error: Invalid --mode '${BUILD_MODE}'. Expected 'debug', 'release', or 'both'." >&2
        exit 2
        ;;
esac

echo "[*] Build mode: ${BUILD_MODE}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
MODULE_DIR="${ROOT_DIR}/module"
STAGE_DIR="${ROOT_DIR}/out/stage"
HUB_DIR="${ROOT_DIR}/hub"
WEBUI_DIR="${ROOT_DIR}/webui"
ANDROID_DIR="${ROOT_DIR}/android"
HUB_TARGET_DIR="${HUB_DIR}/target/aarch64-linux-android/release"
APK_SRC="${ANDROID_DIR}/host-app/build/outputs/apk/release/host-app-release.apk"
APK_DST="${STAGE_DIR}/system/priv-app/SideVeilHost/SideVeilHost.apk"
HUB_DST="${STAGE_DIR}/bin/sideveild"
CTL_DST="${STAGE_DIR}/bin/sideveil-ctl"
PERF_HUD_BIN="${ROOT_DIR}/examples/native-perf-hud/target/aarch64-linux-android/release/sideveil-example-perf-hud"
HUD_JNI_DST="${ANDROID_DIR}/host-app/src/main/jniLibs/arm64-v8a/libsideveil_hud.so"
DEX_DST="${STAGE_DIR}/bin/sideveil-sensor.dex"
OUTPUT_DIR="${ROOT_DIR}/out"
RELEASE_ARCHIVE="${OUTPUT_DIR}/sideveil.zip"
DEBUG_ARCHIVE="${OUTPUT_DIR}/sideveil-debug.zip"

# shellcheck source=scripts/lib/module-env.sh
source "${SCRIPT_DIR}/lib/module-env.sh"

echo "[*] Checking & cross-compiling Rust hub + HUD JNI (sideveild + sideveil-ctl + libsideveil_hud.so for aarch64-linux-android)..."
(
    cd "${HUB_DIR}"
    run_in_env cargo fmt --check
    run_in_env cargo clippy --workspace --all-targets -- -D warnings
    run_in_env cargo test --workspace
    run_in_env cargo build --release --target aarch64-linux-android -p sideveild -p sideveil-ctl -p sideveil-hud-jni
)

mkdir -p "${STAGE_DIR}/bin" "$(dirname "${HUD_JNI_DST}")"
cp "${HUB_TARGET_DIR}/sideveild" "${HUB_DST}"
cp "${HUB_TARGET_DIR}/sideveil-ctl" "${CTL_DST}"
cp "${HUB_TARGET_DIR}/libsideveil_hud.so" "${HUD_JNI_DST}"
chmod 0755 "${HUB_DST}" "${CTL_DST}"
chmod 0644 "${HUD_JNI_DST}"

if [ "${BUILD_MODE}" = "debug" ] || [ "${BUILD_MODE}" = "both" ]; then
    echo "[*] Cross-compiling bundled example plugin daemon (sideveil-example-perf-hud for aarch64-linux-android)..."
    (
        cd "${ROOT_DIR}/examples/native-perf-hud"
        run_in_env cargo fmt --check
        run_in_env cargo clippy --all-targets -- -D warnings
        run_in_env cargo build --release --target aarch64-linux-android
    )
else
    echo "[*] Release mode: skipping bundled example plugin daemon (sideveil-example-perf-hud)."
fi

if [ "${BUILD_MODE}" = "debug" ] || [ "${BUILD_MODE}" = "both" ]; then
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
if [ "${BUILD_MODE}" = "debug" ] || [ "${BUILD_MODE}" = "both" ]; then
    REQUIRED_ARTIFACTS+=("${PERF_HUD_BIN}")
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

echo "[*] Packaging KernelSU archive(s) (mode=${BUILD_MODE})..."
rm -f "${RELEASE_ARCHIVE}" "${DEBUG_ARCHIVE}"

PACKAGED_PLUGINS_SPEC=""
if [ "${BUILD_MODE}" = "debug" ] || [ "${BUILD_MODE}" = "both" ]; then
    PACKAGED_PLUGINS_SPEC="$(printf '%s\n' "${BUNDLED_PLUGINS[@]}")"
fi

BUILD_MODE="${BUILD_MODE}" BUNDLED_PLUGINS_SPEC="${PACKAGED_PLUGINS_SPEC}" python3 - <<EOF
import os
import stat
import zipfile

root_dir = "${ROOT_DIR}"
module_dir = "${MODULE_DIR}"
stage_dir = "${STAGE_DIR}"
release_zip = "${RELEASE_ARCHIVE}"
debug_zip = "${DEBUG_ARCHIVE}"
perf_bin = "${PERF_HUD_BIN}"
build_mode = os.environ.get("BUILD_MODE", "debug")

explicit_files = [
    (os.path.join(module_dir, "module.prop"), "module.prop", 0o644),
    (os.path.join(module_dir, "config.json"), "config.json", 0o644),
    (os.path.join(module_dir, "service.sh"), "service.sh", 0o755),
    (os.path.join(module_dir, "post-fs-data.sh"), "post-fs-data.sh", 0o755),
    (os.path.join(module_dir, "customize.sh"), "customize.sh", 0o755),
    (os.path.join(module_dir, "uninstall.sh"), "uninstall.sh", 0o755),
    (os.path.join(stage_dir, "bin/sideveild"), "bin/sideveild", 0o755),
    (os.path.join(stage_dir, "bin/sideveil-ctl"), "bin/sideveil-ctl", 0o755),
    (os.path.join(stage_dir, "bin/sideveil-sensor.dex"), "bin/sideveil-sensor.dex", 0o644),
]

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

def walk_into_zip(zf, base_dir, arc_prefix):
    if not os.path.isdir(base_dir):
        raise FileNotFoundError(f"Required module directory missing: {base_dir}")
    for root, dirs, files in os.walk(base_dir):
        dirs[:] = sorted(d for d in dirs if not d.startswith("."))
        for fname in sorted(files):
            if fname.startswith(".") or fname.endswith((".tmp", ".swp", "~")):
                continue
            full_path = os.path.join(root, fname)
            rel = os.path.relpath(full_path, base_dir).replace(os.sep, "/")
            write_file(zf, full_path, f"{arc_prefix}/{rel}", 0o644)

def package_archive(output_zip, include_plugins):
    with zipfile.ZipFile(output_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for src_path, arcname, mode in explicit_files:
            if not os.path.isfile(src_path):
                raise FileNotFoundError(f"Required module file missing: {src_path}")
            write_file(zf, src_path, arcname, mode)

        walk_into_zip(zf, os.path.join(module_dir, "system"), "system")
        walk_into_zip(zf, os.path.join(stage_dir, "system"), "system")
        walk_into_zip(zf, os.path.join(stage_dir, "webroot"), "webroot")

        if include_plugins:
            for src_rel, dst_prefix in bundled_plugins:
                src_base = os.path.join(root_dir, src_rel)
                manifest_src = os.path.join(src_base, "manifest.json")
                if not os.path.isfile(manifest_src):
                    raise FileNotFoundError(f"Bundled plugin manifest missing: {manifest_src}")
                write_file(zf, manifest_src, f"{dst_prefix}/manifest.json", 0o644)
                web_dir = os.path.join(src_base, "web")
                if os.path.isdir(web_dir):
                    walk_into_zip(zf, web_dir, f"{dst_prefix}/web")
                if dst_prefix == "plugins/example.native-perf-hud":
                    if not os.path.isfile(perf_bin):
                        raise FileNotFoundError(f"Bundled daemon binary missing: {perf_bin}")
                    write_file(zf, perf_bin, f"{dst_prefix}/bin/sideveil-example-perf-hud", 0o755)
    print("[+] Packaged module archive:", output_zip)

if build_mode == "both":
    package_archive(release_zip, include_plugins=False)
    package_archive(debug_zip, include_plugins=True)
elif build_mode == "debug":
    package_archive(release_zip, include_plugins=True)
else:
    package_archive(release_zip, include_plugins=False)
EOF
