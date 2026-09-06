package foxlost.miracast.sink.media

import foxlost.miracast.sink.DebugEventCategory
import foxlost.miracast.sink.DebugEventLog
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/**
 * Process-local rendezvous used by the RTSP client before M6. Starting an
 * Activity is asynchronous, so a sleep is not a bind guarantee.
 */
object RtpEndpointRegistry {
    private val monitor = Object()
    private data class Bound(val token: String, val rtpPort: Int, val rtcpPort: Int?)
    @Volatile private var bound: Bound? = null

    fun publish(rtpPort: Int, rtcpPort: Int?) = publish("default", rtpPort, rtcpPort)

    fun publish(token: String, rtpPort: Int, rtcpPort: Int?) {
        synchronized(monitor) {
            bound = Bound(token, rtpPort, rtcpPort)
            monitor.notifyAll()
        }
    }

    fun clear() {
        synchronized(monitor) { bound = null }
    }

    fun clear(token: String) {
        synchronized(monitor) {
            if (bound?.token == token) bound = null
        }
    }

    fun await(rtpPort: Int, rtcpPort: Int?, timeoutMs: Long): Boolean =
        await("default", rtpPort, rtcpPort, timeoutMs)

    fun await(token: String, rtpPort: Int, rtcpPort: Int?, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(monitor) {
            while (bound?.let { it.token == token && it.rtpPort == rtpPort && it.rtcpPort == rtcpPort } != true) {
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
) {
    private var socket: DatagramSocket? = null
    private var rtcpSocket: DatagramSocket? = null
    @Volatile private var isRunning = false
    @Volatile var boundRtpPort: Int? = null
        private set
    @Volatile var boundRtcpPort: Int? = null
        private set
    @Volatile var receivedPackets: Long = 0
        private set
    @Volatile var lostPackets: Long = 0
        private set
    private var rxThread: Thread? = null
    private var rtcpThread: Thread? = null
    private var lastSequence: Int? = null

    fun start() {
        synchronized(this) {
            if (isRunning) return
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", rtpPort))
                    receiveBufferSize = 2 * 1024 * 1024
                }
                boundRtpPort = socket!!.localPort
                if (rtcpPort != null) {
                    rtcpSocket = DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress("0.0.0.0", rtcpPort))
                        receiveBufferSize = 256 * 1024
                    }
                    boundRtcpPort = rtcpSocket!!.localPort
                }
                isRunning = true
                rxThread = thread(name = "RtpReceiverThread") { receiveRtp() }
                DebugEventLog.record(DebugEventCategory.RTP, "RTP receiver bound RTP=$boundRtpPort RTCP=${boundRtcpPort ?: "none"}")
                if (rtcpPort != null) rtcpThread = thread(name = "RtcpReceiverThread") { receiveRtcp() }
                RtpEndpointRegistry.publish(sessionToken, boundRtpPort!!, boundRtcpPort)
            } catch (e: Exception) {
                isRunning = false
                RtpEndpointRegistry.clear(sessionToken)
                closeRtp()
                closeRtcp()
                Log.e(TAG, "Socket setup failed on ports $rtpPort/$rtcpPort: ${e.message}")
                DebugEventLog.record(DebugEventCategory.ERROR, "RTP receiver bind failed: ${e.message ?: "unknown"}")
            }
        }
    }

    private fun receiveRtp() {
        try {
            Log.d(TAG, "RTP UDP Listener active on 0.0.0.0:${boundRtpPort}")
            val buffer = ByteArray(65535)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isRunning) {
                try {
                    packet.setLength(buffer.size)
                    socket?.receive(packet)
                    val len = packet.length
                    val parsed = RtpPacket.parse(buffer, packet.offset, len)
                    if (parsed == null) {
                        Log.w(TAG, "Ignoring malformed RTP datagram length=$len")
                        continue
                    }
                    val expected = expectedSsrc
                    if (expected != null && parsed.ssrc != expected) {
                        Log.w(TAG, "Ignoring RTP packet from unexpected SSRC 0x${parsed.ssrc.toString(16)}")
                        continue
                    }
                    lastSequence?.let { previous ->
                        val gap = (parsed.sequenceNumber - previous) and 0xffff
                        if (gap in 2..0x7fff) lostPackets += gap - 1
                    }
                    lastSequence = parsed.sequenceNumber
                    receivedPackets++
                    DebugEventLog.recordOnce(
                        DebugEventCategory.RTP,
                        "first-rtp-$sessionToken",
                        "First RTP packet received SSRC=0x${parsed.ssrc.toString(16)}",
                    )
                    DebugEventLog.recordRateLimited(
                        DebugEventCategory.RTP,
                        "rtp-count-$sessionToken",
                        "RTP packets received=$receivedPackets lost=$lostPackets",
                    )
                    if (parsed.payloadLength > 0) {
                        tsDemuxer.processRtpPayload(buffer, parsed.payloadOffset, parsed.payloadLength)
                    }
                } catch (e: Exception) {
                    if (!isRunning) break
                    Log.e(TAG, "Error receiving RTP packet: ${e.message}")
                    DebugEventLog.recordRateLimited(DebugEventCategory.ERROR, "rtp-error-$sessionToken", "RTP packet processing error: ${e.message ?: "unknown"}")
                }
            }
        } catch (e: Exception) {
            if (isRunning) Log.e(TAG, "Socket setup failed on port $rtpPort: ${e.message}")
        } finally {
            isRunning = false
            RtpEndpointRegistry.clear(sessionToken)
            closeRtp()
    }

    }
    private fun receiveRtcp() {
        try {
            val buffer = ByteArray(65535)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isRunning) {
                try {
                    packet.setLength(buffer.size)
                    rtcpSocket?.receive(packet)
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
            isRunning = false
            RtpEndpointRegistry.clear(sessionToken)
            closeRtcp()
    }

    }
    fun stop() {
        isRunning = false
        DebugEventLog.record(DebugEventCategory.RTP, "RTP receiver stopped packets=$receivedPackets lost=$lostPackets")
        RtpEndpointRegistry.clear(sessionToken)
        closeRtp()
        closeRtcp()
        rxThread = null
        rtcpThread = null

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
