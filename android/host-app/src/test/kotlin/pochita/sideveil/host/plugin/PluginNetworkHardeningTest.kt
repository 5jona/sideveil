package pochita.sideveil.host.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.protocol.PluginCapability
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginEmbedPrefs
import pochita.sideveil.protocol.PluginNetworkPrefs

class PluginNetworkHardeningTest {
    private fun trustedPlugin(
        vararg caps: PluginCapability,
        origins: List<String>,
        allowCleartext: Boolean = true,
    ): PluginDescriptor =
        PluginDescriptor(
            id = "com.example.trusted",
            name = "Trusted",
            version = "1.0.0",
            capabilities = caps.toList(),
            grantedCapabilities = caps.toList(),
            network = PluginNetworkPrefs(allowedOrigins = origins, allowCleartext = allowCleartext),
        )

    @Test
    fun `forbids external main-frame navigations for trusted plugins while permitting subresources`() {
        val plugin =
            trustedPlugin(
                PluginCapability.NETWORK_LOOPBACK,
                PluginCapability.NETWORK_LAN,
                PluginCapability.NETWORK_INTERNET,
                origins =
                    listOf(
                        "http://127.0.0.1:9090",
                        "http://192.168.1.10:8080",
                        "https://api.example.com",
                    ),
                allowCleartext = true,
            )

        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(plugin, "http://127.0.0.1:9090/page", isMainFrame = true))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(plugin, "http://192.168.1.10:8080/ui", isMainFrame = true))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(plugin, "https://api.example.com/login", isMainFrame = true))

        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(plugin, "http://127.0.0.1:9090/api", isMainFrame = false))
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(plugin, "http://192.168.1.10:8080/api", isMainFrame = false))
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(plugin, "https://api.example.com/v1", isMainFrame = false))
    }

    @Test
    fun `unwraps IPv4-mapped IPv6 literals for loopback private LAN and link-local classification`() {
        val mappedLoopback =
            listOf("::ffff:7f00:1", "[::ffff:7f00:1]", "::ffff:127.0.0.1", "[::ffff:127.0.0.1]", "0:0:0:0:0:ffff:127.0.0.2")
        for (host in mappedLoopback) {
            assertTrue("expected loopback for $host", PluginNetworkPolicy.isLoopbackHost(host))
            assertTrue("expected private/loopback for $host", PluginNetworkPolicy.isPrivateOrLoopbackHost(host))
        }

        val mappedPrivate =
            listOf(
                "::ffff:c0a8:101",
                "::ffff:192.168.1.1",
                "::ffff:0a00:0001",
                "::ffff:10.0.0.1",
                "::ffff:ac10:0001",
                "::ffff:172.16.0.1",
                "169.254.169.254",
                "::ffff:169.254.169.254",
                "::ffff:a9fe:a9fe",
            )
        for (host in mappedPrivate) {
            assertFalse("expected non-loopback for $host", PluginNetworkPolicy.isLoopbackHost(host))
            assertTrue("expected private/LAN for $host", PluginNetworkPolicy.isPrivateOrLoopbackHost(host))
        }

        assertFalse(PluginNetworkPolicy.isPrivateOrLoopbackHost("::ffff:8.8.8.8"))
        assertFalse(PluginNetworkPolicy.isPrivateOrLoopbackHost("::ffff:0808:0808"))

        val internetOnly =
            trustedPlugin(
                PluginCapability.NETWORK_INTERNET,
                origins =
                    listOf(
                        "https://[::ffff:7f00:1]:8443",
                        "https://[::ffff:127.0.0.1]:8443",
                        "https://[::ffff:c0a8:101]:8443",
                        "https://[::ffff:192.168.1.1]:8443",
                        "https://169.254.169.254",
                        "https://[::ffff:169.254.169.254]",
                    ),
                allowCleartext = false,
            )
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(internetOnly, "https://[::ffff:7f00:1]:8443/a", false))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(internetOnly, "https://[::ffff:127.0.0.1]:8443/a", false))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(internetOnly, "https://[::ffff:c0a8:101]:8443/a", false))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(internetOnly, "https://[::ffff:192.168.1.1]:8443/a", false))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(internetOnly, "https://169.254.169.254/latest/meta-data", false))
        assertFalse(PluginNetworkPolicy.shouldAllowNativeNetwork(internetOnly, "https://[::ffff:169.254.169.254]/meta", false))

        val loopbackOnly =
            trustedPlugin(
                PluginCapability.NETWORK_LOOPBACK,
                origins = listOf("http://[::ffff:127.0.0.1]:9090", "http://[::ffff:7f00:1]:9090"),
                allowCleartext = true,
            )
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(loopbackOnly, "http://[::ffff:127.0.0.1]:9090/health", false))
        assertTrue(PluginNetworkPolicy.shouldAllowNativeNetwork(loopbackOnly, "http://[::ffff:7f00:1]:9090/health", false))
    }

    @Test
    fun `rejects unspecified IPv4 and IPv6 addresses across all capabilities and embeds`() {
        val unspecifiedHosts = listOf("0.0.0.0", "::", "[::]", "0:0:0:0:0:0:0:0", "::ffff:0.0.0.0", "::ffff:0:0")
        for (host in unspecifiedHosts) {
            assertTrue("expected unspecified for $host", NetworkOriginParser.isUnspecifiedHost(host))
        }

        val unspecifiedUrls =
            listOf(
                "https://0.0.0.0:8443/api",
                "http://0.0.0.0:8080/api",
                "https://[::]:8443/api",
                "http://[::]:8080/api",
                "https://[::ffff:0.0.0.0]:8443/api",
                "https://[::ffff:0:0]:8443/api",
            )
        for (url in unspecifiedUrls) {
            assertNull("expected parseOrigin rejection for $url", NetworkOriginParser.parseOrigin(url))
        }

        val allNet =
            trustedPlugin(
                PluginCapability.NETWORK_LOOPBACK,
                PluginCapability.NETWORK_LAN,
                PluginCapability.NETWORK_INTERNET,
                origins = unspecifiedUrls,
                allowCleartext = true,
            )
        for (url in unspecifiedUrls) {
            assertFalse("expected network denial for $url", PluginNetworkPolicy.shouldAllowNativeNetwork(allNet, url, false))
        }

        val embed =
            PluginDescriptor(
                id = "com.example.embed",
                name = "Embed",
                version = "1.0.0",
                embed = PluginEmbedPrefs(url = "https://0.0.0.0:8443", allowedOrigins = unspecifiedUrls, allowCleartext = true),
            )
        for (url in unspecifiedUrls) {
            assertFalse("expected embed denial for $url", PluginNetworkPolicy.shouldAllowNativeNetwork(embed, url, true))
        }
    }
}
