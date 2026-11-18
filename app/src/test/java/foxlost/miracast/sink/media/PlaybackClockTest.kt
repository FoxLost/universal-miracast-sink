package foxlost.miracast.sink.media

import org.junit.Assert.*
import org.junit.Test

class PlaybackClockTest {
    @Test fun mapsStartupAndBothStreamsOntoOneMonotonicTimeline() {
        val clock = PlaybackClock()
        assertEquals(1_060_000L, clock.targetTimeUs(9_000_000, 1_000_000))
        assertEquals(1_080_000L, clock.targetTimeUs(9_020_000, 1_010_000))
        assertEquals(1_100_000L, clock.targetTimeUs(9_040_000, 1_020_000))
    }

    @Test fun usesAudioPlaybackPositionAndExpiresStaleAudioClock() {
        val clock = PlaybackClock()
        clock.targetTimeUs(0, 1_000_000)
        clock.updateAudio(40_000, 1_100_000, 1_100_000)
        assertEquals(1_140_000L, clock.targetTimeUs(80_000, 1_110_000))
        assertEquals(1_660_000L, clock.targetTimeUs(600_000, 1_700_000))
    }

    @Test fun unwraps33BitPtsWithoutRestartingPlayback() {
        val clock = PlaybackClock()
        val before = PlaybackClock.PTS_WRAP_US - 20_000
        assertEquals(1_060_000L, clock.targetTimeUs(before, 1_000_000))
        assertEquals(1_100_000L, clock.targetTimeUs(20_000, 1_040_000))
        assertEquals(0L, clock.generation)
    }

    @Test fun largeForwardAndBackwardDiscontinuitiesReanchorAndClearAudio() {
        val clock = PlaybackClock()
        clock.targetTimeUs(1_000_000, 1_000_000)
        clock.updateAudio(1_000_000, 1_000_000, 1_000_000)
        assertEquals(1_080_000L, clock.targetTimeUs(20_000_000, 1_020_000))
        assertEquals(1L, clock.generation)
        assertEquals(1_100_000L, clock.targetTimeUs(0, 1_040_000))
        assertEquals(2L, clock.generation)
        clock.reset()
        assertEquals(2_060_000L, clock.targetTimeUs(90_000, 2_000_000))
    }

    @Test fun ignoresInvalidAudioTimestampInsteadOfSchedulingAgainstIt() {
        val clock = PlaybackClock()
        clock.targetTimeUs(0, 1_000_000)
        clock.updateAudio(0, 2_000_000, 1_000_000)
        assertEquals(1_100_000L, clock.targetTimeUs(40_000, 1_010_000))
    }

    @Test fun releasingOrUnderrunningAudioImmediatelyInvalidatesItsAnchor() {
        val clock = PlaybackClock()
        clock.targetTimeUs(0, 1_000_000)
        clock.updateAudio(40_000, 1_100_000, 1_100_000)
        assertEquals(1_140_000L, clock.targetTimeUs(80_000, 1_110_000))
        clock.clearAudio()
        assertEquals(1_140_000L, clock.targetTimeUs(80_000, 1_110_000))
        // Select a PTS for which the old audio anchor and original mapping differ.
        clock.updateAudio(0, 1_200_000, 1_200_000)
        assertEquals(1_300_000L, clock.targetTimeUs(100_000, 1_210_000))
        clock.clearAudio()
        assertEquals(1_160_000L, clock.targetTimeUs(100_000, 1_210_000))
        assertEquals(0L, clock.generation)
    }

    @Test fun timestampFromAnOldTrackCannotReattachAfterTimelineReset() {
        val clock = PlaybackClock()
        clock.targetTimeUs(0, 1_000_000)
        val oldGeneration = clock.generation
        clock.reset()
        clock.targetTimeUs(5_000_000, 2_000_000)
        clock.updateAudio(5_000_000, 2_100_000, 2_100_000, oldGeneration)
        assertEquals(2_100_000L, clock.targetTimeUs(5_040_000, 2_110_000))
        clock.updateAudio(5_000_000, 2_100_000, 2_100_000, clock.generation)
        assertEquals(2_140_000L, clock.targetTimeUs(5_040_000, 2_110_000))
    }

