package pochita.sideveil.host.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.PluginEmbedPrefs
import pochita.sideveil.protocol.PluginNetworkPrefs
import pochita.sideveil.protocol.PluginSurfacePrefs

class PluginWebViewSettingsTest {
    private fun baseDescriptor(): PluginDescriptor =
        PluginDescriptor(
            id = "strata.manager",
            name = "Strata Manager",
            version = "1.0.0",
            entry = "web/index.html",
            assetRevision = "0123456789abcdef-120",
        )

    @Test
    fun `shouldReloadOnDescriptorChange triggers on assetRevision or version updates`() {
        val prev = baseDescriptor()

        assertTrue(
            PluginWebViewSettings.shouldReloadOnDescriptorChange(
                prev,
                prev.copy(assetRevision = "fedcba9876543210-128"),
            ),
        )
        assertTrue(
            PluginWebViewSettings.shouldReloadOnDescriptorChange(
                prev,
                prev.copy(version = "1.0.1"),
            ),
        )
        assertTrue(
            PluginWebViewSettings.shouldReloadOnDescriptorChange(
                prev,
                prev.copy(entry = "web/app.html"),
            ),
        )
        assertTrue(
            PluginWebViewSettings.shouldReloadOnDescriptorChange(
                prev,
                prev.copy(devServerUrl = "http://127.0.0.1:5175"),
            ),
        )
        assertTrue(
            PluginWebViewSettings.shouldReloadOnDescriptorChange(
                prev,
                prev.copy(surface = PluginSurfacePrefs(crossOriginIsolated = true)),
            ),
        )
        assertTrue(
            PluginWebViewSettings.shouldReloadOnDescriptorChange(
                prev,
                prev.copy(network = PluginNetworkPrefs(spaFallback = true)),
            ),
        )
        assertTrue(
            PluginWebViewSettings.shouldReloadOnDescriptorChange(
                prev.copy(embed = PluginEmbedPrefs(url = "http://ha.local:8123")),
                prev.copy(embed = PluginEmbedPrefs(url = "http://ha.local:8123", remoteUrl = "https://ha.example.com")),
            ),
        )
    }

    @Test
    fun `shouldReloadOnDescriptorChange ignores non-bundle metadata updates`() {
        val prev = baseDescriptor()

        assertFalse(
            PluginWebViewSettings.shouldReloadOnDescriptorChange(
                prev,
                prev.copy(name = "Strata Renamed", priority = 99, enabled = true, global = true),
            ),
        )
    }
}
