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
        val encoded = SideveilWireJson.encodeToString(SensorToHub.serializer(), msg)
        assertTrue(encoded.contains("\"type\":\"gestureDragStart\""))
        assertTrue(encoded.contains("\"side\":\"right\""))
        assertTrue(encoded.contains("\"startYFraction\":0.42"))

        val decoded = SideveilWireJson.decodeFromString(SensorToHub.serializer(), encoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `HubToSensor decodes tagged JSON emitted by Rust sideveild`() {
        val rawSync =
            """{"type":"syncState","config":{"version":1,"enabled":true},"scopeActive":true,"panelState":"closed"}"""
        val decoded = SideveilWireJson.decodeFromString(HubToSensor.serializer(), rawSync)
        assertTrue(decoded is HubToSensor.SyncState)
        val sync = decoded as HubToSensor.SyncState
        assertTrue(sync.scopeActive)
        assertEquals(PanelState.CLOSED, sync.panelState)

        val refresh =
            SideveilWireJson.decodeFromString(
                HubToSensor.serializer(),
                """{"type":"refreshPermissions"}""",
            )
        assertEquals(HubToSensor.RefreshPermissions, refresh)

        val ping =
            SideveilWireJson.decodeFromString(
                HubToSensor.serializer(),
                """{"type":"ping","seq":7}""",
            )
        assertEquals(HubToSensor.Ping(7L), ping)
    }

    @Test
    fun `SensorUdsHello includes role sensor and UdsHandshakeAck decodes cleanly`() {
        val hello = SideveilWireJson.encodeToString(SensorUdsHello(pid = 999, version = "0.1.0"))
        assertTrue(hello.contains("\"role\":\"sensor\""))
        assertTrue(hello.contains("\"pid\":999"))

        val ack = SideveilWireJson.decodeFromString<UdsHandshakeAck>("""{"ok":true}""")
        assertTrue(ack.ok)
        assertEquals(null, ack.error)
    }
}
