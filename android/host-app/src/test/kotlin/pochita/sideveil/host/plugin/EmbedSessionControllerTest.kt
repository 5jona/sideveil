package pochita.sideveil.host.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pochita.sideveil.protocol.PluginEmbedPrefs

class EmbedSessionControllerTest {
    private val haEmbed =
        PluginEmbedPrefs(
            url = "http://homeassistant.local:8123",
            remoteUrl = "https://ha.example.com:8123",
            lanSsids = listOf("HomeWifi"),
            allowCleartext = true,
        )

    @Test
    fun `sticky fallback suppresses bounce-back on repeated same-SSID ticks until network changes or reset`() {
        val homeWifi = NetworkRouteContext(transport = "wifi", wifiSsid = "\"HomeWifi\"")
        val state = EmbedStickyFallbackState(initialNetworkRoute = homeWifi)

        // 1. Primary LAN mDNS fails -> automatic fallback to remoteUrl and remembers failed origin on HomeWifi.
        val fallbackUrl =
            state.resolveFallbackAfterMainFrameError(
                haEmbed,
                failedUrl = "http://homeassistant.local:8123/lovelace/0",
            )
        assertEquals("https://ha.example.com:8123/lovelace/0", fallbackUrl)

        // 2. Repeated network.contextChanged tick on the SAME Wi-Fi SSID (quoted/unquoted) must NOT bounce back to LAN.
        assertNull(
            state.resolveNetworkRouteSwitch(
                haEmbed,
                NetworkRouteContext(transport = "WIFI", wifiSsid = "HomeWifi"),
                currentUrl = "https://ha.example.com:8123/lovelace/0",
            ),
        )

        // 3. Switching to cellular and then back to HomeWifi clears sticky failure and retries LAN.
        assertNull(
            state.resolveNetworkRouteSwitch(
                haEmbed,
                NetworkRouteContext(transport = "cellular", wifiSsid = null),
                currentUrl = "https://ha.example.com:8123/lovelace/0",
            ),
        )
        assertEquals(
            "http://homeassistant.local:8123/lovelace/0",
            state.resolveNetworkRouteSwitch(
                haEmbed,
                NetworkRouteContext(transport = "wifi", wifiSsid = "\"HomeWifi\""),
                currentUrl = "https://ha.example.com:8123/lovelace/0",
            ),
        )

        // 4. Manual reload/home reset also clears sticky failure immediately on the same SSID.
        assertEquals(
            "https://ha.example.com:8123/lovelace/0",
            state.resolveFallbackAfterMainFrameError(
                haEmbed,
                failedUrl = "http://homeassistant.local:8123/lovelace/0",
            ),
        )
        assertNull(
            state.resolveNetworkRouteSwitch(
                haEmbed,
                homeWifi,
                currentUrl = "https://ha.example.com:8123/lovelace/0",
            ),
        )
        state.reset()
        assertEquals(
            "http://homeassistant.local:8123/lovelace/0",
            state.resolveNetworkRouteSwitch(
                haEmbed,
                homeWifi,
                currentUrl = "https://ha.example.com:8123/lovelace/0",
            ),
        )
    }

    @Test
    fun `single-shot guard prevents infinite fallback loop on identical failedUrl`() {
        val state = EmbedStickyFallbackState(NetworkRouteContext(transport = "wifi", wifiSsid = "HomeWifi"))
        val first = state.resolveFallbackAfterMainFrameError(haEmbed, "http://homeassistant.local:8123/")
        assertEquals("https://ha.example.com:8123", first)

        val second = state.resolveFallbackAfterMainFrameError(haEmbed, "http://homeassistant.local:8123/")
        assertNull(second)
    }
}
