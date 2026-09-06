package foxlost.miracast.sink.rtsp

import android.util.Log
import foxlost.miracast.sink.SettingsManager
import java.net.Socket
import java.nio.charset.StandardCharsets

/** Compatibility handler for callers that still use the legacy synchronous API. */
class RtspHandler(private val socket: Socket) {
    private val tag = "MiracastRTSP"
    private val sessionId = "${System.currentTimeMillis().toString(16)}"

    fun handle() {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            while (true) {
                val request = RtspCodec.readMessage(input) ?: break
                val method = request.startLine.substringBefore(' ').uppercase()
                val cseq = request.cseq()?.toString() ?: "0"
                val body = RtspCodec.bodyText(request)
                val headers = linkedMapOf<String, String>()
                val responseBody = when (method) {
                    "OPTIONS" -> {
                        headers["Public"] = "org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER"
                        ByteArray(0)
                    }
                    "GET_PARAMETER" -> WfdCapabilities.buildResponse(body).toByteArray(StandardCharsets.UTF_8)
                    "SET_PARAMETER" -> ByteArray(0)
                    "SETUP" -> {
                        val port = configuredRtpPort()
                        headers["Session"] = "$sessionId;timeout=60"
                        headers["Transport"] = "RTP/AVP/UDP;unicast;client_port=$port-${port + 1}"
                        ByteArray(0)
                    }
                    "PLAY", "PAUSE", "TEARDOWN" -> {
                        headers["Session"] = sessionId
                        ByteArray(0)
                    }
                    else -> {
                        val bytes = RtspCodec.encode("RTSP/1.0 501 Not Implemented", mapOf("CSeq" to cseq))
                        output.write(bytes); output.flush(); continue
                    }
                }
                headers["CSeq"] = cseq
                if (responseBody.isNotEmpty()) headers["Content-Type"] = "text/parameters"
                output.write(RtspCodec.encode("RTSP/1.0 200 OK", headers, responseBody))
                output.flush()
            }
        } catch (e: Exception) {
            Log.e(tag, "RTSP handler exception: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun configuredRtpPort(): Int = try {
        SettingsManager.rtpVideoPort.takeIf { it in 1..65534 } ?: SettingsManager.DEFAULT_RTP_VIDEO_PORT
    } catch (_: Exception) { SettingsManager.DEFAULT_RTP_VIDEO_PORT }
}
