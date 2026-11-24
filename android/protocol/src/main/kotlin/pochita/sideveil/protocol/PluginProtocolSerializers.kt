package pochita.sideveil.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Optional diagnostic callback invoked whenever a forward-compatible serializer encounters an
 * unknown wire token (such as a future capability or enum variant from a newer `sideveild`).
 */
object PluginProtocolDiagnostics {
    @Volatile
    var onUnknownToken: ((field: String, token: String) -> Unit)? = null

    internal fun reportUnknown(
        field: String,
        token: String,
    ) {
        onUnknownToken?.invoke(field, token)
    }
}

/**
 * Exhaustive codec mapping generated [PluginCapability] enum entries to and from canonical wire
 * strings (`storage`, `toast`, `network-internet`, etc.).
 */
object PluginCapabilityCodec {
    /** Returns the canonical wire token for [capability]. */
    fun toWire(capability: PluginCapability): String =
        when (capability) {
            PluginCapability.STORAGE -> "storage"
            PluginCapability.TOAST -> "toast"
            PluginCapability.SYSTEM_EXEC -> "system-exec"
            PluginCapability.NATIVE_HUD -> "native-hud"
            PluginCapability.CLIPBOARD -> "clipboard"
            PluginCapability.HAPTICS -> "haptics"
            PluginCapability.TELEMETRY -> "telemetry"
            PluginCapability.INTER_PLUGIN_BUS -> "inter-plugin-bus"
            PluginCapability.NETWORK_LOOPBACK -> "network-loopback"
            PluginCapability.NETWORK_LAN -> "network-lan"
            PluginCapability.NETWORK_INTERNET -> "network-internet"
            PluginCapability.NETWORK_CONTEXT -> "network-context"
        }

    private val byWire: Map<String, PluginCapability> =
        PluginCapability.entries.associateBy(::toWire)

    /** Resolves a wire token into its [PluginCapability], or `null` if unknown. */
    fun fromWire(token: String): PluginCapability? = byWire[token]
}

/**
 * Forward-compatible list serializer for [PluginCapability] tokens that skips unknown future
 * capability strings with a diagnostic callback instead of failing the entire snapshot decode.
 */
object TolerantPluginCapabilityListSerializer : KSerializer<List<PluginCapability>> {
    private val delegate = ListSerializer(String.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(
        encoder: Encoder,
        value: List<PluginCapability>,
    ) {
        encoder.encodeSerializableValue(delegate, value.map(PluginCapabilityCodec::toWire))
    }

    override fun deserialize(decoder: Decoder): List<PluginCapability> {
        val tokens = decoder.decodeSerializableValue(delegate)
        val resolved = ArrayList<PluginCapability>(tokens.size)
        for (token in tokens) {
            val parsed = PluginCapabilityCodec.fromWire(token)
            if (parsed != null) {
                resolved += parsed
            } else {
                PluginProtocolDiagnostics.reportUnknown("capability", token)
            }
        }
        return resolved
    }
}

/**
 * Base serializer for string-backed generated enums that falls back to [defaultValue] on unknown
 * future wire tokens while emitting a diagnostic warning.
 */
internal abstract class TolerantStringEnumSerializer<T : Enum<T>>(
    serialName: String,
    private val fieldName: String,
    private val defaultValue: T,
    private val toWire: (T) -> String,
    entries: Iterable<T>,
) : KSerializer<T> {
    private val byWire: Map<String, T> = entries.associateBy(toWire)
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: T,
    ) {
        encoder.encodeString(toWire(value))
    }

    override fun deserialize(decoder: Decoder): T {
        val raw = decoder.decodeString()
        val parsed = byWire[raw]
        if (parsed != null) return parsed
        PluginProtocolDiagnostics.reportUnknown(fieldName, raw)
        return defaultValue
    }
}

internal object TolerantPluginOriginSerializer : TolerantStringEnumSerializer<PluginOrigin>(
    serialName = "TolerantPluginOrigin",
    fieldName = "origin",
    defaultValue = PluginOrigin.INSTALLED,
    toWire = {
        when (it) {
            PluginOrigin.BUILTIN -> "builtin"
            PluginOrigin.BUNDLED -> "bundled"
            PluginOrigin.INSTALLED -> "installed"
            PluginOrigin.WEB_EMBED -> "web-embed"
        }
    },
    entries = PluginOrigin.entries,
)

