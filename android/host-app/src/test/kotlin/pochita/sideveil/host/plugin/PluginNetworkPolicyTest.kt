package pochita.sideveil.host.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.protocol.PluginCapability
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginEmbedPrefs
import pochita.sideveil.protocol.PluginNetworkPrefs

class PluginNetworkPolicyTest {
    private fun embedDescriptor(
        url: String,
        allowedOrigins: List<String> = emptyList(),
        allowCleartext: Boolean = false,
    ): PluginDescriptor =
        PluginDescriptor(
            id = "com.example.embed",
            name = "Embed",
            version = "1.0.0",
            embed =
                PluginEmbedPrefs(
                    url = url,
                    allowedOrigins = allowedOrigins,
                    allowCleartext = allowCleartext,
                ),
        )

    private fun trustedDescriptor(
        capabilities: List<PluginCapability> = emptyList(),
        network: PluginNetworkPrefs? = null,
    ): PluginDescriptor =
        PluginDescriptor(
            id = "com.example.local",
            name = "Local",
            version = "1.0.0",
            capabilities = capabilities,
            network = network,
        )

    @Test
    fun `classifies RFC1918 loopback IPv6 local and local DNS suffixes strictly`() {
        val allowed =
            listOf(
                "localhost",
                "127.0.0.1",
                "::1",
                "[::1]",
                "0:0:0:0:0:0:0:1",
                "10.0.0.1",
                "10.255.255.254",
                "172.16.0.1",
                "172.31.255.254",
                "192.168.0.1",
                "192.168.1.100",
                "fc00::1",
                "fd12:3456:789a::1",
                "fe80::1",
                "fe80::1%wlan0",
                "febf::1",
                "router.lan",
                "homeassistant.local",
                "sub.box.local",
                "dev.localhost",
                "ha.internal",
                "gateway.home.arpa",
                "sub.ha.home.arpa",
            )
        for (host in allowed) {
            assertTrue("expected private/loopback for $host", PluginNetworkPolicy.isPrivateOrLoopbackHost(host))
        }

        val rejected =
            listOf(
                "",
                "example.com",
                "1.1.1.1",
                "8.8.8.8",
                "11.0.0.1",
                "172.15.255.255",
                "172.32.0.1",
                "192.167.1.1",
                "169.254.1.1",
                "0.0.0.0",
                "127.0.0.2",
                "010.0.0.1",
                "2001:4860:4860::8888",
                "fbff::1",
                "fec0::1",
                "::",
                ".local",
                ".lan",
                ".internal",
                ".home.arpa",
                ".localhost",
                "example.local.evil.com",
                "plugins.sideveil.local",
                "sideveil.diagnostic.plugins.sideveil.local",
                "192.168.1.1@evil.com",
            )
        for (host in rejected) {
            assertFalse("expected public/rejected for $host", PluginNetworkPolicy.isPrivateOrLoopbackHost(host))
        }
    }

