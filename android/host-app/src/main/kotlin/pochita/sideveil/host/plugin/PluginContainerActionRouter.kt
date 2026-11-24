package pochita.sideveil.host.plugin

import pochita.sideveil.host.pinned.PinnedBoundsSpec
import pochita.sideveil.protocol.ContainerActionPayload
import pochita.sideveil.protocol.PluginApprovalStatus
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginOrigin
import pochita.sideveil.protocol.PluginPresentationMode
import pochita.sideveil.protocol.PluginSurfaceBackdrop
import pochita.sideveil.protocol.SideVeilThemes

/** Structured outcome of a host-local `container.action` invocation. */
sealed interface ContainerActionResult {
    data object Ok : ContainerActionResult

    data class Denied(
        val message: String,
    ) : ContainerActionResult

    data class Invalid(
        val message: String,
    ) : ContainerActionResult
}

/**
 * Decodes, validates, and routes `container.action` payloads issued by a plugin WebView to local
 * instance state setters and host-level [PluginContainerCallbacks], returning structured
 * [ContainerActionResult] outcomes for JSON-RPC error propagation.
 */
object PluginContainerActionRouter {
    private val ALLOWED_THEMES = setOf(SideVeilThemes.COLOROS_GLASS, SideVeilThemes.MODERN_SAAS_DARK)
    private val ALLOWED_COLOR_SCHEMES = setOf("light", "dark")

    fun dispatch(
        descriptor: PluginDescriptor,
        action: ContainerActionPayload?,
        callbacks: PluginContainerCallbacks,
        isActive: Boolean,
        isPinned: Boolean,
        onSizeOverride: (PluginSizeOverride) -> Unit,
        onBackIntercept: (Boolean) -> Unit,
        onBackdrop: (PluginSurfaceBackdrop) -> Unit,
        onPresentationMode: (PluginPresentationMode) -> Unit,
    ): ContainerActionResult =
        when {
            PluginNetworkPolicy.isUntrustedEmbed(descriptor) -> {
                ContainerActionResult.Denied("Untrusted web-embed cannot invoke container.action")
            }

            action == null -> {
                ContainerActionResult.Invalid("Invalid container.action params")
            }

            else -> {
                routeAction(
                    descriptor = descriptor,
                    action = action,
                    callbacks = callbacks,
                    isActive = isActive,
                    activeOrPinned = isActive || isPinned,
                    onSizeOverride = onSizeOverride,
                    onBackIntercept = onBackIntercept,
                    onBackdrop = onBackdrop,
                    onPresentationMode = onPresentationMode,
                )
            }
        }

    private fun routeAction(
        descriptor: PluginDescriptor,
        action: ContainerActionPayload,
        callbacks: PluginContainerCallbacks,
        isActive: Boolean,
        activeOrPinned: Boolean,
        onSizeOverride: (PluginSizeOverride) -> Unit,
        onBackIntercept: (Boolean) -> Unit,
        onBackdrop: (PluginSurfaceBackdrop) -> Unit,
        onPresentationMode: (PluginPresentationMode) -> Unit,
    ): ContainerActionResult =
        when (action.type) {
            "dismiss" -> {
                runIfAllowed(activeOrPinned, descriptor.id, action.type) {
                    callbacks.onDismissRequested(descriptor.id)
                }
            }

            "requestImeFocus" -> {
                runIfAllowed(activeOrPinned, descriptor.id, action.type) {
                    callbacks.onImeFocusRequested(descriptor.id, action.focused == true)
                }
            }

            "syncTheme" -> {
                handleSyncTheme(descriptor, action, isActive, callbacks)
            }

            "setPreferredSize" -> {
                runIfAllowed(isActive, descriptor.id, action.type) {
                    val next =
                        PluginSizeOverride(
                            widthDp = action.widthDp,
                            heightDp = action.heightDp,
                            heightPercent = action.heightPercent,
                        )
                    onSizeOverride(next)
                    callbacks.onPreferredSizeChanged(descriptor.id, next)
                }
            }

            "setBackIntercept" -> {
                runIfAllowed(isActive, descriptor.id, action.type) {
                    onBackIntercept(action.enabled == true)
                }
            }

            "setSurfaceBackdrop" -> {
                handleSurfaceBackdrop(descriptor.id, action, activeOrPinned, onBackdrop, callbacks)
            }

            "setPresentationMode" -> {
                handlePresentationMode(descriptor, action, activeOrPinned, onPresentationMode, callbacks)
            }

            "setPinnedBounds" -> {
                handleSetPinnedBounds(descriptor, action, activeOrPinned, callbacks)
            }

            "setEdgeStatus" -> {
                handleEdgeStatus(descriptor, action, activeOrPinned, callbacks)
            }

            else -> {
                ContainerActionResult.Invalid("Unknown container.action type '${action.type}'")
            }
        }

    private inline fun runIfAllowed(
        allowed: Boolean,
        pluginId: String,
        actionType: String,
        block: () -> Unit,
    ): ContainerActionResult {
        if (!allowed) return inactiveDenied(pluginId, actionType)
        block()
        return ContainerActionResult.Ok
    }

