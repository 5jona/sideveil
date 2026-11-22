package pochita.sideveil.host

import pochita.sideveil.protocol.PanelState

/**
 * Pure-Kotlin LRU warm pool for plugin WebView instances.
 *
 * Owns access-order storage, COOP/COEP-first VRAM reclamation, exemption of active, pinned,
 * and `keepAlive`-protected plugins from eviction, stale cleanup, and renderer priority sync.
 */
internal class PluginWebViewPool<T>(
    private val maxWarmInstances: Int = DEFAULT_MAX_WARM_INSTANCES,
    private val maxKeepAliveInstances: Int = DEFAULT_MAX_KEEP_ALIVE_INSTANCES,
    private val isCrossOriginIsolated: (T) -> Boolean,
    private val setForegroundPriority: (T, Boolean) -> Unit,
    private val onDestroy: (T) -> Unit,
    private val onEvicted: (String) -> Unit = {},
) {
    private val warmInstances = LinkedHashMap<String, T>(4, 0.75f, true)

    val size: Int
        get() = warmInstances.size

    /**
     * Retrieves [pluginId] and promotes it to the most-recently-used position.
     */
    fun get(pluginId: String): T? = warmInstances[pluginId]

    /**
     * Retrieves [pluginId] without altering LRU recency order.
     */
    fun peek(pluginId: String): T? = warmInstances.entries.firstOrNull { it.key == pluginId }?.value

    fun put(
        pluginId: String,
        instance: T,
    ) {
        warmInstances[pluginId] = instance
    }

    fun getOrCreate(
        pluginId: String,
        activePluginId: String,
        pinnedPluginId: String? = null,
        protectedIds: Set<String> = emptySet(),
        onHit: (T) -> Unit = {},
        create: () -> T,
    ): T {
        warmInstances[pluginId]?.let { existing ->
            onHit(existing)
            return existing
        }
        evictLruIfNeeded(
            activePluginId = activePluginId,
            keepId = pluginId,
            pinnedPluginId = pinnedPluginId,
            protectedIds = protectedIds,
        )
        return create().also { warmInstances[pluginId] = it }
    }

    fun warmPluginIds(): List<String> = warmInstances.keys.toList()

    /**
     * Returns a snapshot of `(pluginId, instance)` entries in current LRU order without altering
     * recency metadata.
     */
    fun snapshotInstances(): List<Pair<String, T>> = warmInstances.entries.map { it.key to it.value }

    fun forEachInstance(action: (T) -> Unit) {
        for (instance in warmInstances.values) {
            action(instance)
        }
    }

    fun removeAndDestroy(pluginId: String): Boolean {
        val removed = warmInstances.remove(pluginId) ?: return false
        onDestroy(removed)
        return true
    }

    fun evictStale(shouldKeep: (String) -> Boolean): List<String> {
        val staleIds = warmInstances.keys.filterNot(shouldKeep)
        for (id in staleIds) {
            warmInstances.remove(id)?.let(onDestroy)
        }
        return staleIds
    }

    /**
     * Selects the next LRU eviction candidate while exempting [activePluginId], [keepId],
     * [pinnedPluginId], and any plugin IDs present in [protectedIds].
     *
     * Inactive COOP/COEP (`crossOriginIsolated`) instances are prioritized for eviction to reclaim
     * WebGPU/WASM renderer memory aggressively.
     */
    fun selectEvictionCandidate(
        activePluginId: String,
        keepId: String,
        pinnedPluginId: String? = null,
        protectedIds: Set<String> = emptySet(),
    ): String? =
        selectCandidateMatching { id ->
            id != activePluginId &&
                id != keepId &&
                id != pinnedPluginId &&
                !protectedIds.contains(id)
        }

    /**
     * Evicts least-recently-used non-exempt instances using dedicated budgets for regular
     * ([maxWarmInstances]) and `keepAlive`-protected ([maxKeepAliveInstances]) plugins.
     */
    fun evictLruIfNeeded(
        activePluginId: String,
        keepId: String,
        pinnedPluginId: String? = null,
        protectedIds: Set<String> = emptySet(),
    ): List<String> {
        val evicted = mutableListOf<String>()
        val incomingProtected = protectedIds.contains(keepId)
        val regularLimit = if (incomingProtected) maxWarmInstances else (maxWarmInstances - 1).coerceAtLeast(0)
        val keepAliveLimit = if (incomingProtected) (maxKeepAliveInstances - 1).coerceAtLeast(0) else maxKeepAliveInstances

        while (warmInstances.keys.count { !protectedIds.contains(it) } > regularLimit) {
            val candidate =
                selectEvictionCandidate(
                    activePluginId = activePluginId,
                    keepId = keepId,
                    pinnedPluginId = pinnedPluginId,
                    protectedIds = protectedIds,
                ) ?: break
            onEvicted(candidate)
            warmInstances.remove(candidate)?.let(onDestroy)
            evicted += candidate
        }
        while (warmInstances.keys.count { protectedIds.contains(it) } > keepAliveLimit) {
            val candidate =
                selectCandidateMatching { id ->
                    id != activePluginId &&
                        id != keepId &&
                        id != pinnedPluginId &&
                        protectedIds.contains(id)
                } ?: break
            onEvicted(candidate)
            warmInstances.remove(candidate)?.let(onDestroy)
            evicted += candidate
        }
        return evicted
    }

    /**
     * Trims idle warm instances under system memory pressure while preserving [activePluginId],
     * [pinnedPluginId], and optionally `keepAlive` [protectedIds] when [includeKeepAlive] is false.
     */
    fun trimNonActive(
        activePluginId: String,
        pinnedPluginId: String? = null,
        protectedIds: Set<String> = emptySet(),
        includeKeepAlive: Boolean = false,
    ): List<String> {
        val victims =
            warmInstances.keys.filter { id ->
                id != activePluginId &&
                    id != pinnedPluginId &&
                    (includeKeepAlive || !protectedIds.contains(id))
            }
        for (id in victims) {
            onEvicted(id)
            warmInstances.remove(id)?.let(onDestroy)
        }
        return victims
    }

    private fun selectCandidateMatching(predicate: (String) -> Boolean): String? =
        warmInstances.entries
            .firstOrNull { (id, inst) -> predicate(id) && isCrossOriginIsolated(inst) }
            ?.key
            ?: warmInstances.keys.firstOrNull(predicate)

    fun updateRendererPriorities(
        panelState: PanelState,
        activePluginId: String,
        pinnedPluginId: String?,
    ) {
        warmInstances.forEach { (id, inst) ->
            val foreground = (panelState != PanelState.CLOSED && id == activePluginId) || id == pinnedPluginId
            setForegroundPriority(inst, foreground)
        }
    }

    fun destroyAll() {
        for (instance in warmInstances.values) {
            onDestroy(instance)
        }
        warmInstances.clear()
    }

    companion object {
        const val DEFAULT_MAX_WARM_INSTANCES = 3
        const val DEFAULT_MAX_KEEP_ALIVE_INSTANCES = 4
    }
}
