package foxlost.miracast.sink.rtsp

import foxlost.miracast.sink.media.WfdAvcFormats
import org.junit.Assert.*
import org.junit.Test

class WfdSelectionTest {
    private val video = WfdAvcFormats.build(1080, 60, listOf(WfdAvcFormats.Decoder(1, 16) { _, _, _ -> true }))
    private val offer = "wfd_video_formats: $video\r\nwfd_audio_codecs: LPCM 00000002 00\r\nwfd_client_rtp_ports: RTP/AVP/UDP;unicast 15550 0 mode=play\r\nwfd_content_protection: none\r\n"
    private fun selected(level: String, cea: String) = "wfd_video_formats: 00 00 01 $level $cea 00000000 00000000 00 0000 0000 00 none none\r\n"

    @Test fun lowerLevelThanAdvertisedMaximumIsValidWhenTheModeFits() {
        val lowerRate = offer.replace(video, WfdAvcFormats.build(1080, 30,
            listOf(WfdAvcFormats.Decoder(1, 16) { _, _, _ -> true })))
        assertNull(WfdCapabilities.validateSelection(selected("04", "00000080"), lowerRate))
        assertNotNull(WfdCapabilities.validateSelection(selected("01", "00000080"), lowerRate))
        assertNotNull(WfdCapabilities.validateSelection(selected("04", "00000080"), offer))
    }
    @Test fun codecResolutionAndTransportCannotExceedTheOffer() {
        val small = offer.replace(video, WfdAvcFormats.build(720, 30,
            listOf(WfdAvcFormats.Decoder(1, 16) { _, _, _ -> true })))
        assertNotNull(WfdCapabilities.validateSelection(selected("10", "00000100"), small))
        assertNotNull(WfdCapabilities.validateSelection(selected("10", "00000005"), offer))
        assertNotNull(WfdCapabilities.validateSelection("wfd_audio_codecs: AAC 00000001 00\r\n", offer))
        assertNotNull(WfdCapabilities.validateSelection("wfd_audio_codecs: LPCM 00000001 00\r\n", offer))
        assertNotNull(WfdCapabilities.validateSelection("wfd_content_protection: HDCP2.1 port=1024\r\n", offer))
        assertNotNull(WfdCapabilities.validateSelection("wfd_client_rtp_ports: RTP/AVP/TCP;unicast 15550 0 mode=play\r\n", offer))
        assertNotNull(WfdCapabilities.validateSelection("wfd_client_rtp_ports: RTP/AVP/UDP;unicast 16666 0 mode=play\r\n", offer))
        assertNull(WfdCapabilities.validateSelection("wfd_audio_codecs: LPCM 00000002 00\r\n", offer))
    }
    @Test fun snapshotDoesNotReintroduceLiveSettingsOrUnsupportedExtensions() {
        val request = "wfd_video_formats\r\nwfd_client_rtp_ports\r\n"
        val response = WfdCapabilities.buildResponse(request, rtpPort = 9999, advertisedCapabilities = offer)
        assertTrue(response.contains("15550 0 mode=play"))
        assertFalse(response.contains("9999"))
        assertNull(WfdCapabilities.validateSelection("vendor_unknown_extension: 1\r\n", offer))
        assertNotNull(WfdCapabilities.validateSelection("wfd_standby\r\n", offer))
    }

    @Test fun diagnosticTimingUsesTheSelectedBitmapRatherThanTheNativeByte() {
        for ((cea, timing) in listOf(
            "00000020" to "1280x720 nominalFps=30",
            "00000040" to "1280x720 nominalFps=60",
            "00000080" to "1920x1080 nominalFps=30",
            "00000100" to "1920x1080 nominalFps=60",
        )) {
            assertEquals(timing, WfdSelection.selectedCeaTiming(selected("10", cea)))
        }
        assertNull(WfdSelection.selectedCeaTiming(selected("10", "00000180")))
        assertNull(WfdSelection.selectedCeaTiming(selected("01", "00000100")))
        assertNull(WfdSelection.selectedCeaTiming(selected("10", "00000001")))
        assertNull(WfdSelection.selectedCeaTiming(selected("10", "00000100").replaceFirst("00000000", "00000001")))
        assertNull(WfdSelection.selectedCeaTiming("wfd_video_formats: none\r\n"))
    }

    @Test fun diagnosticsAdmitOnlyBoundedMediaValuesWithoutOtherPayloadFields() {
        val multiProfile = WfdAvcFormats.build(1080, 60, listOf(
            WfdAvcFormats.Decoder(1, 16) { _, _, _ -> true },
            WfdAvcFormats.Decoder(2, 16) { _, _, _ -> true },
        ))
        assertTrue(WfdSelection.mediaDiagnostics("wfd_video_formats: $multiProfile\r\n").contains(multiProfile))
        val media = WfdSelection.mediaDiagnostics(offer +
            "wfd_presentation_URL: rtsp://private.example/secret\r\nintel_friendly_name: private-name\r\n")
        assertTrue(media.contains(video))
        assertTrue(media.contains("LPCM 00000002 00"))
        assertFalse(media.contains("private"))
        val hostile = WfdSelection.mediaDiagnostics(
            "wfd_video_formats: rtsp://private.example/secret\t\u001b[31m\r\n" +
                "wfd_audio_codecs: ${"a".repeat(1000)}\r\n")
        assertFalse(hostile.contains("private"))
        assertFalse(hostile.contains('\u001b'))
        assertTrue(hostile.length < 128)
        assertEquals("", WfdSelection.mediaDiagnostics("wfd_presentation_URL: rtsp://private.example/secret\r\n"))
    }
}
