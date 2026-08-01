package foxlost.miracast.sink.media

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.concurrent.thread

class RtpReceiver(
    private val rtpPort: Int = 15550,
    private val tsDemuxer: TsDemuxer
) {
    private var socket: DatagramSocket? = null
    @Volatile private var isRunning = false
    private var rxThread: Thread? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        rxThread = thread(name = "RtpReceiverThread") {
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", rtpPort))
                    receiveBufferSize = 2 * 1024 * 1024 // 2MB buffer for high throughput
                }
                Log.d("RtpReceiver", "RTP UDP Listener active on 0.0.0.0:$rtpPort")

                val buffer = ByteArray(65535)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isRunning) {
                    try {
                        socket?.receive(packet)
                        val len = packet.length
                        if (len > 12) { // Minimum RTP header length is 12 bytes
                            val payloadOffset = 12
                            val payloadLen = len - payloadOffset
                            tsDemuxer.processRtpPayload(buffer, payloadOffset, payloadLen)
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                        Log.e("RtpReceiver", "Error receiving packet: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("RtpReceiver", "Socket setup failed on port $rtpPort: ${e.message}")
            } finally {
                stopInternal()
            }
        }
    }

    fun stop() {
        isRunning = false
        stopInternal()
    }

    private fun stopInternal() {
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        Log.d("RtpReceiver", "RTP Receiver stopped")
    }
}
