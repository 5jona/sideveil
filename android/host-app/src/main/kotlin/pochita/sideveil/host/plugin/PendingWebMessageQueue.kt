package pochita.sideveil.host.plugin

/**
 * Buffers pre-ready WebView messages while isolating RPC replies from evictable event bursts so
 * high-frequency host events can never evict an in-flight JSON-RPC response.
 */
class PendingWebMessageQueue(
    private val maxPendingEvents: Int = DEFAULT_MAX_PENDING_EVENTS,
    private val maxPendingResponses: Int = DEFAULT_MAX_PENDING_RESPONSES,
) {
    private val pendingResponses = ArrayDeque<String>()
    private val pendingEvents = ArrayDeque<String>(maxPendingEvents)

    /** Buffers an outbound JSON-RPC response up to [maxPendingResponses]. */
    fun enqueueResponse(rawJson: String) {
        if (maxPendingResponses <= 0) return
        if (pendingResponses.size >= maxPendingResponses) pendingResponses.removeFirst()
        pendingResponses.addLast(rawJson)
    }

    /** Buffers an outbound event frame, dropping the oldest queued event when full. */
    fun enqueueEvent(rawJson: String) {
        if (maxPendingEvents <= 0) return
        if (pendingEvents.size >= maxPendingEvents) pendingEvents.removeFirst()
        pendingEvents.addLast(rawJson)
    }

    /** Drains all buffered responses first, followed by buffered events in FIFO order. */
    fun drainAll(deliver: (String) -> Unit) {
        while (pendingResponses.isNotEmpty()) deliver(pendingResponses.removeFirst())
        while (pendingEvents.isNotEmpty()) deliver(pendingEvents.removeFirst())
    }

    /** Clears all queued responses and events. */
    fun clear() {
        pendingResponses.clear()
        pendingEvents.clear()
    }

    /** Current number of queued RPC responses. */
    fun responseCount(): Int = pendingResponses.size

    /** Current number of queued host events. */
    fun eventCount(): Int = pendingEvents.size

    companion object {
        const val DEFAULT_MAX_PENDING_EVENTS = 16
        const val DEFAULT_MAX_PENDING_RESPONSES = 64
    }
}
