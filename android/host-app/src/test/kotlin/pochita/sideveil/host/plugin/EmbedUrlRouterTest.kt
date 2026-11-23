package pochita.sideveil.host.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pochita.sideveil.protocol.PluginEmbedPrefs

class EmbedUrlRouterTest {
    private fun haEmbed(
        url: String = "http://homeassistant.local:8123",
        remoteUrl: String? = "https://ha.example.com:8123",
        lanSsids: List<String> = emptyList(),
    ): PluginEmbedPrefs =
        PluginEmbedPrefs(
            url = url,
            remoteUrl = remoteUrl,
            lanSsids = lanSsids,
            allowCleartext = true,
        )

    @Test
    fun `preferredBaseUrl always returns primary url when remoteUrl is absent or blank`() {
        val noRemote = haEmbed(remoteUrl = null)
        val blankRemote = haEmbed(remoteUrl = "   ")

        assertEquals(
            "http://homeassistant.local:8123",
            EmbedUrlRouter.preferredBaseUrl(
                noRemote,
                EmbedUrlRouter.NetworkRouteContext(transport = "cellular", wifiSsid = null),
            ),
        )
        assertEquals(
            "http://homeassistant.local:8123",
            EmbedUrlRouter.preferredBaseUrl(
                blankRemote,
                EmbedUrlRouter.NetworkRouteContext(transport = "cellular", wifiSsid = null),
            ),
        )
    }

    @Test
    fun `preferredBaseUrl routes by transport when lanSsids is empty`() {
        val embed = haEmbed(lanSsids = emptyList())

        assertEquals(
            "http://homeassistant.local:8123",
            EmbedUrlRouter.preferredBaseUrl(
                embed,
                EmbedUrlRouter.NetworkRouteContext(transport = "wifi", wifiSsid = "\"HomeWifi\""),
            ),
        )
        assertEquals(
            "http://homeassistant.local:8123",
            EmbedUrlRouter.preferredBaseUrl(
                embed,
                EmbedUrlRouter.NetworkRouteContext(transport = "ETHERNET"),
            ),
        )
        assertEquals(
            "http://homeassistant.local:8123",
            EmbedUrlRouter.preferredBaseUrl(
                embed,
                EmbedUrlRouter.NetworkRouteContext(transport = null),
            ),
        )
        assertEquals(
            "https://ha.example.com:8123",
            EmbedUrlRouter.preferredBaseUrl(
                embed,
                EmbedUrlRouter.NetworkRouteContext(transport = "cellular"),
            ),
        )
        assertEquals(
            "https://ha.example.com:8123",
            EmbedUrlRouter.preferredBaseUrl(
                embed,
                EmbedUrlRouter.NetworkRouteContext(transport = "vpn"),
            ),
        )
    }

    @Test
    fun `preferredBaseUrl enforces SSID match and unquotes Android SSID when lanSsids is configured`() {
        val embed = haEmbed(lanSsids = listOf("HomeLAN", "OfficeLAN"))

        assertEquals(
            "http://homeassistant.local:8123",
            EmbedUrlRouter.preferredBaseUrl(
                embed,
                EmbedUrlRouter.NetworkRouteContext(transport = "WIFI", wifiSsid = "\"HomeLAN\""),
            ),
        )
        assertEquals(
            "http://homeassistant.local:8123",
            EmbedUrlRouter.preferredBaseUrl(
                embed,
                EmbedUrlRouter.NetworkRouteContext(transport = "wifi", wifiSsid = "OfficeLAN"),
            ),
        )
        assertEquals(
            "https://ha.example.com:8123",
            EmbedUrlRouter.preferredBaseUrl(
                embed,
                EmbedUrlRouter.NetworkRouteContext(transport = "wifi", wifiSsid = "\"CoffeeShopWifi\""),
            ),
        )
        assertEquals(
            "https://ha.example.com:8123",
            EmbedUrlRouter.preferredBaseUrl(
                embed,
                EmbedUrlRouter.NetworkRouteContext(transport = "wifi", wifiSsid = null),
            ),
        )
        assertEquals(
            "https://ha.example.com:8123",
            EmbedUrlRouter.preferredBaseUrl(
                embed,
                EmbedUrlRouter.NetworkRouteContext(transport = "cellular", wifiSsid = "\"HomeLAN\""),
            ),
        )
    }

    @Test
    fun `resolveSwitchUrl preserves sub-route query and fragment across origin switch`() {
        assertEquals(
            "https://ha.example.com:8123",
            EmbedUrlRouter.resolveSwitchUrl(
                currentUrl = null,
                targetBaseUrl = "https://ha.example.com:8123",
            ),
        )
        assertEquals(
            "https://ha.example.com:8123",
            EmbedUrlRouter.resolveSwitchUrl(
                currentUrl = "http://homeassistant.local:8123/",
                targetBaseUrl = "https://ha.example.com:8123",
            ),
        )
        assertEquals(
            "https://ha.example.com:8123/lovelace/lights?kiosk=1#top",
            EmbedUrlRouter.resolveSwitchUrl(
                currentUrl = "http://homeassistant.local:8123/lovelace/lights?kiosk=1#top",
                targetBaseUrl = "https://ha.example.com:8123",
            ),
        )
        assertNull(
            EmbedUrlRouter.resolveSwitchUrl(
                currentUrl = "https://ha.example.com:8123/lovelace/lights",
                targetBaseUrl = "https://ha.example.com:8123",
            ),
        )
    }

    @Test
    fun `fallbackBaseUrl toggles between LAN url and remoteUrl on failure`() {
        val embed = haEmbed()

        assertEquals(
            "https://ha.example.com:8123",
            EmbedUrlRouter.fallbackBaseUrl(embed, "http://homeassistant.local:8123/lovelace/0"),
        )
        assertEquals(
            "http://homeassistant.local:8123",
            EmbedUrlRouter.fallbackBaseUrl(embed, "https://ha.example.com:8123/lovelace/0"),
        )
        assertEquals(
            "https://ha.example.com:8123",
            EmbedUrlRouter.fallbackBaseUrl(embed, null),
        )
        assertNull(
            EmbedUrlRouter.fallbackBaseUrl(haEmbed(remoteUrl = null), "http://homeassistant.local:8123"),
        )
    }
}
