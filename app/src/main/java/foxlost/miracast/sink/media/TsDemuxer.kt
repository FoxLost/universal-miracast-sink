package foxlost.miracast.sink.media

import android.util.Log
import java.io.ByteArrayOutputStream

/** MPEG-TS demuxer for complete or fragmented RTP payloads. */
class TsDemuxer(
    private val onVideoNaluExtracted: (nalu: ByteArray, ptsUs: Long) -> Unit,
    private val onAudioFrameExtracted: (pcmData: ByteArray, sampleRate: Int, channels: Int) -> Unit,
) {
    private var videoPid = -1
    private var audioPid = -1
    private var pmtPid = -1
    private val videoPesBuffer = ByteArrayOutputStream(256 * 1024)
    private var currentVideoPtsUs = 0L
    private val audioPesBuffer = ByteArrayOutputStream(256 * 1024)
    private var currentAudioPtsUs = 0L
    private val carry = ByteArrayOutputStream(188)
    private val patSection = ByteArrayOutputStream(1024)
    private val pmtSection = ByteArrayOutputStream(1024)
    private val continuity = HashMap<Int, Int>()

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
                else Log.w(TAG, "Discarding TS carry-over without sync")
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
        val adaptationControl = (b3 ushr 4) and 0x03
        val counter = b3 and 0x0f
        if (adaptationControl == 0) return
        val hasPayload = adaptationControl == 1 || adaptationControl == 3
        val previous = continuity[pid]
        if (hasPayload && previous != null && ((previous + 1) and 0x0f) != counter) {
            Log.w(TAG, "TS continuity gap pid=$pid expected=${(previous + 1) and 0xf} got=$counter")
            if (pid == 0) patSection.reset()
            if (pid == pmtPid) pmtSection.reset()
            if (pid == videoPid) videoPesBuffer.reset()
            if (pid == audioPid) audioPesBuffer.reset()
        }
        var payloadOffset = offset + 4
        if (adaptationControl == 2 || adaptationControl == 3) {
            if (payloadOffset >= offset + 188) return
            val adaptationLength = packet[payloadOffset].toInt() and 0xff
            if (adaptationLength > offset + 188 - payloadOffset - 1) return
            payloadOffset += 1 + adaptationLength
        }
        if (!hasPayload || payloadOffset >= offset + 188) return
        val payloadLength = offset + 188 - payloadOffset
        // Adaptation-only packets do not advance the payload continuity state.
        if (hasPayload) continuity[pid] = counter
        when {
            pid == 0 && isPsiStart(packet, payloadOffset, payloadLength, payloadStart, 0x00) -> {
                consumePsi(pid, packet, payloadOffset, payloadLength, payloadStart); return
            }
            pmtPid >= 0 && pid == pmtPid && isPsiStart(packet, payloadOffset, payloadLength, payloadStart, 0x02) -> {
                consumePsi(pid, packet, payloadOffset, payloadLength, payloadStart); return
            }
            pid == 0 && !payloadStart && patSection.size() > 0 -> {
                consumePsi(pid, packet, payloadOffset, payloadLength, false); return
            }
            pmtPid >= 0 && pid == pmtPid && !payloadStart && pmtSection.size() > 0 -> {
                consumePsi(pid, packet, payloadOffset, payloadLength, false); return
            }
        }
        if (videoPid == -1 && pid != 0 && pid != 4096 && isPesHeader(packet, payloadOffset, payloadLength)) {
            val streamId = packet[payloadOffset + 3].toInt() and 0xff
            if (streamId in 0xe0..0xef) videoPid = pid

        }
        if (audioPid == -1 && pid != 0 && pid != 4096 && pid != videoPid && isPesHeader(packet, payloadOffset, payloadLength)) {
            val streamId = packet[payloadOffset + 3].toInt() and 0xff
            if (streamId in 0xc0..0xdf || streamId == 0xbd) audioPid = pid
        }
        when {
            pid == videoPid || (videoPid == -1 && isVideoStreamId(packet, payloadOffset, payloadLength)) -> {
                if (payloadStart) { flushVideoPesBuffer(); parsePesHeaderAndExtractPts(packet, payloadOffset, payloadLength) }
                else appendBounded(videoPesBuffer, packet, payloadOffset, payloadLength)
            }
            pid == audioPid -> {
                if (payloadStart) { flushAudioPesBuffer(); parseAudioPesHeader(packet, payloadOffset, payloadLength) }
                else appendBounded(audioPesBuffer, packet, payloadOffset, payloadLength)
            }
        }
    }
    private fun isPsiStart(packet: ByteArray, offset: Int, length: Int, pusi: Boolean, tableId: Int): Boolean {
        if (!pusi || length < 4) return false
        val pointer = packet[offset].toInt() and 0xff
        if (pointer + 4 > length) return false
        val sectionOffset = offset + 1 + pointer
        if ((packet[sectionOffset].toInt() and 0xff) != tableId) return false
        val sectionLength = ((packet[sectionOffset + 1].toInt() and 0x0f) shl 8) or
            (packet[sectionOffset + 2].toInt() and 0xff)
        return sectionLength >= 9
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
                section.write(packet, start, pointer)
                parseCompletedPsi(pid, section)
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
            if (program != 0 && pid != 0) { pmtPid = pid; Log.d(TAG, "PAT: PMT PID=$pid") ; break }
            q += 4
        }
    }

    private fun parsePmt(section: ByteArray) {
        if (section.size < 16) return
        val sectionEnd = section.size - 4
        val programInfoLength = ((section[10].toInt() and 0x0f) shl 8) or (section[11].toInt() and 0xff)
        var q = 12 + programInfoLength
        while (q + 5 <= sectionEnd) {
            val streamType = section[q].toInt() and 0xff
            val elemPid = ((section[q + 1].toInt() and 0x1f) shl 8) or (section[q + 2].toInt() and 0xff)
            val infoLength = ((section[q + 3].toInt() and 0x0f) shl 8) or (section[q + 4].toInt() and 0xff)
            if (q + 5 + infoLength > sectionEnd) return
            when (streamType) {
                0x1b -> videoPid = elemPid // Do not label HEVC (0x24) as H.264.
                0x03, 0x04, 0x0f, 0x81, 0x83, 0x84 -> if (audioPid < 0) audioPid = elemPid
            }
            q += 5 + infoLength
        }
    }

    private fun isPesHeader(packet: ByteArray, offset: Int, length: Int): Boolean =
        length >= 6 && packet[offset] == 0.toByte() && packet[offset + 1] == 0.toByte() && packet[offset + 2] == 1.toByte()

    private fun isVideoStreamId(packet: ByteArray, offset: Int, length: Int): Boolean =
        isPesHeader(packet, offset, length) && (packet[offset + 3].toInt() and 0xff) in 0xe0..0xef

    private fun parsePesHeaderAndExtractPts(packet: ByteArray, offset: Int, length: Int) {
        if (!isPesHeader(packet, offset, length) || length < 9) { appendBounded(videoPesBuffer, packet, offset, length); return }
        val flags = packet[offset + 7].toInt() and 0xff
        val headerDataLength = packet[offset + 8].toInt() and 0xff
        if (headerDataLength > length - 9) { videoPesBuffer.reset(); return }
        if (((flags ushr 6) and 3) >= 2 && headerDataLength >= 5) currentVideoPtsUs = ptsUs(packet, offset + 9)
        val bodyOffset = offset + 9 + headerDataLength
        appendBounded(videoPesBuffer, packet, bodyOffset, offset + length - bodyOffset)
    }

    private fun parseAudioPesHeader(packet: ByteArray, offset: Int, length: Int) {
        if (!isPesHeader(packet, offset, length) || length < 9) { appendBounded(audioPesBuffer, packet, offset, length); return }
        val headerDataLength = packet[offset + 8].toInt() and 0xff
        if (headerDataLength > length - 9) { audioPesBuffer.reset(); return }
        val flags = packet[offset + 7].toInt() and 0xff
        if (((flags ushr 6) and 3) >= 2 && headerDataLength >= 5) currentAudioPtsUs = ptsUs(packet, offset + 9)
        val bodyOffset = offset + 9 + headerDataLength
        appendBounded(audioPesBuffer, packet, bodyOffset, offset + length - bodyOffset)
    }

    private fun ptsUs(packet: ByteArray, offset: Int): Long {
        val pts90k = ((packet[offset].toLong() and 0x0e) shl 29) or
            ((packet[offset + 1].toLong() and 0xff) shl 22) or
            ((packet[offset + 2].toLong() and 0xfe) shl 14) or
            ((packet[offset + 3].toLong() and 0xff) shl 7) or
            ((packet[offset + 4].toLong() and 0xfe) ushr 1)
        return pts90k * 1000L / 90L
    }

    private fun flushVideoPesBuffer() {
        val data = videoPesBuffer.toByteArray(); videoPesBuffer.reset()
        emitNalUnits(data, currentVideoPtsUs)
    }

    private fun emitNalUnits(data: ByteArray, ptsUs: Long) {
        var start = -1; var i = 0
        while (i + 2 < data.size) {
            val three = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
            val four = i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            if (three || four) {
                if (start >= 0) onVideoNaluExtracted(data.copyOfRange(start, i), ptsUs)
                start = i; i += if (four) 4 else 3
            } else i++
        }
        if (start >= 0 && start < data.size) onVideoNaluExtracted(data.copyOfRange(start, data.size), ptsUs)
    }

    private fun flushAudioPesBuffer() {
        val raw = audioPesBuffer.toByteArray(); audioPesBuffer.reset()
        if (raw.size <= 4) return
        val pcm = ByteArray(raw.size - 4)
        var i = 0
        while (i + 1 < pcm.size) { pcm[i] = raw[i + 5]; pcm[i + 1] = raw[i + 4]; i += 2 }
        if (i < pcm.size) pcm[i] = raw[i + 4]
        onAudioFrameExtracted(pcm, 48000, 2)
    }

    private fun appendBounded(out: ByteArrayOutputStream, data: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || offset < 0 || offset > data.size - length) return
        if (out.size() + length > MAX_PES_BYTES) { out.reset(); Log.w(TAG, "Dropping oversized PES") ; return }
        out.write(data, offset, length)
    }

    private companion object { const val TAG = "TsDemuxer"; const val MAX_PES_BYTES = 4 * 1024 * 1024 }
}
