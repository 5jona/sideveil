package pochita.sideveil.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorProtocolTest {
    @Test
    fun `SensorToHub encodes exact tagged JSON expected by Rust sideveild`() {
        val msg: SensorToHub =
            SensorToHub.GestureDragStart(
                side = EdgeSide.RIGHT,
                startYFraction = 0.42f,
            )
        val encoded = SideVeilWireJson.encodeToString(SensorToHub.serializer(), msg)
        assertTrue(encoded.contains("\"type\":\"gestureDragStart\""))
        assertTrue(encoded.contains("\"side\":\"right\""))
        assertTrue(encoded.contains("\"startYFraction\":0.42"))

        val decoded = SideVeilWireJson.decodeFromString(SensorToHub.serializer(), encoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `HubToSensor decodes tagged JSON emitted by Rust sideveild`() {
        val rawSync =
            """{"type":"syncState","config":{"version":1,"enabled":true},"scopeActive":true,"panelState":"closed"}"""
        val decoded = SideVeilWireJson.decodeFromString(HubToSensor.serializer(), rawSync)
        assertTrue(decoded is HubToSensor.SyncState)
        val sync = decoded as HubToSensor.SyncState
        assertTrue(sync.scopeActive)
        assertEquals(PanelState.CLOSED, sync.panelState)

        val refresh =
            SideVeilWireJson.decodeFromString(
                HubToSensor.serializer(),
                """{"type":"refreshPermissions"}""",
            )
        assertEquals(HubToSensor.RefreshPermissions, refresh)

        val ping =
            SideVeilWireJson.decodeFromString(
                HubToSensor.serializer(),
                """{"type":"ping","seq":7}""",
            )
        assertEquals(HubToSensor.Ping(7L), ping)
    }

    @Test
    fun `SensorUdsHello includes role sensor and UdsHandshakeAck decodes cleanly`() {
        val hello = SideVeilWireJson.encodeToString(SensorUdsHello(pid = 999, version = "0.1.0"))
        assertTrue(hello.contains("\"role\":\"sensor\""))
        assertTrue(hello.contains("\"pid\":999"))

        val ack = SideVeilWireJson.decodeFromString<UdsHandshakeAck>("""{"ok":true}""")
        assertTrue(ack.ok)
        assertEquals(null, ack.error)
    }

    @Test
    fun `NetworkContextChanged roundtrips with exact camelCase tags and fields`() {
        val snapshot =
            NetworkContextSnapshot(
                transport = NetworkTransportKind.WIFI,
                vpnActive = true,
                validated = true,
                metered = false,
                interfaceName = "wlan0",
                ssid = "HomeLab-5G",
                gateway = "192.168.1.1",
                localIpv4 = "192.168.1.42",
            )
        val msg: SensorToHub = SensorToHub.NetworkContextChanged(context = snapshot)
        val encoded = SideVeilWireJson.encodeToString(SensorToHub.serializer(), msg)
        assertTrue(encoded.contains("\"type\":\"networkContextChanged\""))
        assertTrue(encoded.contains("\"transport\":\"wifi\""))
        assertTrue(encoded.contains("\"vpnActive\":true"))
        assertTrue(encoded.contains("\"interfaceName\":\"wlan0\""))
        assertTrue(encoded.contains("\"localIpv4\":\"192.168.1.42\""))

        val decoded = SideVeilWireJson.decodeFromString(SensorToHub.serializer(), encoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `GestureStatsUpdated roundtrips with exact camelCase tags and fields`() {
        val stats =
            DebugGestureStats(
                edgeSwipesTriggered = 5L,
                edgeSwipesRejected = 2L,
                hotzoneMisses = 11L,
                pilferInvocations = 7L,
                lastRejectReason = "multi_touch",
                lastEventTimestampMs = 1_700_000_001_000L,
            )
        val msg: SensorToHub = SensorToHub.GestureStatsUpdated(stats = stats)
        val encoded = SideVeilWireJson.encodeToString(SensorToHub.serializer(), msg)
        assertTrue(encoded.contains("\"type\":\"gestureStatsUpdated\""))
        assertTrue(encoded.contains("\"edgeSwipesTriggered\":5"))
        assertTrue(encoded.contains("\"lastRejectReason\":\"multi_touch\""))

        val decoded = SideVeilWireJson.decodeFromString(SensorToHub.serializer(), encoded)
        assertEquals(msg, decoded)
    }
}
