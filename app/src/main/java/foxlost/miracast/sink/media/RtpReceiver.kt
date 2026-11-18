package foxlost.miracast.sink.media

import foxlost.miracast.sink.DebugEventCategory
import foxlost.miracast.sink.DebugEventLog
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.concurrent.thread
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local rendezvous used by the RTSP client before M6. Starting an
 * Activity is asynchronous, so a sleep is not a bind guarantee.
 */
object RtpEndpointRegistry {
    private val monitor = Object()
    private data class Bound(val token: String, val rtpPort: Int, val rtcpPort: Int?, val owner: Any?)
    @Volatile private var bound: Bound? = null
    private var activeToken: String? = null
    private var launchFailure: Exception? = null
    private data class Closing(val rtpPort: Int?, val rtcpPort: Int?)
    private val closing = HashMap<Any, Closing>()

    internal fun retiring(owner: Any, rtpPort: Int?, rtcpPort: Int?) = synchronized(monitor) {
        closing[owner] = Closing(rtpPort, rtcpPort)
    }

    internal fun retired(owner: Any) = synchronized(monitor) {
        closing.remove(owner)
        monitor.notifyAll()
    }

    /** Socket close can defer native port release until the blocked receive returns. */
    internal fun awaitRetirement(rtpPort: Int, rtcpPort: Int?, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(monitor) {
            while (closing.values.any {
                it.rtpPort == rtpPort || it.rtcpPort == rtpPort ||
                    (rtcpPort != null && (it.rtpPort == rtcpPort || it.rtcpPort == rtcpPort))
            }) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
            }
            return true
        }
    }

    fun begin(token: String) {
        synchronized(monitor) {
            activeToken = token
            bound = null
            launchFailure = null
            monitor.notifyAll()
        }
    }

    fun isActive(token: String): Boolean = synchronized(monitor) {
        activeToken == token && launchFailure == null
    }

    fun end(token: String) {
        synchronized(monitor) {
            if (activeToken != token) return
            activeToken = null
            bound = null
            launchFailure = null
            monitor.notifyAll()
        }
    }

    fun failLaunch(token: String, error: Exception) {
        synchronized(monitor) {
            if (activeToken != token) return
            launchFailure = error
            bound = null
            monitor.notifyAll()
        }
    }

    fun publish(rtpPort: Int, rtcpPort: Int?) = publish("default", rtpPort, rtcpPort)

    fun publish(token: String, rtpPort: Int, rtcpPort: Int?, owner: Any? = null) {
        synchronized(monitor) {
            if (activeToken != token && !(activeToken == null && token == "default")) return
            if (launchFailure != null) return
            bound = Bound(token, rtpPort, rtcpPort, owner)
            monitor.notifyAll()
        }
    }

    fun clear() {
        synchronized(monitor) {
            bound = null
            activeToken = null
            launchFailure = null
            monitor.notifyAll()
        }
    }

    /** A retired receiver must not unpublish a replacement using the same RTSP token. */
    fun clear(token: String, owner: Any) {
        synchronized(monitor) {
            if (bound?.let { it.token == token && it.owner === owner } == true) bound = null
        }
    }

    fun await(rtpPort: Int, rtcpPort: Int?, timeoutMs: Long): Boolean =
        await("default", rtpPort, rtcpPort, timeoutMs)

    fun await(token: String, rtpPort: Int, rtcpPort: Int?, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(monitor) {
            while (bound?.let { it.token == token && it.rtpPort == rtpPort && it.rtcpPort == rtcpPort } != true) {
                if (activeToken != token && !(activeToken == null && token == "default")) return false
                if (activeToken == token) {
                    launchFailure?.let {
                        throw IllegalStateException("PlayerActivity launch failed: ${it.message ?: it.javaClass.simpleName}", it)
                    }
                }
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return false
                monitor.wait(remaining)
            }
            return true
        }
    }
}