    private fun handleSyncTheme(
        descriptor: PluginDescriptor,
        action: ContainerActionPayload,
        isActive: Boolean,
        callbacks: PluginContainerCallbacks,
    ): ContainerActionResult {
        val payload = action.payload
        return when {
            descriptor.origin != PluginOrigin.BUILTIN -> {
                ContainerActionResult.Denied("Only builtin plugins may invoke syncTheme")
            }

            !isActive -> {
                inactiveDenied(descriptor.id, action.type)
            }

            payload == null -> {
                ContainerActionResult.Invalid("syncTheme requires payload")
            }

            payload.themeId !in ALLOWED_THEMES -> {
                ContainerActionResult.Invalid("Unsupported themeId '${payload.themeId}'")
            }

            payload.colorScheme !in ALLOWED_COLOR_SCHEMES -> {
                ContainerActionResult.Invalid("Unsupported colorScheme '${payload.colorScheme}'")
            }

            else -> {
                callbacks.onAppearanceSynced(descriptor.id, payload.themeId, payload.colorScheme)
                ContainerActionResult.Ok
            }
        }
    }

    private fun handleSurfaceBackdrop(
        pluginId: String,
        action: ContainerActionPayload,
        activeOrPinned: Boolean,
        onBackdrop: (PluginSurfaceBackdrop) -> Unit,
        callbacks: PluginContainerCallbacks,
    ): ContainerActionResult {
        if (!activeOrPinned) return inactiveDenied(pluginId, action.type)
        val parsed =
            parseBackdrop(action.backdrop)
                ?: return ContainerActionResult.Invalid("Unsupported surface backdrop '${action.backdrop}'")
        onBackdrop(parsed)
        callbacks.onBackdropChanged(pluginId, parsed)
        return ContainerActionResult.Ok
    }

    private fun handlePresentationMode(
        descriptor: PluginDescriptor,
        action: ContainerActionPayload,
        activeOrPinned: Boolean,
        onPresentationMode: (PluginPresentationMode) -> Unit,
        callbacks: PluginContainerCallbacks,
    ): ContainerActionResult {
        if (!activeOrPinned) return inactiveDenied(descriptor.id, action.type)
        val parsed =
            parsePresentationMode(action.mode)
                ?: return ContainerActionResult.Invalid("Unsupported presentation mode '${action.mode}'")
        if (parsed == PluginPresentationMode.PINNED &&
            descriptor.approvalStatus != PluginApprovalStatus.APPROVED
        ) {
            return ContainerActionResult.Denied("Unapproved plugin '${descriptor.id}' cannot enter pinned mode")
        }
        onPresentationMode(parsed)
        callbacks.onPresentationModeChanged(descriptor.id, parsed)
        return ContainerActionResult.Ok
    }

    private fun handleSetPinnedBounds(
        descriptor: PluginDescriptor,
        action: ContainerActionPayload,
        activeOrPinned: Boolean,
        callbacks: PluginContainerCallbacks,
    ): ContainerActionResult =
        when {
            !activeOrPinned -> {
                inactiveDenied(descriptor.id, action.type)
            }

            action.clickThrough == true && descriptor.approvalStatus != PluginApprovalStatus.APPROVED -> {
                ContainerActionResult.Denied("Unapproved plugin '${descriptor.id}' cannot enable click-through")
            }

            action.xPercent?.isFinite() == false || action.yPercent?.isFinite() == false -> {
                ContainerActionResult.Invalid("Pinned coordinates must be finite numbers")
            }

            else -> {
                callbacks.onPinnedBoundsChanged(
                    descriptor.id,
                    PinnedBoundsSpec(
                        widthDp = action.widthDp,
                        heightDp = action.heightDp,
                        xPercent = action.xPercent,
                        yPercent = action.yPercent,
                        clickThrough = action.clickThrough,
                    ),
                )
                ContainerActionResult.Ok
            }
        }

    private fun handleEdgeStatus(
        descriptor: PluginDescriptor,
        action: ContainerActionPayload,
        activeOrPinned: Boolean,
        callbacks: PluginContainerCallbacks,
    ): ContainerActionResult =
        when {
            !activeOrPinned -> {
                inactiveDenied(descriptor.id, action.type)
            }

            descriptor.approvalStatus != PluginApprovalStatus.APPROVED -> {
                ContainerActionResult.Denied("Unapproved plugin '${descriptor.id}' cannot update edge status")
            }

            else -> {
                callbacks.onEdgeStatusChanged(descriptor.id, action.status)
                ContainerActionResult.Ok
            }
        }

    private fun inactiveDenied(
        pluginId: String,
        actionType: String,
    ): ContainerActionResult.Denied = ContainerActionResult.Denied("Plugin '$pluginId' is not active for container.action '$actionType'")

    private fun parseBackdrop(raw: String?): PluginSurfaceBackdrop? =
        when (raw) {
            "native-glass" -> PluginSurfaceBackdrop.NATIVE_GLASS
            "transparent" -> PluginSurfaceBackdrop.TRANSPARENT
            "opaque" -> PluginSurfaceBackdrop.OPAQUE
            else -> null
        }

    private fun parsePresentationMode(raw: String?): PluginPresentationMode? =
        when (raw) {
            "drawer" -> PluginPresentationMode.DRAWER
            "peek" -> PluginPresentationMode.PEEK
            "pinned" -> PluginPresentationMode.PINNED
            "wide" -> PluginPresentationMode.WIDE
            "native-hud" -> PluginPresentationMode.NATIVE_HUD
            else -> null
        }
}
