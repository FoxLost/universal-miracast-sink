package foxlost.miracast.sink.rtsp

import foxlost.miracast.sink.SettingsManager

/**
 * Builds the WFD M4 (GET_PARAMETER response) capability set.
 *
 * Mirrors the Xiaomi Pad 6 (MiPCExpend / libwfdsinker) behaviour observed in the
 * session capture: respond to every parameter the source asked about, using real
 * values where the Pad used them, "none" where the Pad used them, and OMIT the
 * wfd2_* family entirely (the Pad did not include them in its response).
 *
 * Responding only to requested parameters keeps the exchange RFC 2326 compliant
 * and safe for Android sources, which simply ignore the params they never asked
 * for. Windows queries up to 28 parameters (wfd_* + wfd2_* + intel_* +
 * microsoft_*); leaving any of them unanswered can make Windows abort.
 *
 * Device name, manufacturer, video profile, audio and throughput are pulled
 * live from [SettingsManager] so the Settings panel takes effect immediately.
 */
object WfdCapabilities {

    /**
     * Unsupported optional capabilities must remain syntactically valid but
     * must never claim an implementation that is not present.
     */
    private val OMITTED = emptySet<String>()

    private fun configuredRtpPort(): Int =
        safeSettings { rtpVideoPort }?.takeIf { it in 1..65534 } ?: SettingsManager.DEFAULT_RTP_VIDEO_PORT

    private fun rtpCapability(port: Int): String =
        "RTP/AVP/UDP;unicast $port 0 mode=play"

    /**
     * Build the response for exactly the parameters requested by the source.
     * [rtpPort] is the port that the RTP receiver has actually bound, when
     * known; using it keeps M3 and SETUP consistent.
     */
    fun buildResponse(requestBody: String, rtpPort: Int? = null): String {
        val values = ordered(rtpPort ?: configuredRtpPort())
        val byName = values.toMap()
        val sb = StringBuilder()
        for (rawLine in requestBody.split('\n')) {
            val param = rawLine.trim().removeSuffix("\r")
            if (param.isEmpty() || param in OMITTED) continue
            sb.append(param).append(": ").append(byName[param] ?: "none").append("\r\n")
        }
        return sb.toString()
    }

    /** Full capability response without request filtering (diagnostic only). */
    fun fullResponse(rtpPort: Int? = null): String {
        val sb = StringBuilder()
        for ((key, value) in ordered(rtpPort ?: configuredRtpPort())) {
            sb.append(key).append(": ").append(value).append("\r\n")
        }
        return sb.toString()
    }

    private fun ordered(rtpPort: Int): List<Pair<String, String>> {
        val audio = if (safeSettings { audioEnabled } != false) "LPCM 00000002 00" else "none"
        val video = safeSettings { videoFormatsForProfile() }
            ?: ("00 00 01 10 0001bde1 00300000 000003c0 00 0000 0000 00 none none, " +
                "02 10 0001bde1 00300000 000003c0 00 0000 0000 00 none none")
        val devName = safeSettings { deviceName } ?: "Miracast Sink"
        val mfr = safeSettings { manufacturer } ?: "Android"

        return listOf(
            "wfd_video_formats" to video,
            "wfd_audio_codecs" to audio,
            "wfd_client_rtp_ports" to rtpCapability(rtpPort),
            "wfd_display_edid" to "none",
            "wfd_connector_type" to "05",
            "wfd_idr_request_capability" to "0",
            "wfd_uibc_capability" to "none",
            "wfd_content_protection" to "none",
            "intel_friendly_name" to devName,
            "intel_sink_manufacturer_name" to mfr,
            "intel_sink_model_name" to "Android",
            "intel_sink_version" to "none",
            "intel_sink_device_URL" to "none",
            "microsoft_latency_management_capability" to "none",
            "microsoft_format_change_capability" to "none",
            "microsoft_diagnostics_capability" to "none",
            "microsoft_cursor" to "none",
            "microsoft_rtcp_capability" to "none",
            "microsoft_video_formats" to "none",
            "microsoft_max_bitrate" to "none",
            "microsoft_multiscreen_projection" to "none",
            "microsoft_audio_mute" to "none",
            "microsoft_color_space_conversion" to "none",
            "wfd2_rotation_capability" to "none",
            "wfd2_video_formats" to "none",
            "wfd2_audio_codecs" to "none",
            "wfd2_video_stream_control" to "none",
        )
    }

    private fun valueFor(param: String, rtpPort: Int = configuredRtpPort()): String? =
        ordered(rtpPort).firstOrNull { it.first == param }?.second

    /** Read a SettingsManager value, tolerating an uninitialized manager. */
    private inline fun <T> safeSettings(block: SettingsManager.() -> T): T? =
        try {
            SettingsManager.block()
        } catch (e: Exception) {
            null
        }
}

