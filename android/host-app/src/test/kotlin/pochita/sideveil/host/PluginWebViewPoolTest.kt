package pochita.sideveil.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.protocol.PanelState

class PluginWebViewPoolTest {
    private data class FakeInstance(
        val id: String,
        val crossOriginIsolated: Boolean = false,
        var foreground: Boolean = false,
        var destroyed: Boolean = false,
    )

    private fun createPool(
        maxWarm: Int = 3,
        maxKeepAlive: Int = PluginWebViewPool.DEFAULT_MAX_KEEP_ALIVE_INSTANCES,
    ): PluginWebViewPool<FakeInstance> =
        PluginWebViewPool(
            maxWarmInstances = maxWarm,
            maxKeepAliveInstances = maxKeepAlive,
            isCrossOriginIsolated = { it.crossOriginIsolated },
            setForegroundPriority = { inst, fg -> inst.foreground = fg },
            onDestroy = { it.destroyed = true },
        )

    @Test
    fun `never evicts pinnedPluginId even when oldest and crossOriginIsolated`() {
        val pool = createPool(maxWarm = 3)
        val pinned = FakeInstance("plugin.pinned", crossOriginIsolated = true)
        val regular = FakeInstance("plugin.regular", crossOriginIsolated = false)
        val active = FakeInstance("plugin.active", crossOriginIsolated = false)

        pool.put(pinned.id, pinned)
        pool.put(regular.id, regular)
        pool.put(active.id, active)

        val evicted =
            pool.evictLruIfNeeded(
                activePluginId = active.id,
                keepId = "plugin.incoming",
                pinnedPluginId = pinned.id,
            )

        assertEquals(listOf(regular.id), evicted)
        assertFalse("pinned plugin must survive eviction", pinned.destroyed)
        assertTrue("regular non-exempt plugin should be evicted", regular.destroyed)
        assertEquals(listOf(pinned.id, active.id), pool.warmPluginIds())
    }

    @Test
    fun `never evicts protectedIds under regular lru pressure`() {
        val pool = createPool(maxWarm = 2, maxKeepAlive = 4)
        val keepAliveCoop = FakeInstance("plugin.keepalive", crossOriginIsolated = true)
        val disposable = FakeInstance("plugin.disposable", crossOriginIsolated = false)
        val active = FakeInstance("plugin.active", crossOriginIsolated = false)

        pool.put(keepAliveCoop.id, keepAliveCoop)
        pool.put(disposable.id, disposable)
        pool.put(active.id, active)

        val evicted =
            pool.evictLruIfNeeded(
                activePluginId = active.id,
                keepId = "plugin.next",
                pinnedPluginId = null,
                protectedIds = setOf(keepAliveCoop.id),
            )

        assertEquals(listOf(disposable.id), evicted)
        assertFalse(keepAliveCoop.destroyed)
        assertTrue(disposable.destroyed)
    }

    @Test
    fun `enforces dedicated keepAlive cap alongside regular warm budget`() {
        val pool = createPool(maxWarm = 3, maxKeepAlive = 2)
        val kaOld = FakeInstance("plugin.ka1", crossOriginIsolated = false)
        val kaNew = FakeInstance("plugin.ka2", crossOriginIsolated = false)
        val regular = FakeInstance("plugin.regular", crossOriginIsolated = false)
        val active = FakeInstance("plugin.active", crossOriginIsolated = false)
        val protectedIds = setOf(kaOld.id, kaNew.id, "plugin.ka3")

        pool.put(kaOld.id, kaOld)
        pool.put(kaNew.id, kaNew)
        pool.put(regular.id, regular)
        pool.put(active.id, active)

        val evicted =
            pool.evictLruIfNeeded(
                activePluginId = active.id,
                keepId = "plugin.ka3",
                pinnedPluginId = null,
                protectedIds = protectedIds,
            )

        assertEquals(listOf(kaOld.id), evicted)
        assertTrue(kaOld.destroyed)
        assertFalse(kaNew.destroyed)
        assertFalse(regular.destroyed)
    }

