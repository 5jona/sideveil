package pochita.sideveil.host.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.host.pinned.PinnedBoundsSpec
import pochita.sideveil.protocol.ContainerActionPayload
import pochita.sideveil.protocol.ContainerThemePayload
import pochita.sideveil.protocol.EdgeStatusPayload
import pochita.sideveil.protocol.PluginApprovalStatus
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginOrigin
import pochita.sideveil.protocol.PluginPresentationMode
import pochita.sideveil.protocol.PluginSurfaceBackdrop
import pochita.sideveil.protocol.SideVeilThemes

class PluginGovernanceAndLifecycleTest {
    private class RecordingCallbacks : PluginContainerCallbacks {
        var syncedTheme: Pair<String, String>? = null
        var dismissedPlugin: String? = null

        override fun pluginOwnership(pluginId: String): PluginOwnershipState = PluginOwnershipState(active = true, pinned = false)

        override fun onDismissRequested(pluginId: String) {
            dismissedPlugin = pluginId
        }

        override fun onImeFocusRequested(
            pluginId: String,
            focused: Boolean,
        ) = Unit

        override fun onAppearanceSynced(
            pluginId: String,
            themeId: String,
            colorScheme: String,
        ) {
            syncedTheme = themeId to colorScheme
        }

        override fun onPreferredSizeChanged(
            pluginId: String,
            size: PluginSizeOverride,
        ) = Unit

        override fun onBackdropChanged(
            pluginId: String,
            backdrop: PluginSurfaceBackdrop,
        ) = Unit

        override fun onPresentationModeChanged(
            pluginId: String,
            mode: PluginPresentationMode,
        ) = Unit

        override fun onPinnedBoundsChanged(
            pluginId: String,
            bounds: PinnedBoundsSpec,
        ) = Unit

        override fun onEdgeStatusChanged(
            pluginId: String,
            status: EdgeStatusPayload?,
        ) = Unit

        override fun onMainFrameStatusChanged(
            pluginId: String,
            errorTitle: String?,
            errorDetail: String?,
        ) = Unit

        override fun onRenderProcessCrashed(pluginId: String) = Unit
    }

    @Test
    fun `PluginContainerActionRouter restricts syncTheme to builtin plugins with allowlisted themes`() {
        val callbacks = RecordingCallbacks()
        val thirdParty =
            PluginDescriptor(
                id = "com.thirdparty.app",
                name = "Third Party",
                version = "1.0.0",
                origin = PluginOrigin.INSTALLED,
            )
        val syncAction =
            ContainerActionPayload(
                type = "syncTheme",
                payload = ContainerThemePayload(themeId = SideVeilThemes.MODERN_SAAS_DARK, colorScheme = "dark"),
            )
        val denied =
            PluginContainerActionRouter.dispatch(
                descriptor = thirdParty,
                action = syncAction,
                callbacks = callbacks,
                isActive = true,
                isPinned = false,
                onSizeOverride = {},
                onBackIntercept = {},
                onBackdrop = {},
                onPresentationMode = {},
            )
        assertTrue(denied is ContainerActionResult.Denied)
        assertNull(callbacks.syncedTheme)

        val builtin = PluginDescriptor.builtinDiagnostic()
        val badTheme =
            PluginContainerActionRouter.dispatch(
                descriptor = builtin,
                action =
                    ContainerActionPayload(
                        type = "syncTheme",
                        payload = ContainerThemePayload(themeId = "injected-theme", colorScheme = "dark"),
                    ),
                callbacks = callbacks,
                isActive = true,
                isPinned = false,
                onSizeOverride = {},
                onBackIntercept = {},
                onBackdrop = {},
                onPresentationMode = {},
            )
        assertTrue(badTheme is ContainerActionResult.Invalid)
        assertNull(callbacks.syncedTheme)

        val allowed =
            PluginContainerActionRouter.dispatch(
                descriptor = builtin,
                action = syncAction,
                callbacks = callbacks,
                isActive = true,
                isPinned = false,
                onSizeOverride = {},
                onBackIntercept = {},
                onBackdrop = {},
                onPresentationMode = {},
            )
        assertEquals(ContainerActionResult.Ok, allowed)
        assertEquals(SideVeilThemes.MODERN_SAAS_DARK to "dark", callbacks.syncedTheme)
    }

    @Test
    fun `PluginContainerActionRouter rejects unknown actions, inactive plugins, and unapproved click-through`() {
        val callbacks = RecordingCallbacks()
        val unapproved =
            PluginDescriptor(
                id = "com.thirdparty.pending",
                name = "Pending",
                version = "1.0.0",
                approvalStatus = PluginApprovalStatus.PENDING_REVIEW,
            )

        val unknown =
            PluginContainerActionRouter.dispatch(
                descriptor = unapproved,
                action = ContainerActionPayload(type = "nonExistentAction"),
                callbacks = callbacks,
                isActive = true,
                isPinned = false,
                onSizeOverride = {},
                onBackIntercept = {},
                onBackdrop = {},
                onPresentationMode = {},
            )
        assertTrue(unknown is ContainerActionResult.Invalid)

        val clickThroughDenied =
            PluginContainerActionRouter.dispatch(
                descriptor = unapproved,
                action = ContainerActionPayload(type = "setPinnedBounds", clickThrough = true),
                callbacks = callbacks,
                isActive = true,
                isPinned = true,
                onSizeOverride = {},
                onBackIntercept = {},
                onBackdrop = {},
                onPresentationMode = {},
            )
        assertTrue(clickThroughDenied is ContainerActionResult.Denied)

        val inactiveDenied =
            PluginContainerActionRouter.dispatch(
                descriptor = PluginDescriptor.builtinDiagnostic(),
                action = ContainerActionPayload(type = "dismiss"),
                callbacks = callbacks,
                isActive = false,
                isPinned = false,
                onSizeOverride = {},
                onBackIntercept = {},
                onBackdrop = {},
                onPresentationMode = {},
            )
        assertTrue(inactiveDenied is ContainerActionResult.Denied)
    }

    @Test
    fun `PluginLifecycleEvictionPolicy tears down orphaned pinned windows and HUDs and returns null when empty`() {
        val alphaDisabled =
            PluginDescriptor(id = "com.example.alpha", name = "Alpha", version = "1.0.0", enabled = false)
        val plan =
            PluginLifecycleEvictionPolicy.planSnapshotTransition(
                previousPluginIds = setOf("com.example.alpha", "com.example.hud"),
                nextDescriptors = mapOf("com.example.alpha" to alphaDisabled),
                matchedPluginIds = emptyList(),
                requestedActivePluginId = null,
                currentPinnedPluginId = "com.example.alpha",
                currentHudPluginId = "com.example.hud",
            )
        assertEquals(setOf("com.example.hud"), plan.uninstalledPluginIds)
        assertEquals("com.example.alpha", plan.shouldHidePinnedPluginId)
        assertEquals("com.example.hud", plan.shouldDismissHudPluginId)
        assertNull(plan.nextActivePluginId)

        assertTrue(PluginLifecycleEvictionPolicy.shouldHidePinnedOnCrash("com.example.alpha", "com.example.alpha"))
        assertFalse(PluginLifecycleEvictionPolicy.shouldHidePinnedOnCrash("com.example.other", "com.example.alpha"))
    }
}
