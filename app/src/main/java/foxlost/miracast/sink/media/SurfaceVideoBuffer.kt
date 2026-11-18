package foxlost.miracast.sink.media

/** Retains parameter sets and the latest random-access unit while the render surface is unavailable. */
internal class SurfaceVideoBuffer {
    private data class AccessUnit(val bytes: ByteArray, val pts: Long)
    private var sps: AccessUnit? = null
    private var pps: AccessUnit? = null
    private var idr: AccessUnit? = null

    fun hold(bytes: ByteArray, pts: Long) {
        if (bytes.size > 4 * 1024 * 1024) { clear(); return }
        var types = 0
        for (i in 0 until bytes.size - 3) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte()) {
                types = types or (1 shl (bytes[i + 3].toInt() and 0x1f))
            }
        }
        // A later predictive frame makes the cached IDR insufficient: its reference chain was dropped.
        if (types and (1 shl 1) != 0 && types and (1 shl 5) == 0) idr = null
        if (types and ((1 shl 7) or (1 shl 8) or (1 shl 5)) == 0) return
        val unit = AccessUnit(bytes, pts)
        if (types and (1 shl 7) != 0) { sps = unit; pps = null; idr = null }
        if (types and (1 shl 8) != 0) { pps = unit; idr = null }
        if (types and (1 shl 5) != 0) idr = unit
    }

    fun drain(onGap: () -> Unit = {}, consume: (ByteArray, Long) -> Unit) {
        val first = sps
        val second = pps
        val last = idr
        clear()
        if (first == null) return
        if (last == null) onGap()
        consume(first.bytes, first.pts)
        if (second != null && second !== first) consume(second.bytes, second.pts)
        if (last != null && last !== first && last !== second) consume(last.bytes, last.pts)
    }

    fun clear() { sps = null; pps = null; idr = null }
}
