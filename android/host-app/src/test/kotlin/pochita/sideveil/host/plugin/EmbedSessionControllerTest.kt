package pochita.sideveil.host.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginEmbedPrefs

class EmbedSessionControllerTest {
    private class FakeWebViewPort(
        var url: String? = null,
    ) : EmbedWebViewPort {
        val loadedUrls = mutableListOf<String>()
        var reloadCalls: Int = 0

        override val currentUrl: String?
            get() = url

        override fun loadUrl(url: String) {
            this.url = url
            loadedUrls += url
        }

        override fun reload() {
            reloadCalls += 1
        }

        override fun evaluateJavascript(script: String) = Unit

        override fun postToMain(action: () -> Unit) = action()
    }

    private val haEmbed =
        PluginEmbedPrefs(
            url = "http://homeassistant.local:8123",
            remoteUrl = "https://ha.example.com:8123",
            lanSsids = listOf("HomeWifi"),
            allowCleartext = true,
        )

    private val haDescriptor =
        PluginDescriptor(
            id = "lan.homeassistant",
            name = "Home Assistant",
            version = "1.0.0",
            embed = haEmbed,
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

    @Test
    fun `dual-failure guard stops A-to-B-to-A ping-pong when both LAN and remote fail`() {
        val homeWifi = NetworkRouteContext(transport = "wifi", wifiSsid = "HomeWifi")
        val port = FakeWebViewPort()
        val statuses = mutableListOf<Pair<String?, Boolean>>()
        val controller = EmbedSessionController(port, initialNetworkRoute = homeWifi)
        controller.bindStatusListener { msg, isError -> statuses += msg to isError }

        // 1. LAN fails -> falls back once to remote.
        assertEquals(
            true,
            controller.handleMainFrameNetworkError(haDescriptor, "http://homeassistant.local:8123/"),
        )
        assertEquals(listOf("https://ha.example.com:8123"), port.loadedUrls)

        // 2. Remote ALSO fails -> must NOT bounce back to LAN; surfaces error status and stops.
        assertEquals(
            false,
            controller.handleMainFrameNetworkError(haDescriptor, "https://ha.example.com:8123/"),
        )
        assertEquals(1, port.loadedUrls.size)
        assertEquals("连接异常，点击刷新重试" to true, statuses.last())

        // 3. After reload resets state and remote loads cleanly, a subsequent transient error can fall back again.
        controller.reload(haDescriptor)
        controller.handleMainPageFinished("http://homeassistant.local:8123/")
        assertEquals(
            true,
            controller.handleMainFrameNetworkError(haDescriptor, "http://homeassistant.local:8123/"),
        )
    }

    @Test
    fun `loadInitial aligns allowed restoreUrl to preferred LAN origin when on matching Wi-Fi SSID`() {
        val homeWifi = NetworkRouteContext(transport = "wifi", wifiSsid = "\"HomeWifi\"")
        val port = FakeWebViewPort()
        val controller = EmbedSessionController(port, initialNetworkRoute = homeWifi)

        controller.loadInitial(haDescriptor, restoreUrl = "https://ha.example.com:8123/lovelace/0?kiosk=1")
        assertEquals(listOf("http://homeassistant.local:8123/lovelace/0?kiosk=1"), port.loadedUrls)

        // Auth callback restoreUrl resets cleanly to preferred LAN base instead of transplanting OAuth query.
        controller.loadInitial(
            haDescriptor,
            restoreUrl = "https://ha.example.com:8123/auth/authorize?redirect_uri=https%3A%2F%2Fha.example.com%2F",
        )
        assertEquals("http://homeassistant.local:8123", port.loadedUrls.last())

        // Disallowed external restoreUrl is ignored in favor of preferred LAN base.
        controller.loadInitial(haDescriptor, restoreUrl = "https://untrusted.example.org/steal")
        assertEquals("http://homeassistant.local:8123", port.loadedUrls.last())
    }

    @Test
    fun `reload switches back to preferred LAN URL when stuck on fallback remoteUrl on HomeWifi`() {
        val homeWifi = NetworkRouteContext(transport = "wifi", wifiSsid = "HomeWifi")
        val port = FakeWebViewPort(url = "https://ha.example.com:8123/lovelace/lights?edit=1")
        val controller = EmbedSessionController(port, initialNetworkRoute = homeWifi)

        // Different origin from preferred -> loads aligned preferred LAN URL rather than reloading remoteUrl.
        controller.reload(haDescriptor)
        assertEquals(0, port.reloadCalls)
        assertEquals(listOf("http://homeassistant.local:8123/lovelace/lights?edit=1"), port.loadedUrls)

        // Already on preferred LAN origin -> calls WebView.reload() directly.
        controller.reload(haDescriptor)
        assertEquals(1, port.reloadCalls)
        assertEquals(1, port.loadedUrls.size)

        // Blank/about:blank triggers loadInitial(descriptor).
        port.url = "about:blank"
        controller.reload(haDescriptor)
        assertEquals(1, port.reloadCalls)
        assertEquals("http://homeassistant.local:8123", port.loadedUrls.last())
    }
}