internal object TolerantPluginBackendKindSerializer : TolerantStringEnumSerializer<PluginBackendKind>(
    serialName = "TolerantPluginBackendKind",
    fieldName = "backend",
    defaultValue = PluginBackendKind.NONE,
    toWire = {
        when (it) {
            PluginBackendKind.NONE -> "none"
            PluginBackendKind.UDS_NATIVE -> "uds-native"
            PluginBackendKind.UDS_APP -> "uds-app"
        }
    },
    entries = PluginBackendKind.entries,
)

internal object TolerantPluginBackendStateSerializer : TolerantStringEnumSerializer<PluginBackendState>(
    serialName = "TolerantPluginBackendState",
    fieldName = "backendState",
    defaultValue = PluginBackendState.NONE,
    toWire = {
        when (it) {
            PluginBackendState.NONE -> "none"
            PluginBackendState.ONLINE -> "online"
            PluginBackendState.OFFLINE -> "offline"
        }
    },
    entries = PluginBackendState.entries,
)

internal object TolerantPluginApprovalStatusSerializer : TolerantStringEnumSerializer<PluginApprovalStatus>(
    serialName = "TolerantPluginApprovalStatus",
    fieldName = "approvalStatus",
    defaultValue = PluginApprovalStatus.PENDING_REVIEW,
    toWire = {
        when (it) {
            PluginApprovalStatus.APPROVED -> "approved"
            PluginApprovalStatus.PENDING_REVIEW -> "pending-review"
            PluginApprovalStatus.NEEDS_REAPPROVAL -> "needs-reapproval"
        }
    },
    entries = PluginApprovalStatus.entries,
)

internal object TolerantPluginDaemonStatusSerializer : TolerantStringEnumSerializer<PluginDaemonStatus>(
    serialName = "TolerantPluginDaemonStatus",
    fieldName = "daemonStatus",
    defaultValue = PluginDaemonStatus.NOT_CONFIGURED,
    toWire = {
        when (it) {
            PluginDaemonStatus.NOT_CONFIGURED -> "not-configured"
            PluginDaemonStatus.STOPPED -> "stopped"
            PluginDaemonStatus.AWAITING_APPROVAL -> "awaiting-approval"
            PluginDaemonStatus.RUNNING -> "running"
            PluginDaemonStatus.CRASHED -> "crashed"
            PluginDaemonStatus.CIRCUIT_OPEN -> "circuit-open"
        }
    },
    entries = PluginDaemonStatus.entries,
)

internal object TolerantPluginSurfaceBackdropSerializer : TolerantStringEnumSerializer<PluginSurfaceBackdrop>(
    serialName = "TolerantPluginSurfaceBackdrop",
    fieldName = "surface.backdrop",
    defaultValue = PluginSurfaceBackdrop.NATIVE_GLASS,
    toWire = {
        when (it) {
            PluginSurfaceBackdrop.NATIVE_GLASS -> "native-glass"
            PluginSurfaceBackdrop.TRANSPARENT -> "transparent"
            PluginSurfaceBackdrop.OPAQUE -> "opaque"
        }
    },
    entries = PluginSurfaceBackdrop.entries,
)

internal object TolerantPluginPresentationModeSerializer : TolerantStringEnumSerializer<PluginPresentationMode>(
    serialName = "TolerantPluginPresentationMode",
    fieldName = "surface.presentation",
    defaultValue = PluginPresentationMode.DRAWER,
    toWire = {
        when (it) {
            PluginPresentationMode.DRAWER -> "drawer"
            PluginPresentationMode.PEEK -> "peek"
            PluginPresentationMode.PINNED -> "pinned"
            PluginPresentationMode.WIDE -> "wide"
            PluginPresentationMode.NATIVE_HUD -> "native-hud"
        }
    },
    entries = PluginPresentationMode.entries,
)
