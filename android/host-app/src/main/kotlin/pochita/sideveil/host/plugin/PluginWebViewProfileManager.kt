package pochita.sideveil.host.plugin

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Manages per-plugin Chromium `ProfileStore` isolation (`WebViewFeature.MULTI_PROFILE`) and
 * cookie/WebStorage cleanup on plugin uninstall so plugins cannot leak cookies across shared
 * `*.plugins.sideveil.local` subdomains or leave orphaned state on disk.
 */
object PluginWebViewProfileManager {
    private const val TAG = "SideVeilWebProfile"
    private val NON_SAFE_CHARS = Regex("[^a-zA-Z0-9._-]")

    /** Returns the deterministic Chromium profile name for [pluginId] and [isUntrustedEmbed]. */
    fun profileNameFor(
        pluginId: String,
        isUntrustedEmbed: Boolean,
    ): String {
        val safe = pluginId.trim().replace(NON_SAFE_CHARS, "_").ifEmpty { "unknown" }
        val prefix = if (isUntrustedEmbed) "embed_" else "plugin_"
        return "$prefix$safe"
    }

    /** Attaches an isolated profile (when supported) and enforces strict cookie policy on [webView]. */
    internal fun configureProfileAndCookies(
        webView: WebView,
        pluginId: String,
        isUntrustedEmbed: Boolean,
    ) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val profileName = profileNameFor(pluginId, isUntrustedEmbed)
            runCatching {
                WebViewCompat.setProfile(webView, profileName)
                val profile = WebViewCompat.getProfile(webView)
                val cookies = profile.cookieManager
                cookies.setAcceptCookie(isUntrustedEmbed)
                cookies.setAcceptThirdPartyCookies(webView, false)
                return
            }.onFailure { err ->
                Log.w(TAG, "Multi-profile setup failed for $pluginId (falling back): ${err.message}")
            }
        }
        runCatching {
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(isUntrustedEmbed)
            cookies.setAcceptThirdPartyCookies(webView, false)
        }.onFailure { err ->
            Log.w(TAG, "CookieManager setup failed for $pluginId: ${err.message}")
        }
    }

    /** Flushes cookies for [pluginId]'s isolated profile (or default profile fallback). */
    internal fun flushCookies(
        webView: WebView?,
        pluginId: String,
        isUntrustedEmbed: Boolean,
    ) {
        if (!isUntrustedEmbed) return
        if (webView != null && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching {
                WebViewCompat.getProfile(webView).cookieManager.flush()
                return
            }.onFailure { err ->
                Log.w(TAG, "Profile cookie flush failed for $pluginId: ${err.message}")
            }
        }
        runCatching { CookieManager.getInstance().flush() }
            .onFailure { err -> Log.w(TAG, "CookieManager.flush failed for $pluginId: ${err.message}") }
    }

    /** Deletes isolated Chromium profile(s) and origin WebStorage when a plugin is uninstalled. */
    internal fun deletePluginData(
        pluginId: String,
        virtualOrigin: String,
    ) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            val store = runCatching { ProfileStore.getInstance() }.getOrNull()
            if (store != null) {
                for (embed in listOf(false, true)) {
                    val name = profileNameFor(pluginId, isUntrustedEmbed = embed)
                    runCatching {
                        store.deleteProfile(name)
                    }.onFailure { err ->
                        Log.d(TAG, "deleteProfile($name) skipped: ${err.message}")
                    }
                }
            }
        }
        runCatching {
            WebStorage.getInstance().deleteOrigin(virtualOrigin)
        }.onFailure { err ->
            Log.w(TAG, "WebStorage.deleteOrigin failed for $pluginId: ${err.message}")
        }
    }
}
