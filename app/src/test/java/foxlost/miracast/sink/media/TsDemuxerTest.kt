package foxlost.miracast.sink.media

import org.junit.Assert.assertFalse
import org.junit.Test

class TsDemuxerTest {
    @Test
    fun acceptsTsPacketSplitAcrossRtpPayloads() {
        val nalus = mutableListOf<ByteArray>()
        val demuxer = TsDemuxer({ nalu, _ -> nalus += nalu }, { _, _, _ -> })
        val first = videoTsPacket(256, true, 0)
        val next = videoTsPacket(256, true, 1)

        demuxer.processRtpPayload(first, 0, 73)
        demuxer.processRtpPayload(first, 73, first.size - 73)
        demuxer.processRtpPayload(next, 0, next.size)

        assertFalse(nalus.isEmpty())
    }

    @Test
    fun adaptationOnlyPacketDoesNotCreateContinuityGap() {
        val nalus = mutableListOf<ByteArray>()
        val demuxer = TsDemuxer({ nalu, _ -> nalus += nalu }, { _, _, _ -> })
        val payload = videoTsPacket(256, true, 0)
        val adaptationOnly = ByteArray(188)
        adaptationOnly[0] = 0x47
        adaptationOnly[1] = 0x01
        adaptationOnly[2] = 0x00
        adaptationOnly[3] = 0x27 // adaptation only, continuity counter 7
        adaptationOnly[4] = 183.toByte() // adaptation field fills packet
        demuxer.processRtpPayload(payload, 0, payload.size)
        demuxer.processRtpPayload(adaptationOnly, 0, adaptationOnly.size)
        demuxer.processRtpPayload(videoTsPacket(256, true, 1), 0, 188)

        assertFalse(nalus.isEmpty())
    }

    private fun videoTsPacket(pid: Int, pusi: Boolean, continuity: Int): ByteArray {
        val packet = ByteArray(188) { 0 }
        packet[0] = 0x47
        packet[1] = (((if (pusi) 0x40 else 0) or ((pid ushr 8) and 0x1f))).toByte()
        packet[2] = pid.toByte()
        packet[3] = (0x10 or (continuity and 0x0f)).toByte()
        if (pusi) {
            val pes = byteArrayOf(
                0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5,
                0, 0, 0, 0, 1,
                0, 0, 0, 1, 0x65, 0x11, 0x22,
            )
            pes.copyInto(packet, 4)
        }
        return packet
    }
}
