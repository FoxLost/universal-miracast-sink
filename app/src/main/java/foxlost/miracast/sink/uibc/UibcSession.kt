package foxlost.miracast.sink.uibc

import android.util.Log
import foxlost.miracast.sink.DebugEventCategory
import foxlost.miracast.sink.DebugEventLog
import java.net.InetSocketAddress
import java.net.Socket

private fun logUibcState(message: String) {
    try { Log.i("MiracastUIBC", message) } catch (_: RuntimeException) { }
}

/** Source-owned UIBC TCP endpoint negotiated on RTSP. */
data class UibcEndpoint(
    val sourceAddress: String,
    val port: Int,
    val capabilities: Set<UibcCapability>,
    val generation: Long,
    val category: UibcCategory = UibcCategory.GENERIC,
    val path: UibcInputPath? = null,
    val hidType: UibcCapability? = null,
) {
    init {
        require(sourceAddress.isNotBlank() && port in 1..65535 && generation >= 0)
        require(capabilities.isNotEmpty())
        if (category == UibcCategory.HIDC) {
            require(path == UibcInputPath.USB && hidType != null && capabilities == setOf(hidType))
        } else require(path == null && hidType == null)
    }
}

enum class UibcCapability { SingleTouch, MultiTouch }
enum class TouchAction(val wireValue: Int) { DOWN(0), UP(1), MOVE(2) }
data class TouchPoint(val pointerId: Int, val x: Int, val y: Int) {
    init { require(pointerId in 0..255 && x in 0..65535 && y in 0..65535) }
}
data class TouchEvent(val action: TouchAction, val points: List<TouchPoint>) {
    init {
        require(points.isNotEmpty() && points.size <= HidTouchCodec.MAX_CONTACTS)
        for (i in points.indices) {
            for (j in 0 until i) require(points[i].pointerId != points[j].pointerId)
        }
    }
}

interface UibcSession {
    val endpoint: UibcEndpoint?
    fun start(endpoint: UibcEndpoint)
    fun submit(event: TouchEvent)
    fun reset()
    fun close(reason: String = "closed")
}

/** Bounded, gesture-safe queue: coalesce only an adjacent move, never across an edge. */
internal class TouchEventQueue(private val capacity: Int) {
    private val events = ArrayDeque<TouchEvent>()
    init { require(capacity > 0) }
    val isEmpty: Boolean get() = events.isEmpty()
    fun offer(event: TouchEvent): Boolean {
        val last = events.lastOrNull()
        if (event.action == TouchAction.MOVE && last?.action == TouchAction.MOVE &&
            last.points.size == event.points.size &&
            last.points.indices.all { last.points[it].pointerId == event.points[it].pointerId }) {
            events.removeLast()
            events.addLast(event)
            return true
        }
        if (events.size == capacity) return false
        events.addLast(event)
        return true
    }
    fun removeFirst(): TouchEvent = events.removeFirst()
    fun clear() = events.clear()
}

