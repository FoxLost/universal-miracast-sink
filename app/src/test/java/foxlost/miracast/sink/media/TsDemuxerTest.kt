package foxlost.miracast.sink.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TsDemuxerTest {
    @Test
    fun acceptsTsPacketSplitAcrossRtpPayloads() {
        val nalus = mutableListOf<ByteArray>()
        val demuxer = TsDemuxer({ nalu, _ -> nalus += nalu }, { _, _, _, _ -> })
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
        val demuxer = TsDemuxer({ nalu, _ -> nalus += nalu }, { _, _, _, _ -> })
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

    @Test fun completePesEmitsWholeAccessUnitWithItsPtsWithoutNextPacket() {
        val frames = mutableListOf<Pair<ByteArray, Long>>()
        val demux = TsDemuxer({ bytes, pts -> frames += bytes to pts }, { _, _, _, _ -> })
        val au = byteArrayOf(0, 0, 1, 0x65, 0x11, 0, 0, 1, 0x65, 0x22)
        val packet = pesPacket(256, 0xe0, 0, au, 90_000)
        demux.processRtpPayload(packet, 0, packet.size)
        assertEquals(1, frames.size)
        assertArrayEquals(au, frames[0].first)
        assertEquals(1_000_000L, frames[0].second)
    }

    @Test fun audioCallbackCarriesPtsAndConvertsOnlySupportedLpcm() {
        val output = mutableListOf<Pair<ByteArray, Long>>()
        val demux = TsDemuxer({ _, _ -> }, { pcm, rate, channels, pts ->
            assertEquals(48000, rate); assertEquals(2, channels)
            output += pcm to pts
        })
        val body = byteArrayOf(0xa0.toByte(), 1, 0, 0x11, 0x12, 0x34, 0x56, 0x78)
        val packet = pesPacket(257, 0xbd, 0, body, 180_000)
        demux.processRtpPayload(packet, 0, packet.size)
        assertEquals(1, output.size)
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0x78, 0x56), output[0].first)
        assertEquals(2_000_000L, output[0].second)
        body[3] = 0x51 // 24-bit data must not be rendered as PCM16.
        val unsupported = pesPacket(257, 0xbd, 1, body, 181_000)
        demux.processRtpPayload(unsupported, 0, unsupported.size)
        assertEquals(1, output.size)
    }

    @Test fun discontinuityDiscardsPartialPesAndDependentFramesUntilIdr() {
        val output = mutableListOf<ByteArray>()
        val demux = TsDemuxer({ bytes, _ -> output += bytes }, { _, _, _, _ -> })
        val partial = videoTsPacket(256, true, 0)
        demux.processRtpPayload(partial, 0, partial.size)
        demux.discontinuity()
        val continuation = videoTsPacket(256, false, 2)
        demux.processRtpPayload(continuation, 0, continuation.size)
        val dependent = pesPacket(256, 0xe0, 3, byteArrayOf(0, 0, 1, 0x41, 0x11), 90_000)
        demux.processRtpPayload(dependent, 0, dependent.size)
        assertTrue(output.isEmpty())
        val idr = pesPacket(256, 0xe0, 4, byteArrayOf(0, 0, 1, 0x65, 0x22), 93_000)
        demux.processRtpPayload(idr, 0, idr.size)
        assertEquals(1, output.size)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0x65, 0x22), output.single())
    }

    @Test fun continuityGapRequestsRecoveryButAcceptsNextIdr() {
        var recoveries = 0
        val output = mutableListOf<ByteArray>()
        val demux = TsDemuxer({ bytes, _ -> output += bytes }, { _, _, _, _ -> }, { recoveries++ })
        val au = byteArrayOf(0, 0, 1, 0x65, 0x22)
        for (cc in listOf(0, 2)) {
            val packet = pesPacket(256, 0xe0, cc, au, cc * 3000L)
            demux.processRtpPayload(packet, 0, packet.size)
        }
        assertEquals(1, recoveries)
        assertEquals(2, output.size)
    }

    @Test fun nullAndUnselectedPacketsCannotInvalidateVideo() {
        val output = mutableListOf<ByteArray>()
        var recoveries = 0
        val demux = TsDemuxer({ bytes, _ -> output += bytes }, { _, _, _, _ -> }, { recoveries++ })
        val dependent = byteArrayOf(0, 0, 1, 0x41, 0x22)
        demux.feed(pesPacket(256, 0xe0, 0, dependent, 0))
        for (pid in listOf(0x1fff, 900)) {
            demux.feed(tsPacket(pid, true, 0, byteArrayOf(1)))
            demux.feed(tsPacket(pid, true, 7, byteArrayOf(1)))
            val damaged = tsPacket(pid, true, 8, byteArrayOf(1))
            damaged[1] = (damaged[1].toInt() or 0x80).toByte()
            damaged[5] = 0x80.toByte()
            demux.feed(damaged)
            demux.feed(tsPacket(pid, true, 11, byteArrayOf(1)))
        }
        demux.feed(pesPacket(256, 0xe0, 1, dependent, 3000))
        assertEquals(0, recoveries)
        assertEquals(2, output.size)
        assertArrayEquals(dependent, output.last())
    }

    @Test fun audioGapDoesNotPurgeInProgressVideoOrRequestIdr() {
        val video = mutableListOf<ByteArray>()
        val audio = mutableListOf<ByteArray>()
        var recoveries = 0
        val demux = TsDemuxer({ bytes, _ -> video += bytes }, { pcm, _, _, _ -> audio += pcm }, { recoveries++ })
        val au = byteArrayOf(0, 0, 1, 0x41, 0x22, 0x33, 0x44)
        val pcm = byteArrayOf(0xa0.toByte(), 1, 0, 0x11, 1, 2, 3, 4)
        demux.feed(pesPacket(256, 0xe0, 0, au.copyOfRange(0, 5), 0, au.size))
        demux.feed(pesPacket(257, 0xbd, 0, pcm.copyOfRange(0, 5), 0, pcm.size))
        demux.feed(tsPacket(257, false, 2, pcm.copyOfRange(5, pcm.size)))
        demux.feed(tsPacket(256, false, 1, au.copyOfRange(5, au.size)))
        demux.feed(pesPacket(257, 0xbd, 3, pcm, 3000))
        assertEquals(0, recoveries)
        assertEquals(1, video.size)
        assertArrayEquals(au, video.single())
        assertEquals(1, audio.size)
    }

    @Test fun selectedVideoGapDropsPartialAndDependentPesUntilIdr() {
        val output = mutableListOf<ByteArray>()
        var recoveries = 0
        val demux = TsDemuxer({ bytes, _ -> output += bytes }, { _, _, _, _ -> }, { recoveries++ })
        val dependent = byteArrayOf(0, 0, 1, 0x41, 0x22)
        val idr = byteArrayOf(0, 0, 1, 0x65, 0x33)
        demux.feed(pesPacket(256, 0xe0, 0, dependent, 0, 10))
        demux.feed(tsPacket(256, false, 2, dependent))
        demux.feed(pesPacket(256, 0xe0, 3, dependent, 3000))
        assertTrue(output.isEmpty())
        demux.feed(pesPacket(256, 0xe0, 4, idr, 6000))
        assertEquals(1, recoveries)
        assertArrayEquals(idr, output.single())
    }

    @Test fun newPesDropsTruncatedFiniteVideoAndRequiresIdr() {
        val output = mutableListOf<ByteArray>()
        var recoveries = 0
        val demux = TsDemuxer({ bytes, _ -> output += bytes }, { _, _, _, _ -> }, { recoveries++ })
        val idr = byteArrayOf(0, 0, 1, 0x65, 0x22)
        val dependent = byteArrayOf(0, 0, 1, 0x41, 0x33)
        demux.feed(pesPacket(256, 0xe0, 0, idr, 0, 10))
        demux.feed(pesPacket(256, 0xe0, 1, dependent, 3000))
        assertTrue(output.isEmpty())
        demux.feed(pesPacket(256, 0xe0, 2, idr, 6000))
        assertEquals(1, recoveries)
        assertArrayEquals(idr, output.single())
    }

    @Test fun newPesDropsTruncatedFiniteAudioWithoutStallingVideo() {
        val audio = mutableListOf<ByteArray>()
        val video = mutableListOf<ByteArray>()
        var recoveries = 0
        val demux = TsDemuxer({ bytes, _ -> video += bytes }, { pcm, _, _, _ -> audio += pcm }, { recoveries++ })
        val pcm = byteArrayOf(0xa0.toByte(), 1, 0, 0x11, 1, 2, 3, 4)
        val dependent = byteArrayOf(0, 0, 1, 0x41, 0x22)
        demux.feed(pesPacket(256, 0xe0, 0, dependent, 0))
        demux.feed(pesPacket(257, 0xbd, 0, pcm, 0, 12))
        demux.feed(pesPacket(257, 0xbd, 1, pcm, 3000))
        demux.feed(pesPacket(256, 0xe0, 1, dependent, 3000))
        assertEquals(1, audio.size)
        assertArrayEquals(byteArrayOf(2, 1, 4, 3), audio.single())
        assertEquals(2, video.size)
        assertEquals(0, recoveries)
    }

    @Test fun oversizedAudioPesCannotDiscardHealthyVideo() {
        val video = mutableListOf<ByteArray>()
        var recoveries = 0
        val demux = TsDemuxer({ bytes, _ -> video += bytes }, { _, _, _, _ -> }, { recoveries++ })
        val au = byteArrayOf(0, 0, 1, 0x41, 0x22, 0x33, 0x44)
        demux.feed(pesPacket(256, 0xe0, 0, au.copyOfRange(0, 5), 0, au.size))
        demux.feed(pesPacket(257, 0xbd, 0, byteArrayOf(0xa0.toByte(), 1, 0, 0x11), 0, unbounded = true))
        val continuation = ByteArray(180)
        repeat(4 * 1024 * 1024 / continuation.size + 1) {
            demux.feed(tsPacket(257, false, (it + 1) and 15, continuation))
        }
        demux.feed(tsPacket(256, false, 1, au.copyOfRange(5, au.size)))
        assertEquals(0, recoveries)
        assertArrayEquals(au, video.single())
    }

    @Test fun zeroLengthVideoEndsAtNextStartAndDuplicateDoesNotAppend() {
        val output = mutableListOf<ByteArray>()
        val demux = TsDemuxer({ bytes, _ -> output += bytes }, { _, _, _, _ -> })
        val first = byteArrayOf(0, 0, 1, 0x65, 0x22)
        val tail = byteArrayOf(0x33, 0x44)
        demux.feed(pesPacket(256, 0xe0, 0, first, 0, unbounded = true))
        val continuation = tsPacket(256, false, 1, tail)
        demux.feed(continuation)
        demux.feed(continuation)
        assertTrue(output.isEmpty())
        val next = byteArrayOf(0, 0, 1, 0x41, 0x55)
        demux.feed(pesPacket(256, 0xe0, 2, next, 3000))
        assertEquals(2, output.size)
        assertArrayEquals(first + tail, output[0])
        assertArrayEquals(next, output[1])
    }

    @Test fun audioDiscontinuityIndicatorResetsOnlyAudioAndRebasesCounter() {
        val output = mutableListOf<ByteArray>()
        var audioFrames = 0
        var recoveries = 0
        val demux = TsDemuxer({ bytes, _ -> output += bytes }, { _, _, _, _ -> audioFrames++ }, { recoveries++ })
        val au = byteArrayOf(0, 0, 1, 0x41, 0x22, 0x33)
        val pcm = byteArrayOf(0xa0.toByte(), 1, 0, 0x11, 1, 2, 3, 4)
        demux.feed(pesPacket(256, 0xe0, 0, au.copyOfRange(0, 5), 0, au.size))
        demux.feed(pesPacket(257, 0xbd, 0, pcm, 0))
        val discontinuousAudio = pesPacket(257, 0xbd, 0, pcm, 3000)
        discontinuousAudio[5] = 0x80.toByte() // Same CC is not a duplicate when explicitly rebased.
        demux.feed(discontinuousAudio)
        demux.feed(tsPacket(256, false, 1, au.copyOfRange(5, au.size)))
        assertEquals(0, recoveries)
        assertEquals(2, audioFrames)
        assertArrayEquals(au, output.single())
    }

    @Test fun fragmentedPsiAndPsiCounterGapsPreserveHealthyVideo() {
        val output = mutableListOf<ByteArray>()
        var recoveries = 0
        val demux = TsDemuxer({ bytes, _ -> output += bytes }, { _, _, _, _ -> }, { recoveries++ })
        val pat = patSection()
        // Even a section header split after its first byte must be reassembled.
        demux.feed(tsPacket(0, true, 0, byteArrayOf(0) + pat.copyOfRange(0, 1)))
        demux.feed(tsPacket(0, false, 1, pat.copyOfRange(1, pat.size)))
        val pmt = pmtSection(300, 301)
        demux.feed(tsPacket(4096, true, 0, byteArrayOf(0) + pmt.copyOfRange(0, 2)))
        demux.feed(tsPacket(4096, false, 1, pmt.copyOfRange(2, pmt.size)))
        val au = byteArrayOf(0, 0, 1, 0x41, 0x22, 0x33)
        demux.feed(pesPacket(300, 0xe0, 0, au.copyOfRange(0, 5), 0, au.size))
        demux.feed(tsPacket(0, true, 7, byteArrayOf(0) + pat))
        demux.feed(tsPacket(4096, true, 7, byteArrayOf(0) + pmt))
        // A second video-looking PID is unrelated once PMT selected the stream.
        demux.feed(pesPacket(500, 0xe0, 0, au, 0))
        demux.feed(pesPacket(500, 0xe0, 5, au, 0))
        demux.feed(tsPacket(300, false, 1, au.copyOfRange(5, au.size)))
        assertEquals(0, recoveries)
        assertArrayEquals(au, output.single())
    }

    @Test fun actualProgramVideoChangeDiscardsOldPesAndRecoversAtNewIdr() {
        val output = mutableListOf<ByteArray>()
        var recoveries = 0
        val demux = TsDemuxer({ bytes, _ -> output += bytes }, { _, _, _, _ -> }, { recoveries++ })
        val dependent = byteArrayOf(0, 0, 1, 0x41, 0x22)
        val idr = byteArrayOf(0, 0, 1, 0x65, 0x33)
        demux.feed(tsPacket(0, true, 0, byteArrayOf(0) + patSection()))
        demux.feed(tsPacket(4096, true, 0, byteArrayOf(0) + pmtSection(300, 301)))
        demux.feed(pesPacket(300, 0xe0, 0, dependent, 0, 10))
        demux.feed(tsPacket(4096, true, 1, byteArrayOf(0) + pmtSection(302, 301)))
        demux.feed(tsPacket(300, false, 1, dependent))
        demux.feed(pesPacket(302, 0xe0, 0, dependent, 3000))
        assertTrue(output.isEmpty())
        demux.feed(pesPacket(302, 0xe0, 1, idr, 6000))
        assertEquals(1, recoveries)
        assertArrayEquals(idr, output.single())
    }

    @Test fun adaptationOnlyVideoDiscontinuityCannotJoinMissingFragments() {
        val output = mutableListOf<ByteArray>()
        var recoveries = 0
        val demux = TsDemuxer({ bytes, _ -> output += bytes }, { _, _, _, _ -> }, { recoveries++ })
        val idr = byteArrayOf(0, 0, 1, 0x65, 0x22)
        val dependent = byteArrayOf(0, 0, 1, 0x41, 0x33)
        demux.feed(pesPacket(256, 0xe0, 0, idr, 0, 10))
        val reset = tsPacket(256, false, 7, byteArrayOf())
        reset[3] = 0x27
        reset[5] = 0x80.toByte()
        demux.feed(reset)
        demux.feed(tsPacket(256, false, 11, idr))
        demux.feed(pesPacket(256, 0xe0, 12, dependent, 3000))
        assertTrue(output.isEmpty())
        demux.feed(pesPacket(256, 0xe0, 13, idr, 6000))
        assertEquals(1, recoveries)
        assertArrayEquals(idr, output.single())
    }

    private fun patSection() = byteArrayOf(
        0, 0xb0.toByte(), 13, 0, 1, 0xc1.toByte(), 0, 0,
        0, 1, 0xf0.toByte(), 0, 0, 0, 0, 0,
    )

    private fun pmtSection(videoPid: Int, audioPid: Int) = byteArrayOf(
        2, 0xb0.toByte(), 23, 0, 1, 0xc1.toByte(), 0, 0,
        (0xe0 or (videoPid ushr 8)).toByte(), videoPid.toByte(), 0xf0.toByte(), 0,
        0x1b, (0xe0 or (videoPid ushr 8)).toByte(), videoPid.toByte(), 0xf0.toByte(), 0,
        0x83.toByte(), (0xe0 or (audioPid ushr 8)).toByte(), audioPid.toByte(), 0xf0.toByte(), 0,
        0, 0, 0, 0,
    )

    private fun TsDemuxer.feed(packet: ByteArray) = processRtpPayload(packet, 0, packet.size)

    private fun pesPacket(
        pid: Int, stream: Int, cc: Int, body: ByteArray, pts90k: Long,
        declaredBodySize: Int = body.size, unbounded: Boolean = false,
    ): ByteArray {
        val pes = ByteArray(14 + body.size)
        pes[2] = 1; pes[3] = stream.toByte()
        val size = if (unbounded) 0 else 8 + declaredBodySize
        pes[4] = (size ushr 8).toByte(); pes[5] = size.toByte()
        pes[6] = 0x80.toByte(); pes[7] = 0x80.toByte(); pes[8] = 5
        pes[9] = (0x21 or (((pts90k ushr 30).toInt() and 7) shl 1)).toByte()
        pes[10] = (pts90k ushr 22).toByte()
        pes[11] = (((pts90k ushr 15).toInt() shl 1) or 1).toByte()
        pes[12] = (pts90k ushr 7).toByte()
        pes[13] = ((pts90k.toInt() shl 1) or 1).toByte()
        body.copyInto(pes, 14)
        return tsPacket(pid, true, cc, pes)
    }

    private fun tsPacket(pid: Int, pusi: Boolean, cc: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 182)
        val packet = ByteArray(188)
        packet[0] = 0x47; packet[1] = ((if (pusi) 0x40 else 0) or (pid ushr 8)).toByte()
        packet[2] = pid.toByte(); packet[3] = (0x30 or cc).toByte()
        val adaptationLength = 183 - payload.size
        packet[4] = adaptationLength.toByte()
        payload.copyInto(packet, 5 + adaptationLength)
        return packet
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
