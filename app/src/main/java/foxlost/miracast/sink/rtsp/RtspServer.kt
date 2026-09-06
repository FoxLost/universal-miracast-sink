package foxlost.miracast.sink.rtsp

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class RtspServer(
    private val port: Int = 7236,
    private val onConnection: (Socket) -> RtspConnection?,
) {
    private val tag = "MiracastRTSP"
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var handlerThread: Thread? = null
    private val connections = CopyOnWriteArrayList<RtspConnection>()

    /** Legacy constructor retained for callers that do not provide a session adapter. */
    constructor(
        port: Int = 7236,
        onPlayTriggered: () -> Unit,
        onSetupTriggered: () -> Unit = {},
    ) : this(port, { socket -> RtspConnection(socket, onPlayTriggered, onSetupTriggered) })

    fun start(): Boolean {
        if (running) return true
        return try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(port))
            }
            running = true
            handlerThread = thread(name = "RTSP-Server") {
                while (running) {
                    try {
                        val socket = serverSocket?.accept() ?: continue
                        if (!running) {
                            socket.close()
                            continue
                        }
                        Log.d(tag, "RTSP client connected from: ${socket.inetAddress.hostAddress}")
                        val connection = onConnection(socket)
                        if (connection == null) {
                            socket.close()
                        } else {
                            connections += connection
                            connection.start()
                        }
                    } catch (e: Exception) {
                        if (running) Log.e(tag, "RTSP accept error: ${e.message}")
                    }
                }
            }
            Log.d(tag, "RTSP server started on port $port")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start RTSP server on port $port: ${e.message}")
            false
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        connections.forEach { it.close("RTSP server stopped") }
        connections.clear()
        handlerThread = null
        Log.d(tag, "RTSP server stopped")
    }
}
