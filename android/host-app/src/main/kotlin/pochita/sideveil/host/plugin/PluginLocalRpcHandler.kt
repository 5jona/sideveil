package pochita.sideveil.host.plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import pochita.sideveil.host.overlay.EdgeHapticsController
import pochita.sideveil.protocol.HostLocalAuditPayload
import pochita.sideveil.protocol.PluginCapability
import pochita.sideveil.protocol.PluginCapabilityCodec
import pochita.sideveil.protocol.PluginDescriptor
import pochita.sideveil.protocol.RpcError
import pochita.sideveil.protocol.RpcRequest
import pochita.sideveil.protocol.RpcResponse
import pochita.sideveil.protocol.SideVeilErrorCodes
import pochita.sideveil.protocol.SideVeilMethods
import pochita.sideveil.protocol.SideVeilWireJson

/**
 * Handles host-local ACL-gated plugin RPCs (`ui.toast`, `clipboard.get`, `clipboard.set`,
 * `haptics.trigger`) enforcing Hub-granted capabilities (`grantedCapabilities`), foreground
 * visibility for `clipboard.get`, payload/rate limits, and unified `host.localAudit` emission.
 */
class PluginLocalRpcHandler internal constructor(
    private val actions: LocalRpcPlatformActions,
    private val postOnMain: (Runnable) -> Unit,
    private val onAudit: (HostLocalAuditPayload) -> Unit = {},
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onWarnLog: (String) -> Unit = { msg -> Log.w(TAG, msg) },
) {
    private val rateLimiter = LocalRpcRateLimiter(clockMs = clockMs)

    constructor(
        context: Context,
        haptics: EdgeHapticsController,
        onAudit: (HostLocalAuditPayload) -> Unit = {},
    ) : this(
        actions = AndroidLocalRpcActions(context, haptics),
        postOnMain = { runnable -> Handler(Looper.getMainLooper()).post(runnable) },
        onAudit = onAudit,
    )

    /** Attempts to handle [req] as a host-local capability RPC, returning `true` if handled. */
    fun tryHandle(
        req: RpcRequest,
        descriptor: PluginDescriptor,
        isForegroundVisible: Boolean = true,
        sendReply: (String) -> Unit,
    ): Boolean {
        when (req.method) {
            SideVeilMethods.UI_TOAST -> handleToast(req, descriptor, isForegroundVisible, sendReply)
            SideVeilMethods.CLIPBOARD_GET -> handleClipboardGet(req, descriptor, isForegroundVisible, sendReply)
            SideVeilMethods.CLIPBOARD_SET -> handleClipboardSet(req, descriptor, isForegroundVisible, sendReply)
            SideVeilMethods.HAPTICS_TRIGGER -> handleHaptics(req, descriptor, isForegroundVisible, sendReply)
            else -> return false
        }
        return true
    }

    private fun handleToast(
        req: RpcRequest,
        descriptor: PluginDescriptor,
        isForegroundVisible: Boolean,
        sendReply: (String) -> Unit,
    ) {
        if (!checkCapability(req, descriptor, PluginCapability.TOAST, sendReply)) return
        if (!checkForegroundVisible(req, descriptor, isForegroundVisible, sendReply)) return
        val msg =
            when (val parsed = parseOptionalStringParam(req.params, "message")) {
                is ParsedStringParam.Valid -> parsed.value
                is ParsedStringParam.Invalid -> return sendInvalidParams(req.id, parsed.message, sendReply)
            }
        if (msg.length > MAX_TOAST_CHARS) {
            return sendInvalidParams(req.id, "Toast message exceeds $MAX_TOAST_CHARS chars", sendReply)
        }
        if (!checkRateLimit(req, descriptor, MAX_TOAST_PER_SEC, sendReply)) return
        emitAudit(descriptor.id, req.method, allowed = true, detail = "granted")
        postOnMain {
            if (msg.isNotBlank()) actions.showToast(msg)
            sendOk(req.id, sendReply)
        }
    }

    private fun handleClipboardGet(
        req: RpcRequest,
        descriptor: PluginDescriptor,
        isForegroundVisible: Boolean,
        sendReply: (String) -> Unit,
    ) {
        if (!checkCapability(req, descriptor, PluginCapability.CLIPBOARD, sendReply)) return
        if (!checkForegroundVisible(req, descriptor, isForegroundVisible, sendReply)) return
        val paramsError = validateObjectOrNullParams(req.params)
        if (paramsError != null) return sendInvalidParams(req.id, paramsError, sendReply)
        if (!checkRateLimit(req, descriptor, MAX_CLIPBOARD_PER_SEC, sendReply)) return
        emitAudit(descriptor.id, req.method, allowed = true, detail = "granted")
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
        isForegroundVisible: Boolean,
        sendReply: (String) -> Unit,
    ) {
        if (!checkCapability(req, descriptor, PluginCapability.CLIPBOARD, sendReply)) return
        if (!checkForegroundVisible(req, descriptor, isForegroundVisible, sendReply)) return
        val text =
            when (val parsed = parseOptionalStringParam(req.params, "text")) {
                is ParsedStringParam.Valid -> parsed.value
                is ParsedStringParam.Invalid -> return sendInvalidParams(req.id, parsed.message, sendReply)
            }
        if (text.toByteArray(Charsets.UTF_8).size > MAX_CLIPBOARD_BYTES) {
            return sendInvalidParams(req.id, "Clipboard payload exceeds $MAX_CLIPBOARD_BYTES bytes", sendReply)
        }
        if (!checkRateLimit(req, descriptor, MAX_CLIPBOARD_PER_SEC, sendReply)) return
        emitAudit(descriptor.id, req.method, allowed = true, detail = "granted")
        postOnMain {
            runCatching { actions.writeClipboardText(text) }
            sendOk(req.id, sendReply)
        }
    }

    private fun handleHaptics(
        req: RpcRequest,
        descriptor: PluginDescriptor,
        isForegroundVisible: Boolean,
        sendReply: (String) -> Unit,
    ) {
        if (!checkCapability(req, descriptor, PluginCapability.HAPTICS, sendReply)) return
        if (!checkForegroundVisible(req, descriptor, isForegroundVisible, sendReply)) return
        val kind =
            when (val parsed = parseOptionalStringParam(req.params, "kind")) {
                is ParsedStringParam.Valid -> parsed.value
                is ParsedStringParam.Invalid -> return sendInvalidParams(req.id, parsed.message, sendReply)
            }
        if (!checkRateLimit(req, descriptor, MAX_HAPTICS_PER_SEC, sendReply)) return
        emitAudit(descriptor.id, req.method, allowed = true, detail = "granted")
        postOnMain {
            actions.triggerHaptics(kind)
            sendOk(req.id, sendReply)
        }
    }

    private fun checkForegroundVisible(
        req: RpcRequest,
        descriptor: PluginDescriptor,
        isForegroundVisible: Boolean,
        sendReply: (String) -> Unit,
    ): Boolean {
        if (isForegroundVisible) return true
        onWarnLog("Denied background ${req.method} for plugin=${descriptor.id}")
        emitAudit(descriptor.id, req.method, allowed = false, detail = "not-visible")
        val denied =
            RpcResponse(
                id = req.id,
                error =
                    RpcError(
                        SideVeilErrorCodes.PERMISSION_DENIED,
                        "Plugin '${descriptor.id}' must be visible in foreground to call '${req.method}'",
                    ),
            )
        postOnMain { sendReply(SideVeilWireJson.encodeToString(denied)) }
        return false
    }

    private fun checkCapability(
        req: RpcRequest,
        descriptor: PluginDescriptor,
        cap: PluginCapability,
        sendReply: (String) -> Unit,
    ): Boolean {
        if (descriptor.hasGrantedCapability(cap)) return true
        val capWire = PluginCapabilityCodec.toWire(cap)
        onWarnLog("Denied local RPC method=${req.method} plugin=${descriptor.id} (missing granted '$capWire')")
        emitAudit(descriptor.id, req.method, allowed = false, detail = "missing-capability:$capWire")
        val denied =
            RpcResponse(
                id = req.id,
                error = RpcError(SideVeilErrorCodes.PERMISSION_DENIED, "Plugin '${descriptor.id}' lacks '$capWire' capability"),
            )
        postOnMain { sendReply(SideVeilWireJson.encodeToString(denied)) }
        return false
    }

    private fun checkRateLimit(
        req: RpcRequest,
        descriptor: PluginDescriptor,
        maxPerSec: Int,
        sendReply: (String) -> Unit,
    ): Boolean {
        if (rateLimiter.allow(descriptor.id, req.method, maxPerSec)) return true
        onWarnLog("Rate-limited local RPC method=${req.method} plugin=${descriptor.id} (limit=$maxPerSec/s)")
        emitAudit(descriptor.id, req.method, allowed = false, detail = "rate-limited")
        val limited =
            RpcResponse(
                id = req.id,
                error = RpcError(SideVeilErrorCodes.RATE_LIMITED, "Rate limit exceeded for '${req.method}'"),
            )
        postOnMain { sendReply(SideVeilWireJson.encodeToString(limited)) }
        return false
    }

    private fun emitAudit(
        pluginId: String,
        method: String,
        allowed: Boolean,
        detail: String,
    ) = onAudit(HostLocalAuditPayload(pluginId = pluginId, method = method, allowed = allowed, detail = detail))

    private fun sendOk(
        id: String,
        sendReply: (String) -> Unit,
    ) = sendReply(SideVeilWireJson.encodeToString(RpcResponse(id = id, result = buildJsonObject { put("ok", JsonPrimitive(true)) })))

    private fun sendInvalidParams(
        id: String,
        reason: String,
        sendReply: (String) -> Unit,
    ) = postOnMain {
        sendReply(SideVeilWireJson.encodeToString(RpcResponse(id = id, error = RpcError(SideVeilErrorCodes.INVALID_PARAMS, reason))))
    }

    companion object {
        private const val TAG = "SideVeilLocalRpc"
        const val MAX_CLIPBOARD_BYTES = 64 * 1024
        const val MAX_TOAST_CHARS = 240
        const val MAX_CLIPBOARD_PER_SEC = 5
        const val MAX_TOAST_PER_SEC = 2
        const val MAX_HAPTICS_PER_SEC = 10

        internal fun validateObjectOrNullParams(params: JsonElement?): String? =
            if (params == null || params is JsonNull || params is JsonObject) null else "RPC params must be a JSON object"

        internal fun parseOptionalStringParam(
            params: JsonElement?,
            key: String,
        ): ParsedStringParam {
            if (params == null || params is JsonNull) return ParsedStringParam.Valid("")
            val obj = params as? JsonObject ?: return ParsedStringParam.Invalid("RPC params must be a JSON object")
            val field = obj[key]
            if (field == null || field is JsonNull) return ParsedStringParam.Valid("")
            val primitive = field as? JsonPrimitive
            if (primitive == null || !primitive.isString) return ParsedStringParam.Invalid("Parameter '$key' must be a string")
            return ParsedStringParam.Valid(primitive.content)
        }
    }
}
