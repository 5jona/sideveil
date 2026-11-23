package pochita.sideveil.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DisplayGeometry(
    val widthPx: Int = 1080,
    val heightPx: Int = 2400,
    val density: Float = 2.625f,
)

@Serializable
enum class NetworkTransportKind {
    @SerialName("wifi")
    WIFI,

    @SerialName("cellular")
    CELLULAR,

    @SerialName("ethernet")
    ETHERNET,

    @SerialName("other")
    OTHER,

    @SerialName("none")
    NONE,
}

@Serializable
data class NetworkContextSnapshot(
    val transport: NetworkTransportKind = NetworkTransportKind.NONE,
    val vpnActive: Boolean = false,
    val validated: Boolean = false,
    val metered: Boolean = false,
    val interfaceName: String? = null,
    val ssid: String? = null,
    val gateway: String? = null,
    val localIpv4: String? = null,
)

@Serializable
data class DebugGestureStats(
    val edgeSwipesTriggered: Long = 0L,
    val edgeSwipesRejected: Long = 0L,
    val hotzoneMisses: Long = 0L,
    val pilferInvocations: Long = 0L,
    val lastRejectReason: String? = null,
    val lastEventTimestampMs: Long = 0L,
)

@Serializable
data class SensorUdsHello(
    val role: String = "sensor",
    val pid: Int,
    val version: String,
)

@Serializable
data class UdsHandshakeAck(
    val ok: Boolean,
    val error: String? = null,
)

/**
 * NDJSON frames sent from the UID 1000 `:sensor` process to Rust `sideveild` over `hub.sock`.
 * Matches `sideveil-protocol::sensor::SensorToHub` (`#[serde(tag = "type", rename_all = "camelCase")]`).
 */
@Serializable
sealed interface SensorToHub {
    @Serializable
    @SerialName("hello")
    data class Hello(
        val pid: Int,
        val version: String,
        val display: DisplayGeometry,
        val taskListenerActive: Boolean,
        val gestureMonitorActive: Boolean,
        val overlayPermissionGranted: Boolean,
        val foregroundPackage: String,
        val foregroundActivity: String = "",
    ) : SensorToHub

    @Serializable
    @SerialName("foregroundChanged")
    data class ForegroundChanged(
        val packageName: String,
        val activityName: String = "",
    ) : SensorToHub

    @Serializable
    @SerialName("displayChanged")
    data class DisplayChanged(
        val display: DisplayGeometry,
    ) : SensorToHub

    @Serializable
    @SerialName("permissionsStatus")
    data class PermissionsStatus(
        val installed: Boolean,
        val uid: Int?,
        val overlayGranted: Boolean,
    ) : SensorToHub

    @Serializable
    @SerialName("gestureDragStart")
    data class GestureDragStart(
        val side: EdgeSide,
        val startYFraction: Float,
    ) : SensorToHub

    @Serializable
    @SerialName("gestureDragMove")
    data class GestureDragMove(
        val side: EdgeSide,
        val progress: Float,
        val rawX: Float,
        val rawY: Float,
        val deltaX: Float,
    ) : SensorToHub

    @Serializable
    @SerialName("gestureDragEnd")
    data class GestureDragEnd(
        val shouldOpen: Boolean,
        val velocityPxPerSec: Float,
        val canceled: Boolean = false,
    ) : SensorToHub

    @Serializable
    @SerialName("gestureFullscreenHint")
    data class GestureFullscreenHint(
        val side: EdgeSide,
    ) : SensorToHub

    @Serializable
    @SerialName("edgeRepositionStart")
    data class EdgeRepositionStart(
        val side: EdgeSide,
        val topPercent: Int,
    ) : SensorToHub

    @Serializable
    @SerialName("edgeRepositionMove")
    data class EdgeRepositionMove(
        val side: EdgeSide,
        val topPercent: Int,
    ) : SensorToHub

    @Serializable
    @SerialName("edgeRepositionEnd")
    data class EdgeRepositionEnd(
        val side: EdgeSide,
        val topPercent: Int,
        val committed: Boolean,
    ) : SensorToHub

    @Serializable
    @SerialName("networkContextChanged")
    data class NetworkContextChanged(
        val context: NetworkContextSnapshot,
    ) : SensorToHub

    @Serializable
    @SerialName("gestureStatsUpdated")
    data class GestureStatsUpdated(
        val stats: DebugGestureStats,
    ) : SensorToHub

    @Serializable
    @SerialName("statusBarPullDown")
    data object StatusBarPullDown : SensorToHub

    @Serializable
    @SerialName("pong")
    data class Pong(
        val seq: Long,
    ) : SensorToHub
}

/**
 * NDJSON frames pushed from Rust `sideveild` to `:sensor` over `hub.sock`.
 * Matches `sideveil-protocol::sensor::HubToSensor` (`#[serde(tag = "type", rename_all = "camelCase")]`).
 */
@Serializable
sealed interface HubToSensor {
    @Serializable
    @SerialName("syncState")
    data class SyncState(
        val config: SideVeilConfig,
        val scopeActive: Boolean,
        val panelState: PanelState,
        val fullscreenActive: Boolean = false,
    ) : HubToSensor

    @Serializable
    @SerialName("refreshPermissions")
    data object RefreshPermissions : HubToSensor

    @Serializable
    @SerialName("ping")
    data class Ping(
        val seq: Long,
    ) : HubToSensor
}
