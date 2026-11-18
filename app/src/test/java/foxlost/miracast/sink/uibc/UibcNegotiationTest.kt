package foxlost.miracast.sink.uibc

import org.junit.Assert.*
import org.junit.Test

class UibcNegotiationTest {
    @Test fun windowsHidcSelectionChoosesOnlyUsbMultiTouch() {
        val result = UibcCapabilityParser.parse("input_category_list=HIDC;generic_cap_list=none;hidc_cap_list=Keyboard/USB, Mouse/USB, SingleTouch/USB, MultiTouch/USB;port=50000")
        assertTrue(result.valid)
        assertEquals(50000, result.port)
        assertEquals(UibcCategory.HIDC, result.category)
        assertEquals(UibcInputPath.USB, result.path)
        assertEquals(UibcCapability.MultiTouch, result.hidType)
        assertEquals(setOf(UibcCapability.MultiTouch), result.capabilities)
    }

    @Test fun unsupportedPathsCannotLeakIntoSelectedCapabilities() {
        val selected = UibcCapabilityParser.parse("input_category_list=HIDC;hidc_cap_list=SingleTouch/USB, MultiTouch/BT;port=50000")
        assertTrue(selected.valid)
        assertEquals(setOf(UibcCapability.SingleTouch), selected.capabilities)
        assertEquals(UibcCapability.SingleTouch, selected.hidType)
        assertFalse(UibcCapabilityParser.parse("input_category_list=HIDC;hidc_cap_list=MultiTouch/BT;port=50000").valid)
        assertFalse(UibcCapabilityParser.parse("input_category_list=HIDC;hidc_cap_list=MultiTouch;port=50000").valid)
    }

    @Test fun genericFallbackDoesNotUnionUnsupportedHid() {
        val result = UibcCapabilityParser.parse("input_category_list=GENERIC,HIDC;generic_cap_list=SingleTouch;hidc_cap_list=MultiTouch/BT;port=none")
        assertTrue(result.valid)
        assertNull(result.port)
        assertNull(result.path)
        assertNull(result.hidType)
        assertEquals(UibcCategory.GENERIC, result.category)
        assertEquals(setOf(UibcCapability.SingleTouch), result.capabilities)
    }

    @Test fun malformedAndDuplicateFieldsAndParametersAreRejected() {
        val valid = "input_category_list=GENERIC;generic_cap_list=SingleTouch;port=50000"
        listOf(";port=50001", ";generic-cap-list=MultiTouch", ";input_category_list=HIDC", ";hidc_cap_list=none;hidc_cap_list=none", ";", ";unknown=x").forEach {
            assertFalse(it, UibcCapabilityParser.parse(valid + it).valid)
        }
        listOf("SingleTouch,SingleTouch", "SingleTouch/USB", "SingleTouch MultiTouch", "none,SingleTouch").forEach {
            assertFalse(UibcCapabilityParser.parse("input_category_list=GENERIC;generic_cap_list=$it;port=50000").valid)
        }
        assertFalse(UibcCapabilityParser.parseBody("wfd_uibc_capability: $valid\r\nwfd_uibc_capability: $valid\r\n").valid)
        assertFalse(UibcCapabilityParser.parseSettingBody("wfd_uibc_setting: enable\r\nwfd_uibc_setting: disable\r\n").valid)
        assertFalse(UibcCapabilityParser.parse(valid.replace("50000", "+50000")).valid)
    }
}
