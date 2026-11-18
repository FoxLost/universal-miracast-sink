package foxlost.miracast.sink.media

import org.junit.Assert.*
import org.junit.Test

class PcmTimingTest {
    @Test fun isolatedDroppedPacketsRebaseRatherThanRequestTrackRecreation() {
        assertEquals(PcmTiming.Continuity.CONTINUE, PcmTiming.continuity(1_020_000, 1_000_000))
        assertEquals(PcmTiming.Continuity.REBASE_AFTER_GAP, PcmTiming.continuity(1_080_000, 1_000_000))
        // Production shifts the track's PTS base, not its sample counter or device lifetime.
        val rebasedExpected = 1_000_000L + PcmTiming.ptsDeltaUs(1_080_000, 1_000_000)
        assertEquals(PcmTiming.Continuity.CONTINUE, PcmTiming.continuity(1_100_000, rebasedExpected + 20_000))
    }

    @Test fun overlapDropsOnlyThePacketAndWrapDoesNotLookLikeAReset() {
        assertEquals(PcmTiming.Continuity.DROP_OVERLAP, PcmTiming.continuity(900_000, 1_000_000))
        assertEquals(PcmTiming.Continuity.CONTINUE,
            PcmTiming.continuity(20_000, PlaybackClock.PTS_WRAP_US - 20_000))
        assertEquals(40_000L, PcmTiming.ptsDeltaUs(20_000, PlaybackClock.PTS_WRAP_US - 20_000))
        assertEquals(-40_000L, PcmTiming.ptsDeltaUs(PlaybackClock.PTS_WRAP_US - 20_000, 20_000))
    }

    @Test fun deadlineStaleEpochAndLateFramesAreDropsNotAudioWriteErrors() {
        assertEquals("stale-epoch", PcmTiming.dropReason(true, 1, 2, 0, 0))
        assertEquals("write-deadline", PcmTiming.dropReason(false, 2, 2, 0, 0))
        assertEquals("late", PcmTiming.dropReason(false, 1, 2, 100_001, 0))
        assertNull(PcmTiming.dropReason(false, 1, 2, 100_000, 0))
        assertNull(PcmTiming.dropReason(false, 1, 2, 0, 100_000))
    }

    @Test fun partialWriteBudgetNeverExceedsTwoHundredMilliseconds() {
        val sampleRate = 48_000
        assertEquals(9_600L, PcmTiming.writableFrames(0, 0, sampleRate))
        assertEquals(100L, PcmTiming.writableFrames(9_500, 0, sampleRate))
        assertEquals(0L, PcmTiming.writableFrames(9_600, 0, sampleRate))
        assertEquals(0L, PcmTiming.writableFrames(10_000, 0, sampleRate))
        assertEquals(2_400L, PcmTiming.writableFrames(9_600, 2_400, sampleRate))
    }

    @Test fun gapBoundarySuppressesOldTimestampsUntilNewSamplesAreAudible() {
        assertFalse(PcmTiming.canAnchor(0, 960, 0))
        assertTrue(PcmTiming.canAnchor(480, 960, 0))
        // Gap detected after 960 frames: preserve the track, invalidate its old mapping.
        assertFalse(PcmTiming.canAnchor(480, 1920, 960))
        assertFalse(PcmTiming.canAnchor(960, 1920, 960))
        assertTrue(PcmTiming.canAnchor(961, 1920, 960))
        assertFalse(PcmTiming.canAnchor(1920, 1920, 960))
        assertFalse(PcmTiming.canAnchor(2000, 1920, 960))
    }

    @Test fun durationBudgetRejectsLongPacketsEvenWhenEntryCountIsSmall() {
        assertTrue(PcmTiming.fitsQueue(100_000, 100_000))
        assertFalse(PcmTiming.fitsQueue(100_000, 100_001))
        assertFalse(PcmTiming.fitsQueue(180_000, 40_000))
        assertTrue(PcmTiming.fitsQueue(0, 200_000))
    }

    @Test fun pendingPartialPacketFreshnessUsesCurrentSamplePositionAndWrap() {
        assertTrue(PcmTiming.superseded(1_000_000, 1_220_000))
        assertFalse(PcmTiming.superseded(1_020_000, 1_220_000))
        assertTrue(PcmTiming.superseded(PlaybackClock.PTS_WRAP_US - 100_000, 100_001))
        assertFalse(PcmTiming.superseded(PlaybackClock.PTS_WRAP_US - 100_000, 100_000))
        assertFalse(PcmTiming.superseded(1_220_000, 1_000_000))
    }

    @Test fun roundedDeviceThresholdCanAlwaysBePrimedWithPartialNonblockingWrites() {
        // A ROM rounds the requested 100 ms buffer to 240 ms. With the old
        // 200 ms hard cap the head stays zero forever, even though writes succeed.
        val threshold = 11_520
        var written = 0L
        repeat(45) {
            val accepted = minOf(256L, PcmTiming.writableFrames(written, 0, 48_000, threshold))
            written += accepted
            if (written < threshold) assertFalse(PcmTiming.readyToStart(written, threshold, 300_000, 60_000))
        }
        assertEquals(threshold.toLong(), written)
        assertTrue(PcmTiming.readyToStart(written, threshold, 300_000, 60_000))
        assertEquals(0L, PcmTiming.writableFrames(written, 0, 48_000, threshold))
        // Once the device consumes 10 ms, exactly that much becomes writable.
        assertEquals(480L, PcmTiming.writableFrames(written, 480, 48_000, threshold))
    }

    @Test fun fullPrimedBufferStartsAtPresentationEvenWhenNoWriteSpaceRemains() {
        assertFalse(PcmTiming.readyToStart(4800, 4800, 59_999, 60_000))
        assertTrue(PcmTiming.readyToStart(4800, 4800, 60_000, 60_000))
        assertFalse(PcmTiming.readyToStart(4799, 4800, 60_000, 60_000))
    }

    @Test fun delayedDeviceClockCannotStarveItsOwnPcmProducer() {
        val clock = PlaybackClock(60_000)
        clock.targetTimeUs(0, 0)
        // Playback head is 100 ms behind the source, timestamped "now" by a
        // legacy AudioTrack. Using the audio master to schedule its own writes
        // adds this lag again and can block the very samples needed to advance.
        clock.updateAudio(20_000, 200_000, 200_000)
        assertEquals(380_000L, clock.queuedTimeUs(200_000, 200_000, clock.generation))
        assertEquals(260_000L, clock.sourceTimeUs(200_000, clock.generation))
        clock.clearAudio()
        assertEquals(260_000L, clock.sourceTimeUs(200_000, clock.generation))
        clock.reset()
        assertEquals(PlaybackClock.STALE_TARGET, clock.sourceTimeUs(200_000, 0))
    }
}
