package pochita.sideveil.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

typealias HostPluginDiagnosticsPayload = pochita.sideveil.protocol.generated.HostPluginDiagnosticsPayload
typealias PluginOrigin = pochita.sideveil.protocol.generated.PluginOrigin
typealias PluginBackendKind = pochita.sideveil.protocol.generated.PluginBackendKind
typealias PluginBackendState = pochita.sideveil.protocol.generated.PluginBackendState
typealias PluginCapability = pochita.sideveil.protocol.generated.PluginCapability
typealias CapabilityRiskTier = pochita.sideveil.protocol.generated.CapabilityRiskTier
typealias PluginSurfaceBackdrop = pochita.sideveil.protocol.generated.PluginSurfaceBackdrop
typealias PluginPresentationMode = pochita.sideveil.protocol.generated.PluginPresentationMode
typealias PluginDaemonRestartPolicy = pochita.sideveil.protocol.generated.PluginDaemonRestartPolicy
typealias PluginDaemonStatus = pochita.sideveil.protocol.generated.PluginDaemonStatus
typealias PluginApprovalStatus = pochita.sideveil.protocol.generated.PluginApprovalStatus
typealias PluginDaemonSpec = pochita.sideveil.protocol.generated.PluginDaemonSpec
typealias PluginEmbedPrefs = pochita.sideveil.protocol.generated.PluginEmbedPrefs
typealias PluginNetworkPrefs = pochita.sideveil.protocol.generated.PluginNetworkPrefs
typealias PluginScopeOverride = pochita.sideveil.protocol.generated.PluginScopeOverride
typealias PluginManifest = pochita.sideveil.protocol.generated.PluginManifest
typealias PluginRuntimeDescriptor = pochita.sideveil.protocol.generated.PluginRuntimeDescriptor

const val BUILTIN_DIAG_PLUGIN_ID = SideVeilBuiltinDiagnostic.ID

/**
 * Forward-compatible surface preference projection backed by generated [PluginSurfaceBackdrop]
 * and [PluginPresentationMode] enums with safe defaults when optional fields or future enum
 * variants appear on the wire.
 */
@Serializable
data class PluginSurfacePrefs(
    @Serializable(with = TolerantPluginSurfaceBackdropSerializer::class)
    val backdrop: PluginSurfaceBackdrop = PluginSurfaceBackdrop.NATIVE_GLASS,
    @Serializable(with = TolerantPluginPresentationModeSerializer::class)
    val presentation: PluginPresentationMode = PluginPresentationMode.DRAWER,
    val crossOriginIsolated: Boolean = false,
    val keepAlive: Boolean = false,
    val widthDp: Int? = null,
    val heightPercent: Int? = null,
)

/**
 * Forward-compatible runtime plugin descriptor decoded from Hub `plugins.updated` snapshots.
 *
 * Uses generated enums and sub-DTOs from [PluginRuntimeDescriptor] with tolerant deserializers for
 * capability lists and enum variants so future Hub additions never drop a live plugin catalog.
 */
@Serializable
data class PluginDescriptor(
    val id: String,
    val name: String,
    val version: String,
    @Serializable(with = TolerantPluginOriginSerializer::class)
    val origin: PluginOrigin = PluginOrigin.INSTALLED,
    val schemaVersion: Long = SideVeilProtocolVersions.MANIFEST_SCHEMA_VERSION,
    val apiVersion: Long = SideVeilProtocolVersions.PLUGIN_API_VERSION,
    val minHostVersion: String? = null,
    val description: String? = null,
    val author: String? = null,
    val homepage: String? = null,
    val icon: String? = null,
    val entry: String = "index.html",
    val assetRevision: String? = null,
    val builtin: Boolean = false,
    val defaultEnabled: Boolean = true,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val packages: List<String> = emptyList(),
    val activities: List<String> = emptyList(),
    val global: Boolean = false,
    val hasScopeOverride: Boolean = false,
    val devServerUrl: String? = null,
    @Serializable(with = TolerantPluginBackendKindSerializer::class)
    val backend: PluginBackendKind = PluginBackendKind.NONE,
    @Serializable(with = TolerantPluginBackendStateSerializer::class)
    val backendState: PluginBackendState = PluginBackendState.NONE,
    val backendPackages: List<String> = emptyList(),
    @Serializable(with = TolerantPluginCapabilityListSerializer::class)
    val capabilities: List<PluginCapability> = emptyList(),
    @Serializable(with = TolerantPluginCapabilityListSerializer::class)
    val grantedCapabilities: List<PluginCapability> = emptyList(),
    @Serializable(with = TolerantPluginCapabilityListSerializer::class)
    val pendingCapabilities: List<PluginCapability> = emptyList(),
    @Serializable(with = TolerantPluginApprovalStatusSerializer::class)
    val approvalStatus: PluginApprovalStatus = PluginApprovalStatus.APPROVED,
    val daemonApproved: Boolean = false,
    @Serializable(with = TolerantPluginDaemonStatusSerializer::class)
    val daemonStatus: PluginDaemonStatus = PluginDaemonStatus.NOT_CONFIGURED,
    val daemonPid: Long? = null,
    val daemonRestartCount: Long = 0L,
    val daemonLastError: String? = null,
    val hasDaemon: Boolean = false,
    val surface: PluginSurfacePrefs = PluginSurfacePrefs(),
    val embed: PluginEmbedPrefs? = null,
    val network: PluginNetworkPrefs? = null,
) {
    /** Returns `true` when [cap] is explicitly present in [grantedCapabilities]. */
    fun hasGrantedCapability(cap: PluginCapability): Boolean = grantedCapabilities.contains(cap)

    /** Wire-encoded list of [grantedCapabilities] for `host.ready` handshakes. */
    fun grantedCapabilityWireTokens(): List<String> = grantedCapabilities.map(PluginCapabilityCodec::toWire)

    companion object {
        fun builtinDiagnostic(): PluginDescriptor {
            val caps = listOf(PluginCapability.STORAGE, PluginCapability.TOAST)
            return PluginDescriptor(
                id = SideVeilBuiltinDiagnostic.ID,
                name = SideVeilBuiltinDiagnostic.NAME,
                version = SideVeilBuiltinDiagnostic.VERSION,
                origin = PluginOrigin.BUILTIN,
                entry = SideVeilBuiltinDiagnostic.ENTRY,
                builtin = true,
                enabled = true,
                priority = SideVeilBuiltinDiagnostic.PRIORITY,
                global = SideVeilBuiltinDiagnostic.GLOBAL,
                capabilities = caps,
                grantedCapabilities = caps,
                approvalStatus = PluginApprovalStatus.APPROVED,
            )
        }
    }
}

@Serializable
data class PluginsSnapshotPayload(
    val plugins: List<PluginDescriptor> = emptyList(),
    val matchedPluginIds: List<String> = emptyList(),
    val activePluginId: String? = null,
    val foregroundPackage: String = "",
    val foregroundActivity: String = "",
)

@Serializable
data class PluginActiveChangedPayload(
    val pluginId: String,
    val foregroundPackage: String = "",
    val matchedPluginIds: List<String> = emptyList(),
)

@Serializable
data class PluginBackendStatePayload(
    val pluginId: String,
    @Serializable(with = TolerantPluginBackendStateSerializer::class)
    val state: PluginBackendState,
)

@Serializable
data class PluginScopedEventPayload(
    val pluginId: String,
    val event: String,
    val payload: JsonElement? = null,
)

@Serializable
data class PluginSelectEventPayload(
    val pluginId: String,
    val packageName: String? = null,
)
