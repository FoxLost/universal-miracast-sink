package foxlost.miracast.sink.media

import android.media.MediaCodecInfo.CodecProfileLevel

internal object AvcSpsProfile {
    fun read(nalu: ByteArray): Pair<Int, Int>? {
        val start = when {
            nalu.size >= 8 && nalu[0] == 0.toByte() && nalu[1] == 0.toByte() && nalu[2] == 1.toByte() -> 3
            nalu.size >= 9 && nalu[0] == 0.toByte() && nalu[1] == 0.toByte() && nalu[2] == 0.toByte() && nalu[3] == 1.toByte() -> 4
            else -> return null
        }
        if (nalu[start].toInt() and 0x1f != 7) return null
        val profile = when (nalu[start + 1].toInt() and 0xff) {
            66 -> CodecProfileLevel.AVCProfileBaseline
            77 -> CodecProfileLevel.AVCProfileMain
            100 -> CodecProfileLevel.AVCProfileHigh
            else -> return null
        }
        val level = when (nalu[start + 3].toInt() and 0xff) {
            10 -> CodecProfileLevel.AVCLevel1; 11 -> CodecProfileLevel.AVCLevel11
            12 -> CodecProfileLevel.AVCLevel12; 13 -> CodecProfileLevel.AVCLevel13
            20 -> CodecProfileLevel.AVCLevel2; 21 -> CodecProfileLevel.AVCLevel21; 22 -> CodecProfileLevel.AVCLevel22
            30 -> CodecProfileLevel.AVCLevel3; 31 -> CodecProfileLevel.AVCLevel31; 32 -> CodecProfileLevel.AVCLevel32
            40 -> CodecProfileLevel.AVCLevel4; 41 -> CodecProfileLevel.AVCLevel41; 42 -> CodecProfileLevel.AVCLevel42
            50 -> CodecProfileLevel.AVCLevel5; 51 -> CodecProfileLevel.AVCLevel51; 52 -> CodecProfileLevel.AVCLevel52
            else -> return null
        }
        return profile to level
    }
}
