package foxlost.miracast.sink.media

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

class MediaDecoderPipeline(
    private val surface: Surface,
    private val onVideoSizeChanged: ((Int, Int) -> Unit)? = null
) {
    private var videoCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    @Volatile private var isConfigured = false
    private var isSoftwareFallback = false
    private var videoFormatReported = false

    fun initVideoDecoder(width: Int = 1920, height: Int = 1080) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_OPERATING_RATE, 60)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        try {
            // Attempt 1: Default HW Decoder
            videoCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoCodec?.configure(format, surface, null, 0)
            videoCodec?.start()
            isConfigured = true
            Log.d("MediaDecoderPipeline", "Hardware H.264 MediaCodec started successfully (${videoCodec?.name})")
        } catch (e: Exception) {
            Log.w("MediaDecoderPipeline", "Default MediaCodec failed: ${e.message}. Trying Software Fallback...")
            initSoftwareDecoderFallback(format)
        }
    }

    private fun initSoftwareDecoderFallback(format: MediaFormat) {
        val swDecoders = arrayOf("c2.android.avc.decoder", "OMX.google.h264.decoder")
        for (decoderName in swDecoders) {
            try {
                videoCodec = MediaCodec.createByCodecName(decoderName)
                videoCodec?.configure(format, surface, null, 0)
                videoCodec?.start()
                isConfigured = true
                isSoftwareFallback = true
                Log.d("MediaDecoderPipeline", "Software H.264 Fallback MediaCodec started: $decoderName")
                return
            } catch (e: Exception) {
                Log.w("MediaDecoderPipeline", "Software decoder $decoderName failed: ${e.message}")
            }
        }
        Log.e("MediaDecoderPipeline", "All H.264 decoders failed to initialize")
    }

    fun feedVideoNalu(nalu: ByteArray, ptsUs: Long) {
        if (!isConfigured || videoCodec == null) {
            initVideoDecoder(1920, 1080)
        }

        try {
            val codec = videoCodec ?: return
            val inputIndex = codec.dequeueInputBuffer(10000L) // 10ms wait
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
                        if (w > 0 && h > 0) { videoFormatReported = true; onVideoSizeChanged?.invoke(w, h) }
                    } catch (_: Exception) {}
                }
                codec.releaseOutputBuffer(outputIndex, true)
                outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)
            }
        } catch (e: Exception) {
            Log.e("MediaDecoderPipeline", "Error feeding video NALU: ${e.message}")
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
        }

        audioTrack?.write(pcmData, 0, pcmData.size)
    }

    fun release() {
        isConfigured = false
        try {
            videoCodec?.stop()
            videoCodec?.release()
        } catch (e: Exception) {}
        videoCodec = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
        Log.d("MediaDecoderPipeline", "MediaDecoderPipeline released")
    }
}
