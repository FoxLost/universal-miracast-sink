package foxlost.miracast.sink.media

import org.junit.Assert.*
import org.junit.Test

class RtpReorderBufferTest {
    @Test fun reconstructsOutOfOrderAndDeduplicatesWithoutReportingLoss() {
        val output = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        val losses = mutableListOf<Long>()
        val buffer = RtpReorderBuffer<Int>(8, 40, { output += it }, { discarded += it }, { losses += it })
        buffer.offer(10, 10, 0)
        buffer.offer(12, 12, 1)
        buffer.offer(12, 12, 2)
        buffer.offer(11, 11, 3)
        buffer.offer(11, 11, 4)
        assertEquals(listOf(10, 11, 12), output)
        assertEquals(2L, buffer.duplicates)
        assertEquals(listOf(12, 11), discarded)
        assertTrue(losses.isEmpty())
        assertEquals(0, buffer.buffered)
    }

    @Test fun reordersAcrossSequenceWrap() {
        val output = mutableListOf<Int>()
        val buffer = RtpReorderBuffer<Int>(8, 40, { output += it }, {}, { fail("Unexpected loss $it") })
        buffer.offer(65534, 65534, 0)
        buffer.offer(0, 0, 1)
        buffer.offer(65535, 65535, 2)
        buffer.offer(1, 1, 3)
        assertEquals(listOf(65534, 65535, 0, 1), output)
        assertEquals(4L, buffer.delivered)
    }

    @Test fun idleTickSkipsRealLossAndReleasesBufferedTail() {
        val events = mutableListOf<String>()
        val buffer = RtpReorderBuffer<Int>(8, 40, { events += "packet:$it" }, {}, { events += "loss:$it" })
        buffer.offer(100, 100, 0)
        buffer.offer(102, 102, 5)
        buffer.tick(44)
        assertEquals(listOf("packet:100"), events)
        buffer.tick(45)
        assertEquals(listOf("packet:100", "loss:1", "packet:102"), events)
        buffer.offer(101, 101, 46)
        assertEquals(1L, buffer.lost)
        assertEquals(1L, buffer.duplicates)
        assertEquals(0, buffer.buffered)
    }

    @Test fun windowAdvanceDeliversPredecessorsAndCountsOnlyMissingPackets() {
        val output = mutableListOf<Int>()
        var loss = 0L
        val buffer = RtpReorderBuffer<Int>(4, 40, { output += it }, {}, { loss += it })
        buffer.offer(0, 0, 0)
        buffer.offer(2, 2, 1)
        buffer.offer(7, 7, 2)
        assertEquals(listOf(0, 2), output)
        assertEquals(2L, loss) // Missing 1 and 3 before new window begins at 4.
        assertTrue(buffer.buffered <= 4)
        buffer.tick(42)
        assertEquals(listOf(0, 2, 7), output)
        assertEquals(5L, loss) // Also missing 4, 5, 6.
    }

    @Test fun clearReleasesOwnedPacketsAndPermitsNewSequenceEpoch() {
        val output = mutableListOf<Int>()
        val discarded = mutableListOf<Int>()
        val buffer = RtpReorderBuffer<Int>(8, 40, { output += it }, { discarded += it }, {})
        buffer.offer(100, 100, 0)
        buffer.offer(102, 102, 1)
        buffer.clear()
        buffer.offer(3, 3, 2)
        assertEquals(listOf(100, 3), output)
        assertEquals(listOf(102), discarded)
        assertEquals(0, buffer.buffered)
    }
}
