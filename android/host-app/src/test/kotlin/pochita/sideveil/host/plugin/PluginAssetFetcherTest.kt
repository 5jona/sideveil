package pochita.sideveil.host.plugin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class PluginAssetFetcherTest {
    @Test
    fun `injects COOP COEP CORP headers strictly when crossOriginIsolated is enabled`() {
        val plain = PluginAssetFetcher.buildResponseHeaders(crossOriginIsolated = false)
        assertEquals("no-store", plain["Cache-Control"])
        assertFalse(plain.containsKey("Cross-Origin-Opener-Policy"))
        assertFalse(plain.containsKey("Cross-Origin-Embedder-Policy"))

        val isolated = PluginAssetFetcher.buildResponseHeaders(crossOriginIsolated = true)
        assertEquals("same-origin", isolated["Cross-Origin-Opener-Policy"])
        assertEquals("require-corp", isolated["Cross-Origin-Embedder-Policy"])
        assertEquals("same-origin", isolated["Cross-Origin-Resource-Policy"])
    }

    @Test
    fun `restricts devServerUrl strictly to loopback http or https addresses and rejects SSRF`() {
        assertTrue(PluginAssetFetcher.isValidLoopbackDevUrl("http://127.0.0.1:5175"))
        assertTrue(PluginAssetFetcher.isValidLoopbackDevUrl("http://localhost:3000/"))
        assertTrue(PluginAssetFetcher.isValidLoopbackDevUrl("https://localhost:8443/app"))
        assertTrue(PluginAssetFetcher.isValidLoopbackDevUrl("http://[::1]:5173"))
        assertTrue(PluginAssetFetcher.isValidLoopbackDevUrl("http://127.0.0.1"))

        val rejected =
            listOf(
                "",
                "   ",
                "http://127.0.0.1:80@attacker.example",
                "http://localhost@evil.example:5173",
                "http://user:pass@127.0.0.1:5173",
                "http://127.0.0.1:0",
                "http://127.0.0.1:99999",
                "http://127.0.0.1:",
                "http://127.0.0.1:abc",
                "http://127.0.0.1?@attacker.example",
                "http://127.0.0.1#frag",
                "http://127.0.0.1\\attacker.example",
                "http://127.0.0.1:5173/\r\nX-Inject: 1",
                "http://127.0.0.1:5173/with space",
                "http://192.168.1.2:5175",
                "http://[::2]:5173",
                "https://evil.example.com",
                "file:///etc/passwd",
                "ftp://127.0.0.1:5173",
            )
        for (url in rejected) {
            assertFalse("expected rejection for $url", PluginAssetFetcher.isValidLoopbackDevUrl(url))
        }
    }

    @Test
    fun `readBoundedBytes enforces exact byte cap without overflowing`() {
        val exact = PluginAssetFetcher.readBoundedBytes(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), maxBytes = 4)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), exact)

        val overLimit = PluginAssetFetcher.readBoundedBytes(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), maxBytes = 4)
        assertNull(overLimit)

        val empty = PluginAssetFetcher.readBoundedBytes(ByteArrayInputStream(ByteArray(0)), maxBytes = 4)
        assertArrayEquals(ByteArray(0), empty)
    }

    @Test
    fun `resolves extended static web asset MIME types accurately`() {
        assertEquals("image/x-icon", PluginAssetFetcher.mimeTypeFor("favicon.ico"))
        assertEquals("font/woff", PluginAssetFetcher.mimeTypeFor("fonts/inter.woff"))
        assertEquals("font/ttf", PluginAssetFetcher.mimeTypeFor("fonts/mono.ttf"))
        assertEquals("font/otf", PluginAssetFetcher.mimeTypeFor("fonts/display.otf"))
        assertEquals("application/manifest+json", PluginAssetFetcher.mimeTypeFor("site.webmanifest"))
        assertEquals("image/avif", PluginAssetFetcher.mimeTypeFor("hero.avif?v=2"))
        assertEquals("application/json", PluginAssetFetcher.mimeTypeFor("assets/app.js.map"))
        assertEquals("text/plain", PluginAssetFetcher.mimeTypeFor("robots.txt"))
        assertEquals("application/xml", PluginAssetFetcher.mimeTypeFor("feed.xml"))
    }
}
