package foxlost.miracast.sink.rtsp

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class RtspConnection(
    private val socket: Socket,
    private val onPlayTriggered: () -> Unit
) {
    private val TAG = "MiracastRTSP"
    private val cseqGenerator = AtomicInteger(1)
    private var sourceIp = socket.inetAddress.hostAddress
    @Volatile private var isRunning = true
    
    private lateinit var outputStream: OutputStream
    private lateinit var inputStream: InputStream

    fun start() {
        try {
            outputStream = socket.getOutputStream()
            inputStream = socket.getInputStream()
            thread(name = "RTSP-Read-$sourceIp") { readLoop() }
        } catch (e: Exception) {
            Log.e(TAG, "RTSP start error: ${e.message}")
            close()
        }
    }

    private fun readLoop() {
        try {
            val reader = inputStream.bufferedReader(StandardCharsets.UTF_8)
            while (isRunning) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue

                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                while (true) {
                    val hLine = reader.readLine() ?: break
                    if (hLine.isEmpty()) break
                    val parts = hLine.split(":", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        headers[key] = value
                        if (key.equals("Content-Length", ignoreCase = true)) {
                            contentLength = value.toIntOrNull() ?: 0
                        }
                    }
                }

                val body = if (contentLength > 0) {
                    val chars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(chars, read, contentLength - read)
                        if (n == -1) break
                        read += n
                    }
                    String(chars)
                } else ""

                if (line.startsWith("RTSP/1.0 200 OK")) {
                    Log.d(TAG, "Received Response: $line (CSeq: ${headers["CSeq"]})")
                    // If this was a response to PLAY, trigger the UI
                    // A simple heuristic is that we just trigger it anyway or check sequence
                    // But we can trigger it after we sent PLAY which we know we did if we reach here
                    if (headers["CSeq"]?.toIntOrNull() == cseqGenerator.get() - 1) {
                        // Assuming the last request was PLAY
                        onPlayTriggered()
                    }
                } else {
                    processRequest(line, headers, body)
                }
            }
        } catch (e: Exception) {
            if (isRunning) {
                Log.e(TAG, "RTSP Read error: ${e.message}")
            }
        } finally {
            close()
        }
    }

    private fun processRequest(requestLine: String, headers: Map<String, String>, body: String) {
        val reqCseq = headers["CSeq"] ?: "0"
        Log.d(TAG, "Received Request: $requestLine CSeq: $reqCseq")

        when {
            requestLine.startsWith("OPTIONS") -> {
                val extraHeaders = "Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER\r\n"
                sendResponse(reqCseq, "200 OK", extraHeaders, "")
                
                // If it's the first OPTIONS, we also send our OPTIONS
                if (cseqGenerator.get() == 1) {
                    sendRequest("OPTIONS * RTSP/1.0", "Require: org.wfa.wfd1.0\r\n", "")
                }
            }
            requestLine.startsWith("GET_PARAMETER") -> {
                val resBody = """
                    wfd_video_formats: 00 00 01 10 0001bde1 00300000 000003c0
                    wfd_audio_codecs: LPCM 00000002 00
                    wfd_client_rtp_ports: RTP/AVP/UDP;unicast 15550 0 mode=play
                    wfd_uibc_capability: input_category_list=GENERIC, HIDC; generic_cap_list=Keyboard,Mouse,SingleTouch,MultiTouch; hidc_cap_list=Keyboard/USB,Mouse/USB,SingleTouch/USB,MultiTouch/USB; port=none
                """.trimIndent().replace("\n", "\r\n") + "\r\n"
                sendResponse(reqCseq, "200 OK", "", resBody)
            }
            requestLine.startsWith("SET_PARAMETER") -> {
                sendResponse(reqCseq, "200 OK", "", "")
                
                var presentationUrl = ""
                body.lines().forEach { line ->
                    if (line.startsWith("wfd_presentation_URL:")) {
                        presentationUrl = line.substringAfter(":").trim().ifEmpty { "rtsp://$sourceIp/wfd1.0/streamid=0" }
                    }
                }
                
                if (body.contains("wfd_trigger_method: SETUP") || presentationUrl.isNotEmpty()) {
                    if (presentationUrl.isEmpty()) presentationUrl = "rtsp://$sourceIp/wfd1.0/streamid=0"
                    if (presentationUrl.contains("255.255.255.255")) {
                        presentationUrl = presentationUrl.replace("255.255.255.255", sourceIp)
                    }
                    
                    // Trigger SETUP
                    thread {
                        Thread.sleep(100)
                        sendRequest("SETUP $presentationUrl RTSP/1.0", "Transport: RTP/AVP/UDP;unicast;client_port=15550-15551\r\n", "")
                        Thread.sleep(100)
                        sendRequest("PLAY $presentationUrl RTSP/1.0", "Session: 1\r\n", "")
                        Thread.sleep(50)
                        onPlayTriggered()
                    }
                }
            }
            requestLine.startsWith("TEARDOWN") -> {
                sendResponse(reqCseq, "200 OK", "", "")
            }
            else -> {
                sendResponse(reqCseq, "501 Not Implemented", "", "")
            }
        }
    }

    private fun sendResponse(cseq: String, status: String, extraHeaders: String, body: String) {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 $status\r\n")
        sb.append("CSeq: $cseq\r\n")
        if (extraHeaders.isNotEmpty()) {
            sb.append(extraHeaders)
            if (!extraHeaders.endsWith("\r\n")) {
                sb.append("\r\n")
            }
        }
        if (body.isNotEmpty()) {
            sb.append("Content-Length: ${body.length}\r\n")
            sb.append("Content-Type: text/parameters\r\n\r\n")
            sb.append(body)
        } else {
            sb.append("\r\n")
        }
        writeToSocket(sb.toString())
    }

    private fun sendRequest(requestLine: String, extraHeaders: String, body: String) {
        val cseq = cseqGenerator.getAndIncrement()
        val sb = StringBuilder()
        sb.append("$requestLine\r\n")
        sb.append("CSeq: $cseq\r\n")
        if (extraHeaders.isNotEmpty()) {
            sb.append(extraHeaders)
            if (!extraHeaders.endsWith("\r\n")) {
                sb.append("\r\n")
            }
        }
        if (body.isNotEmpty()) {
            sb.append("Content-Length: ${body.length}\r\n")
            sb.append("Content-Type: text/parameters\r\n\r\n")
            sb.append(body)
        } else {
            sb.append("\r\n")
        }
        Log.d(TAG, "Sending Request: $requestLine CSeq: $cseq")
        writeToSocket(sb.toString())
    }

    private fun writeToSocket(data: String) {
        try {
            outputStream.write(data.toByteArray(StandardCharsets.UTF_8))
            outputStream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Write error: ${e.message}")
            close()
        }
    }

    fun close() {
        if (!isRunning) return
        isRunning = false
        try { socket.close() } catch (e: Exception) {}
        Log.d(TAG, "Connection with $sourceIp closed")
    }
}
