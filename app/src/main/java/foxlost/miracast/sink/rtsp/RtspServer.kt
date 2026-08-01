package foxlost.miracast.sink.rtsp

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class RtspServer(
    private val port: Int = 7236,
    private val onPlayTriggered: () -> Unit
) {
    private val TAG = "MiracastRTSP"
    private var serverSocket: ServerSocket? = null
    private var running = false
    private var handlerThread: Thread? = null

    fun start(): Boolean {
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(port))
            }
            running = true
            handlerThread = thread(name = "RTSP-Server") {
                while (running) {
                    try {
                        val socket = serverSocket?.accept() ?: continue
                        Log.d(TAG, "RTSP client connected from: ${socket.inetAddress.hostAddress}")
                        val connection = RtspConnection(socket, onPlayTriggered)
                        connection.start()
                    } catch (e: Exception) {
                        if (running) {
                            Log.e(TAG, "RTSP accept error: ${e.message}")
                        }
                    }
                }
            }
            Log.d(TAG, "RTSP server started on port $port")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTSP server on port $port: ${e.message}")
            return false
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        handlerThread = null
        Log.d(TAG, "RTSP server stopped")
    }
}
