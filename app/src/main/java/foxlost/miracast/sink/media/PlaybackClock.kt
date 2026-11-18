package foxlost.miracast.sink.media

/** Shared PTS timeline. All times are monotonic microseconds, never wall clock. */
class PlaybackClock(private val startupUs: Long = 60_000L) {
    companion object {
        const val PTS_WRAP_US = (1L shl 33) * 1_000_000L / 90_000L
        const val MAX_LATENESS_US = 100_000L
        const val STALE_TARGET = Long.MIN_VALUE
        const val MAX_OUTPUT_HOLD_US = 500_000L
        private const val DISCONTINUITY_US = 2_000_000L
    }
    private var basePts = Long.MIN_VALUE
    private var baseTime = 0L
    private var referencePts = 0L
    private var audioPts = 0L
    private var audioTime = Long.MIN_VALUE
    @Volatile var generation = 0L; private set

    @Synchronized fun reset() {
        basePts = Long.MIN_VALUE; audioTime = Long.MIN_VALUE; generation++
    }
    /** Invalidate a released/underrun track without restarting the shared source timeline. */
    @Synchronized fun clearAudio() {
        audioTime = Long.MIN_VALUE
    }
    private fun unwrap(pts: Long): Long {
        if (basePts == Long.MIN_VALUE) return pts
        val turns = Math.floorDiv(referencePts - pts + PTS_WRAP_US / 2, PTS_WRAP_US)
        return pts + turns * PTS_WRAP_US
    }
    /**
     * Scheduling queued work must not discover discontinuities: only fresh ingress may
     * call targetTimeUs. The generation check and mapping are atomic with reset.
     */
    @Synchronized fun queuedTimeUs(ptsUs: Long, nowUs: Long, expectedGeneration: Long): Long {
        if (expectedGeneration != generation || basePts == Long.MIN_VALUE) return STALE_TARGET
        val pts = unwrap(ptsUs)
        return if (audioTime != Long.MIN_VALUE && nowUs - audioTime in 0..500_000L)
            audioTime + pts - audioPts else baseTime + pts - basePts
    }
    /** PCM feeds the device from the source timeline, never from its own delayed head. */
    @Synchronized fun sourceTimeUs(ptsUs: Long, expectedGeneration: Long): Long {
        if (expectedGeneration != generation || basePts == Long.MIN_VALUE) return STALE_TARGET
        return baseTime + unwrap(ptsUs) - basePts
    }

    /**
     * Only the current generation may recover an implausibly early decoded timestamp.
     * Reanchor both streams rather than holding codec/SurfaceTexture buffers for seconds.
     * This changes output/PCM scheduling, never the H.264 input reference epoch.
     */
    @Synchronized fun reanchorIfAheadUs(ptsUs: Long, nowUs: Long, expectedGeneration: Long): Long {
        val target = queuedTimeUs(ptsUs, nowUs, expectedGeneration)
        if (target == STALE_TARGET || target - nowUs <= MAX_OUTPUT_HOLD_US) return target
        reset()
        return targetTimeUs(ptsUs, nowUs)
    }
    @Synchronized fun targetTimeUs(ptsUs: Long, nowUs: Long): Long {
        var pts = unwrap(ptsUs)
        if (basePts == Long.MIN_VALUE) {
            basePts = pts; baseTime = nowUs + startupUs; referencePts = pts
        }
        var target = baseTime + pts - basePts
        if (kotlin.math.abs(target - nowUs) > DISCONTINUITY_US) {
            reset(); pts = ptsUs
            basePts = pts; baseTime = nowUs + startupUs; referencePts = pts
            target = baseTime
        }
        referencePts = maxOf(referencePts, pts)
        if (audioTime != Long.MIN_VALUE && nowUs - audioTime in 0..500_000L)
            target = audioTime + pts - audioPts
        return target
    }
    /** Audio media position at the reported AudioTrack monotonic timestamp. */
    @Synchronized fun updateAudio(ptsUs: Long, timestampUs: Long, nowUs: Long, expectedGeneration: Long = generation) {
        if (expectedGeneration != generation) return
        if (timestampUs > nowUs + 10_000L || nowUs - timestampUs > 500_000L) return
        val pts = unwrap(ptsUs)
        if (basePts == Long.MIN_VALUE ||
            kotlin.math.abs(baseTime + pts - basePts - timestampUs) > DISCONTINUITY_US) return
        // A stalled playback head must expire, not slide every future video target forever.
        if (audioTime != Long.MIN_VALUE && pts <= audioPts && timestampUs > audioTime) return
        audioPts = pts; audioTime = timestampUs
    }
}

/** Decoder crop coordinates are inclusive; invalid/missing crops retain coded dimensions. */
fun visibleVideoSize(width: Int, height: Int, left: Int?, right: Int?, top: Int?, bottom: Int?): Pair<Int, Int> {
    val w = if (left != null && right != null && left >= 0 && right in left until width) right - left + 1 else width
    val h = if (top != null && bottom != null && top >= 0 && bottom in top until height) bottom - top + 1 else height
    return w to h
}
