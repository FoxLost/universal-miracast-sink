package foxlost.miracast.sink.media

/** A dropped packet is not a broken AudioTrack. Keep transport failures separate from timing. */
internal object PcmTiming {
    const val DROP_FRAME = Int.MIN_VALUE
    const val WRITE_DEADLINE_NS = 200_000_000L
    const val MAX_QUEUE_US = 200_000L
    private const val PTS_TOLERANCE_US = 40_000L

    enum class Continuity { CONTINUE, DROP_OVERLAP, REBASE_AFTER_GAP }

    fun ptsDeltaUs(ptsUs: Long, expectedUs: Long): Long =
        Math.floorMod(ptsUs - expectedUs + PlaybackClock.PTS_WRAP_US / 2, PlaybackClock.PTS_WRAP_US) -
            PlaybackClock.PTS_WRAP_US / 2
    /** Includes the not-yet-written portion of a pending packet, with MPEG PTS wrap. */
    fun superseded(positionPtsUs: Long, newestPtsUs: Long): Boolean =
        ptsDeltaUs(newestPtsUs, positionPtsUs) > MAX_QUEUE_US

    fun fitsQueue(queuedDurationUs: Long, incomingDurationUs: Long): Boolean =
        queuedDurationUs + incomingDurationUs <= MAX_QUEUE_US

    fun continuity(ptsUs: Long, expectedUs: Long): Continuity {
        val delta = ptsDeltaUs(ptsUs, expectedUs)
        return when {
            delta < -PTS_TOLERANCE_US -> Continuity.DROP_OVERLAP
            delta > PTS_TOLERANCE_US -> Continuity.REBASE_AFTER_GAP
            else -> Continuity.CONTINUE
        }
    }

    fun dropReason(staleEpoch: Boolean, nowNs: Long, deadlineNs: Long, nowUs: Long, targetUs: Long): String? = when {
        staleEpoch -> "stale-epoch"
        nowNs >= deadlineNs -> "write-deadline"
        nowUs - targetUs > PlaybackClock.MAX_LATENESS_US -> "late"
        else -> null
    }

    /** A pre-gap (or drained) device position must never become the new audio master. */
    fun canAnchor(position: Long, writtenFrames: Long, resumeFrame: Long): Boolean =
        position > resumeFrame && position < writtenFrames

    fun writableFrames(writtenFrames: Long, headFrames: Long, sampleRate: Int, startThresholdFrames: Int = 0): Long =
        (maxOf(sampleRate / 5L, startThresholdFrames.toLong()) -
            (writtenFrames - headFrames).coerceAtLeast(0)).coerceAtLeast(0)

    fun readyToStart(writtenFrames: Long, thresholdFrames: Int, nowUs: Long, startUs: Long): Boolean =
        writtenFrames >= thresholdFrames && nowUs >= startUs
}
