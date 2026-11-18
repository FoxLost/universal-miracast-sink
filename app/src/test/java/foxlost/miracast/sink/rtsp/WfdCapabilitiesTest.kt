package foxlost.miracast.sink.rtsp

import org.junit.Assert.assertTrue
import org.junit.Test

class WfdCapabilitiesTest {
    @Test fun requestedUibcCapabilityNeverClaimsInboundListener() {
        val response = WfdCapabilities.buildResponse("wfd_uibc_capability\r\nwfd_audio_codecs\r\n")
        assertTrue(response.contains("wfd_uibc_capability: none"))
        assertTrue(!response.contains("port=", ignoreCase = true))
    }
    @Test fun enabledSinkAdvertisesOnlyImplementedTouchWithoutListener() {
        val response = WfdCapabilities.buildResponse(
            "wfd_uibc_capability\r\n",
            uibcEnabled = true,
        )
        assertTrue(response.contains("wfd_uibc_capability: input_category_list=GENERIC, HIDC;"))
        assertTrue(response.contains("generic_cap_list=SingleTouch, MultiTouch"))
        assertTrue(response.contains("port=none"))
        assertTrue(response.contains("hidc_cap_list=SingleTouch/USB, MultiTouch/USB;"))
        assertTrue(!response.contains("Keyboard") && !response.contains("Mouse"))
        assertTrue(!response.contains("wfd_uibc_capability: none"))
    }

    @Test fun uninitializedSettingsDoNotAdvertiseUnsupportedVideoOrEnableUibc() {
        val response = WfdCapabilities.fullResponse()
        assertTrue(response.contains("wfd_uibc_capability: none"))
        assertTrue(response.contains("wfd_video_formats: none\r\n"))
    }
}
