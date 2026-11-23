#!/usr/bin/env bash
#
# Shared toolchain runner and bundled example plugin registry for build.sh & reload.sh.
#

if [ -n "${SIDEVEIL_MODULE_ENV_LOADED:-}" ]; then
    return 0 2>/dev/null || exit 0
fi
SIDEVEIL_MODULE_ENV_LOADED=1

# Bundled example plugins shipped inside the KernelSU module archive and synced during full reload.
# Entry format: "<repo-relative-source-dir>:<installed-plugin-id>"
BUNDLED_PLUGINS=(
    "examples/vello-vector-scope:example.vello-scope"
    "examples/native-perf-hud:example.native-perf-hud"
)

run_in_env() {
    if command -v mise >/dev/null 2>&1; then
        mise exec -- "$@"
    else
        "$@"
    fi
}
