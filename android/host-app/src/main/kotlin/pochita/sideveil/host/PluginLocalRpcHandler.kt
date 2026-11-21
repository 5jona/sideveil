package pochita.sideveil.host

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pochita.sideveil.protocol.PluginCapability
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.RpcError
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.RpcResponse
import pochita.sideveil.protocol.SideveilMethods
import pochita.sideveil.protocol.SideveilWireJson

/**
 * Handles host-local ACL-gated plugin RPCs (`ui.toast`, `clipboard.get`, `clipboard.set`,
 * `haptics.trigger`) without round-tripping through `sideveild`.
 */
internal class PluginLocalRpcHandler(
    private val context: Context,
    private val haptics: EdgeHapticsController,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun tryHandle(
        req: RpcRequest,
        descriptor: PluginDescriptor,
        sendReply: (String) -> Unit,
    ): Boolean {
        when (req.method) {
            SideveilMethods.UI_TOAST -> handleToast(req, descriptor, sendReply)
            SideveilMethods.CLIPBOARD_GET -> handleClipboardGet(req, descriptor, sendReply)
            SideveilMethods.CLIPBOARD_SET -> handleClipboardSet(req, descriptor, sendReply)
            SideveilMethods.HAPTICS_TRIGGER -> handleHaptics(req, descriptor, sendReply)
            else -> return false
        }
        return true
    }

    private fun handleToast(
        req: RpcRequest,
        descriptor: PluginDescriptor,
        sendReply: (String) -> Unit,
    ) {
        if (!hasCapability(descriptor, PluginCapability.TOAST)) {
            sendDenied(req.id, descriptor.id, "toast", sendReply)
            return
        }
        val msg =
            req.params
                ?.jsonObject
                ?.get("message")
                ?.jsonPrimitive
                ?.content
                .orEmpty()
        mainHandler.post {
            if (msg.isNotBlank()) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            sendOk(req.id, sendReply)
        }
    }

    private fun handleClipboardGet(
        req: RpcRequest,
        descriptor: PluginDescriptor,
        sendReply: (String) -> Unit,
    ) {
        if (!hasCapability(descriptor, PluginCapability.CLIPBOARD)) {
            sendDenied(req.id, descriptor.id, "clipboard", sendReply)
            return
        }
        mainHandler.post {
            val text =
                runCatching {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm
                        ?.primaryClip
                        ?.getItemAt(0)
                        ?.coerceToText(context)
                        ?.toString()
                }.getOrNull()
            val resp =
                RpcResponse(
                    id = req.id,
                    result = buildJsonObject { put("text", JsonPrimitive(text.orEmpty())) },
                )
            sendReply(SideveilWireJson.encodeToString(resp))
        }
    }

    private fun handleClipboardSet(
        req: RpcRequest,
        descriptor: PluginDescriptor,
        sendReply: (String) -> Unit,
    ) {
        if (!hasCapability(descriptor, PluginCapability.CLIPBOARD)) {
            sendDenied(req.id, descriptor.id, "clipboard", sendReply)
            return
        }
        val text =
            req.params
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content
                .orEmpty()
        mainHandler.post {
            runCatching {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("Sideveil Plugin", text))
            }
            sendOk(req.id, sendReply)
        }
    }

    private fun handleHaptics(
        req: RpcRequest,
        descriptor: PluginDescriptor,
        sendReply: (String) -> Unit,
    ) {
        if (!hasCapability(descriptor, PluginCapability.HAPTICS)) {
            sendDenied(req.id, descriptor.id, "haptics", sendReply)
            return
        }
        val kind =
            req.params
                ?.jsonObject
                ?.get("kind")
                ?.jsonPrimitive
                ?.content
                .orEmpty()
        mainHandler.post {
            when (kind) {
                "click" -> haptics.onThresholdCrossed()
                "heavy" -> haptics.onRepositionStart()
                "cancel" -> haptics.onCancel()
                else -> haptics.onRailSwitch()
            }
            sendOk(req.id, sendReply)
        }
    }

    private fun hasCapability(
        descriptor: PluginDescriptor,
        cap: PluginCapability,
    ): Boolean = descriptor.builtin || descriptor.capabilities.contains(cap)

    private fun sendOk(
        id: String,
        sendReply: (String) -> Unit,
    ) {
        val ok = RpcResponse(id = id, result = buildJsonObject { put("ok", JsonPrimitive(true)) })
        sendReply(SideveilWireJson.encodeToString(ok))
    }

    private fun sendDenied(
        id: String,
        pluginId: String,
        capName: String,
        sendReply: (String) -> Unit,
    ) {
        val denied = RpcResponse(id = id, error = RpcError(-32003, "Plugin '$pluginId' lacks '$capName' capability"))
        mainHandler.post { sendReply(SideveilWireJson.encodeToString(denied)) }
    }
}
