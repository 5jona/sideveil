package pochita.sideveil.host.plugin

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import pochita.sideveil.protocol.ConfigUpdatedPayload
import pochita.sideveil.protocol.HostEventEnvelope
import pochita.sideveil.protocol.PluginCapability
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginOrigin
import pochita.sideveil.protocol.SideVeilEvents

/**
 * Pure-Kotlin outbound event ACL and payload sanitization policy enforced before delivering
 * daemon events into isolated plugin WebViews.
 */
object PluginEventSecurityPolicy {
    /**
     * Returns `true` strictly when [descriptor] is a trusted plugin explicitly granted
     * [PluginCapability.INTER_PLUGIN_BUS].
     */
    fun shouldDeliverBusMessage(descriptor: PluginDescriptor): Boolean =
        !PluginNetworkPolicy.isUntrustedEmbed(descriptor) &&
            descriptor.hasGrantedCapability(PluginCapability.INTER_PLUGIN_BUS)

    /**
     * Returns `true` strictly when [descriptor] is a builtin host-owned plugin permitted to
     * receive the unsanitized `config.updated` payload.
     */
    fun canReceiveFullConfig(descriptor: PluginDescriptor): Boolean =
        !PluginNetworkPolicy.isUntrustedEmbed(descriptor) &&
            descriptor.origin == PluginOrigin.BUILTIN

    /**
     * Builds a sanitized `config.updated` envelope for non-builtin plugins exposing only panel
     * appearance presentation settings (`panel.themeId`, `panel.colorMode`,
     * `panel.nativeBlurEnabled`) and omitting `scope.allowlist`, `scope.denylist`,
     * `debug.webviewDebuggingEnabled`, edge geometry, and foreground package metadata.
     */
    fun sanitizeConfigUpdatedEnvelope(decoded: ConfigUpdatedPayload): HostEventEnvelope {
        val panel = decoded.config.panel
        val payload =
            buildJsonObject {
                put(
                    "config",
                    buildJsonObject {
                        put(
                            "panel",
                            buildJsonObject {
                                put("themeId", JsonPrimitive(panel.themeId))
                                put("colorMode", JsonPrimitive(panel.colorMode))
                                put("nativeBlurEnabled", JsonPrimitive(panel.nativeBlurEnabled))
                            },
                        )
                    },
                )
            }
        return HostEventEnvelope(event = SideVeilEvents.CONFIG_UPDATED, payload = payload)
    }

    /** Selects [fullEnvelope] for builtin plugins and [sanitizedEnvelope] for all other plugins. */
    fun selectConfigEnvelope(
        descriptor: PluginDescriptor,
        fullEnvelope: HostEventEnvelope,
        sanitizedEnvelope: HostEventEnvelope,
    ): HostEventEnvelope = if (canReceiveFullConfig(descriptor)) fullEnvelope else sanitizedEnvelope
}
