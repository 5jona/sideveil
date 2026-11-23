package pochita.sideveil.host.plugin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import pochita.sideveil.host.overlay.EdgeHapticsController
import pochita.sideveil.protocol.PluginCapability
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.RpcError
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.RpcResponse
import pochita.sideveil.protocol.SideVeilErrorCodes
import pochita.sideveil.protocol.SideVeilMethods
import pochita.sideveil.protocol.SideVeilWireJson

/**
 * Platform side-effects required by [PluginLocalRpcHandler], abstracted for unit testing.
 */
interface LocalRpcPlatformActions {
    fun showToast(message: String)

    fun readClipboardText(): String?

    fun writeClipboardText(text: String)

    fun triggerHaptics(kind: String)
}

internal sealed interface ParsedStringParam {
    data class Valid(
        val value: String,
    ) : ParsedStringParam

    data class Invalid(
        val message: String,
    ) : ParsedStringParam
}

/**
 * Handles host-local ACL-gated plugin RPCs (`ui.toast`, `clipboard.get`, `clipboard.set`,
 * `haptics.trigger`) without round-tripping through `sideveild`.
 */
class PluginLocalRpcHandler internal constructor(
    private val actions: LocalRpcPlatformActions,
    private val postOnMain: (Runnable) -> Unit,
) {
    constructor(
        context: Context,
        haptics: EdgeHapticsController,
    ) : this(
        actions = AndroidLocalRpcActions(context, haptics),
        postOnMain = { runnable -> Handler(Looper.getMainLooper()).post(runnable) },
    )

    /** Attempts to handle [req] as a host-local capability RPC, returning `true` if handled. */
    fun tryHandle(
        req: RpcRequest,
        descriptor: PluginDescriptor,
        sendReply: (String) -> Unit,
    ): Boolean {
        when (req.method) {
            SideVeilMethods.UI_TOAST -> handleToast(req, descriptor, sendReply)
            SideVeilMethods.CLIPBOARD_GET -> handleClipboardGet(req, descriptor, sendReply)
            SideVeilMethods.CLIPBOARD_SET -> handleClipboardSet(req, descriptor, sendReply)
            SideVeilMethods.HAPTICS_TRIGGER -> handleHaptics(req, descriptor, sendReply)
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
            when (val parsed = parseOptionalStringParam(req.params, "message")) {
                is ParsedStringParam.Valid -> {
                    parsed.value
                }

                is ParsedStringParam.Invalid -> {
                    sendInvalidParams(req.id, parsed.message, sendReply)
                    return
                }
            }
        postOnMain {
            if (msg.isNotBlank()) actions.showToast(msg)
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
        val paramsError = validateObjectOrNullParams(req.params)
        if (paramsError != null) {
            sendInvalidParams(req.id, paramsError, sendReply)
            return
        }
        postOnMain {
            val text = runCatching { actions.readClipboardText() }.getOrNull()
            val resp =
                RpcResponse(
                    id = req.id,
                    result = buildJsonObject { put("text", JsonPrimitive(text.orEmpty())) },
                )
            sendReply(SideVeilWireJson.encodeToString(resp))
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
            when (val parsed = parseOptionalStringParam(req.params, "text")) {
                is ParsedStringParam.Valid -> {
                    parsed.value
                }

                is ParsedStringParam.Invalid -> {
                    sendInvalidParams(req.id, parsed.message, sendReply)
                    return
                }
            }
        postOnMain {
            runCatching { actions.writeClipboardText(text) }
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
            when (val parsed = parseOptionalStringParam(req.params, "kind")) {
                is ParsedStringParam.Valid -> {
                    parsed.value
                }

                is ParsedStringParam.Invalid -> {
                    sendInvalidParams(req.id, parsed.message, sendReply)
                    return
                }
            }
        postOnMain {
            actions.triggerHaptics(kind)
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
        sendReply(SideVeilWireJson.encodeToString(ok))
    }

    private fun sendDenied(
        id: String,
        pluginId: String,
        capName: String,
        sendReply: (String) -> Unit,
    ) {
        val denied =
            RpcResponse(
                id = id,
                error = RpcError(SideVeilErrorCodes.PERMISSION_DENIED, "Plugin '$pluginId' lacks '$capName' capability"),
            )
        postOnMain { sendReply(SideVeilWireJson.encodeToString(denied)) }
    }

    private fun sendInvalidParams(
        id: String,
        reason: String,
        sendReply: (String) -> Unit,
    ) {
        val invalid = RpcResponse(id = id, error = RpcError(SideVeilErrorCodes.INVALID_PARAMS, reason))
        postOnMain { sendReply(SideVeilWireJson.encodeToString(invalid)) }
    }

    companion object {
        internal fun validateObjectOrNullParams(params: JsonElement?): String? =
            if (params == null || params is JsonNull || params is JsonObject) {
                null
            } else {
                "RPC params must be a JSON object"
            }

        internal fun parseOptionalStringParam(
            params: JsonElement?,
            key: String,
        ): ParsedStringParam {
            if (params == null || params is JsonNull) return ParsedStringParam.Valid("")
            val obj = params as? JsonObject ?: return ParsedStringParam.Invalid("RPC params must be a JSON object")
            val field = obj[key]
            if (field == null || field is JsonNull) return ParsedStringParam.Valid("")
            val primitive = field as? JsonPrimitive
            if (primitive == null || !primitive.isString) {
                return ParsedStringParam.Invalid("Parameter '$key' must be a string")
            }
            return ParsedStringParam.Valid(primitive.content)
        }
    }
}

private class AndroidLocalRpcActions(
    private val context: Context,
    private val haptics: EdgeHapticsController,
) : LocalRpcPlatformActions {
    override fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    override fun readClipboardText(): String? {
        val cm = context.getSystemService(ClipboardManager::class.java)
        return cm
            ?.primaryClip
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
    }

    override fun writeClipboardText(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("SideVeil Plugin", text))
    }

    override fun triggerHaptics(kind: String) {
        when (kind) {
            "click" -> haptics.onThresholdCrossed()
            "heavy" -> haptics.onRepositionStart()
            "cancel" -> haptics.onCancel()
            else -> haptics.onRailSwitch()
        }
    }
}