/** A single connection, no automatic reconnect. A terminal fault invalidates its registry entry. */
class TcpUibcSession(
    private val generation: Long,
    queueCapacity: Int = 64,
    private val connectTimeoutMs: Int = 5_000,
    private val socketFactory: ((String, Int) -> Socket)? = null,
    private val onClosed: (String) -> Unit = {},
) : UibcSession {
    private val lock = Object()
    private val queue = TouchEventQueue(queueCapacity)
    private val submitted = linkedMapOf<Int, TouchPoint>()
    @Volatile override var endpoint: UibcEndpoint? = null
        private set
    private var socket: Socket? = null
    private var closeDeadlineStarted = false
    private var worker: Thread? = null
    private var accepting = false
    private var closing = false
    private var stopped = false
    private var closeReason = "closed"

    init { require(generation >= 0 && connectTimeoutMs > 0) }

    override fun start(endpoint: UibcEndpoint) {
        require(endpoint.generation == generation) { "stale UIBC endpoint generation" }
        synchronized(lock) {
            if (stopped || closing || accepting) return
            this.endpoint = endpoint
            accepting = true
            worker = Thread({ runWriter(endpoint) }, "UIBC-$generation").also { it.isDaemon = true; it.start() }
        }
        logUibcState("UIBC session starting generation=$generation source=${endpoint.sourceAddress} port=${endpoint.port} category=${endpoint.category} type=${endpoint.hidType}")
    }

    override fun submit(event: TouchEvent) {
        synchronized(lock) {
            if (!accepting || closing || stopped) return
            val target = endpoint ?: return
            val single = UibcCapability.MultiTouch !in target.capabilities
            val points = if (single) {
                val primary = submitted.keys.firstOrNull()
                if (primary == null) {
                    if (event.action != TouchAction.DOWN) return
                    listOf(event.points.first())
                } else event.points.filter { it.pointerId == primary }.also { if (it.isEmpty()) return }
            } else event.points.toList()
            val normalized = TouchEvent(event.action, points)
            if (normalized.action == TouchAction.UP) points.forEach { submitted.remove(it.pointerId) }
            else {
                // Do not let a malformed caller accumulate unbounded contacts.
                if (points.count { it.pointerId !in submitted } + submitted.size > HidTouchCodec.MAX_CONTACTS) {
                    requestCloseLocked("too many touch contacts")
                    return
                }
                points.forEach { submitted[it.pointerId] = it }
            }
            if (!queue.offer(normalized)) requestCloseLocked("UIBC queue saturated")
            lock.notifyAll()
        }
    }

    /** Queue explicit UPs in order; a subsequent close releases wire state independently. */
    override fun reset() {
        synchronized(lock) {
            if (!accepting || closing || stopped || submitted.isEmpty()) return
            val release = TouchEvent(TouchAction.UP, submitted.values.toList())
            submitted.clear()
            if (!queue.offer(release)) requestCloseLocked("UIBC reset queue saturated")
            lock.notifyAll()
        }
    }

    private fun requestCloseLocked(reason: String) {
        accepting = false
        closing = true
        closeReason = reason
        queue.clear()
        submitted.clear()
        lock.notifyAll()
        if (!closeDeadlineStarted) {
            closeDeadlineStarted = true
            Thread({
                try { Thread.sleep(CLOSE_GRACE_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                finish(reason)
            }, "UIBC-close-$generation").also { it.isDaemon = true; it.start() }
        }
    }

    private fun runWriter(target: UibcEndpoint) {
        val wireActive = linkedMapOf<Int, TouchPoint>()
        var reason = "UIBC writer stopped"
        try {
            val opened = if (socketFactory != null) socketFactory.invoke(target.sourceAddress, target.port) else Socket()
            synchronized(lock) {
                if (stopped || closing) { opened.close(); return }
                socket = opened
            }
            if (!opened.isConnected) opened.connect(InetSocketAddress(target.sourceAddress, target.port), connectTimeoutMs)
            opened.tcpNoDelay = true
            val out = opened.getOutputStream()
            DebugEventLog.record(DebugEventCategory.UIBC, "UIBC connected generation=$generation category=${target.category}")
            logUibcState("UIBC TCP connected generation=$generation source=${target.sourceAddress} port=${target.port} category=${target.category}")
            Thread({
                try {
                    val input = opened.getInputStream()
                    // UIBC is sink→source. Drain unexpected bytes without buffering;
                    // EOF is terminal even when no touch is being submitted.
                    while (input.read() != -1) { }
                    finish("UIBC peer EOF")
                } catch (_: Exception) { finish("UIBC peer disconnected") }
            }, "UIBC-peer-$generation").also { it.isDaemon = true; it.start() }
            val hid = target.hidType?.let { HidTouchCodec(it) }
            if (hid != null) {
                out.write(hid.descriptorPacket())
                out.write(hid.contactMaximumPacket())
                out.flush()
                logUibcState("UIBC HID descriptor sent generation=$generation type=${target.hidType}")
                logUibcState("UIBC HID contact maximum sent generation=$generation reportId=${HidTouchCodec.MAX_CONTACTS_REPORT_ID} maximum=${if (target.hidType == UibcCapability.MultiTouch) HidTouchCodec.MAX_CONTACTS else 1}")
            }
            var firstInputSent = false
            while (true) {
                val event = synchronized(lock) {
                    while (queue.isEmpty && !closing && !stopped) lock.wait()
                    if (closing || stopped) null else queue.removeFirst()
                } ?: break
                out.write(hid?.encode(event) ?: UibcCodec.encodeTouch(event))
                out.flush()
                if (!firstInputSent) {
                    firstInputSent = true
                    logUibcState("UIBC first input report sent generation=$generation category=${target.category} type=${target.hidType}")
                }
                if (event.action == TouchAction.UP) event.points.forEach { wireActive.remove(it.pointerId) }
                else event.points.forEach { wireActive[it.pointerId] = it }
            }
            // Best effort neutral state before orderly close / saturation. This is
            // bounded by close's grace deadline even if the peer stops reading.
            if (wireActive.isNotEmpty()) {
                val release = TouchEvent(TouchAction.UP, wireActive.values.toList())
                out.write(hid?.encode(release) ?: UibcCodec.encodeTouch(release))
                out.flush()
            }
            reason = synchronized(lock) { closeReason }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            reason = "UIBC writer interrupted"
        } catch (e: Exception) {
            reason = "UIBC connection failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            finish(reason)
        }
    }

    private fun finish(reason: String) {
        val opened: Socket?
        synchronized(lock) {
            if (stopped) return
            stopped = true
            accepting = false
            closing = true
            queue.clear()
            submitted.clear()
            opened = socket
            socket = null
            lock.notifyAll()
        }
        try { opened?.close() } catch (_: Exception) { }
        UibcSessionRegistry.unregister(generation, this)
        logUibcState("UIBC session closed generation=$generation: $reason")
        try { DebugEventLog.record(DebugEventCategory.UIBC, "UIBC closed generation=$generation: $reason") } catch (_: RuntimeException) { }
        try { onClosed(reason) } catch (_: Exception) { }
    }

    override fun close(reason: String) {
        val current: Thread?
        synchronized(lock) {
            if (stopped) return
            requestCloseLocked(reason)
            current = worker
        }
        if (current != null && current != Thread.currentThread()) {
            try { current.join(CLOSE_GRACE_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        finish(reason) // Closes a still-connecting / blocked-write socket after bounded grace.
        current?.interrupt()
    }

    companion object { private const val CLOSE_GRACE_MS = 150L }
}

object UibcSessionRegistry {
    private val lock = Any()
    private val sessions = mutableMapOf<Long, UibcSession>()
    fun register(generation: Long, session: UibcSession) = synchronized(lock) {
        sessions[generation] = session
    }
    fun session(generation: Long): UibcSession? = synchronized(lock) { sessions[generation] }
    fun unregister(generation: Long, expected: UibcSession? = null) = synchronized(lock) {
        if (expected == null || sessions[generation] === expected) sessions.remove(generation)
    }
    fun clear() = synchronized(lock) { sessions.clear() }
}
