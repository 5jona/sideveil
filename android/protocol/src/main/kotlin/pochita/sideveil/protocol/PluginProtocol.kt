package pochita.sideveil.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val BUILTIN_DIAG_PLUGIN_ID = "sideveil.diagnostic"

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
}

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
data class PluginSurfacePrefs(
    val backdrop: PluginSurfaceBackdrop = PluginSurfaceBackdrop.NATIVE_GLASS,
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
    val builtin: Boolean = false,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val packages: List<String> = emptyList(),
    val global: Boolean = false,
    val hasScopeOverride: Boolean = false,
    val backend: PluginBackendKind = PluginBackendKind.NONE,
    val backendState: PluginBackendState = PluginBackendState.NONE,
    val capabilities: List<PluginCapability> = emptyList(),
    val surface: PluginSurfacePrefs = PluginSurfacePrefs(),
) {
    companion object {
        fun builtinDiagnostic(): PluginDescriptor =
            PluginDescriptor(
                id = BUILTIN_DIAG_PLUGIN_ID,
                name = "内置诊断",
                version = "0.1.0",
                entry = "index.html",
                builtin = true,
                enabled = true,
                priority = 0,
                capabilities = listOf(PluginCapability.TOAST),
            )
    }
}

@Serializable
data class PluginsSnapshotPayload(
    val plugins: List<PluginDescriptor> = emptyList(),
    val matchedPluginIds: List<String> = emptyList(),
    val activePluginId: String? = null,
    val foregroundPackage: String = "",
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
