package foxlost.miracast.sink.media

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class TsDemuxer(
    private val onVideoNaluExtracted: (nalu: ByteArray, ptsUs: Long) -> Unit,
    private val onAudioFrameExtracted: (pcmData: ByteArray, sampleRate: Int, channels: Int) -> Unit
) {
    private var videoPid: Int = -1
    private var audioPid: Int = -1
    
    private val videoPesBuffer = ByteArrayOutputStream(256 * 1024)
    private var currentVideoPtsUs: Long = 0L

    private val audioPesBuffer = ByteArrayOutputStream(256 * 1024)
    private var currentAudioPtsUs: Long = 0L

    fun processRtpPayload(payload: ByteArray, offset: Int, length: Int) {
        var cursor = offset
        val end = offset + length

        // MPEG-TS packets are 188 bytes each, starting with sync byte 0x47
        while (cursor + 188 <= end) {
            if (payload[cursor] == 0x47.toByte()) {
                parseTsPacket(payload, cursor)
                cursor += 188
            } else {
                // Resynchronize to next 0x47 sync byte
                cursor++
            }
        }
    }

    private fun parseTsPacket(packet: ByteArray, offset: Int) {
        val b1 = packet[offset + 1].toInt() and 0xFF
        val b2 = packet[offset + 2].toInt() and 0xFF
        val b3 = packet[offset + 3].toInt() and 0xFF

        val payloadStartIndicator = (b1 and 0x40) != 0
        val pid = ((b1 and 0x1F) shl 8) or b2

        val adaptationControl = (b3 and 0x30) shr 4
        var payloadOffset = offset + 4

        if (adaptationControl == 0x02 || adaptationControl == 0x03) { // Adaptation field present
            val adaptationLength = packet[payloadOffset].toInt() and 0xFF
            payloadOffset += 1 + adaptationLength
        }

        if (adaptationControl == 0x02 || payloadOffset >= offset + 188) {
            return // No payload bytes
        }

        val payloadLen = (offset + 188) - payloadOffset

        // Auto-detect Video PID
        if (videoPid == -1 && pid != 0 && pid != 4096) {
            if (isPesHeader(packet, payloadOffset, payloadLen)) {
                val streamId = packet[payloadOffset + 3].toInt() and 0xFF
                if (streamId in 0xE0..0xEF) {
                    videoPid = pid
                    Log.d("TsDemuxer", "Auto-detected H.264 Video PID: $videoPid (0x${videoPid.toString(16)})")
                }
            }
        }
        // Auto-detect Audio PID (runs independently)
        if (audioPid == -1 && pid != 0 && pid != 4096 && pid != videoPid) {
            if (isPesHeader(packet, payloadOffset, payloadLen)) {
                val streamId = packet[payloadOffset + 3].toInt() and 0xFF
                if (streamId in 0xC0..0xDF || streamId == 0xBD) {
                    audioPid = pid
                    Log.d("TsDemuxer", "Auto-detected Audio PID: $audioPid (0x${audioPid.toString(16)})")
                }
            }
        }

        if (pid == videoPid || (videoPid == -1 && isVideoStreamId(packet, payloadOffset, payloadLen))) {
            if (payloadStartIndicator) {
                flushVideoPesBuffer()
                parsePesHeaderAndExtractPts(packet, payloadOffset, payloadLen)
            } else {
                videoPesBuffer.write(packet, payloadOffset, payloadLen)
            }
        } else if (pid == audioPid) {
            if (payloadStartIndicator) {
                flushAudioPesBuffer()
                parseAudioPesHeader(packet, payloadOffset, payloadLen)
            } else {
                audioPesBuffer.write(packet, payloadOffset, payloadLen)
            }
        }
    }

    private fun isPesHeader(packet: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 6) return false
        return packet[offset] == 0x00.toByte() &&
               packet[offset + 1] == 0x00.toByte() &&
               packet[offset + 2] == 0x01.toByte()
    }

    private fun isVideoStreamId(packet: ByteArray, offset: Int, length: Int): Boolean {
        if (!isPesHeader(packet, offset, length)) return false
        val streamId = packet[offset + 3].toInt() and 0xFF
        return streamId in 0xE0..0xEF
    }

    private fun parsePesHeaderAndExtractPts(packet: ByteArray, offset: Int, length: Int) {
        if (!isPesHeader(packet, offset, length)) {
            videoPesBuffer.write(packet, offset, length)
            return
        }

        val flags = packet[offset + 7].toInt() and 0xFF
        val pesHeaderDataLen = packet[offset + 8].toInt() and 0xFF

        val ptsFlags = (flags and 0xC0) shr 6
        if (ptsFlags == 2 || ptsFlags == 3) { // PTS present
            val ptsByte0 = packet[offset + 9].toLong() and 0xFF
            val ptsByte1 = packet[offset + 10].toLong() and 0xFF
            val ptsByte2 = packet[offset + 11].toLong() and 0xFF
            val ptsByte3 = packet[offset + 12].toLong() and 0xFF
            val ptsByte4 = packet[offset + 13].toLong() and 0xFF

            val pts90kHz = ((ptsByte0 and 0x0E) shl 29) or
                           ((ptsByte1 and 0xFF) shl 22) or
                           ((ptsByte2 and 0xFE) shl 14) or
                           ((ptsByte3 and 0xFF) shl 7) or
                           ((ptsByte4 and 0xFE) shr 1)

            currentVideoPtsUs = (pts90kHz * 1000L) / 90L
        }

        val headerSize = 9 + pesHeaderDataLen
        if (length > headerSize) {
            videoPesBuffer.write(packet, offset + headerSize, length - headerSize)
        }
    }

    private fun flushVideoPesBuffer() {
        val pesData = videoPesBuffer.toByteArray()
        videoPesBuffer.reset()

        if (pesData.isEmpty()) return

        var startPos = -1
        var i = 0
        while (i <= pesData.size - 4) {
            val is4ByteStart = pesData[i] == 0x00.toByte() &&
                               pesData[i + 1] == 0x00.toByte() &&
                               pesData[i + 2] == 0x00.toByte() &&
                               pesData[i + 3] == 0x01.toByte()
            val is3ByteStart = !is4ByteStart &&
                               pesData[i] == 0x00.toByte() &&
                               pesData[i + 1] == 0x00.toByte() &&
                               pesData[i + 2] == 0x01.toByte()

            if (is4ByteStart || is3ByteStart) {
                if (startPos != -1) {
                    val naluLen = i - startPos
                    val nalu = ByteArray(naluLen)
                    System.arraycopy(pesData, startPos, nalu, 0, naluLen)
                    onVideoNaluExtracted(nalu, currentVideoPtsUs)
                }
                startPos = i
                i += if (is4ByteStart) 4 else 3
            } else {
                i++
            }
        }

        if (startPos != -1 && startPos < pesData.size) {
            val naluLen = pesData.size - startPos
            val nalu = ByteArray(naluLen)
            System.arraycopy(pesData, startPos, nalu, 0, naluLen)
            onVideoNaluExtracted(nalu, currentVideoPtsUs)
        }
    }

    private fun parseAudioPesHeader(packet: ByteArray, offset: Int, length: Int) {
        if (length < 9) { audioPesBuffer.write(packet, offset, length); return }
        if (!(packet[offset] == 0x00.toByte() && packet[offset + 1] == 0x00.toByte() && packet[offset + 2] == 0x01.toByte())) {
            audioPesBuffer.write(packet, offset, length); return
        }

        val flags = packet[offset + 7].toInt() and 0xFF
        val pesHeaderDataLen = packet[offset + 8].toInt() and 0xFF

        val ptsFlags = (flags and 0xC0) shr 6
        if (ptsFlags == 2 || ptsFlags == 3) {
            val ptsByte0 = packet[offset + 9].toLong() and 0xFF
            val ptsByte1 = packet[offset + 10].toLong() and 0xFF
            val ptsByte2 = packet[offset + 11].toLong() and 0xFF
            val ptsByte3 = packet[offset + 12].toLong() and 0xFF
            val ptsByte4 = packet[offset + 13].toLong() and 0xFF
            val pts90kHz = ((ptsByte0 and 0x0E) shl 29) or
                           ((ptsByte1 and 0xFF) shl 22) or
                           ((ptsByte2 and 0xFE) shl 14) or
                           ((ptsByte3 and 0xFF) shl 7) or
                           ((ptsByte4 and 0xFE) shr 1)
            currentAudioPtsUs = (pts90kHz * 1000L) / 90L
        }

        val headerSize = 9 + pesHeaderDataLen
        if (length > headerSize) {
            audioPesBuffer.write(packet, offset + headerSize, length - headerSize)
        }
    }

    private fun flushAudioPesBuffer() {
        val raw = audioPesBuffer.toByteArray()
        audioPesBuffer.reset()

        if (raw.size < 4) return

        // Skip LPCM audio descriptor (4 bytes) from PES payload header
        val offset = 4
        val size = raw.size - offset
        if (size <= 0) return

        // LPCM in MPEG-TS is big-endian, Android AudioTrack expects little-endian
        val pcmData = ByteArray(size)
        for (i in 0 until size step 2) {
            if (i + 1 < size) {
                pcmData[i] = raw[offset + i + 1]
                pcmData[i + 1] = raw[offset + i]
            } else {
                pcmData[i] = raw[offset + i]
            }
        }
        onAudioFrameExtracted(pcmData, 48000, 2)
    }
}
