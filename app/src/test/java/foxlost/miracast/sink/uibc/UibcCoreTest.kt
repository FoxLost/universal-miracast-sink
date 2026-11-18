package foxlost.miracast.sink.uibc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UibcCoreTest {
    @Test fun singleTouchDownUsesExactEvenPaddedFrame() {
        val bytes = UibcCodec.encodeTouch(TouchEvent(TouchAction.DOWN, listOf(TouchPoint(7, 0, 65535))))
        assertArrayEquals(byteArrayOf(0, 0, 0, 14, 0, 0, 6, 1, 7, 0, 0, -1, -1, 0), bytes)
    }

    @Test fun multiTouchMoveEncodesAllPointersBigEndian() {
        val bytes = UibcCodec.encodeTouch(TouchEvent(TouchAction.MOVE, listOf(TouchPoint(1, 1, 2), TouchPoint(2, 65535, 0))))
        assertEquals(18, bytes.size)
        assertEquals(2, bytes[7].toInt())
        assertEquals(1, bytes[8].toInt())
        assertEquals(0, bytes[9].toInt())
        assertEquals(1, bytes[10].toInt())
        assertEquals(0, bytes[11].toInt())
        assertEquals(2, bytes[12].toInt())
        assertEquals(2, bytes[13].toInt())
        assertEquals(255, bytes[14].toInt() and 255)
        assertEquals(255, bytes[15].toInt() and 255)
    }

    @Test fun parsesCapabilityAndSettingsCaseInsensitively() {
        val canonical = UibcCapabilityParser.parse(
            "INPUT_CATEGORY_LIST=generic; GENERIC_CAP_LIST=SingleTouch, MultiTouch; port=50000",
        )
        assertTrue(canonical.valid)
        assertEquals(50000, canonical.port)
        assertEquals(setOf(UibcCapability.SingleTouch, UibcCapability.MultiTouch), canonical.capabilities)
        // Be tolerant of compact vendor syntax while emitting canonical comma-SP syntax.
        val compact = UibcCapabilityParser.parse(
            "INPUT_CATEGORY_LIST=generic; GENERIC_CAP_LIST=SingleTouch,MultiTouch; port=50000",
        )
        assertTrue(compact.valid)
        assertEquals(canonical.capabilities, compact.capabilities)
        assertEquals(UibcSetting.ENABLED, UibcCapabilityParser.parseSetting(" ENABLED ").setting)
        assertEquals(UibcSetting.DISABLED, UibcCapabilityParser.parseSetting("0").setting)
    }

    @Test fun rejectsHidcOnlyAndInvalidPort() {
        assertTrue(UibcCapabilityParser.parse("input_category_list=HIDC; generic_cap_list=SingleTouch; port=50000").error != null)
        assertTrue(UibcCapabilityParser.parse("input_category_list=GENERIC; generic_cap_list=SingleTouch; port=65536").error != null)
        assertTrue(UibcCapabilityParser.parse("none").valid)
        assertNull(UibcCapabilityParser.parse("none").port)
    }
    @Test fun sinkCapabilityAndParameterQueriesAreNonFatal() {
        val sink = UibcCapabilityParser.parse(
            "input_category_list=GENERIC; generic_cap_list=SingleTouch, MultiTouch; " +
                "hidc_cap_list=none; port=none",
        )
        assertTrue(sink.valid)
        assertEquals(setOf(UibcCapability.SingleTouch, UibcCapability.MultiTouch), sink.capabilities)
        assertNull(sink.port)
        assertTrue(!UibcCapabilityParser.parseBody("wfd_uibc_capability\r\n").advertised)
        assertTrue(!UibcCapabilityParser.parseSettingBody("wfd_uibc_setting\r\n").present)
    }

    @Test fun mapsVideoRectangleIntoNegotiatedPixelCoordinates() {
        val layout = VideoLayout(10f, 20f, 100f, 50f)
        assertEquals(TouchPoint(1, 0, 0), TouchMapper.map(MotionPoint(10f, 20f), layout, 1920, 1080, 1, UibcCategory.GENERIC))
        assertEquals(TouchPoint(1, 1919, 1079), TouchMapper.map(MotionPoint(110f, 70f), layout, 1920, 1080, 1, UibcCategory.GENERIC))
        assertNull(TouchMapper.map(MotionPoint(0f, 0f), layout, 1920, 1080, 1, UibcCategory.GENERIC, clamp = false))
    }
}
