package foxlost.miracast.sink.uibc

/** Miralink USB HID digitizer: little-endian HID values inside network-order WFD frames. */
class HidTouchCodec(
    private val type: UibcCapability,
    private val scanTime100Us: () -> Long = { System.nanoTime() / 100_000L },
) {
    private val maximum = if (type == UibcCapability.MultiTouch) MAX_CONTACTS else 1
    private val slots = if (type == UibcCapability.MultiTouch) 2 else 1
    private val active = arrayOfNulls<TouchPoint>(MAX_CONTACTS)
    private var activeCount = 0
    private var gestureStart = 0L

    fun descriptorPacket(): ByteArray = ByteArray(alignedSize(descriptorSize(type))).also {
        putHeader(it, 0, type, 1, descriptorSize(type))
        putDescriptor(it, 9, type)
    }

    /** Miralink transports Contact Count Maximum as a Usage=0 report after the descriptor. */
    fun contactMaximumPacket(): ByteArray = ByteArray(alignedSize(3)).also {
        putHeader(it, 0, type, 0, 3)
        it[9] = MAX_CONTACTS_REPORT_ID.toByte()
        it[10] = maximum.toByte()
    }

    /** One allocation contains every two-slot hybrid fragment of this logical touch frame. */
    fun encode(event: TouchEvent): ByteArray {
        var eventMask = 0
        var added = 0
        // Validate the whole event before changing ownership or last pressed coordinates.
        for (index in event.points.indices) {
            val point = event.points[index]
            require(point.pointerId in 0 until MAX_CONTACTS)
            require(point.x in 0..LOGICAL_MAX && point.y in 0..LOGICAL_MAX)
            val bit = 1 shl point.pointerId
            require(eventMask and bit == 0)
            eventMask = eventMask or bit
            if (active[point.pointerId] == null) {
                require(event.action == TouchAction.DOWN)
                added++
            }
        }
        require(activeCount + added <= maximum)
        val now = scanTime100Us()
        if (activeCount == 0) gestureStart = now
        val scan = ((now - gestureStart) and 0xffffL).toInt()
        val releasing = event.action == TouchAction.UP
        if (!releasing) {
            for (index in event.points.indices) {
                val point = event.points[index]
                active[point.pointerId] = point
            }
            activeCount += added
        }
        // Count includes explicit tip-up records; later hybrid fragments carry count zero.
        val count = activeCount
        val fragments = (count + slots - 1) / slots
        val reportSize = 4 + slots * 6
        val packetSize = alignedSize(reportSize)
        val output = ByteArray(fragments * packetSize)
        for (fragment in 0 until fragments) {
            val base = fragment * packetSize
            putHeader(output, base, type, 0, reportSize)
            output[base + 9] = INPUT_REPORT_ID.toByte()
            output[base + 10] = if (fragment == 0) count.toByte() else 0
            put16(output, base + 11, scan)
        }
        var record = 0
        // Keep survivors before lifts, without copying the active state into a snapshot.
        for (id in active.indices) {
            val point = active[id] ?: continue
            if (releasing && eventMask and (1 shl id) != 0) continue
            putContact(output, (record / slots) * packetSize + 13 + (record % slots) * 6, point, true)
            record++
        }
        if (releasing) {
            for (id in active.indices) {
                if (eventMask and (1 shl id) == 0) continue
                val point = active[id]!!
                // Android's UP sample may have moved: repeat the last transmitted pressed point.
                putContact(output, (record / slots) * packetSize + 13 + (record % slots) * 6, point, false)
                record++
                active[id] = null
                activeCount--
            }
        }
        return output
    }

    private fun putContact(output: ByteArray, offset: Int, point: TouchPoint, pressed: Boolean) {
        output[offset] = if (pressed) 3 else 2 // Tip Switch + Confidence; lifts remain confident.
        output[offset + 1] = point.pointerId.toByte()
        put16(output, offset + 2, point.x)
        put16(output, offset + 4, point.y)
    }

    companion object {
        const val MAX_CONTACTS = 10
        const val LOGICAL_MAX = 32767
        const val INPUT_REPORT_ID = 1
        const val MAX_CONTACTS_REPORT_ID = 0x12

        // Captured Miralink profile: a nominal virtual 160 x 90 mm plane, not display DPI.
        private val PREFIX = byteArrayOf(
            0x05, 0x0d, 0x09, 0x04, 0xa1.toByte(), 0x01, 0x85.toByte(), 0x01,
            0x05, 0x0d, 0x09, 0x54, 0x95.toByte(), 0x01, 0x75, 0x08, 0x15, 0x00,
            0x25, 0x0a, 0x81.toByte(), 0x02,
            0x55, 0x0c, 0x66, 0x01, 0x10, 0x47, 0xff.toByte(), 0xff.toByte(), 0x00, 0x00,
            0x27, 0xff.toByte(), 0xff.toByte(), 0x00, 0x00, 0x75, 0x10,
            0x95.toByte(), 0x01, 0x09, 0x56, 0x81.toByte(), 0x02,
        )
        private val FINGER = byteArrayOf(
            0x05, 0x0d, 0x09, 0x22, 0xa1.toByte(), 0x02,
            0x15, 0x00, 0x25, 0x01, 0x35, 0x00, 0x45, 0x00, 0x55, 0x00, 0x65, 0x00,
            0x75, 0x01, 0x95.toByte(), 0x01, 0x09, 0x42, 0x81.toByte(), 0x02,
            0x09, 0x47, 0x81.toByte(), 0x02, 0x95.toByte(), 0x06, 0x81.toByte(), 0x03,
            0x05, 0x0d, 0x09, 0x51, 0x25, 0x09, 0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x02,
            0x05, 0x01, 0x09, 0x30, 0x75, 0x10, 0x55, 0x0e, 0x65, 0x11,
            0x27, 0xff.toByte(), 0x7f, 0x00, 0x00, 0x46, 0x40, 0x06, 0x81.toByte(), 0x02,
            0x05, 0x01, 0x09, 0x31, 0x46, 0x84.toByte(), 0x03, 0x81.toByte(), 0x02, 0xc0.toByte(),
        )
        private val SUFFIX = byteArrayOf(
            0x05, 0x0d, 0x85.toByte(), 0x12, 0x09, 0x55, 0x95.toByte(), 0x01,
            0x75, 0x10, 0x15, 0x00, 0x25, 0x0a, 0xb1.toByte(), 0x02, 0xc0.toByte(),
        )

        fun descriptor(type: UibcCapability): ByteArray =
            ByteArray(descriptorSize(type)).also { putDescriptor(it, 0, type) }

        private fun descriptorSize(type: UibcCapability): Int =
            PREFIX.size + (if (type == UibcCapability.MultiTouch) 2 else 1) * FINGER.size + SUFFIX.size

        private fun putDescriptor(output: ByteArray, offset: Int, type: UibcCapability) {
            val maximum = if (type == UibcCapability.MultiTouch) MAX_CONTACTS else 1
            PREFIX.copyInto(output, offset)
            output[offset + 19] = maximum.toByte()
            var at = offset + PREFIX.size
            repeat(if (type == UibcCapability.MultiTouch) 2 else 1) {
                FINGER.copyInto(output, at)
                at += FINGER.size
            }
            SUFFIX.copyInto(output, at)
            output[at + 13] = maximum.toByte()
        }

        private fun put16(output: ByteArray, offset: Int, value: Int) {
            output[offset] = value.toByte()
            output[offset + 1] = (value ushr 8).toByte()
        }

        private fun alignedSize(valueSize: Int) = (9 + valueSize + 1) and -2


        private fun putHeader(output: ByteArray, offset: Int, type: UibcCapability, usage: Int, valueSize: Int) {
            val length = alignedSize(valueSize)
            output[offset + 1] = 1 // HIDC
            output[offset + 2] = (length ushr 8).toByte()
            output[offset + 3] = length.toByte()
            output[offset + 4] = UibcInputPath.USB.wireValue.toByte()
            output[offset + 5] = if (type == UibcCapability.SingleTouch) 2 else 3
            output[offset + 6] = usage.toByte()
            output[offset + 7] = (valueSize ushr 8).toByte()
            output[offset + 8] = valueSize.toByte()
        }
    }
}
