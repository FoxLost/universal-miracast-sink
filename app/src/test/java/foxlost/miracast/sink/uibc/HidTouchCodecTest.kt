package foxlost.miracast.sink.uibc

import org.junit.Assert.*
import org.junit.Test

class HidTouchCodecTest {
    private fun codec(type: UibcCapability, time: Long = 0x1234L) =
        HidTouchCodec(type, scanTime100Us = { time })

    @Test fun descriptorsDeclareHybridSlotsConfidenceAndVirtualAxisScale() {
        for (type in UibcCapability.entries) {
            val parsed = parse(HidTouchCodec.descriptor(type))
            val maximum = if (type == UibcCapability.MultiTouch) 10 else 1
            val slots = if (type == UibcCapability.MultiTouch) 2 else 1
            assertEquals(listOf(0x0d to 0x04), parsed.applications)
            assertEquals(slots, parsed.fingers)
            assertEquals(setOf(1), parsed.fields.filter { it.kind == 8 }.map { it.reportId }.toSet())
            val headers = parsed.fields.filter { it.finger == 0 && it.kind == 8 }
            assertEquals(listOf(0x0d0054, 0x0d0056), headers.map { it.usage })
            assertEquals(listOf(0, 8), headers.map { it.offset })
            for (finger in 1..slots) {
                val fields = parsed.fields.filter { it.finger == finger && it.kind == 8 && !it.constant }
                assertEquals(listOf(0x0d0042, 0x0d0047, 0x0d0051, 0x010030, 0x010031), fields.map { it.usage })
                assertEquals(listOf(1, 1, 8, 16, 16), fields.map { it.bits })
                assertEquals(9, fields.single { it.usage == 0x0d0051 }.globals[2])
                for ((usage, physicalMaximum) in listOf(0x010030 to 1600, 0x010031 to 900)) {
                    val axis = fields.single { it.usage == usage }
                    assertEquals(0, axis.globals[1])
                    assertEquals(32767, axis.globals[2])
                    assertEquals(0, axis.globals[3])
                    assertEquals(physicalMaximum, axis.globals[4])
                    assertEquals(0x11, axis.globals[6])
                    assertEquals(0x0e, axis.globals[5]) // -2 centimetres = 0.1 mm
                }
                assertEquals(0, fields.first().globals[6])
            }
            val count = parsed.fields.single { it.usage == 0x0d0054 }
            assertEquals(maximum, count.globals[2])
            assertEquals(8, count.bits)
            val scan = parsed.fields.single { it.usage == 0x0d0056 }
            assertEquals(16, scan.bits)
            assertEquals(0x1001, scan.globals[6])
            assertEquals(0x0c, scan.globals[5])
            assertEquals(65535, scan.globals[2])
            assertEquals(65535, scan.globals[4])
            val feature = parsed.fields.single { it.kind == 11 && !it.constant }
            assertEquals(0x0d0055, feature.usage)
            assertEquals(0x12, feature.reportId)
            assertEquals(maximum, feature.globals[2])
            assertEquals(16, feature.bits)
            assertEquals(16, parsed.featureBits.getValue(0x12))
            assertEquals((3 + slots * 6) * 8, parsed.inputBits.getValue(1))
        }
    }

    @Test fun startupAndInputLengthsMatchDescriptorReportSizes() {
        for (type in UibcCapability.entries) {
            val codec = codec(type)
            val wireType = if (type == UibcCapability.SingleTouch) 2 else 3
            val parsed = parse(bodies(codec.descriptorPacket(), wireType, 1).single())
            val maximum = bodies(codec.contactMaximumPacket(), wireType, 0).single()
            assertEquals(1 + parsed.featureBits.getValue(0x12) / 8, maximum.size)
            assertEquals(0x12, maximum[0].toInt())
            assertEquals(if (wireType == 3) 10 else 1, maximum[1].toInt())
            assertEquals(0, maximum[2].toInt())
            val report = bodies(codec.encode(TouchEvent(TouchAction.DOWN,
                listOf(TouchPoint(7, 0x1234, 0x7bcd)))), wireType, 0).single()
            assertEquals(1 + parsed.inputBits.getValue(1) / 8, report.size)
            assertEquals(1, report[0].toInt())
            assertEquals(1, value(parsed, report, 0x0d0042, 1))
            assertEquals(1, value(parsed, report, 0x0d0047, 1))
            assertEquals(7, value(parsed, report, 0x0d0051, 1))
            assertEquals(0x1234, value(parsed, report, 0x010030, 1))
            assertEquals(0x7bcd, value(parsed, report, 0x010031, 1))
            assertEquals(1, value(parsed, report, 0x0d0054))
            assertEquals(0, value(parsed, report, 0x0d0056))
        }
    }

