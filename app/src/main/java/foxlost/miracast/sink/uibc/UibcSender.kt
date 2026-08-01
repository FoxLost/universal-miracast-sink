package foxlost.miracast.sink.uibc

import android.util.Log
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class UibcSender(private val targetIp: String, private val targetPort: Int) {
    private val TAG = "MiracastUIBC"
    private var socket: Socket? = null
    private var outStream: OutputStream? = null
    @Volatile private var isRunning = false

    fun start() {
        if (targetPort <= 0) return
        isRunning = true
        thread(name = "UibcSender") {
            try {
                Log.d(TAG, "Connecting to UIBC port $targetIp:$targetPort")
                socket = Socket(targetIp, targetPort)
                outStream = socket?.getOutputStream()
                Log.d(TAG, "UIBC Connected")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect UIBC: ${e.message}")
                isRunning = false
            }
        }
    }

    fun sendTouchEvent(action: Int, x: Float, y: Float, width: Int, height: Int) {
        if (!isRunning || outStream == null) return

        thread {
            try {
                // UIBC Generic Touch Event Payload
                val normX = (x / width).coerceIn(0f, 1f)
                val normY = (y / height).coerceIn(0f, 1f)
                
                val buffer = ByteBuffer.allocate(20)
                buffer.put(0x00.toByte()) // Version + T
                buffer.put(0x00.toByte()) // Category: Generic
                buffer.putShort(0) // Length placeholder
                
                buffer.put(3.toByte()) 
                buffer.putShort(8) // IE Length
                
                val uibcAction = when (action) {
                    android.view.MotionEvent.ACTION_DOWN -> 0
                    android.view.MotionEvent.ACTION_UP -> 1
                    android.view.MotionEvent.ACTION_MOVE -> 2
                    else -> return@thread
                }
                
                buffer.put(uibcAction.toByte()) 
                buffer.put(1.toByte()) // Pointer ID
                
                buffer.putShort((normX * 65535).toInt().toShort())
                buffer.putShort((normY * 65535).toInt().toShort())
                
                val finalLength = buffer.position() - 4
                buffer.putShort(2, finalLength.toShort())

                val data = buffer.array().copyOfRange(0, buffer.position())
                outStream?.write(data)
                outStream?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send UIBC touch: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            outStream?.close()
            socket?.close()
        } catch (e: Exception) {}
        Log.d(TAG, "UIBC sender stopped")
    }
}
