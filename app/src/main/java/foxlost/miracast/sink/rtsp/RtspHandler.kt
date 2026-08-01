package foxlost.miracast.sink.rtsp

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

class RtspHandler(private val socket: Socket) {
    private val TAG = "MiracastRTSP"
    private var cseq = 0
    private var streamId = 0
    private var sessionId = "12345678"

    fun handle() {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val reader = input.bufferedReader(StandardCharsets.UTF_8)

            while (true) {
                val requestLine = reader.readLine() ?: break
                if (requestLine.isEmpty()) continue

                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val parts = line.split(":", limit = 2)
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

                processRequest(requestLine, headers, body, output)
            }
        } catch (e: Exception) {
            Log.e(TAG, "RTSP Handler exception: ${e.message}")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {}
            Log.d(TAG, "RTSP connection closed")
        }
    }

    private fun processRequest(requestLine: String, headers: Map<String, String>, body: String, output: OutputStream) {
        val reqCseq = headers["CSeq"] ?: "0"
        val reqCseqInt = reqCseq.toIntOrNull() ?: 0
        Log.d(TAG, "Received: $requestLine CSeq: $reqCseq")
        
        // Simple RTSP Response Generator
        val buildResponse = { status: String, extraHeaders: String, responseBody: String ->
            val sb = java.lang.StringBuilder()
            sb.append("RTSP/1.0 $status\r\n")
            sb.append("CSeq: $reqCseq\r\n")
            if (extraHeaders.isNotEmpty()) {
                sb.append(extraHeaders)
                if (!extraHeaders.endsWith("\r\n")) {
                    sb.append("\r\n")
                }
            }
            if (responseBody.isNotEmpty()) {
                sb.append("Content-Length: ${responseBody.length}\r\n")
                sb.append("Content-Type: text/parameters\r\n")
                sb.append("\r\n")
                sb.append(responseBody)
            } else {
                sb.append("\r\n")
            }
            val resStr = sb.toString()
            Log.d(TAG, "Sending Response CSeq: $reqCseq")
            output.write(resStr.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }

        when {
            requestLine.startsWith("OPTIONS") -> {
                val extraHeaders = "Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, PAUSE, TEARDOWN\r\n"
                buildResponse("200 OK", extraHeaders, "")
            }
            requestLine.startsWith("GET_PARAMETER") -> {
                // Reply with sink capabilities
                val resBody = """
                    wfd_video_formats: 00 00 01 10 0001bde1 00300000 000003c0
                    wfd_audio_codecs: LPCM 00000002 00
                    wfd_client_rtp_ports: RTP/AVP/UDP;unicast 15550 0 mode=play
                    wfd_uibc_capability: input_category_list=GENERIC, HIDC; generic_cap_list=Keyboard,Mouse,SingleTouch,MultiTouch; hidc_cap_list=Keyboard/USB,Mouse/USB,SingleTouch/USB,MultiTouch/USB; port=none
                """.trimIndent().replace("\n", "\r\n") + "\r\n"
                buildResponse("200 OK", "", resBody)
            }
            requestLine.startsWith("SET_PARAMETER") -> {
                // Look for trigger method or just ack
                buildResponse("200 OK", "", "")
                if (body.contains("wfd_trigger_method: SETUP")) {
                    // Start SETUP flow from sink to source if we were acting as client
                }
            }
            requestLine.startsWith("SETUP") -> {
                val extra = "Session: $sessionId;timeout=60\r\nTransport: RTP/AVP/UDP;unicast;client_port=15550-15551\r\n"
                buildResponse("200 OK", extra, "")
            }
            requestLine.startsWith("PLAY") -> {
                buildResponse("200 OK", "Session: $sessionId\r\n", "")
            }
            requestLine.startsWith("TEARDOWN") -> {
                buildResponse("200 OK", "Session: $sessionId\r\n", "")
            }
            else -> {
                buildResponse("501 Not Implemented", "", "")
            }
        }
    }
}
