package foxlost.miracast.sink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebugEventLogTest {
    @Before
    fun resetSink() {
        DebugEventLog.clear()
    }

    @Test
    fun retainsOnlyNewestTwelveEventsInOrder() {
        repeat(15) { index ->
            DebugEventLog.record(DebugEventCategory.SINK, "event-$index", index.toLong())
        }

        val events = DebugEventLog.events.value
        assertEquals(DebugEventLog.MAX_EVENTS, events.size)
        assertEquals("event-3", events.first().message)
        assertEquals("event-14", events.last().message)
        assertEquals((3L..14L).toList(), events.map { it.timestampMs })
    }

    @Test
    fun recordOnceDeduplicatesUntilCleared() {
        assertTrue(DebugEventLog.recordOnce(DebugEventCategory.RTP, "first", "First packet", 10L))
        assertFalse(DebugEventLog.recordOnce(DebugEventCategory.RTP, "first", "First packet", 11L))
        assertEquals(1, DebugEventLog.events.value.size)

        DebugEventLog.clear()
        assertTrue(DebugEventLog.recordOnce(DebugEventCategory.RTP, "first", "First packet", 12L))
    }

    @Test
    fun rateLimitedRecordAllowsEventsAtIntervalBoundary() {
        assertTrue(DebugEventLog.recordRateLimited(DebugEventCategory.RTP, "counter", "count=1", 1_000L, 100L))
        assertFalse(DebugEventLog.recordRateLimited(DebugEventCategory.RTP, "counter", "count=2", 1_000L, 1_099L))
        assertTrue(DebugEventLog.recordRateLimited(DebugEventCategory.RTP, "counter", "count=3", 1_000L, 1_100L))
        assertEquals(listOf("count=1", "count=3"), DebugEventLog.events.value.map { it.message })
    }
}
