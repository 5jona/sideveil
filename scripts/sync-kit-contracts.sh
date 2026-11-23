#!/usr/bin/env bash
# Copy generated SideVeil TypeScript contract files into a gojona-kit checkout.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SRC_DIR="${REPO_ROOT}/contracts/generated/ts"

RAW_KIT_ROOT="${1:-${POCHITA_KIT_ROOT:-${REPO_ROOT}/../../../gojona-kit}}"
if [ ! -d "${RAW_KIT_ROOT}" ]; then
    echo "[!] Error: gojona-kit directory not found: ${RAW_KIT_ROOT}" >&2
    echo "Usage: bash scripts/sync-kit-contracts.sh [path/to/gojona-kit]" >&2
    exit 1
fi
KIT_ROOT="$(cd "${RAW_KIT_ROOT}" && pwd)"

BRIDGE_DST="${KIT_ROOT}/packages/bridge/src/generated"
SDK_DST="${KIT_ROOT}/packages/sdk/src/generated"

for dir in "${SRC_DIR}" "${BRIDGE_DST}" "${SDK_DST}"; do
    if [ ! -d "${dir}" ]; then
        echo "[!] Error: Expected directory not found: ${dir}" >&2
        exit 1
    fi
done

install -m 0644 "${SRC_DIR}/sideveilConstants.ts" "${BRIDGE_DST}/sideveilConstants.ts"
install -m 0644 "${SRC_DIR}/sideveilConfig.ts" "${BRIDGE_DST}/sideveilConfig.ts"
install -m 0644 "${SRC_DIR}/sideveilWire.ts" "${SDK_DST}/sideveilWire.ts"
install -m 0644 "${SRC_DIR}/sideveilThemeHud.ts" "${SDK_DST}/sideveilThemeHud.ts"

echo "[+] Synced 4 generated TypeScript contract files to ${KIT_ROOT}"