/** Parsed RTP packet with a payload slice into the received datagram buffer. */
data class RtpPacket(
    val version: Int,
    val padding: Boolean,
    val extension: Boolean,
    val csrcCount: Int,
    val marker: Boolean,
    val payloadType: Int,
    val sequenceNumber: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payloadOffset: Int,
    val payloadLength: Int,
) {
    companion object {
        /** Returns null for malformed or unsupported (non-version-2) packets. */
        fun parse(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): RtpPacket? {
            if (offset < 0 || length < 12 || offset > data.size - length) return null
            val end = offset + length
            val b0 = data[offset].toInt() and 0xff
            val b1 = data[offset + 1].toInt() and 0xff
            val version = b0 ushr 6
            if (version != 2) return null
            val padding = (b0 and 0x20) != 0
            val extension = (b0 and 0x10) != 0
            val csrcCount = b0 and 0x0f
            var headerEnd = offset + 12 + csrcCount * 4
            if (headerEnd > end) return null
            if (extension) {
                if (headerEnd + 4 > end) return null
                val extensionLengthWords = ((data[headerEnd + 2].toInt() and 0xff) shl 8) or
                    (data[headerEnd + 3].toInt() and 0xff)
                val extensionBytes = extensionLengthWords * 4
                if (extensionBytes > end - (headerEnd + 4)) return null
                headerEnd += 4 + extensionBytes
            }
            var payloadEnd = end
            if (padding) {
                val paddingLength = data[end - 1].toInt() and 0xff
                if (paddingLength == 0 || paddingLength > end - headerEnd) return null
                payloadEnd -= paddingLength
            }
            if (payloadEnd < headerEnd) return null
            return RtpPacket(
                version = version,
                padding = padding,
                extension = extension,
                csrcCount = csrcCount,
                marker = (b1 and 0x80) != 0,
                payloadType = b1 and 0x7f,
                sequenceNumber = ((data[offset + 2].toInt() and 0xff) shl 8) or
                    (data[offset + 3].toInt() and 0xff),
                timestamp = u32(data, offset + 4),
                ssrc = u32(data, offset + 8),
                payloadOffset = headerEnd,
                payloadLength = payloadEnd - headerEnd,
            )
        }

        private fun u32(data: ByteArray, offset: Int): Long =
            ((data[offset].toLong() and 0xff) shl 24) or
                ((data[offset + 1].toLong() and 0xff) shl 16) or
                ((data[offset + 2].toLong() and 0xff) shl 8) or
                (data[offset + 3].toLong() and 0xff)
    }
}

