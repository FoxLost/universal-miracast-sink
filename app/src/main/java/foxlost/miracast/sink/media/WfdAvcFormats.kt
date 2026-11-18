package foxlost.miracast.sink.media

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import java.util.Locale

/** Strict HD-only CEA offer: one selected timing, only from a decoder that supports it. */
internal object WfdAvcFormats {
    data class Decoder(val profile: Int, val level: Int, val supports: (Int, Int, Double) -> Boolean)
    private data class Mode(val bit: Int, val width: Int, val height: Int, val fps: Int, val minimumLevel: Int)
    private val modes = listOf(
        Mode(5, 1280, 720, 30, 1), Mode(6, 1280, 720, 60, 2),
        Mode(7, 1920, 1080, 30, 4), Mode(8, 1920, 1080, 60, 16),
    )
    private val decoders by lazy { probe() }

    fun forPreference(height: Int, fps: Int): String = build(height, fps, decoders)

    fun build(height: Int, fps: Int, available: List<Decoder>): String {
        require(height == 720 || height == 1080)
        require(fps == 30 || fps == 60)
        val mode = modes.first { it.height == height && it.fps == fps }
        var baselineLevel = 0
        var highLevel = 0
        for (decoder in available) {
            val currentLevel = when (decoder.profile) {
                1 -> baselineLevel
                2 -> highLevel
                else -> continue
            }
            when (decoder.level) {
                1, 2, 4, 8, 16 -> Unit
                else -> continue
            }
            if (decoder.level < mode.minimumLevel || decoder.level <= currentLevel) continue
            if (!decoder.supports(mode.width, mode.height, mode.fps.toDouble())) continue
            if (decoder.profile == 1) baselineLevel = decoder.level else highLevel = decoder.level
        }
        // Deliberately omit WFD's mandatory SD modes in this forced-HD policy.
        // An unsupported choice stays unavailable; never substitute a different mode.
        if (baselineLevel == 0 && highLevel == 0) return "none"
        return buildString {
            // WFD Table 37: native CEA index in B7:B3; no arbitrary preferred-mode extension.
            append(String.format(Locale.US, "%02x 00 ", mode.bit shl 3))
            var first = true
            for (profile in 1..2) {
                val level = if (profile == 1) baselineLevel else highLevel
                if (level == 0) continue
                if (!first) append(", ")
                first = false
                append(String.format(Locale.US,
                    "%02x %02x %08x 00000000 00000000 00 0000 0000 00 none none",
                    profile, level, 1 shl mode.bit))
            }
        }
    }

    private fun probe(): List<Decoder> {
        val result = mutableListOf<Decoder>()
        val infos = try { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos } catch (_: RuntimeException) { return emptyList() }
        for (info in infos) {
            if (info.isEncoder || info.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }) continue
            try {
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                for ((profile, androidProfiles) in listOf(
                    1 to setOf(CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCProfileConstrainedBaseline),
                    2 to setOf(CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCProfileConstrainedHigh),
                )) {
                    val maximum = caps.profileLevels.filter { it.profile in androidProfiles }.maxByOrNull { it.level } ?: continue
                    val level = when {
                        maximum.level >= CodecProfileLevel.AVCLevel42 -> 16
                        maximum.level >= CodecProfileLevel.AVCLevel41 -> 8
                        maximum.level >= CodecProfileLevel.AVCLevel4 -> 4
                        maximum.level >= CodecProfileLevel.AVCLevel32 -> 2
                        maximum.level >= CodecProfileLevel.AVCLevel31 -> 1
                        else -> continue
                    }
                    val androidLevel = when (level) {
                        1 -> CodecProfileLevel.AVCLevel31; 2 -> CodecProfileLevel.AVCLevel32
                        4 -> CodecProfileLevel.AVCLevel4; 8 -> CodecProfileLevel.AVCLevel41
                        else -> CodecProfileLevel.AVCLevel42
                    }
                    result += Decoder(profile, level) { width, height, rate ->
                        try {
                            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                            format.setInteger(MediaFormat.KEY_PROFILE, maximum.profile)
                            format.setInteger(MediaFormat.KEY_LEVEL, androidLevel)
                            format.setFloat(MediaFormat.KEY_FRAME_RATE, rate.toFloat())
                            caps.isFormatSupported(format)
                        } catch (_: RuntimeException) { false }
                    }
                }
            } catch (_: RuntimeException) { /* Unsupported/broken codec entries are not advertised. */ }
        }
        return result
    }
}
