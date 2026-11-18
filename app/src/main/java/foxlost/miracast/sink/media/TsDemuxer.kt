package foxlost.miracast.sink.media

import foxlost.miracast.sink.DebugEventCategory
import foxlost.miracast.sink.DebugEventLog
import java.io.ByteArrayOutputStream

/** MPEG-TS demuxer for complete or fragmented RTP payloads. */
class TsDemuxer(
    private val onVideoNaluExtracted: (nalu: ByteArray, ptsUs: Long) -> Unit,
    private val onAudioFrameExtracted: (pcmData: ByteArray, sampleRate: Int, channels: Int, ptsUs: Long) -> Unit,
    // Requests video reference recovery, not a global A/V reset. Known audio/PSI
    // transport loss never invokes this; explicit discontinuity() is caller-owned.
    private val onDiscontinuity: (() -> Unit)? = null,
) {
    private var videoPid = -1
    private var audioPid = -1
    private var pmtPid = -1
    private var hasProgramMap = false
    private val videoPesBuffer = ByteArrayOutputStream(256 * 1024)
    private var currentVideoPtsUs = 0L
    private val audioPesBuffer = ByteArrayOutputStream(256 * 1024)
    private var currentAudioPtsUs = 0L
    private val carry = ByteArrayOutputStream(188)
    private val patSection = ByteArrayOutputStream(1024)
    private val pmtSection = ByteArrayOutputStream(1024)
    private val continuity = HashMap<Int, Int>()
    private var videoActive = false
    private var audioActive = false
    private var awaitingIdr = false
    private var videoRemaining = -1
    private var audioRemaining = -1

    /** A known PID loss must not invalidate another elementary stream. */
    private fun discardPid(pid: Int) {
        continuity.remove(pid)
        when (pid) {
            0 -> patSection.reset()
            pmtPid -> pmtSection.reset()
            videoPid -> {
                videoPesBuffer.reset()
                videoActive = false
                videoRemaining = -1
                awaitingIdr = true
                onDiscontinuity?.invoke()
            }
            audioPid -> {
                audioPesBuffer.reset()
                audioActive = false
                audioRemaining = -1
            }
        }
    }

    /** Discard partial transport/PES state; never concatenate across a lost datagram. */
    fun discontinuity() {
        carry.reset(); patSection.reset(); pmtSection.reset()
        videoPesBuffer.reset(); audioPesBuffer.reset(); continuity.clear()
        videoActive = false; audioActive = false
        videoRemaining = -1; audioRemaining = -1; awaitingIdr = true
    }

    /** Accepts an arbitrary slice; an RTP payload may end in the middle of a TS packet. */
    fun processRtpPayload(payload: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > payload.size - length) return
        var cursor = offset
        val end = offset + length
        var startedPacket = false
        if (cursor < end && payload[cursor] == 0x47.toByte()) startedPacket = true
        if (carry.size() > 0) {
            val needed = 188 - carry.size()
            val take = minOf(needed, end - cursor)
            if (take > 0) carry.write(payload, cursor, take)
            cursor += take
            if (carry.size() == 188) {
                val packet = carry.toByteArray()
                if (packet[0] == 0x47.toByte()) parseTsPacket(packet, 0)
                else DebugEventLog.recordRateLimited(DebugEventCategory.MEDIA, "ts-sync", "Discarding TS carry-over without sync")
                carry.reset()
                startedPacket = true
            }
        }
        while (cursor + 188 <= end) {
            if (payload[cursor] == 0x47.toByte()) {
                parseTsPacket(payload, cursor)
                cursor += 188
                startedPacket = true
            } else {
                val sync = findSync(payload, cursor, end)
                if (sync < 0) { cursor = end; break }
                cursor = sync
            }
        }
        if (cursor < end && startedPacket) {
            // Preserve all bytes after a validated TS start, even when this
            // fragment is the middle of the next packet.
            carry.write(payload, cursor, end - cursor)
        } else if (cursor < end && end - cursor < 188 && payload[cursor] == 0x47.toByte()) {
            carry.write(payload, cursor, end - cursor)
        }
    }

    private fun findSync(data: ByteArray, start: Int, end: Int): Int {
        for (i in start until end) if (data[i] == 0x47.toByte()) return i
        return -1
    }

    private fun parseTsPacket(packet: ByteArray, offset: Int) {
        if (offset < 0 || offset > packet.size - 188 || packet[offset] != 0x47.toByte()) return
        val b1 = packet[offset + 1].toInt() and 0xff
        val b2 = packet[offset + 2].toInt() and 0xff
        val b3 = packet[offset + 3].toInt() and 0xff
        val payloadStart = (b1 and 0x40) != 0
        val pid = ((b1 and 0x1f) shl 8) or b2
        // Null packets have no continuity semantics, including their TEI/flags.
        if (pid == 0x1fff) return
        val adaptationControl = (b3 ushr 4) and 0x03
        val counter = b3 and 0x0f
        if (adaptationControl == 0) return
        val hasPayload = adaptationControl == 1 || adaptationControl == 3
        var payloadOffset = offset + 4
        var signaledDiscontinuity = false
        if (adaptationControl == 2 || adaptationControl == 3) {
            val adaptationLength = packet[payloadOffset].toInt() and 0xff
            if (adaptationLength > offset + 188 - payloadOffset - 1) {
                discardPid(pid)
                return
            }
            signaledDiscontinuity = adaptationLength > 0 && packet[payloadOffset + 1].toInt() and 0x80 != 0
            payloadOffset += 1 + adaptationLength
        }
        val payloadLength = offset + 188 - payloadOffset
        // Discover fallback streams before tracking their first payload counter.
        if (!hasProgramMap && hasPayload && payloadStart && b1 and 0x80 == 0 && isPesHeader(packet, payloadOffset, payloadLength)) {
            val streamId = packet[payloadOffset + 3].toInt() and 0xff
            if (pid != 0 && pid != pmtPid && pid != 4096) {
                if (videoPid == -1 && streamId in 0xe0..0xef) videoPid = pid
                if (audioPid == -1 && pid != videoPid && (streamId in 0xc0..0xdf || streamId == 0xbd)) audioPid = pid
            }
        }
        if (pid != 0 && pid != pmtPid && pid != videoPid && pid != audioPid) return
        if (b1 and 0x80 != 0) { discardPid(pid); return }
        if (signaledDiscontinuity) discardPid(pid)
        if (!hasPayload || payloadLength <= 0) return
        val previous = continuity[pid]
        if (previous != null && previous == counter) return
        if (previous != null && ((previous + 1) and 0x0f) != counter) {
            DebugEventLog.recordRateLimited(DebugEventCategory.MEDIA, "ts-continuity", "TS continuity gap pid=$pid expected=${(previous + 1) and 0xf} got=$counter")
            discardPid(pid)
        }
        // Adaptation-only packets do not advance the payload continuity state.
        continuity[pid] = counter
        when {
            pid == 0 && (payloadStart || patSection.size() > 0) -> {
                consumePsi(pid, packet, payloadOffset, payloadLength, payloadStart); return
            }
            pmtPid >= 0 && pid == pmtPid && (payloadStart || pmtSection.size() > 0) -> {
                consumePsi(pid, packet, payloadOffset, payloadLength, payloadStart); return
            }
        }
        when {
            pid == videoPid -> {
                if (payloadStart) {
                    if (videoRemaining > 0) discardPid(videoPid) else flushVideoPesBuffer()
                    continuity[pid] = counter
                    parsePesHeaderAndExtractPts(packet, payloadOffset, payloadLength)
                }
                else if (videoActive) appendVideo(packet, payloadOffset, payloadLength)
            }
            pid == audioPid -> {
                if (payloadStart) {
                    if (audioRemaining > 0) discardPid(audioPid) else flushAudioPesBuffer()
                    continuity[pid] = counter
                    parseAudioPesHeader(packet, payloadOffset, payloadLength)
                }
                else if (audioActive) appendAudio(packet, payloadOffset, payloadLength)
            }
        }
    }


    private fun consumePsi(pid: Int, packet: ByteArray, offset: Int, length: Int, pusi: Boolean) {
        val section = if (pid == 0) patSection else pmtSection
        var start = offset
        var remaining = length
        if (pusi) {
            if (remaining == 0) return
            val pointer = packet[start].toInt() and 0xff
            start++; remaining--
            if (pointer > remaining) { section.reset(); return }
            if (pointer > 0) {
                if (section.size() > 0) consumePsi(pid, packet, start, pointer, false)
                section.reset()
                start += pointer; remaining -= pointer
            } else {
                section.reset()
            }
        }
        while (remaining > 0) {
            val wanted = expectedSectionSize(section)
            val take = minOf(remaining, if (wanted == null) 3 - section.size() else wanted - section.size())
            if (take <= 0) { parseCompletedPsi(pid, section); section.reset(); continue }
            section.write(packet, start, take)
            start += take; remaining -= take
            if (expectedSectionSize(section)?.let { section.size() >= it } == true) {
                parseCompletedPsi(pid, section); section.reset()
            }
        }
    }

    private fun expectedSectionSize(section: ByteArrayOutputStream): Int? {
        if (section.size() < 3) return null
        val bytes = section.toByteArray()
        val sectionLength = ((bytes[1].toInt() and 0x0f) shl 8) or (bytes[2].toInt() and 0xff)
        if (sectionLength > 1021) return 3
        return 3 + sectionLength
    }

    private fun parseCompletedPsi(pid: Int, section: ByteArrayOutputStream) {
        val bytes = section.toByteArray()
        if (bytes.size < 3) return
        val expected = expectedSectionSize(section) ?: return
        if (expected != bytes.size || expected < 7) return
        if (pid == 0 && bytes[0].toInt() and 0xff == 0) parsePat(bytes)
        if (pid == pmtPid && bytes[0].toInt() and 0xff == 2) parsePmt(bytes)
    }

    private fun parsePat(section: ByteArray) {
        val sectionEnd = section.size - 4
        var q = 8
        while (q + 4 <= sectionEnd) {
            val program = ((section[q].toInt() and 0xff) shl 8) or (section[q + 1].toInt() and 0xff)
            val pid = ((section[q + 2].toInt() and 0x1f) shl 8) or (section[q + 3].toInt() and 0xff)
            if (program != 0 && pid != 0) {
                if (pmtPid != pid) {
                    continuity.remove(pmtPid)
                    pmtSection.reset()
                    pmtPid = pid
                }
                break
            }
            q += 4
        }
    }

    private fun parsePmt(section: ByteArray) {
        if (section.size < 16) return
        val sectionEnd = section.size - 4
        val programInfoLength = ((section[10].toInt() and 0x0f) shl 8) or (section[11].toInt() and 0xff)
        var q = 12 + programInfoLength
        if (q > sectionEnd) return
        var nextVideoPid = -1
        var nextAudioPid = -1
        while (q + 5 <= sectionEnd) {
            val streamType = section[q].toInt() and 0xff
            val elemPid = ((section[q + 1].toInt() and 0x1f) shl 8) or (section[q + 2].toInt() and 0xff)
            val infoLength = ((section[q + 3].toInt() and 0x0f) shl 8) or (section[q + 4].toInt() and 0xff)
            if (q + 5 + infoLength > sectionEnd) return
            when (streamType) {
                0x1b -> if (nextVideoPid < 0) nextVideoPid = elemPid // H.264, not HEVC.
                0x83 -> if (nextAudioPid < 0) nextAudioPid = elemPid // WFD LPCM only.
            }
            q += 5 + infoLength
        }
        if (q != sectionEnd) return
        if (videoPid != nextVideoPid) {
            if (videoPid >= 0) discardPid(videoPid)
            continuity.remove(nextVideoPid)
            videoPid = nextVideoPid
        }
        if (audioPid != nextAudioPid) {
            if (audioPid >= 0) discardPid(audioPid)
            continuity.remove(nextAudioPid)
            audioPid = nextAudioPid
        }
        hasProgramMap = true
    }

    private fun isPesHeader(packet: ByteArray, offset: Int, length: Int): Boolean =
        length >= 6 && packet[offset] == 0.toByte() && packet[offset + 1] == 0.toByte() && packet[offset + 2] == 1.toByte()


    private fun parsePesHeaderAndExtractPts(packet: ByteArray, offset: Int, length: Int) {
        videoActive = false
        if (!isPesHeader(packet, offset, length) || length < 9) return
        val flags = packet[offset + 7].toInt() and 0xff
        val headerDataLength = packet[offset + 8].toInt() and 0xff
        if (headerDataLength > length - 9) { videoPesBuffer.reset(); return }
        if (((flags ushr 6) and 3) >= 2 && headerDataLength >= 5) currentVideoPtsUs = ptsUs(packet, offset + 9)
        val bodyOffset = offset + 9 + headerDataLength
        val pesLength = ((packet[offset + 4].toInt() and 0xff) shl 8) or (packet[offset + 5].toInt() and 0xff)
        if (pesLength != 0 && pesLength < 3 + headerDataLength) return
        videoRemaining = if (pesLength == 0) -1 else pesLength - 3 - headerDataLength
        videoActive = videoRemaining != 0
        appendVideo(packet, bodyOffset, offset + length - bodyOffset)
    }

    private fun parseAudioPesHeader(packet: ByteArray, offset: Int, length: Int) {
        audioActive = false
        if (!isPesHeader(packet, offset, length) || length < 9) return
        val headerDataLength = packet[offset + 8].toInt() and 0xff
        if (headerDataLength > length - 9) { audioPesBuffer.reset(); return }
        val flags = packet[offset + 7].toInt() and 0xff
        if (((flags ushr 6) and 3) >= 2 && headerDataLength >= 5) currentAudioPtsUs = ptsUs(packet, offset + 9)
        val bodyOffset = offset + 9 + headerDataLength
        val pesLength = ((packet[offset + 4].toInt() and 0xff) shl 8) or (packet[offset + 5].toInt() and 0xff)
        if (pesLength != 0 && pesLength < 3 + headerDataLength) return
        audioRemaining = if (pesLength == 0) -1 else pesLength - 3 - headerDataLength
        audioActive = audioRemaining != 0
        appendAudio(packet, bodyOffset, offset + length - bodyOffset)
    }

    private fun ptsUs(packet: ByteArray, offset: Int): Long {
        val pts90k = ((packet[offset].toLong() and 0x0e) shl 29) or
            ((packet[offset + 1].toLong() and 0xff) shl 22) or
            ((packet[offset + 2].toLong() and 0xfe) shl 14) or
            ((packet[offset + 3].toLong() and 0xff) shl 7) or
            ((packet[offset + 4].toLong() and 0xfe) ushr 1)
        return pts90k * 1000L / 90L
    }

    private fun appendVideo(data: ByteArray, offset: Int, length: Int) {
        val take = if (videoRemaining >= 0) minOf(length, videoRemaining) else length
        appendBounded(videoPesBuffer, data, offset, take, videoPid)
        if (videoRemaining >= 0) {
            videoRemaining -= take
            if (videoRemaining == 0) { flushVideoPesBuffer(); videoActive = false }
        }
    }

    private fun appendAudio(data: ByteArray, offset: Int, length: Int) {
        val take = if (audioRemaining >= 0) minOf(length, audioRemaining) else length
        appendBounded(audioPesBuffer, data, offset, take, audioPid)
        if (audioRemaining >= 0) {
            audioRemaining -= take
            if (audioRemaining == 0) { flushAudioPesBuffer(); audioActive = false }
        }
    }

    private fun flushVideoPesBuffer() {
        if (videoPesBuffer.size() == 0) return
        val data = videoPesBuffer.toByteArray(); videoPesBuffer.reset()
        var hasIdr = false
        var hasSlice = false
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                val type = data[i + 3].toInt() and 0x1f
                if (type == 5) hasIdr = true
                if (type in 1..5) hasSlice = true
                i += 3
            } else i++
        }
        if (awaitingIdr && hasSlice && !hasIdr) return
        if (hasIdr) awaitingIdr = false
        // Keep SPS/PPS and all slices together: one PES carries the source AU and its PTS.
        onVideoNaluExtracted(data, currentVideoPtsUs)
    }

    private fun flushAudioPesBuffer() {
        if (audioPesBuffer.size() <= 4) { audioPesBuffer.reset(); return }
        val raw = audioPesBuffer.toByteArray(); audioPesBuffer.reset()
        // WFD LPCM: four-byte private header, 16-bit big-endian stereo 48 kHz.
        // Refuse unsupported source formats rather than rendering compressed bytes as PCM.
        val format = raw[3].toInt() and 0xff
        if (raw[0].toInt() and 0xff != 0xa0 || format != 0x11) return
        val pcm = ByteArray((raw.size - 4) and -4)
        var i = 0
        while (i + 1 < pcm.size) { pcm[i] = raw[i + 5]; pcm[i + 1] = raw[i + 4]; i += 2 }
        onAudioFrameExtracted(pcm, 48000, 2, currentAudioPtsUs)
    }

    private fun appendBounded(out: ByteArrayOutputStream, data: ByteArray, offset: Int, length: Int, pid: Int) {
        if (length <= 0 || offset < 0 || offset > data.size - length) return
        if (out.size() + length > MAX_PES_BYTES) {
            discardPid(pid)
            DebugEventLog.recordRateLimited(DebugEventCategory.MEDIA, "ts-pes-size", "Dropping oversized PES"); return
        }
        out.write(data, offset, length)
    }

    private companion object { const val MAX_PES_BYTES = 4 * 1024 * 1024 }
}