class RtpReceiver(
    private val rtpPort: Int = 15550,
    private val tsDemuxer: TsDemuxer,
    private val rtcpPort: Int? = null,
    @Volatile var expectedSsrc: Long? = null,
    private val onRtcpPacket: ((ByteArray) -> Unit)? = null,
    private val onPortsBound: ((rtpPort: Int, rtcpPort: Int?) -> Unit)? = null,
    private val sessionToken: String = "default",
    private val onDiscontinuity: (() -> Unit)? = null,
) {
    private var socket: DatagramSocket? = null
    private var rtcpSocket: DatagramSocket? = null
    @Volatile private var isRunning = false
    private var retired = false
    private var workersRemaining = 0
    @Volatile var boundRtpPort: Int? = null
        private set
    @Volatile var boundRtcpPort: Int? = null
        private set
    @Volatile var receivedPackets: Long = 0
        private set
    @Volatile var lostPackets: Long = 0
        private set
    private var mediaThread: Thread? = null
    private class Datagram(
        val bytes: ByteArray = ByteArray(65535),
        var length: Int = 0,
        var parsed: RtpPacket? = null,
        var receivedAtNs: Long = 0L,
    )
    private val free = ArrayBlockingQueue<Datagram>(128)
    private val ready = ArrayBlockingQueue<Datagram>(128)
    private val overflow = AtomicBoolean(false)
    @Volatile var duplicatePackets = 0L; private set
    @Volatile var rejectedSsrcPackets = 0L; private set
    @Volatile var droppedDatagrams = 0L; private set

    fun start() {
        check(RtpEndpointRegistry.awaitRetirement(rtpPort, rtcpPort, 1_000L)) {
            "Previous UDP receiver has not finished closing"
        }
        synchronized(this) {
            if (isRunning) return
            check(!retired) { "A stopped RTP receiver cannot be restarted; create a new owner" }
            try {
                socket = DatagramSocket(null)
                socket!!.apply {
                    reuseAddress = false
                    bind(InetSocketAddress("0.0.0.0", rtpPort))
                    receiveBufferSize = 2 * 1024 * 1024
                }
                boundRtpPort = socket!!.localPort
                if (rtcpPort != null) {
                    rtcpSocket = DatagramSocket(null)
                    rtcpSocket!!.apply {
                        reuseAddress = false
                        bind(InetSocketAddress("0.0.0.0", rtcpPort))
                        receiveBufferSize = 256 * 1024
                    }
                    boundRtcpPort = rtcpSocket!!.localPort
                }
                workersRemaining = if (rtcpPort == null) 2 else 3
                isRunning = true
                free.clear(); ready.clear()
                repeat(128) { free.add(Datagram()) }
                overflow.set(false)
                mediaThread = thread(name = "RtpMediaThread") { consumeRtp() }
                thread(name = "RtpReceiverThread") { receiveRtp() }
                DebugEventLog.record(DebugEventCategory.RTP, "RTP receiver bound RTP=$boundRtpPort RTCP=${boundRtcpPort ?: "none"}")
                if (rtcpPort != null) thread(name = "RtcpReceiverThread") { receiveRtcp() }
                RtpEndpointRegistry.publish(sessionToken, boundRtpPort!!, boundRtcpPort, this)
            } catch (e: Exception) {
                retire()
                Log.e(TAG, "Socket setup failed on ports $rtpPort/$rtcpPort: ${e.message}")
                DebugEventLog.record(DebugEventCategory.ERROR, "RTP receiver bind failed: ${e.message ?: "unknown"}")
            }
        }
    }

    private fun receiveRtp() {
        try {
            val receiver = socket ?: return
            Log.d(TAG, "RTP UDP Listener active on 0.0.0.0:${boundRtpPort}")
            val overflowBuffer = ByteArray(65535)
            val packet = DatagramPacket(overflowBuffer, overflowBuffer.size)
            while (isRunning) {
                val datagram = free.poll()
                try {
                    val buffer = datagram?.bytes ?: overflowBuffer
                    packet.setData(buffer, 0, buffer.size)
                    receiver.receive(packet)
                    if (!isRunning) {
                        if (datagram != null) free.offer(datagram)
                        break
                    }
                    if (datagram == null) {
                        droppedDatagrams++; overflow.set(true)
                    } else {
                        datagram.length = packet.length
                        datagram.receivedAtNs = System.nanoTime()
                        if (!ready.offer(datagram)) {
                            free.offer(datagram); droppedDatagrams++; overflow.set(true)
                        }
                    }
                } catch (e: Exception) {
                    if (datagram != null) free.offer(datagram)
                    if (!isRunning) break
                    Log.e(TAG, "Error receiving RTP packet: ${e.message}")
                    DebugEventLog.recordRateLimited(DebugEventCategory.ERROR, "rtp-error-$sessionToken", "RTP packet processing error: ${e.message ?: "unknown"}")
                }
            }
        } catch (e: Exception) {
            if (isRunning) Log.e(TAG, "Socket setup failed on port $rtpPort: ${e.message}")
        } finally {
            workerFinished()
        }
    }

    private fun consumeRtp() {
        var activeSsrc: Long? = null
        var nextStatsNs = System.nanoTime() + 5_000_000_000L
        var maxQueueWaitNs = 0L
        val reorder = RtpReorderBuffer<Datagram>(
            deliver = { datagram ->
                try {
                    val parsed = datagram.parsed
                    if (isRunning && parsed != null && parsed.payloadLength > 0)
                        tsDemuxer.processRtpPayload(datagram.bytes, parsed.payloadOffset, parsed.payloadLength)
                } catch (e: Exception) {
                    tsDemuxer.discontinuity()
                    onDiscontinuity?.invoke()
                    Log.w(TAG, "Discarding damaged media payload", e)
                } finally { free.offer(datagram) }
            },
            discard = { free.offer(it) },
            onLoss = { count ->
                lostPackets += count
                tsDemuxer.discontinuity()
                onDiscontinuity?.invoke()
            },
        )
        try {
            while (isRunning) {
                if (overflow.getAndSet(false)) {
                    reorder.clear()
                    tsDemuxer.discontinuity()
                    onDiscontinuity?.invoke()
                }
                val datagram = ready.poll(10, TimeUnit.MILLISECONDS)
                val now = System.nanoTime()
                if (now >= nextStatsNs) {
                    nextStatsNs = now + 5_000_000_000L
                    val summary = "RTP generation=$sessionToken received=$receivedPackets lost=$lostPackets " +
                        "duplicate=$duplicatePackets dropped=$droppedDatagrams rejectedSsrc=$rejectedSsrcPackets " +
                        "queue=${ready.size} maxQueueWaitMs=${maxQueueWaitNs / 1_000_000L}"
                    Log.i(TAG, summary)
                    DebugEventLog.record(DebugEventCategory.RTP, summary)
                    maxQueueWaitNs = 0L
                }
                if (datagram == null) { reorder.tick(now); continue }
                val parsed = RtpPacket.parse(datagram.bytes, 0, datagram.length)
                if (parsed == null || parsed.payloadType != 33) { free.offer(datagram); continue }
                val expected = expectedSsrc ?: activeSsrc
                if (expected != null && parsed.ssrc != expected) {
                    rejectedSsrcPackets++; free.offer(datagram); continue
                }
                if (activeSsrc != null && activeSsrc != parsed.ssrc) {
                    reorder.clear(); tsDemuxer.discontinuity(); onDiscontinuity?.invoke()
                }
                activeSsrc = parsed.ssrc
                maxQueueWaitNs = maxOf(maxQueueWaitNs, now - datagram.receivedAtNs)
                receivedPackets++
                datagram.parsed = parsed
                reorder.offer(parsed.sequenceNumber, datagram, now)
                duplicatePackets = reorder.duplicates
                if (receivedPackets == 1L) {
                    DebugEventLog.record(DebugEventCategory.RTP, "First RTP packet received SSRC=0x${parsed.ssrc.toString(16)}")
                    Log.i(TAG, "First RTP packet generation=$sessionToken SSRC=0x${parsed.ssrc.toString(16)}")
                }
            }
        } catch (e: Exception) {
            if (isRunning) Log.e(TAG, "RTP media worker failed", e)
        } finally {
            reorder.clear()
            workerFinished()
        }
    }
    private fun receiveRtcp() {
        try {
            val receiver = rtcpSocket ?: return
            val buffer = ByteArray(65535)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isRunning) {
                try {
                    packet.setLength(buffer.size)
                    receiver.receive(packet)
                    if (!isRunning) break
                    val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    if (bytes.size >= 4 && (bytes[0].toInt() and 0xc0) == 0x80) {
                        onRtcpPacket?.invoke(bytes)
                    } else {
                        Log.w(TAG, "Ignoring malformed RTCP datagram length=${bytes.size}")
                    }
                } catch (e: Exception) {
                    if (!isRunning) break
                    Log.e(TAG, "Error receiving RTCP packet: ${e.message}")
                }
            }
        } catch (e: Exception) {
            if (isRunning) Log.e(TAG, "RTCP receive failed on port $rtcpPort: ${e.message}")
        } finally {
            workerFinished()
        }
    }
    fun stop() {
        retire()
        DebugEventLog.record(DebugEventCategory.RTP, "RTP receiver stopped packets=$receivedPackets lost=$lostPackets")
        Log.i(TAG, "RTP receiver stopped generation=$sessionToken received=$receivedPackets " +
            "lost=$lostPackets duplicate=$duplicatePackets dropped=$droppedDatagrams rejectedSsrc=$rejectedSsrcPackets")
    }

    /** Close the owned ports immediately; socket close/interrupt wake workers without a UI join. */
    @Synchronized private fun retire() {
        if (retired) return
        retired = true
        isRunning = false
        if (workersRemaining > 0) {
            RtpEndpointRegistry.retiring(this, boundRtpPort, boundRtcpPort)
        }
        RtpEndpointRegistry.clear(sessionToken, this)
        closeRtp()
        closeRtcp()
        mediaThread?.interrupt()
        boundRtpPort = null
        boundRtcpPort = null
    }

    @Synchronized private fun workerFinished() {
        retire()
        workersRemaining--
        if (workersRemaining == 0) RtpEndpointRegistry.retired(this)
    }
    private fun closeRtp() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun closeRtcp() {
        try { rtcpSocket?.close() } catch (_: Exception) {}
        rtcpSocket = null
    }

    private companion object { const val TAG = "RtpReceiver" }
}
