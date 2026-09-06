package foxlost.miracast.sink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Categories shown by the in-app diagnostic panel. */
enum class DebugEventCategory {
    SINK,
    P2P,
    WFD,
    DHCP,
    RTSP,
    RTP,
    MEDIA,
    ERROR,
}

data class DebugEvent(
    val timestampMs: Long,
    val category: DebugEventCategory,
    val message: String,
)

/**
 * Process-local diagnostic sink for the active sink session.
 *
 * This is deliberately not a general application event bus: it retains only a
 * small, in-memory tail and is cleared when the user starts a new diagnostic
 * view/session. All methods are safe to call from protocol and media worker
 * threads.
 */
object DebugEventLog {
    const val MAX_EVENTS = 12

    private val lock = Any()
    private val _events = MutableStateFlow<List<DebugEvent>>(emptyList())
    private val onceKeys = mutableSetOf<String>()
    private val rateLimitedAt = mutableMapOf<String, Long>()

    /** Newest event is the last item; at most [MAX_EVENTS] items are retained. */
    val events: StateFlow<List<DebugEvent>> = _events.asStateFlow()

    /** Append one event, evicting the oldest event when the cap is reached. */
    fun record(
        category: DebugEventCategory,
        message: String,
        timestampMs: Long = System.currentTimeMillis(),
    ): DebugEvent = synchronized(lock) {
        val event = DebugEvent(timestampMs, category, message)
        _events.value = (_events.value + event).takeLast(MAX_EVENTS)
        event
    }

    /** Append only the first event for [key] until [clear] is called. */
    fun recordOnce(
        category: DebugEventCategory,
        key: String,
        message: String,
        timestampMs: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        if (!onceKeys.add(key)) return@synchronized false
        appendLocked(DebugEvent(timestampMs, category, message))
        true
    }

    /**
     * Append at most one event for [key] during [intervalMs]. Suppressed calls
     * are intentionally not retained, keeping noisy packet paths bounded.
     */
    fun recordRateLimited(
        category: DebugEventCategory,
        key: String,
        message: String,
        intervalMs: Long = DEFAULT_RATE_LIMIT_MS,
        timestampMs: Long = System.currentTimeMillis(),
    ): Boolean = synchronized(lock) {
        require(intervalMs >= 0L) { "intervalMs must be non-negative" }
        val previous = rateLimitedAt[key]
        if (previous != null && timestampMs - previous < intervalMs) return@synchronized false
        rateLimitedAt[key] = timestampMs
        appendLocked(DebugEvent(timestampMs, category, message))
        true
    }

    /** Clear retained events and all dedupe/rate-limit state. */
    fun clear() = synchronized(lock) {
        _events.value = emptyList()
        onceKeys.clear()
        rateLimitedAt.clear()
    }

    private fun appendLocked(event: DebugEvent) {
        _events.value = (_events.value + event).takeLast(MAX_EVENTS)
    }

    private const val DEFAULT_RATE_LIMIT_MS = 5_000L
}
