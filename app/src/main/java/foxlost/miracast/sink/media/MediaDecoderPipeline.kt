package foxlost.miracast.sink.media

import foxlost.miracast.sink.DebugEventCategory
import foxlost.miracast.sink.DebugEventLog
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

class MediaDecoderPipeline(
    private val surface: Surface,
    private val onVideoSizeChanged: ((Int, Int) -> Unit)? = null
) {
    private var videoCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    @Volatile private var isConfigured = false
    private var isSoftwareFallback = false
    private var videoFormatReported = false

    /** Currently configured video dimensions (from SPS or default). */
    private var configuredWidth = 0
    private var configuredHeight = 0

    /** Fingerprint of the last seen SPS, to detect mid-stream changes. */
    private var lastSpsHash: Int = 0

    fun initVideoDecoder(width: Int = 1920, height: Int = 1080) {
        if (isConfigured && width == configuredWidth && height == configuredHeight) return
        releaseVideoCodec()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_OPERATING_RATE, 60)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        try {
            videoCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoCodec?.configure(format, surface, null, 0)
            videoCodec?.start()
            isConfigured = true
            configuredWidth = width
            configuredHeight = height
            isSoftwareFallback = false
            Log.d("MediaDecoderPipeline", "Hardware H.264 MediaCodec started (${videoCodec?.name}) ${width}x${height}")
            DebugEventLog.record(DebugEventCategory.MEDIA, "H.264 hardware decoder started ${width}x${height}")
        } catch (e: Exception) {
            Log.w("MediaDecoderPipeline", "Default MediaCodec failed: ${e.message}. Trying Software Fallback...")
            initSoftwareDecoderFallback(format, width, height)
        }
    }

    private fun initSoftwareDecoderFallback(format: MediaFormat, width: Int, height: Int) {
        val swDecoders = arrayOf("c2.android.avc.decoder", "OMX.google.h264.decoder")
        for (decoderName in swDecoders) {
            try {
                videoCodec = MediaCodec.createByCodecName(decoderName)
                videoCodec?.configure(format, surface, null, 0)
                videoCodec?.start()
                isConfigured = true
                configuredWidth = width
                configuredHeight = height
                isSoftwareFallback = true
                Log.d("MediaDecoderPipeline", "Software H.264 Fallback started: $decoderName ${width}x${height}")
                DebugEventLog.record(DebugEventCategory.MEDIA, "H.264 software decoder started $decoderName ${width}x${height}")
                return
            } catch (e: Exception) {
                Log.w("MediaDecoderPipeline", "Software decoder $decoderName failed: ${e.message}")
            }
        }
        Log.e("MediaDecoderPipeline", "All H.264 decoders failed to initialize")
        DebugEventLog.record(DebugEventCategory.ERROR, "All H.264 decoders failed")
    }

    fun feedVideoNalu(nalu: ByteArray, ptsUs: Long) {
        // Detect SPS and (re)configure the decoder if the resolution changed.
        // Windows can renegotiate the video format mid-stream; the Pad 6 handles
        // this by reinitializing its decoder on SPS change.
        val nalType = nalUnitType(nalu)
        if (nalType == NAL_SPS) {
            val dims = parseSpsDimensions(nalu)
            val hash = nalu.contentHashCode()
            if (dims != null && hash != lastSpsHash) {
                lastSpsHash = hash
                if (!isConfigured || dims.first != configuredWidth || dims.second != configuredHeight) {
                    Log.i("MediaDecoderPipeline", "SPS change: ${dims.first}x${dims.second} (was ${configuredWidth}x${configuredHeight}) — reconfiguring decoder")
                    DebugEventLog.record(DebugEventCategory.MEDIA, "H.264 SPS resolution ${dims.first}x${dims.second}; reconfiguring")
                    initVideoDecoder(dims.first, dims.second)
                }
            }
        }

        if (!isConfigured || videoCodec == null) {
            initVideoDecoder(1920, 1080)
        }

        try {
            val codec = videoCodec ?: return
            val inputIndex = codec.dequeueInputBuffer(10000L)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(nalu)
                codec.queueInputBuffer(inputIndex, 0, nalu.size, ptsUs, 0)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            while (outputIndex >= 0) {
                if (!videoFormatReported && bufferInfo.size > 0) {
                    try {
                        val fmt = codec.outputFormat
                        val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
                        val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                        if (w > 0 && h > 0) {
                            videoFormatReported = true
                            DebugEventLog.recordOnce(
                                DebugEventCategory.MEDIA,
                                "first-video-frame",
                                "First decoded video frame ${w}x${h}",
                            )
                            onVideoSizeChanged?.invoke(w, h)
                        }
                    } catch (_: Exception) {}
                }
                codec.releaseOutputBuffer(outputIndex, true)
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            }
        } catch (e: Exception) {
            Log.e("MediaDecoderPipeline", "Error feeding video NALU: ${e.message}")
            DebugEventLog.recordRateLimited(DebugEventCategory.ERROR, "decoder-feed-error", "Video decode error: ${e.message ?: "unknown"}")
        }
    }

    fun playPcmAudio(pcmData: ByteArray, sampleRate: Int = 48000, channels: Int = 2) {
        if (audioTrack == null) {
            val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufSize * 2,
                AudioTrack.MODE_STREAM
            )
            audioTrack?.play()
            Log.d("MediaDecoderPipeline", "AudioTrack stream initialized ($sampleRate Hz, $channels ch)")
            DebugEventLog.record(DebugEventCategory.MEDIA, "AudioTrack started $sampleRate Hz ${channels}ch")
        }

        audioTrack?.write(pcmData, 0, pcmData.size)
    }

    private fun releaseVideoCodec() {
        isConfigured = false
        videoFormatReported = false
        try {
            videoCodec?.stop()
            videoCodec?.release()
        } catch (e: Exception) {}
        videoCodec = null
    }

    fun release() {
        releaseVideoCodec()
        configuredWidth = 0
        configuredHeight = 0
        lastSpsHash = 0
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
        DebugEventLog.record(DebugEventCategory.MEDIA, "Media decoder pipeline released")
        Log.d("MediaDecoderPipeline", "MediaDecoderPipeline released")
    }

    // ------------------------------------------------------------------
    // Minimal Annex-B / SPS parsing for mid-stream resolution changes
    // ------------------------------------------------------------------

    private val NAL_SPS = 7

    /** NAL unit type from an Annex-B buffer (handles 3- and 4-byte start codes). */
    private fun nalUnitType(nalu: ByteArray): Int {
        var i = 0
        // Skip leading start code
        while (i + 3 < nalu.size && nalu[i].toInt() == 0 && nalu[i + 1].toInt() == 0) {
            if (nalu[i + 2].toInt() == 1) { i += 3; break }
            if (i + 4 < nalu.size && nalu[i + 2].toInt() == 0 && nalu[i + 3].toInt() == 1) { i += 4; break }
            i++
        }
        return if (i < nalu.size) nalu[i].toInt() and 0x1F else -1
    }

    /** Extract (width, height) from an SPS NAL unit, or null on parse failure. */
    private fun parseSpsDimensions(nalu: ByteArray): Pair<Int, Int>? {
        return try {
            // Strip start code + NAL header, then remove emulation-prevention bytes.
            var start = 0
            while (start + 3 < nalu.size && nalu[start].toInt() == 0 && nalu[start + 1].toInt() == 0) {
                if (nalu[start + 2].toInt() == 1) { start += 3; break }
                if (start + 4 < nalu.size && nalu[start + 2].toInt() == 0 && nalu[start + 3].toInt() == 1) { start += 4; break }
                start++
            }
            val rbsp = ByteArray(nalu.size)
            var rbspLen = 0
            var i = start + 1 // skip NAL header byte
            while (i < nalu.size) {
                if (i + 2 < nalu.size && nalu[i].toInt() == 0 && nalu[i + 1].toInt() == 0 && nalu[i + 2].toInt() == 3) {
                    rbsp[rbspLen++] = 0; rbsp[rbspLen++] = 0
                    i += 3
                } else {
                    rbsp[rbspLen++] = nalu[i++]
                }
            }

            val br = BitReader(rbsp, rbspLen)
            val profileIdc = br.u(8)
            br.u(8) // constraint flags + reserved
            br.u(8) // level_idc
            br.ue() // seq_parameter_set_id

            // High-profile chroma fields (not present for CBP 66, but handle anyway)
            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244 ||
                profileIdc == 44 || profileIdc == 83 || profileIdc == 86 || profileIdc == 118 ||
                profileIdc == 128 || profileIdc == 138 || profileIdc == 139 || profileIdc == 134 || profileIdc == 135) {
                val chromaFormatIdc = br.ue()
                if (chromaFormatIdc == 3) br.u(1)
                br.ue(); br.ue(); br.u(1)
                if (br.u(1) == 1) { // seq_scaling_matrix_present
                    val n = if (chromaFormatIdc != 3) 8 else 12
                    for (j in 0 until n) {
                        if (br.u(1) == 1) {
                            val size = if (j < 6) 16 else 64
                            var lastScale = 8; var nextScale = 8
                            for (k in 0 until size) {
                                if (nextScale != 0) { nextScale = (lastScale + br.se() + 256) % 256 }
                                lastScale = if (nextScale == 0) lastScale else nextScale
                            }
                        }
                    }
                }
            }

            br.ue() // log2_max_frame_num_minus4
            val pocType = br.ue()
            if (pocType == 0) {
                br.ue()
            } else if (pocType == 1) {
                br.u(1); br.se(); br.se()
                val cycles = br.ue()
                for (k in 0 until cycles) br.se()
            }
            br.ue() // num_ref_frames
            br.u(1) // gaps_in_frame_num_value_allowed_flag

            val picWidthMbs = br.ue() + 1
            val picHeightMapUnits = br.ue() + 1
            val frameMbsOnly = br.u(1)
            if (frameMbsOnly == 0) br.u(1) // mb_adaptive_frame_field_flag
            br.u(1) // direct_8x8_inference_flag

            var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
            if (br.u(1) == 1) { // frame_cropping_flag
                cropLeft = br.ue(); cropRight = br.ue(); cropTop = br.ue(); cropBottom = br.ue()
            }

            val width = picWidthMbs * 16
            val height = picHeightMapUnits * 16 * (2 - frameMbsOnly)
            // CBP is 4:2:0 → crop unit x=2, y=2*frameMbsOnly factor
            val cropUnitX = 2
            val cropUnitY = 2 * (2 - frameMbsOnly)
            val finalW = width - (cropLeft + cropRight) * cropUnitX
            val finalH = height - (cropTop + cropBottom) * cropUnitY
            if (finalW > 0 && finalH > 0) Pair(finalW, finalH) else Pair(width, height)
        } catch (e: Exception) {
            Log.w("MediaDecoderPipeline", "SPS parse failed: ${e.message}")
            null
        }
    }

    /** Exp-Golomb bit reader for SPS parsing. */
    private class BitReader(private val data: ByteArray, private val length: Int) {
        private var bitPos = 0

        fun u(n: Int): Int {
            var value = 0
            for (i in 0 until n) {
                value = (value shl 1) or readBit()
            }
            return value
        }

        fun ue(): Int {
            var zeros = 0
            while (readBit() == 0 && zeros < 32) zeros++
            if (zeros == 0) return 0
            return ((1 shl zeros) - 1) + u(zeros)
        }

        fun se(): Int {
            val k = ue()
            return if (k % 2 == 0) -(k / 2) else (k + 1) / 2
        }

        private fun readBit(): Int {
            if (bitPos >= length * 8) return 0
            val byteVal = data[bitPos / 8].toInt() and 0xFF
            val bit = (byteVal shr (7 - (bitPos % 8))) and 1
            bitPos++
            return bit
        }
    }
}