    @Test fun staleQueuedAudioAndDecoderOutputCannotResetNewTimeline() {
        val clock = PlaybackClock()
        clock.targetTimeUs(0, 1_000_000)
        val oldGeneration = clock.generation
        clock.targetTimeUs(3_100_000, 1_010_000)
        val newGeneration = clock.generation
        assertEquals(oldGeneration + 1, newGeneration)
        assertEquals(PlaybackClock.STALE_TARGET,
            clock.queuedTimeUs(1_000_000, 1_020_000, oldGeneration))
        clock.updateAudio(1_000_000, 1_020_000, 1_020_000, oldGeneration)
        assertEquals(PlaybackClock.STALE_TARGET,
            clock.queuedTimeUs(0, 1_025_000, oldGeneration))
        assertEquals(1_103_333L, clock.targetTimeUs(3_133_333, 1_030_000))
        assertEquals(newGeneration, clock.generation)
        // A genuine backward restart still belongs to fresh ingress and is accepted.
        assertEquals(1_100_000L, clock.targetTimeUs(0, 1_040_000))
        assertEquals(newGeneration + 1, clock.generation)
    }

    @Test fun queuedVideoThreeHundredMillisecondsAheadRemainsScheduledUntilDue() {
        val clock = PlaybackClock()
        clock.targetTimeUs(0, 1_000_000)
        clock.updateAudio(0, 1_100_000, 1_100_000)
        val generation = clock.generation
        assertEquals(1_400_000L, clock.queuedTimeUs(300_000, 1_100_000, generation))
        assertEquals(1_400_000L, clock.queuedTimeUs(300_000, 1_399_999, generation))
        assertEquals(1_400_000L, clock.queuedTimeUs(300_000, 1_400_000, generation))
        assertEquals(generation, clock.generation)
    }

    @Test fun stalledAudioHeadExpiresRatherThanSlidingVideoTargetsForever() {
        val clock = PlaybackClock()
        clock.targetTimeUs(0, 1_000_000)
        clock.updateAudio(0, 1_100_000, 1_100_000)
        clock.updateAudio(0, 1_400_000, 1_400_000)
        assertEquals(1_400_000L, clock.queuedTimeUs(300_000, 1_400_000, clock.generation))
        clock.updateAudio(0, 1_700_000, 1_700_000)
        assertEquals(1_360_000L, clock.queuedTimeUs(300_000, 1_700_000, clock.generation))
    }

    @Test fun excessiveOutputLeadReanchorsBothStreamsButStaleOutputCannotReanchor() {
        val clock = PlaybackClock()
        clock.targetTimeUs(0, 1_000_000)
        val originalGeneration = clock.generation
        assertEquals(1_360_000L, clock.reanchorIfAheadUs(300_000, 1_060_000, originalGeneration))
        assertEquals(originalGeneration, clock.generation)
        assertEquals(1_120_000L, clock.reanchorIfAheadUs(900_000, 1_060_000, originalGeneration))
        val recoveredGeneration = clock.generation
        assertEquals(originalGeneration + 1, recoveredGeneration)
        assertEquals(PlaybackClock.STALE_TARGET,
            clock.reanchorIfAheadUs(0, 1_070_000, originalGeneration))
        assertEquals(PlaybackClock.STALE_TARGET,
            clock.queuedTimeUs(0, 1_070_000, originalGeneration))
        assertEquals(1_153_333L, clock.targetTimeUs(933_333, 1_080_000))
        assertEquals(recoveredGeneration, clock.generation)
    }

    @Test fun usesInclusiveDecoderCropAndRejectsInvalidCrop() {
        assertEquals(1920 to 1080, visibleVideoSize(1920, 1088, 0, 1919, 0, 1079))
        assertEquals(1920 to 1088, visibleVideoSize(1920, 1088, null, null, null, null))
        assertEquals(1920 to 1088, visibleVideoSize(1920, 1088, 10, 0, -1, 2000))
    }
}