    @Test
    fun `trimNonActive preserves keepAlive under moderate pressure and evicts under severe pressure`() {
        val pool = createPool(maxWarm = 3, maxKeepAlive = 4)
        val active = FakeInstance("plugin.active")
        val pinned = FakeInstance("plugin.pinned")
        val keepAlive = FakeInstance("plugin.keepalive")
        val regular = FakeInstance("plugin.regular")

        pool.put(active.id, active)
        pool.put(pinned.id, pinned)
        pool.put(keepAlive.id, keepAlive)
        pool.put(regular.id, regular)

        val moderate =
            pool.trimNonActive(
                activePluginId = active.id,
                pinnedPluginId = pinned.id,
                protectedIds = setOf(keepAlive.id),
                includeKeepAlive = false,
            )
        assertEquals(listOf(regular.id), moderate)
        assertTrue(regular.destroyed)
        assertFalse(keepAlive.destroyed)

        val severe =
            pool.trimNonActive(
                activePluginId = active.id,
                pinnedPluginId = pinned.id,
                protectedIds = setOf(keepAlive.id),
                includeKeepAlive = true,
            )
        assertEquals(listOf(keepAlive.id), severe)
        assertTrue(keepAlive.destroyed)
        assertFalse(active.destroyed)
        assertFalse(pinned.destroyed)
    }

    @Test
    fun `stops gracefully without eviction when all warm plugins are exempt`() {
        val pool = createPool(maxWarm = 3)
        val pinned = FakeInstance("plugin.pinned", crossOriginIsolated = true)
        val protectedInst = FakeInstance("plugin.protected", crossOriginIsolated = true)
        val active = FakeInstance("plugin.active", crossOriginIsolated = false)

        pool.put(pinned.id, pinned)
        pool.put(protectedInst.id, protectedInst)
        pool.put(active.id, active)

        assertNull(
            pool.selectEvictionCandidate(
                activePluginId = active.id,
                keepId = "plugin.incoming",
                pinnedPluginId = pinned.id,
                protectedIds = setOf(protectedInst.id),
            ),
        )
        val evicted =
            pool.evictLruIfNeeded(
                activePluginId = active.id,
                keepId = "plugin.incoming",
                pinnedPluginId = pinned.id,
                protectedIds = setOf(protectedInst.id),
            )
        assertTrue(evicted.isEmpty())
        assertEquals(3, pool.size)
        assertFalse(pinned.destroyed)
        assertFalse(protectedInst.destroyed)
        assertFalse(active.destroyed)
    }

    @Test
    fun `prioritizes inactive crossOriginIsolated eviction and preserves access-order lru`() {
        val pool = createPool(maxWarm = 3)
        val first = FakeInstance("plugin.a", crossOriginIsolated = false)
        val secondCoop = FakeInstance("plugin.b", crossOriginIsolated = true)
        val active = FakeInstance("plugin.c", crossOriginIsolated = false)

        pool.put(first.id, first)
        pool.put(secondCoop.id, secondCoop)
        pool.put(active.id, active)

        val evicted = pool.evictLruIfNeeded(activePluginId = active.id, keepId = "plugin.d")
        assertEquals(listOf(secondCoop.id), evicted)
        assertTrue(secondCoop.destroyed)
        assertFalse(first.destroyed)

        // Touch plugin.a so it becomes MRU, add plugin.d, then evict -> plugin.d is newer than plugin.c? No, active is exempt.
        pool.get(first.id)
        val fourth = FakeInstance("plugin.d", crossOriginIsolated = false)
        pool.put(fourth.id, fourth)
        // LRU order is now: plugin.c (active), plugin.a (MRU-1), plugin.d (MRU) -> plugin.a should evict before plugin.d.
        val nextEvicted = pool.evictLruIfNeeded(activePluginId = active.id, keepId = "plugin.e")
        assertEquals(listOf(first.id), nextEvicted)
        assertTrue(first.destroyed)
    }

    @Test
    fun `updates renderer priorities for active visible and pinned instances`() {
        val pool = createPool(maxWarm = 3)
        val active = FakeInstance("plugin.active")
        val pinned = FakeInstance("plugin.pinned")
        val idle = FakeInstance("plugin.idle")

        pool.put(active.id, active)
        pool.put(pinned.id, pinned)
        pool.put(idle.id, idle)

        pool.updateRendererPriorities(PanelState.OPEN, activePluginId = active.id, pinnedPluginId = pinned.id)
        assertTrue(active.foreground)
        assertTrue(pinned.foreground)
        assertFalse(idle.foreground)

        pool.updateRendererPriorities(PanelState.CLOSED, activePluginId = active.id, pinnedPluginId = pinned.id)
        assertFalse(active.foreground)
        assertTrue("pinned plugin must stay foreground while panel is closed", pinned.foreground)
        assertFalse(idle.foreground)
    }
}
