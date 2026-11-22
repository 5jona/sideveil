package pochita.sideveil.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun `restricts devServerUrl strictly to loopback http addresses`() {
        assertTrue(PluginAssetFetcher.isValidLoopbackDevUrl("http://127.0.0.1:5175"))
        assertTrue(PluginAssetFetcher.isValidLoopbackDevUrl("http://localhost:3000/"))
        assertFalse(PluginAssetFetcher.isValidLoopbackDevUrl("http://192.168.1.2:5175"))
        assertFalse(PluginAssetFetcher.isValidLoopbackDevUrl("https://evil.example.com"))
        assertFalse(PluginAssetFetcher.isValidLoopbackDevUrl("file:///etc/passwd"))
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