    @Test fun hybridTenContactsUseFiveFragmentsWithOneCountAndSharedScan() {
        var now = 100L
        val codec = HidTouchCodec(UibcCapability.MultiTouch) { now }
        val parsed = parse(HidTouchCodec.descriptor(UibcCapability.MultiTouch))
        val points = (0 until 10).map { TouchPoint(it, it * 1000, 32767 - it) }
        codec.encode(TouchEvent(TouchAction.DOWN, points))
        now += 89
        val reports = bodies(codec.encode(TouchEvent(TouchAction.MOVE, points)), 3, 0)
        assertEquals(5, reports.size)
        reports.forEachIndexed { index, report ->
            assertEquals(16, report.size)
            assertEquals(if (index == 0) 10 else 0, value(parsed, report, 0x0d0054))
            assertEquals(89, value(parsed, report, 0x0d0056))
            for (slot in 1..2) {
                val id = index * 2 + slot - 1
                assertEquals(id, value(parsed, report, 0x0d0051, slot))
                assertEquals(id * 1000, value(parsed, report, 0x010030, slot))
                assertEquals(32767 - id, value(parsed, report, 0x010031, slot))
            }
        }
    }

    @Test fun partialLiftPreservesOtherContactsAndLastPressedCoordinatesAcrossFragments() {
        val codec = codec(UibcCapability.MultiTouch)
        val parsed = parse(HidTouchCodec.descriptor(UibcCapability.MultiTouch))
        val points = (0..4).map { TouchPoint(it, 100 + it, 200 + it) }
        codec.encode(TouchEvent(TouchAction.DOWN, points))
        codec.encode(TouchEvent(TouchAction.MOVE, listOf(points[1].copy(x = 123, y = 234))))
        val lifts = bodies(codec.encode(TouchEvent(TouchAction.UP,
            listOf(points[1].copy(x = 999, y = 999), points[3]))), 3, 0)
        assertEquals(3, lifts.size)
        assertEquals(listOf(5, 0, 0), lifts.map { value(parsed, it, 0x0d0054) })
        val ids = listOf(0, 2, 4, 1, 3)
        for (record in ids.indices) {
            val report = lifts[record / 2]
            val slot = record % 2 + 1
            assertEquals(ids[record], value(parsed, report, 0x0d0051, slot))
            assertEquals(if (record < 3) 1 else 0, value(parsed, report, 0x0d0042, slot))
            assertEquals(1, value(parsed, report, 0x0d0047, slot))
        }
        assertEquals(123, value(parsed, lifts[1], 0x010030, 2))
        assertEquals(234, value(parsed, lifts[1], 0x010031, 2))
        assertTrue(lifts.last().copyOfRange(10, 16).all { it == 0.toByte() })
        val remaining = bodies(codec.encode(TouchEvent(TouchAction.MOVE, listOf(points[0]))), 3, 0)
        assertEquals(2, remaining.size)
        assertEquals(3, value(parsed, remaining[0], 0x0d0054))
        assertEquals(4, value(parsed, remaining[1], 0x0d0051, 1))
        assertTrue(remaining.last().copyOfRange(10, 16).all { it == 0.toByte() })
    }

    @Test fun gestureClockStartsAtZeroWrapsAndResetsAfterFinalLift() {
        var now = 123456L
        val codec = HidTouchCodec(UibcCapability.MultiTouch) { now }
        val parsed = parse(HidTouchCodec.descriptor(UibcCapability.MultiTouch))
        val point = TouchPoint(0, 1, 2)
        fun scan(action: TouchAction): Int = value(parsed,
            bodies(codec.encode(TouchEvent(action, listOf(point))), 3, 0).single(), 0x0d0056)
        assertEquals(0, scan(TouchAction.DOWN))
        now += 65538
        assertEquals(2, scan(TouchAction.MOVE))
        assertEquals(2, scan(TouchAction.UP))
        now += 500
        assertEquals(0, scan(TouchAction.DOWN))
    }