    @Test
    fun `enforces triple-lock cleartext rejection on public HTTP for untrusted embeds`() {
        val publicCleartext =
            embedDescriptor(
                url = "http://example.com/dashboard",
                allowedOrigins = listOf("http://example.com"),
                allowCleartext = true,
            )
        assertTrue(PluginNetworkPolicy.isUntrustedEmbed(publicCleartext))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(publicCleartext, "http://example.com/dashboard", true))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(publicCleartext, "http://example.com/app.js", false))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(publicCleartext, "ws://example.com/socket", false))
    }

    @Test
    fun `enforces LAN HTTP triple-lock and HTTPS subresource rules for untrusted embeds`() {
        val lanAllowed =
            embedDescriptor(
                url = "http://192.168.1.1:9090/ui",
                allowedOrigins = listOf("https://router.lan"),
                allowCleartext = true,
            )
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(lanAllowed, "http://192.168.1.1:9090/ui", true))
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(lanAllowed, "ws://192.168.1.1:9090/traffic", false))
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(lanAllowed, "https://router.lan/settings", true))
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(lanAllowed, "https://cdn.example.com/font.woff2", false))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(lanAllowed, "https://cdn.example.com/login", true))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(lanAllowed, "http://192.168.1.2:9090/ui", true))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(lanAllowed, "http://192.168.1.1:8080/ui", true))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(lanAllowed, "http://192.168.1.1@evil.com/", true))
        assertFalse(
            PluginNetworkPolicy.shouldAllowNativeNetwork(
                lanAllowed,
                "https://other.plugins.sideveil.local/index.html",
                false,
            ),
        )

        val lanCleartextDisabled =
            embedDescriptor(
                url = "http://192.168.1.1:9090/ui",
                allowCleartext = false,
            )
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(lanCleartextDisabled, "http://192.168.1.1:9090/ui", true))

        val dualOriginHa =
            PluginDescriptor(
                id = "com.example.ha",
                name = "Home Assistant",
                version = "1.0.0",
                embed =
                    PluginEmbedPrefs(
                        url = "http://homeassistant.local:8123",
                        remoteUrl = "https://ha.example.com:8123",
                        allowCleartext = true,
                    ),
            )
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(dualOriginHa, "http://homeassistant.local:8123/lovelace", true))
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(dualOriginHa, "ws://homeassistant.local:8123/api/websocket", false))
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(dualOriginHa, "https://ha.example.com:8123/lovelace", true))
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(dualOriginHa, "wss://ha.example.com:8123/api/websocket", false))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(dualOriginHa, "wss://evil.example.com:8123/api/websocket", false))
    }

    @Test
    fun `enforces capability and triple-lock rules for trusted local plugins`() {
        val loopbackPlugin =
            trustedDescriptor(
                capabilities = listOf(PluginCapability.NETWORK_LOOPBACK),
                network =
                    PluginNetworkPrefs(
                        allowedOrigins = listOf("http://127.0.0.1:9090", "https://localhost:9443"),
                        allowCleartext = true,
                    ),
            )
        assertFalse(PluginNetworkPolicy.isUntrustedEmbed(loopbackPlugin))
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(loopbackPlugin, "http://127.0.0.1:9090/proxies", false))
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(loopbackPlugin, "ws://127.0.0.1:9090/traffic", false))
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(loopbackPlugin, "https://localhost:9443/api", false))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(loopbackPlugin, "http://192.168.1.1:9090/proxies", false))

        val lanPlugin =
            trustedDescriptor(
                capabilities = listOf(PluginCapability.NETWORK_LAN),
                network =
                    PluginNetworkPrefs(
                        allowedOrigins =
                            listOf(
                                "http://192.168.1.1:9090",
                                "http://example.com",
                                "http://127.0.0.1:9090",
                                "https://192.168.1.1:8443",
                            ),
                        allowCleartext = true,
                    ),
            )
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(lanPlugin, "http://192.168.1.1:9090/status", false))
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(lanPlugin, "https://192.168.1.1:8443/v1", false))
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(lanPlugin, "wss://192.168.1.1:8443/ws", false))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(lanPlugin, "http://example.com/status", false))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(lanPlugin, "http://127.0.0.1:9090/status", false))

        val noCleartextLan =
            lanPlugin.copy(network = lanPlugin.network?.copy(allowCleartext = false))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(noCleartextLan, "http://192.168.1.1:9090/status", false))
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(noCleartextLan, "https://192.168.1.1:8443/v1", false))

        val noCapPlugin = trustedDescriptor(network = lanPlugin.network)
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(noCapPlugin, "https://192.168.1.1:8443/v1", false))
    }

    @Test
    fun `allows mixed content strictly for trusted plugins with cleartext and network capability`() {
        val embed = embedDescriptor("https://ha.local:8123", allowCleartext = true)
        assertFalse(PluginNetworkPolicy.shouldAllowMixedContent(embed))

        val trustedNoCap =
            trustedDescriptor(
                network = PluginNetworkPrefs(allowedOrigins = listOf("http://127.0.0.1:9090"), allowCleartext = true),
            )
        assertFalse(PluginNetworkPolicy.shouldAllowMixedContent(trustedNoCap))

        val trustedNoCleartext =
            trustedDescriptor(
                capabilities = listOf(PluginCapability.NETWORK_LOOPBACK),
                network = PluginNetworkPrefs(allowedOrigins = listOf("http://127.0.0.1:9090"), allowCleartext = false),
            )
        assertFalse(PluginNetworkPolicy.shouldAllowMixedContent(trustedNoCleartext))

        val trustedLoopback =
            trustedDescriptor(
                capabilities = listOf(PluginCapability.NETWORK_LOOPBACK),
                network = PluginNetworkPrefs(allowedOrigins = listOf("http://127.0.0.1:9090"), allowCleartext = true),
            )
        assertTrue(PluginNetworkPolicy.shouldAllowMixedContent(trustedLoopback))

        val trustedLan =
            trustedDescriptor(
                capabilities = listOf(PluginCapability.NETWORK_LAN),
                network = PluginNetworkPrefs(allowedOrigins = listOf("http://192.168.1.1:9090"), allowCleartext = true),
            )
        assertTrue(PluginNetworkPolicy.shouldAllowMixedContent(trustedLan))
    }

    @Test
    fun `isSameTrustedOrigin matches exact origin and rejects prefix spoofing`() {
        val expected = "https://demo.plugins.sideveil.local"
        assertTrue(PluginNetworkPolicy.isSameTrustedOrigin("https://demo.plugins.sideveil.local/index.html", expected))
        assertTrue(PluginNetworkPolicy.isSameTrustedOrigin("https://demo.plugins.sideveil.local:443/a?b=1", expected))
        assertFalse(PluginNetworkPolicy.isSameTrustedOrigin("https://demo.plugins.sideveil.local.evil.com/", expected))
        assertFalse(PluginNetworkPolicy.isSameTrustedOrigin("http://demo.plugins.sideveil.local/index.html", expected))
        assertFalse(PluginNetworkPolicy.isSameTrustedOrigin("https://other.plugins.sideveil.local/index.html", expected))
        assertFalse(PluginNetworkPolicy.isSameTrustedOrigin(null, expected))
    }
}
