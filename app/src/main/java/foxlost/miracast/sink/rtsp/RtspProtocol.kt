package foxlost.miracast.sink.rtsp

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** A parsed RTSP message. Header names are canonicalized to lower case. */
data class RtspMessage(
    val startLine: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.trim().lowercase()]
    fun cseq(): Int? = header("cseq")?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
    fun sessionId(): String? = header("session")?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
}

data class RtspTransport(
    val profile: String,
    val unicast: Boolean,
    val clientRtpPort: Int? = null,
    val clientRtcpPort: Int? = null,
    val serverRtpPort: Int? = null,
    val serverRtcpPort: Int? = null,
    val ssrc: Long? = null,
    val rtcpFeedbackSsrc: Long? = null,
    val parameters: Map<String, String?> = emptyMap(),
) {
    companion object {
        fun parse(value: String): RtspTransport? {
            val parts = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val profile = parts.first()
            val params = linkedMapOf<String, String?>()
            for (part in parts.drop(1)) {
                val separator = part.indexOf('=')
                val key = (if (separator < 0) part else part.substring(0, separator)).trim().lowercase()
                if (key.isEmpty()) continue
                params[key] = if (separator < 0) null else part.substring(separator + 1).trim()
            }
            fun portPair(name: String): Pair<Int?, Int?> {
                val raw = params[name] ?: return null to null
                val ports = raw.split('-', limit = 2)
                val first = ports.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..65535 }
                val second = ports.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..65535 }
                return first to second
            }
            fun number(name: String): Long? {
                val original = params[name] ?: return null
                val raw = original.removePrefix("0x").removePrefix("0X")
                // Explicit 0x values are hexadecimal; unprefixed values are
                // decimal in common Windows transports, with hex fallback.
                return if (original.startsWith("0x", true)) {
                    raw.toLongOrNull(16)
                } else {
                    raw.toLongOrNull(10) ?: raw.toLongOrNull(16)
                }
            }
            val client = portPair("client_port")
            val server = portPair("server_port")
            return RtspTransport(
                profile = profile,
                unicast = params.containsKey("unicast"),
                clientRtpPort = client.first,
                clientRtcpPort = client.second,
                serverRtpPort = server.first,
                serverRtcpPort = server.second,
                ssrc = number("ssrc"),
                rtcpFeedbackSsrc = number("rtcp-fb-ssrc"),
                parameters = params,
            )
        }
    }
}

/** Byte-oriented RTSP codec; Content-Length is an encoded-byte count, not a character count. */
object RtspCodec {
    private const val MAX_LINE_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024

    fun readMessage(input: InputStream): RtspMessage? {
        val first = readLine(input) ?: return null
        if (first.isEmpty()) return readMessage(input)
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: throw EOFException("RTSP headers ended before blank line")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            headers[name] = if (headers.containsKey(name)) "${headers[name]}, $value" else value
        }
        val contentLength = headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
        require(contentLength in 0..MAX_BODY_BYTES) { "invalid RTSP Content-Length: $contentLength" }
        val body = ByteArray(contentLength.toInt())
        readFully(input, body)
        return RtspMessage(first, headers, body)
    }

    fun bodyText(message: RtspMessage): String = String(message.body, StandardCharsets.UTF_8)

    fun encode(startLine: String, headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(startLine.toByteArray(StandardCharsets.UTF_8))
        out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        for ((name, value) in headers) {
            out.write(name.toByteArray(StandardCharsets.US_ASCII))
            out.write(": ".toByteArray(StandardCharsets.US_ASCII))
            out.write(value.toByteArray(StandardCharsets.UTF_8))
            out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        }
        if (body.isNotEmpty() && headers.keys.none { it.equals("content-length", true) }) {
            out.write("Content-Length: ${body.size}\r\n".toByteArray(StandardCharsets.US_ASCII))
        }
        out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        out.write(body)
        return out.toByteArray()
    }
    fun parseHeaderBlock(block: String): LinkedHashMap<String, String> {
        val parsed = linkedMapOf<String, String>()
        for (line in block.split("\r\n", "\n")) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            if (name.isEmpty()) continue
            parsed[name] = line.substring(colon + 1).trim()
        }
        return parsed
    }


    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) {
                if (line.size() == 0) return null
                throw EOFException("RTSP line ended before LF")
            }
            if (value == '\n'.code) break
            if (line.size() >= MAX_LINE_BYTES) throw IllegalArgumentException("RTSP line too long")
            line.write(value)
        }
        val bytes = line.toByteArray()
        val size = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, size, StandardCharsets.ISO_8859_1)
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) throw EOFException("RTSP body ended before Content-Length")
            if (read == 0) continue
            offset += read
        }
    }
}
