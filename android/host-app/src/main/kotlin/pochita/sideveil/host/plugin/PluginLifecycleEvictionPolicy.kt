package pochita.sideveil.host.plugin

import pochita.sideveil.protocol.PluginDescriptor

/**
 * Pure-Kotlin teardown and active-plugin resolution plan computed whenever a fresh
 * `plugins.updated` catalog snapshot arrives from `sideveild`.
 */
internal data class SnapshotLifecyclePlan(
    val uninstalledPluginIds: Set<String>,
    val disabledOrRemovedIds: Set<String>,
    val shouldHidePinnedPluginId: String?,
    val shouldDismissHudPluginId: String?,
    val nextActivePluginId: String?,
)

/**
 * Pure-Kotlin lifecycle eviction planner guaranteeing orphaned pinned windows, native HUDs,
 * and uninstalled plugin storage/URLs are cleanly torn down without falling back to hidden
 * built-in diagnostic plugins.
 */
internal object PluginLifecycleEvictionPolicy {
    fun planSnapshotTransition(
        previousPluginIds: Set<String>,
        nextDescriptors: Map<String, PluginDescriptor>,
        matchedPluginIds: List<String>,
        requestedActivePluginId: String?,
        currentPinnedPluginId: String?,
        currentHudPluginId: String?,
    ): SnapshotLifecyclePlan {
        val uninstalled = previousPluginIds - nextDescriptors.keys
        val disabledOrRemoved =
            buildSet {
                addAll(uninstalled)
                for ((id, desc) in nextDescriptors) {
                    if (!desc.enabled) add(id)
                }
            }
        val hidePinned = currentPinnedPluginId?.takeIf { it in disabledOrRemoved }
        val dismissHud = currentHudPluginId?.takeIf { it in disabledOrRemoved }
        val validMatched = matchedPluginIds.filter { id -> nextDescriptors[id]?.enabled == true }
        val nextActive =
            requestedActivePluginId?.takeIf { id -> nextDescriptors[id]?.enabled == true }
                ?: validMatched.firstOrNull()
        return SnapshotLifecyclePlan(
            uninstalledPluginIds = uninstalled,
            disabledOrRemovedIds = disabledOrRemoved,
            shouldHidePinnedPluginId = hidePinned,
            shouldDismissHudPluginId = dismissHud,
            nextActivePluginId = nextActive,
        )
    }

    /** Returns `true` when a render-process crash affects the currently pinned plugin window. */
    fun shouldHidePinnedOnCrash(
        crashedPluginId: String,
        currentPinnedPluginId: String?,
    ): Boolean = currentPinnedPluginId != null && crashedPluginId == currentPinnedPluginId

    /** Builds the native recovery overlay state shown after the renderer crash circuit breaker opens. */
    fun buildCrashCircuitError(
        pluginId: String,
        pluginName: String,
        attempt: Int,
        onRetry: () -> Unit,
    ): PluginPanelOverlayState.Error =
        PluginPanelOverlayState.Error(
            PluginSurfaceErrorState(
                pluginId = pluginId,
                pluginName = pluginName,
                title = "插件渲染进程已停止",
                detail = "连续崩溃 $attempt 次，已暂停自动恢复，请点击重新加载",
                onRetry = onRetry,
            ),
        )
}
