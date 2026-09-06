package foxlost.miracast.sink.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpPacketTest {
    @Test
    fun parsesCsrcAndExtensionAndPreservesPayloadSlice() {
        val packet = ByteArray(12 + 4 + 4 + 4 + 3)
        packet[0] = 0x91.toByte() // V=2, X=1, CC=1
        packet[1] = 0xE0.toByte() // M=1, PT=96
        packet[2] = 0x12
        packet[3] = 0x34
        packet[4] = 0x01
        packet[8] = 0x55
        packet[12] = 1 // CSRC
        packet[16] = 0xBE.toByte() // extension profile
        packet[18] = 0 // one extension word
        packet[19] = 1
        packet[24] = 10
        packet[25] = 11
        packet[26] = 12

        val parsed = RtpPacket.parse(packet)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.version)
        assertEquals(1, parsed.csrcCount)
        assertTrue(parsed.extension)
        assertTrue(parsed.marker)
        assertEquals(96, parsed.payloadType)
        assertEquals(0x1234, parsed.sequenceNumber)
        assertEquals(3, parsed.payloadLength)
        assertEquals(10, packet[parsed.payloadOffset].toInt())
    }

    @Test
    fun removesPaddingFromPayload() {
        val packet = ByteArray(12 + 3 + 2)
        packet[0] = 0xA0.toByte() // V=2, P=1
        packet[1] = 96
        packet[12] = 1
        packet[13] = 2
        packet[14] = 3
        packet[15] = 0
        packet[16] = 2 // two padding bytes

        val parsed = RtpPacket.parse(packet)
        assertNotNull(parsed)
        assertEquals(3, parsed!!.payloadLength)
        assertTrue(parsed.padding)
    }
    
    @Test
    fun acceptsZeroLengthHeaderExtension() {
        val packet = ByteArray(12 + 4 + 2)
        packet[0] = 0x90.toByte() // V=2, X=1, CC=0
        packet[1] = 96
        packet[16] = 0x12
        packet[17] = 0x34

        val parsed = RtpPacket.parse(packet)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.payloadLength)
        assertEquals(16, parsed.payloadOffset)
    }

    @Test
    fun rejectsMalformedVersionAndHeaderExtension() {
        val wrongVersion = ByteArray(12)
        wrongVersion[0] = 0x40
        assertNull(RtpPacket.parse(wrongVersion))

        val truncatedExtension = ByteArray(16)
        truncatedExtension[0] = 0x90.toByte()
        truncatedExtension[15] = 1 // one extension word is missing
        assertNull(RtpPacket.parse(truncatedExtension))
    }
}
