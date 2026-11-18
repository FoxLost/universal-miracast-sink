package foxlost.miracast.sink.rtsp

import java.util.Locale

/** Checks source-selected R1 media/transport against the immutable M3 offer. Unknown extensions stay optional. */
internal object WfdSelection {
    private val spaces = Regex("\\s+")
    private val ports = Regex("RTP/AVP/UDP;unicast\\s+([0-9]+)\\s+([0-9]+)\\s+mode=play", RegexOption.IGNORE_CASE)
    private val unsupported = setOf(
        "wfd_3d_video_formats", "wfd_3d_formats", "wfd_preferred_display_mode", "wfd_i2c",
        "wfd_standby", "wfd_standby_resume_capability", "wfd2_video_formats", "wfd2_audio_codecs",
        "wfd2_aux_stream_formats", "wfd2_transport_switch", "wfd2_direct_streaming_mode",
    )

    fun parameters(body: String): Map<String, String> = buildMap {
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val key = line.substringBefore(':').trim().lowercase(Locale.ROOT)
            put(key, if (':' in line) line.substringAfter(':').trim() else "")
        }
    }

    /** Only media fields cross the diagnostic boundary; never log arbitrary RTSP text. */
    fun mediaDiagnostics(body: String): String = parameters(body).entries
        .filter { it.key == "wfd_video_formats" || it.key == "wfd_audio_codecs" }
        .joinToString(" ") { (name, value) ->
            val safe = when {
                value.length > 256 -> "<oversize>"
                value.split(spaces).all { token ->
                    token.trimEnd(',').let { part ->
                        part == "none" || part == "LPCM" || part == "AAC" || part == "AC3" ||
                            (part.isNotEmpty() && part.all { it in "0123456789abcdefABCDEF" })
                    }
                } -> value.replace(spaces, " ")
                else -> "<redacted>"
            }
            "$name=$safe"
        }

    /** A single supported CEA timing, not the native/preferred byte or measured frame rate. */
    fun selectedCeaTiming(body: String): String? {
        val value = parameters(body)["wfd_video_formats"] ?: return null
        if (value.length > 256 || ',' in value) return null
        val fields = value.split(spaces)
        if (fields.size != 13 || hex(fields[0], 2) == null || fields[1] != "00") return null
        val selected = codec(fields.drop(2)) ?: return null
        if (selected.formats.sumOf { java.lang.Long.bitCount(it) } != 1 || !withinLevel(selected)) return null
        return when (java.lang.Long.numberOfTrailingZeros(selected.formats[0])) {
            5 -> "1280x720 nominalFps=30"
            6 -> "1280x720 nominalFps=60"
            7 -> "1920x1080 nominalFps=30"
            8 -> "1920x1080 nominalFps=60"
            else -> null
        }
    }

    fun validate(body: String, offer: String): String? {
        val advertised = parameters(offer)
        for ((name, value) in parameters(body)) {
            when {
                name == "wfd_video_formats" && !video(value, advertised[name]) -> return "Unsupported video selection"
                name == "wfd_audio_codecs" && !audio(value, advertised[name]) -> return "Unsupported audio selection"
                name == "wfd_content_protection" && !value.equals("none", true) -> return "HDCP is not supported"
                name == "wfd_client_rtp_ports" -> {
                    val selected = ports.matchEntire(value) ?: return "Unsupported RTP transport"
                    val local = ports.matchEntire(advertised[name].orEmpty()) ?: return "RTP was not advertised"
                    if (selected.groupValues.drop(1).map { it.toIntOrNull() } != local.groupValues.drop(1).map { it.toIntOrNull() }) {
                        return "RTP ports do not match the bound endpoint"
                    }
                }
                name == "wfd_route" && !value.equals("primary", true) -> return "Coupled sink routing is not supported"
                name in unsupported && !value.equals("none", true) -> return "Unsupported parameter: $name"
            }
        }
        return null
    }

    private fun audio(value: String, offer: String?): Boolean {
        if (value.equals("none", true)) return true
        if (offer == null || offer.equals("none", true) || ',' in value) return false
        val selected = value.split(spaces)
        val advertised = offer.split(spaces)
        if (selected.size != 3 || advertised.size != 3 || !selected[0].equals("LPCM", true)) return false
        val modes = hex(selected[1], 8) ?: return false
        val supported = hex(advertised[1], 8) ?: return false
        return modes == 2L && modes and supported == modes && hex(selected[2], 2) != null
    }

    private fun video(value: String, offer: String?): Boolean {
        if (value.equals("none", true)) return true
        if (offer == null || offer.equals("none", true)) return false
        val selectedParts = value.split(',')
        if (selectedParts.size != 1) return false
        val selected = selectedParts[0].trim().split(spaces)
        if (selected.size != 13 || hex(selected[0], 2) == null || selected[1] != "00") return false
        val chosen = codec(selected.drop(2)) ?: return false
        // Source selects one format. M3's level is a maximum, not a bitmap of levels.
        if (chosen.formats.sumOf { java.lang.Long.bitCount(it) } != 1) return false
        if (!withinLevel(chosen)) return false
        val offers = offer.split(',')
        return offers.mapIndexedNotNull { index, part ->
            val fields = part.trim().split(spaces)
            codec(if (index == 0) fields.drop(2) else fields)
        }.any { supported ->
            chosen.profile == supported.profile && chosen.level <= supported.level &&
                chosen.formats.indices.all { chosen.formats[it] and supported.formats[it] == chosen.formats[it] } &&
                chosen.frameControl and supported.frameControl == chosen.frameControl
        }
    }

    private data class Codec(val profile: Int, val level: Int, val formats: List<Long>, val frameControl: Long)

    private fun codec(fields: List<String>): Codec? {
        if (fields.size != 11) return null
        val profile = hex(fields[0], 2)?.toInt() ?: return null
        val level = hex(fields[1], 2)?.toInt() ?: return null
        if (profile !in listOf(1, 2) || level !in listOf(1, 2, 4, 8, 16)) return null
        val formats = fields.subList(2, 5).map { hex(it, 8) ?: return null }
        if (hex(fields[5], 2) == null || hex(fields[6], 4) != 0L || hex(fields[7], 4) != 0L) return null
        val frameControl = hex(fields[8], 2) ?: return null
        if (fields[9] != "none" || fields[10] != "none") return null
        return Codec(profile, level, formats, frameControl)
    }

    private fun withinLevel(codec: Codec): Boolean {
        // Only the four forced HD timings can be selected, never SD, PAL, or VESA/HH.
        if (codec.formats[1] != 0L || codec.formats[2] != 0L) return false
        val bit = java.lang.Long.numberOfTrailingZeros(codec.formats[0])
        val required = when (bit) {
            5 -> 1
            6 -> 2
            7 -> 4
            8 -> 16
            else -> return false
        }
        return codec.level >= required
    }

    private fun hex(value: String, width: Int): Long? =
        value.takeIf { it.length == width && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' } }?.toLongOrNull(16)
}
