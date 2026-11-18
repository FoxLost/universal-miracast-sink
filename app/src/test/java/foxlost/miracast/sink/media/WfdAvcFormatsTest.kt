package foxlost.miracast.sink.media

import foxlost.miracast.sink.rtsp.WfdCapabilities
import org.junit.Assert.*
import org.junit.Test

class WfdAvcFormatsTest {
    private fun cea(value: String) = value.split(' ')[4].toLong(16)
    private val capable = listOf(
        WfdAvcFormats.Decoder(1, 16) { _, _, _ -> true },
        WfdAvcFormats.Decoder(2, 16) { _, _, _ -> true },
    )

    @Test fun allFourChoicesOfferOnlyTheirExactTimingInEverySupportedProfile() {
        for ((height, fps, bit) in listOf(
            Triple(720, 30, 5), Triple(720, 60, 6),
            Triple(1080, 30, 7), Triple(1080, 60, 8),
        )) {
            val value = WfdAvcFormats.build(height, fps, capable)
            val parts = value.split(',')
            assertEquals(bit shl 3, parts[0].substringBefore(' ').toInt(16))
            assertEquals("00", parts[0].split(' ')[1])
            val profiles = mutableSetOf<Int>()
            for ((index, part) in parts.withIndex()) {
                val fields = part.trim().split(' ').let { if (index == 0) it.drop(2) else it }
                profiles += fields[0].toInt(16)
                assertEquals(1L shl bit, fields[2].toLong(16))
                assertEquals(0L, fields[3].toLong(16))
                assertEquals(0L, fields[4].toLong(16))
            }
            assertEquals(setOf(1, 2), profiles)
        }
    }

    @Test fun unsupportedChoiceNeverFallsBackToAnotherResolutionOrRate() {
        val only720p30 = listOf(WfdAvcFormats.Decoder(1, 16) { w, h, rate ->
            w == 1280 && h == 720 && rate == 30.0
        })
        assertEquals(1L shl 5, cea(WfdAvcFormats.build(720, 30, only720p30)))
        for ((height, fps) in listOf(720 to 60, 1080 to 30, 1080 to 60)) {
            assertEquals("none", WfdAvcFormats.build(height, fps, only720p30))
        }
        val onlySd = listOf(WfdAvcFormats.Decoder(1, 16) { _, h, _ -> h <= 576 })
        assertEquals("none", WfdAvcFormats.build(720, 30, onlySd))
    }

    @Test fun eachTimingRequiresItsH264LevelEvenWhenTheCodecClaimsSizeAndRateSupport() {
        for ((height, fps, required) in listOf(
            Triple(720, 30, 1), Triple(720, 60, 2),
            Triple(1080, 30, 4), Triple(1080, 60, 16),
        )) {
            for (level in listOf(1, 2, 4, 8, 16)) {
                val value = WfdAvcFormats.build(height, fps,
                    listOf(WfdAvcFormats.Decoder(1, level) { _, _, _ -> true }))
                if (level < required) assertEquals("none", value)
                else {
                    val selection = value.replaceFirst(Regex("^.. .. "), "00 00 ")
                    assertNull(WfdCapabilities.validateSelection(
                        "wfd_video_formats: $selection\r\n", "wfd_video_formats: $value\r\n"))
                }
            }
        }
    }

    @Test fun anUnsupportedHigherLevelCodecDoesNotMaskAnotherUsableCodec() {
        val decoders = listOf(
            WfdAvcFormats.Decoder(1, 16) { _, h, _ -> h <= 720 },
            WfdAvcFormats.Decoder(1, 4) { _, _, _ -> true },
        )
        val value = WfdAvcFormats.build(1080, 30, decoders)
        assertEquals("04", value.split(' ')[3])
        assertEquals(1L shl 7, cea(value))
        assertEquals(value, WfdAvcFormats.build(1080, 30, decoders.reversed()))
    }

    @Test fun highProfileHdDoesNotDependOnAFabricatedBaselineOrSdOffer() {
        val highOnly = listOf(WfdAvcFormats.Decoder(2, 16) { w, h, rate ->
            w == 1920 && h == 1080 && rate == 60.0
        })
        val value = WfdAvcFormats.build(1080, 60, highOnly)
        assertEquals("02", value.split(' ')[2])
        assertEquals(1L shl 8, cea(value))
        assertFalse(value.contains(','))
    }

    @Test fun absentOrUnrecognizedDecodersDoNotProduceAVideoOffer() {
        assertEquals("none", WfdAvcFormats.build(1080, 60, emptyList()))
        assertEquals("none", WfdAvcFormats.build(1080, 60,
            listOf(WfdAvcFormats.Decoder(3, 16) { _, _, _ -> true })))
        assertEquals("none", WfdAvcFormats.build(1080, 60,
            listOf(WfdAvcFormats.Decoder(1, 32) { _, _, _ -> true })))
    }

    @Test fun sourcesCannotSelectSdPalOrAnyOtherHdTimingInsteadOfTheRequestedMode() {
        for ((height, fps, bit) in listOf(
            Triple(720, 30, 5), Triple(720, 60, 6),
            Triple(1080, 30, 7), Triple(1080, 60, 8),
        )) {
            val offer = "wfd_video_formats: ${WfdAvcFormats.build(height, fps, capable)}\r\n"
            for (selectedBit in 0..16) {
                val bitmap = (1L shl selectedBit).toString(16).padStart(8, '0')
                val selected = "wfd_video_formats: 00 00 02 10 $bitmap 00000000 00000000 00 0000 0000 00 none none\r\n"
                val error = WfdCapabilities.validateSelection(selected, offer)
                if (selectedBit == bit) assertNull(error) else assertNotNull(error)
            }
        }
    }

    @Test fun spsProfileIsReadFromBothAnnexBStartCodesAndMalformedHeadersAreRejected() {
        val baseline = byteArrayOf(0, 0, 1, 0x67, 66, 0, 31, 0)
        val high = byteArrayOf(0, 0, 0, 1, 0x67, 100, 0, 42, 0)
        assertEquals(1, AvcSpsProfile.read(baseline)?.first)
        assertEquals(8, AvcSpsProfile.read(high)?.first)
        assertNull(AvcSpsProfile.read(byteArrayOf(0, 0, 1, 0x67)))
        assertNull(AvcSpsProfile.read(baseline.copyOf().apply { this[3] = 0x65 }))
    }
}
