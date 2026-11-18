package foxlost.miracast.sink.uibc

/** Exact WFD UIBC Generic touch framing for the supported v2.1 subset. */
object UibcCodec {
    const val GENERIC_CATEGORY = 0

    fun encodeTouch(event: TouchEvent): ByteArray {
        require(event.points.size <= 255) { "too many pointers" }
        // Generic touch body: type/action (1), Describe length (2), count (1),
        // followed by one ID + unsigned 16-bit X/Y record per pointer.
        val genericLength = 1 + event.points.size * 5
        var packetLength = 4 + 4 + event.points.size * 5
        if (packetLength and 1 != 0) packetLength++
        require(genericLength <= 0xffff && packetLength <= 0xffff)
        val bytes = ByteArray(packetLength)
        var offset = 0
        bytes[offset++] = 0 // version=000, T=0, reserved=0
        bytes[offset++] = GENERIC_CATEGORY.toByte()
        putU16(bytes, offset, packetLength); offset += 2
        bytes[offset++] = event.action.wireValue.toByte()
        putU16(bytes, offset, genericLength); offset += 2
        bytes[offset++] = event.points.size.toByte()
        event.points.forEach { point ->
            bytes[offset++] = point.pointerId.toByte()
            putU16(bytes, offset, point.x); offset += 2
            putU16(bytes, offset, point.y); offset += 2
        }
        // ByteArray is zero initialized, supplying the required even padding.
        return bytes
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        require(value in 0..65535)
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
