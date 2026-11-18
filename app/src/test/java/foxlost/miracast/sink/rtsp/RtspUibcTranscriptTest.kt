package foxlost.miracast.sink.rtsp

import foxlost.miracast.sink.uibc.UibcCapability
import foxlost.miracast.sink.uibc.UibcCapabilityParser
import foxlost.miracast.sink.uibc.UibcSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspUibcTranscriptTest {
    @Test fun windowsM4M14M15OrderingIsIdempotent() {
        val m4 = "wfd_uibc_capability: input_category_list=GENERIC; generic_cap_list=SingleTouch, MultiTouch; port=50000\r\n"
        val m14 = "WFD_UIBC_CAPABILITY: input_category_list=generic; generic_cap_list=SingleTouch,MultiTouch; port=50000\r\n"
        val m15 = "wfd_uibc_setting: enabled\r\n"
        val first = UibcCapabilityParser.parseBody(m4)
        val second = UibcCapabilityParser.parseBody(m14)
        val setting = UibcCapabilityParser.parseSettingBody(m15)
        assertEquals(first.capabilities, second.capabilities)
        assertEquals(setOf(UibcCapability.SingleTouch, UibcCapability.MultiTouch), first.capabilities)
        assertEquals(50000, second.port)
        assertEquals(UibcSetting.ENABLED, setting.setting)
    }

    @Test fun androidDelayedOrOmittedOptionalFieldsRemainNonFatal() {
        val omitted = UibcCapabilityParser.parseBody("wfd_video_formats: 00\r\n")
        val delayed = UibcCapabilityParser.parseBody("wfd_uibc_capability: input_category_list=generic; generic_cap_list=SingleTouch; port=50001\r\n")
        assertTrue(omitted.valid)
        assertTrue(!omitted.advertised)
        assertTrue(delayed.valid)
    }
}
