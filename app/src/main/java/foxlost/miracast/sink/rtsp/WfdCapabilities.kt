package foxlost.miracast.sink.rtsp

import foxlost.miracast.sink.SettingsManager

/** WFD R1 capabilities, snapshotted for the session and never copied from another device. */
object WfdCapabilities {


    private fun configuredRtpPort(): Int =
        safeSettings { rtpVideoPort }?.takeIf { it in 1..65534 } ?: SettingsManager.DEFAULT_RTP_VIDEO_PORT

    private fun rtpCapability(port: Int): String =
        "RTP/AVP/UDP;unicast $port 0 mode=play"

    /**
     * Build the response for exactly the parameters requested by the source.
     * [rtpPort] is the port that the RTP receiver has actually bound, when
     * known; using it keeps M3 and SETUP consistent.
     */
    fun buildResponse(
        requestBody: String,
        rtpPort: Int? = null,
        uibcEnabled: Boolean? = null,
        advertisedCapabilities: String? = null,
    ): String {
        val byName = WfdSelection.parameters(
            advertisedCapabilities ?: fullResponse(rtpPort, uibcEnabled),
        )
        val sb = StringBuilder()
        for (rawLine in requestBody.split('\n')) {
            val param = rawLine.trim().substringBefore(':').lowercase(java.util.Locale.ROOT)
            if (param.isEmpty()) continue
            sb.append(param).append(": ").append(byName[param] ?: "none").append("\r\n")
        }
        return sb.toString()
    }

    /** Full capability response without request filtering (diagnostic only). */
    fun fullResponse(rtpPort: Int? = null, uibcEnabled: Boolean? = null): String {
        val sb = StringBuilder()
        for ((key, value) in ordered(
            rtpPort ?: configuredRtpPort(),
            uibcEnabled ?: (safeSettings { SettingsManager.uibcEnabled } == true),
        )) {
            sb.append(key).append(": ").append(value).append("\r\n")
        }
        return sb.toString()
    }

    fun validateSelection(body: String, advertisedCapabilities: String? = null): String? =
        WfdSelection.validate(body, advertisedCapabilities ?: fullResponse())

    private fun ordered(rtpPort: Int, uibcEnabled: Boolean): List<Pair<String, String>> {
        val audio = if (safeSettings { audioEnabled } != false) "LPCM 00000002 00" else "none"
        val video = safeSettings { videoFormats() } ?: "none"
        val devName = safeSettings { deviceName } ?: "Miracast Sink"
        val mfr = safeSettings { manufacturer } ?: "Android"
        val uibc = if (uibcEnabled) {
            "input_category_list=GENERIC, HIDC; generic_cap_list=SingleTouch, MultiTouch; " +
                "hidc_cap_list=SingleTouch/USB, MultiTouch/USB; port=none"
        } else {
            "none"
        }

        return listOf(
            "wfd_video_formats" to video,
            "wfd_audio_codecs" to audio,
            "wfd_client_rtp_ports" to rtpCapability(rtpPort),
            "wfd_display_edid" to "none",
            "wfd_connector_type" to "05",
            "wfd_idr_request_capability" to "1",
            "wfd_uibc_capability" to uibc,
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


    /** Read a SettingsManager value, tolerating an uninitialized manager. */
    private inline fun <T> safeSettings(block: SettingsManager.() -> T): T? =
        try {
            SettingsManager.block()
        } catch (e: Exception) {
            null
        }
}

