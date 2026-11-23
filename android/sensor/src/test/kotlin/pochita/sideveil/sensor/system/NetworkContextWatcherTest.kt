package pochita.sideveil.sensor.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.protocol.NetworkContextSnapshot
import pochita.sideveil.protocol.NetworkTransportKind

class NetworkContextWatcherTest {
    @Test
    fun `sanitizeSsid strips quotes and filters unknown or blank SSIDs`() {
        assertEquals("HomeLab-5G", NetworkContextWatcher.sanitizeSsid("\"HomeLab-5G\""))
        assertEquals("OfficeWiFi", NetworkContextWatcher.sanitizeSsid("OfficeWiFi"))
        assertEquals("Cafe \"Guest\"", NetworkContextWatcher.sanitizeSsid("\"Cafe \"Guest\"\""))
        assertNull(NetworkContextWatcher.sanitizeSsid("\"<unknown ssid>\""))
        assertNull(NetworkContextWatcher.sanitizeSsid("<unknown ssid>"))
        assertNull(NetworkContextWatcher.sanitizeSsid("<UNKNOWN SSID>"))
        assertNull(NetworkContextWatcher.sanitizeSsid("\"\""))
        assertNull(NetworkContextWatcher.sanitizeSsid("   "))
        assertNull(NetworkContextWatcher.sanitizeSsid(null))
    }

    @Test
    fun `deduplicator suppresses identical snapshots and emits on field transitions`() {
        val dedup = NetworkContextDeduplicator()
        val wifi =
            NetworkContextSnapshot(
                transport = NetworkTransportKind.WIFI,
                vpnActive = false,
                validated = true,
                metered = false,
                interfaceName = "wlan0",
                ssid = "HomeLab-5G",
                gateway = "192.168.1.1",
                localIpv4 = "192.168.1.42",
            )
        assertTrue(dedup.accept(wifi))
        assertFalse(dedup.accept(wifi.copy()))

        val wifiWithVpn = wifi.copy(vpnActive = true)
        assertTrue(dedup.accept(wifiWithVpn))
        assertFalse(dedup.accept(wifiWithVpn.copy()))

        val disconnected = NetworkContextSnapshot()
        assertTrue(dedup.accept(disconnected))
        assertFalse(dedup.accept(NetworkContextSnapshot()))
    }
}
