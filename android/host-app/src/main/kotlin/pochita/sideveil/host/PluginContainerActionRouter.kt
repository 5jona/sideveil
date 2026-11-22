package pochita.sideveil.host

import android.util.Log
import pochita.sideveil.protocol.ContainerActionPayload
import pochita.sideveil.protocol.PluginPresentationMode
import pochita.sideveil.protocol.PluginSurfaceBackdrop

/**
 * Decodes and routes `container.action` payloads issued by a plugin WebView to local instance
 * state setters and host-level [PluginContainerCallbacks].
 */
internal object PluginContainerActionRouter {
    private const val TAG = "SideVeilPluginWebView"

    fun dispatch(
        pluginId: String,
        action: ContainerActionPayload?,
        callbacks: PluginContainerCallbacks,
        onSizeOverride: (PluginSizeOverride) -> Unit,
        onBackIntercept: (Boolean) -> Unit,
        onBackdrop: (PluginSurfaceBackdrop) -> Unit,
        onPresentationMode: (PluginPresentationMode) -> Unit,
    ) {
        when (action?.type) {
            "dismiss" -> {
                callbacks.onDismissRequested(pluginId)
            }

            "requestImeFocus" -> {
                callbacks.onImeFocusRequested(pluginId, action.focused == true)
            }

            "syncTheme" -> {
                val payload = action.payload ?: return
                callbacks.onAppearanceSynced(pluginId, payload.themeId, payload.colorScheme)
            }

            "setPreferredSize" -> {
                val next =
                    PluginSizeOverride(
                        widthDp = action.widthDp,
                        heightDp = action.heightDp,
                        heightPercent = action.heightPercent,
                    )
                onSizeOverride(next)
                callbacks.onPreferredSizeChanged(pluginId, next)
            }

            "setBackIntercept" -> {
                onBackIntercept(action.enabled == true)
            }

            "setSurfaceBackdrop" -> {
                val parsed = parseBackdrop(action.backdrop) ?: return
                onBackdrop(parsed)
                callbacks.onBackdropChanged(pluginId, parsed)
            }

            "setPresentationMode" -> {
                val parsed = parsePresentationMode(action.mode) ?: return
                onPresentationMode(parsed)
                callbacks.onPresentationModeChanged(pluginId, parsed)
            }

            "setPinnedBounds" -> {
                callbacks.onPinnedBoundsChanged(
                    pluginId,
                    PinnedBoundsSpec(
                        widthDp = action.widthDp,
                        heightDp = action.heightDp,
                        xPercent = action.xPercent,
                        yPercent = action.yPercent,
                        clickThrough = action.clickThrough,
                    ),
                )
            }

            "setEdgeStatus" -> {
                callbacks.onEdgeStatusChanged(pluginId, action.status)
            }

            else -> {
                Log.d(TAG, "Ignored unknown container.action type=${action?.type}")
            }
        }
    }

    private fun parseBackdrop(raw: String?): PluginSurfaceBackdrop? =
        when (raw) {
            "native-glass" -> PluginSurfaceBackdrop.NATIVE_GLASS
            "transparent" -> PluginSurfaceBackdrop.TRANSPARENT
            "opaque" -> PluginSurfaceBackdrop.OPAQUE
            else -> null
        }

    private fun parsePresentationMode(raw: String?): PluginPresentationMode? =
        when (raw) {
            "drawer" -> PluginPresentationMode.DRAWER
            "peek" -> PluginPresentationMode.PEEK
            "pinned" -> PluginPresentationMode.PINNED
            "wide" -> PluginPresentationMode.WIDE
            "native-hud" -> PluginPresentationMode.NATIVE_HUD
            else -> null
        }
}