    @Test fun singleContactLimitAndInvalidEventsDoNotCorruptOwnership() {
        val codec = codec(UibcCapability.SingleTouch)
        val parsed = parse(HidTouchCodec.descriptor(UibcCapability.SingleTouch))
        val point = TouchPoint(9, 32767, 0)
        codec.encode(TouchEvent(TouchAction.DOWN, listOf(point)))
        rejects { codec.encode(TouchEvent(TouchAction.DOWN, listOf(TouchPoint(0, 1, 2)))) }
        rejects { codec.encode(TouchEvent(TouchAction.MOVE, listOf(TouchPoint(9, 32768, 2)))) }
        rejects { codec.encode(TouchEvent(TouchAction.UP, listOf(TouchPoint(0, 1, 2)))) }
        val released = bodies(codec.encode(TouchEvent(TouchAction.UP, listOf(point))), 2, 0).single()
        assertEquals(10, released.size)
        assertEquals(9, value(parsed, released, 0x0d0051, 1))
        assertEquals(32767, value(parsed, released, 0x010030, 1))
        assertEquals(0, value(parsed, released, 0x0d0042, 1))
        assertEquals(1, value(parsed, released, 0x0d0047, 1))
        rejects { codec.encode(TouchEvent(TouchAction.DOWN, listOf(TouchPoint(10, 1, 2)))) }
        rejects { codec.encode(TouchEvent(TouchAction.MOVE, listOf(point))) }
        codec.encode(TouchEvent(TouchAction.DOWN, listOf(TouchPoint(0, 0, 32767))))
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid HID event to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected; subsequent reports verify ownership was left intact.
        }
    }

    private fun bodies(frames: ByteArray, type: Int, usage: Int): List<ByteArray> {
        val reports = mutableListOf<ByteArray>()
        var base = 0
        while (base < frames.size) {
            fun u(index: Int) = frames[base + index].toInt() and 255
            assertEquals(0, u(0))
            assertEquals(1, u(1))
            val length = (u(2) shl 8) or u(3)
            assertEquals(0, length % 2)
            assertEquals(1, u(4))
            assertEquals(type, u(5))
            assertEquals(usage, u(6))
            val size = (u(7) shl 8) or u(8)
            assertEquals((9 + size + 1) and -2, length)
            assertTrue(base + length <= frames.size)
            assertTrue(frames.copyOfRange(base + 9 + size, base + length).all { it == 0.toByte() })
            reports.add(frames.copyOfRange(base + 9, base + 9 + size))
            base += length
        }
        assertEquals(frames.size, base)
        return reports
    }

    private data class Field(val kind: Int, val reportId: Int, val usage: Int,
                             val finger: Int, val offset: Int, val bits: Int,
                             val constant: Boolean, val globals: Map<Int, Int>)
    private data class Parsed(val fields: List<Field>, val inputBits: Map<Int, Int>,
                              val featureBits: Map<Int, Int>,
                              val applications: List<Pair<Int, Int>>, val fingers: Int)

    // Independent HID short-item interpreter: globals are scoped by PUSH/POP,
    // locals expire at every main item, and bit offsets are tracked per report kind/ID.
    private fun parse(descriptor: ByteArray): Parsed {
        var globals = mutableMapOf<Int, Int>()
        val stack = ArrayDeque<Map<Int, Int>>()
        val usages = mutableListOf<Int>()
        val collections = ArrayDeque<Int>()
        val offsets = mutableMapOf<Pair<Int, Int>, Int>()
        val fields = mutableListOf<Field>()
        val applications = mutableListOf<Pair<Int, Int>>()
        var fingers = 0
        var cursor = 0
        while (cursor < descriptor.size) {
            val prefix = descriptor[cursor++].toInt() and 255
            require(prefix != 0xfe) { "long HID item not supported by this test parser" }
            val size = when (prefix and 3) { 3 -> 4; else -> prefix and 3 }
            require(cursor + size <= descriptor.size)
            var data = 0
            repeat(size) { data = data or ((descriptor[cursor++].toInt() and 255) shl (it * 8)) }
            val kind = (prefix ushr 2) and 3
            val tag = prefix ushr 4
            when (kind) {
                1 -> when (tag) {
                    10 -> stack.addLast(globals.toMap())
                    11 -> globals = stack.removeLast().toMutableMap()
                    else -> globals[tag] = data
                }
                2 -> if (tag == 0) usages.add(((globals[0] ?: 0) shl 16) or data)
                0 -> {
                    when (tag) {
                        10 -> {
                            val usage = usages.lastOrNull() ?: 0
                            if (data == 1) applications.add((usage ushr 16) to (usage and 65535))
                            collections.addLast(if (usage == 0x0d0022) ++fingers else collections.lastOrNull() ?: 0)
                        }
                        12 -> collections.removeLast()
                        8, 9, 11 -> {
                            val id = globals[8] ?: 0
                            val key = tag to id
                            var offset = offsets[key] ?: 0
                            val bits = globals[7] ?: 0
                            repeat(globals[9] ?: 0) { index ->
                                fields.add(Field(tag, id, usages.getOrNull(index) ?: usages.lastOrNull() ?: 0,
                                    collections.lastOrNull() ?: 0, offset, bits, data and 1 != 0, globals.toMap()))
                                offset += bits
                            }
                            offsets[key] = offset
                        }
                    }
                    usages.clear()
                }
            }
        }
        assertTrue(collections.isEmpty())
        assertTrue(stack.isEmpty())
        return Parsed(fields,
            offsets.filterKeys { it.first == 8 }.mapKeys { it.key.second },
            offsets.filterKeys { it.first == 11 }.mapKeys { it.key.second }, applications, fingers)
    }

    private fun value(parsed: Parsed, report: ByteArray, usage: Int, finger: Int = 0): Int {
        val field = parsed.fields.single { it.kind == 8 && it.reportId == (report[0].toInt() and 255) &&
            !it.constant && it.usage == usage && it.finger == finger }
        var value = 0
        repeat(field.bits) { bit ->
            val position = 8 + field.offset + bit // report ID is outside descriptor bit offsets
            value = value or (((report[position / 8].toInt() ushr (position % 8)) and 1) shl bit)
        }
        return value
    }
}
