package pochita.sideveil.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

typealias HostPluginDiagnosticsPayload = pochita.sideveil.protocol.generated.HostPluginDiagnosticsPayload

const val BUILTIN_DIAG_PLUGIN_ID = SideVeilBuiltinDiagnostic.ID

fun isBuiltinTestPlugin(id: String): Boolean =
    id == SideVeilBuiltinTestPlugins.DIAGNOSTIC ||
        id == SideVeilBuiltinTestPlugins.VELLO_SCOPE ||
        id == SideVeilBuiltinTestPlugins.NATIVE_PERF_HUD

@Serializable
enum class PluginBackendKind {
    @SerialName("none")
    NONE,

    @SerialName("uds-native")
    UDS_NATIVE,

    @SerialName("uds-app")
    UDS_APP,
}

@Serializable
enum class PluginBackendState {
    @SerialName("none")
    NONE,

    @SerialName("online")
    ONLINE,

    @SerialName("offline")
    OFFLINE,
}

@Serializable
enum class PluginCapability {
    @SerialName("storage")
    STORAGE,

    @SerialName("toast")
    TOAST,

    @SerialName("system-exec")
    SYSTEM_EXEC,

    @SerialName("native-hud")
    NATIVE_HUD,

    @SerialName("clipboard")
    CLIPBOARD,

    @SerialName("haptics")
    HAPTICS,

    @SerialName("telemetry")
    TELEMETRY,

    @SerialName("inter-plugin-bus")
    INTER_PLUGIN_BUS,

    @SerialName("network-loopback")
    NETWORK_LOOPBACK,

    @SerialName("network-lan")
    NETWORK_LAN,

    @SerialName("network-context")
    NETWORK_CONTEXT,
}

@Serializable
data class PluginEmbedPrefs(
    val url: String,
    val remoteUrl: String? = null,
    val lanSsids: List<String> = emptyList(),
    val allowedOrigins: List<String> = emptyList(),
    val allowCleartext: Boolean = false,
    val wideViewport: Boolean = true,
    val allowZoom: Boolean = false,
    val webHistoryBack: Boolean = true,
    val textZoom: Int? = null,
    val showToolbar: Boolean = true,
    val keepAliveGraceSec: Int? = null,
)

@Serializable
data class PluginNetworkPrefs(
    val allowedOrigins: List<String> = emptyList(),
    val allowCleartext: Boolean = false,
    val spaFallback: Boolean = false,
)

@Serializable
enum class PluginSurfaceBackdrop {
    @SerialName("native-glass")
    NATIVE_GLASS,

    @SerialName("transparent")
    TRANSPARENT,

    @SerialName("opaque")
    OPAQUE,
}

@Serializable
enum class PluginPresentationMode {
    @SerialName("drawer")
    DRAWER,

    @SerialName("peek")
    PEEK,

    @SerialName("pinned")
    PINNED,

    @SerialName("wide")
    WIDE,

    @SerialName("native-hud")
    NATIVE_HUD,
}

@Serializable
data class PluginSurfacePrefs(
    val backdrop: PluginSurfaceBackdrop = PluginSurfaceBackdrop.NATIVE_GLASS,
    val presentation: PluginPresentationMode = PluginPresentationMode.DRAWER,
    val crossOriginIsolated: Boolean = false,
    val keepAlive: Boolean = false,
    val widthDp: Int? = null,
    val heightPercent: Int? = null,
)

@Serializable
data class PluginDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val icon: String? = null,
    val entry: String = "index.html",
    val assetRevision: String? = null,
    val builtin: Boolean = false,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val packages: List<String> = emptyList(),
    val activities: List<String> = emptyList(),
    val global: Boolean = false,
    val hasScopeOverride: Boolean = false,
    val devServerUrl: String? = null,
    val backend: PluginBackendKind = PluginBackendKind.NONE,
    val backendState: PluginBackendState = PluginBackendState.NONE,
    val capabilities: List<PluginCapability> = emptyList(),
    val surface: PluginSurfacePrefs = PluginSurfacePrefs(),
    val embed: PluginEmbedPrefs? = null,
    val network: PluginNetworkPrefs? = null,
) {
    companion object {
        fun builtinDiagnostic(): PluginDescriptor =
            PluginDescriptor(
                id = SideVeilBuiltinDiagnostic.ID,
                name = SideVeilBuiltinDiagnostic.NAME,
                version = SideVeilBuiltinDiagnostic.VERSION,
                entry = SideVeilBuiltinDiagnostic.ENTRY,
                builtin = true,
                enabled = true,
                priority = SideVeilBuiltinDiagnostic.PRIORITY,
                global = SideVeilBuiltinDiagnostic.GLOBAL,
                capabilities = listOf(PluginCapability.STORAGE, PluginCapability.TOAST),
            )
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
