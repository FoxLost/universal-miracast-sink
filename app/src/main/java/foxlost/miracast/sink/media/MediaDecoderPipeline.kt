package foxlost.miracast.sink.media

import foxlost.miracast.sink.DebugEventCategory
import foxlost.miracast.sink.DebugEventLog
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import android.media.AudioTimestamp
import android.os.Build
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicLong

class MediaDecoderPipeline(
    private val surface: Surface,
    private val onVideoSizeChanged: ((Int, Int) -> Unit)? = null,
    private val onRecoveryNeeded: (() -> Unit)? = null,
) {
    private var videoCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    @Volatile private var isConfigured = false
    private var videoFormatReported = false

    /** Currently configured video dimensions (from SPS or default). */
    private var configuredWidth = 0
    private var configuredHeight = 0
    private var configuredProfile = 0
    private var configuredLevel = 0

    /** Fingerprint of the last seen SPS, to detect mid-stream changes. */
    private var lastSpsHash: Int = 0
    private val clock = PlaybackClock()
    private val videoLock = Any()
    private val audioLock = Any()
    private val outputInfo = MediaCodec.BufferInfo()
    private data class AccessUnit(val data: ByteArray, val ptsUs: Long, val epoch: Long, val generation: Long) {
        var prepared = false
        var picture = false
    }
    private val videoEpoch = AtomicLong()
    private val audioEpoch = AtomicLong()
    // Covers 300 ms early output plus continuous 60 fps ingress while retaining references.
    private val videoQueue = ArrayBlockingQueue<AccessUnit>(32)
    private val videoQueueLock = Any()
    private var queuedVideoBytes = 0
    private var pendingVideoOutput = -1
    private var pendingVideoPts = 0L
    private var pendingVideoGeneration = -1L
    private val submittedPts = LongArray(64)
    private val submittedGenerations = LongArray(64)
    private var submittedCount = 0
    @Volatile private var awaitingVideoIdr = false
    @Volatile private var running = true
    private val videoWorker = thread(name = "VideoDecoder") {
        var pending: AccessUnit? = null
        var busySinceNs = 0L
        while (running) {
            try {
                if (pending == null) {
                    synchronized(videoQueueLock) {
                        pending = videoQueue.poll()
                        pending?.let { queuedVideoBytes -= it.data.size }
                    }
                    busySinceNs = System.nanoTime()
                }
                synchronized(videoLock) {
                    drainVideo()
                    val au = pending
                    if (au != null) {
                        if (au.epoch != videoEpoch.get()) pending = null
                        else {
                            if (decodeVideoAccessUnit(au)) pending = null
                            else if (pendingVideoOutput >= 0) {
                                // Deliberate output pacing is not decoder overload.
                                busySinceNs = System.nanoTime()
                            } else if (System.nanoTime() - busySinceNs >= 500_000_000L) {
                                signalVideoLoss()
                                pending = null
                            }
                        }
                    }
                    drainVideo()
                }
                // Codec backpressure and future PTS waits never hold either media lock.
                Thread.sleep(2)
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.w("MediaDecoderPipeline", "Video worker error", e)
                pending = null
                synchronized(videoLock) { releaseVideoCodec() }
                signalVideoLoss()
            }
        }
    }
    private val audioTimestamp = AudioTimestamp()
    private var audioBasePtsUs = Long.MIN_VALUE
    private var audioWrittenFrames = 0L
    private var audioHeadTurns = 0L
    private var audioLastHead = 0L
    private var audioSampleRate = 0
    private var audioChannels = 0
    private var audioGeneration = -1L
    private var audioClockResumeFrame = 0L
    private var audioStartThresholdFrames = 0
    private var audioStartTimeUs = 0L
    private val diagnosticTimes = HashMap<String, Long>()

    private inline fun timingDiagnostic(reason: String, detail: () -> String) {
        val now = System.nanoTime()
        synchronized(diagnosticTimes) {
            val previous = diagnosticTimes[reason]
            if (previous != null && now - previous < 2_000_000_000L) return
            diagnosticTimes[reason] = now
        }
        Log.w("MediaDecoderPipeline", "$reason: ${detail()}")
    }
    private data class AudioFrame(
        val data: ByteArray, val sampleRate: Int, val channels: Int, val ptsUs: Long,
        val epoch: Long, val generation: Long, val enqueuedNs: Long,
    ) {
        val durationUs: Long = data.size / (channels * 2) * 1_000_000L / sampleRate
    }
    private val audioQueue = ArrayBlockingQueue<AudioFrame>(32)
    private val audioQueueLock = Any()
    private var queuedAudioUs = 0L
    @Volatile private var newestAudioPtsUs = Long.MIN_VALUE
    @Volatile private var newestAudioGeneration = -1L
    private val audioWorker = thread(name = "PcmPlayback") {
        var pending: AudioFrame? = null
        var offset = 0
        var deadline = 0L
        while (running) {
            try {
                if (pending == null) {
                    synchronized(audioQueueLock) {
                        pending = audioQueue.poll()
                        pending?.let { queuedAudioUs -= it.durationUs }
                    }
                    if (pending == null) { Thread.sleep(2); continue }
                    offset = 0
                    deadline = pending!!.enqueuedNs + PcmTiming.WRITE_DEADLINE_NS
                }
                val frame = pending ?: continue
                val written = writePcmAudio(frame, offset, deadline)
                if (written == PcmTiming.DROP_FRAME) {
                    pending = null
                } else if (written < 0) {
                    synchronized(audioLock) { releaseAudio("write-error-$written") }
                    pending = null
                } else {
                    offset += written
                    if (offset >= frame.data.size) pending = null
                    else Thread.sleep(2)
                }
            } catch (_: InterruptedException) { break }
            catch (e: Exception) {
                synchronized(audioLock) { releaseAudio("worker-exception") }
                pending = null
                Log.w("MediaDecoderPipeline", "PCM playback error", e)
            }
        }
    }

    private fun initVideoDecoder(width: Int, height: Int, profile: Int, level: Int): Unit = synchronized(videoLock) {
        if (!running) return@synchronized
        if (isConfigured && width == configuredWidth && height == configuredHeight &&
            profile == configuredProfile && level == configuredLevel) return
        releaseVideoCodec()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_PROFILE, profile)
            setInteger(MediaFormat.KEY_LEVEL, level)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val codecs = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
        val preferred = codecs.findDecoderForFormat(format)
        val candidates = codecs.codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                try { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).isFormatSupported(format) }
                catch (_: RuntimeException) { false }
        }.sortedWith(compareBy(
            { info -> if (isSoftwareDecoder(info)) 1 else 0 },
            { info -> if (info.name == preferred) 0 else 1 },
        ))
        for (candidate in candidates) {
            var lowLatency = Build.VERSION.SDK_INT >= 30 &&
                candidate.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            while (true) {
                try {
                    if (lowLatency) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    else format.removeKey(MediaFormat.KEY_LOW_LATENCY)
                    videoCodec = MediaCodec.createByCodecName(candidate.name)
                    videoCodec?.configure(format, surface, null, 0)
                    videoCodec?.start()
                    isConfigured = true
                    configuredWidth = width
                    configuredHeight = height
                    configuredProfile = profile
                    configuredLevel = level
                    Log.d("MediaDecoderPipeline", "AVC decoder ${candidate.name} ${width}x$height profile=$profile level=$level lowLatency=$lowLatency")
                    DebugEventLog.record(DebugEventCategory.MEDIA, "H.264 decoder started ${width}x$height")
                    return
                } catch (e: Exception) {
                    Log.w("MediaDecoderPipeline", "AVC decoder ${candidate.name} failed lowLatency=$lowLatency", e)
                    releaseVideoCodec()
                    if (!lowLatency) break
                    // The optional driver hint must not disqualify an otherwise eligible decoder.
                    lowLatency = false
                }
            }
        }
        DebugEventLog.record(DebugEventCategory.ERROR, "No AVC decoder accepted the stream profile/level/size")
    }

    private fun isSoftwareDecoder(info: android.media.MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
        else info.name.startsWith("OMX.google.", true) || info.name.startsWith("c2.android.", true)

    fun feedVideoNalu(nalu: ByteArray, ptsUs: Long) {
        var requestRecovery = false
        var overloaded = false
        synchronized(videoQueueLock) {
            if (!running) return
            var admissible = true
            if (awaitingVideoIdr) {
                if (containsNal(nalu, 5)) awaitingVideoIdr = false
                else if (!containsNal(nalu, 7) && !containsNal(nalu, 8)) admissible = false
            }
            if (!admissible) requestRecovery = true
            else if (videoQueue.remainingCapacity() == 0 || nalu.size > 8 * 1024 * 1024 - queuedVideoBytes) {
                invalidateVideoQueueLocked()
                requestRecovery = true
                overloaded = true
            } else {
                val generation = synchronized(clock) {
                    clock.targetTimeUs(ptsUs, System.nanoTime() / 1000)
                    clock.generation
                }
                videoQueue.add(AccessUnit(nalu, ptsUs, videoEpoch.get(), generation))
                queuedVideoBytes += nalu.size
            }
        }
        // Never invoke the service's recovery callback while holding queue ownership.
        if (requestRecovery) onRecoveryNeeded?.invoke()
        if (overloaded)
            DebugEventLog.recordRateLimited(DebugEventCategory.MEDIA, "video-queue-full", "Video decoder queue full; awaiting IDR")
    }

    internal fun signalVideoLoss() {
        synchronized(videoQueueLock) { invalidateVideoQueueLocked() }
        onRecoveryNeeded?.invoke()
    }

    /** Admission, epoch replacement and IDR gating share the same queue monitor. */
    private fun invalidateVideoQueueLocked() {
        videoEpoch.incrementAndGet()
        awaitingVideoIdr = true
        videoQueue.clear()
        queuedVideoBytes = 0
    }

    private fun clearVideoQueue() = synchronized(videoQueueLock) {
        videoQueue.clear()
        queuedVideoBytes = 0
    }

    /** True consumes this AU; false retains the exact AU for a later worker turn. */
    private fun decodeVideoAccessUnit(au: AccessUnit): Boolean {
        val nalu = au.data
        val ptsUs = au.ptsUs
        if (!au.prepared) {
            au.prepared = true
            au.picture = containsNal(nalu, 1) || containsNal(nalu, 5)
            // Use the actual SPS profile, level and dimensions, not a guessed 1080p decoder.
            val sps = findSps(nalu)
            if (sps != null) {
                val dims = parseSpsDimensions(sps)
                val codec = AvcSpsProfile.read(sps)
                val hash = sps.contentHashCode()
                if (dims != null && codec != null && (!isConfigured || hash != lastSpsHash)) {
                    initVideoDecoder(dims.first, dims.second, codec.first, codec.second)
                    if (isConfigured) lastSpsHash = hash
                }
            }
        }
        if (au.epoch != videoEpoch.get()) return true

        if (!isConfigured || videoCodec == null) {
            signalVideoLoss()
            return true
        }

        try {
            val codec = videoCodec ?: return true
            val inputIndex = codec.dequeueInputBuffer(0L)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                    ?: throw IllegalStateException("Missing codec input buffer")
                inputBuffer.clear()
                if (nalu.size > inputBuffer.remaining() || submittedCount == submittedPts.size) {
                    codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0)
                    signalVideoLoss()
                    return true
                }
                inputBuffer.put(nalu)
                // Output keeps the ingress generation even if a newer timeline arrives.
                if (au.picture) {
                    submittedPts[submittedCount] = ptsUs
                    submittedGenerations[submittedCount++] = au.generation
                }
                codec.queueInputBuffer(inputIndex, 0, nalu.size, ptsUs, 0)
            } else return false

        } catch (e: Exception) {
            Log.e("MediaDecoderPipeline", "Error feeding video NALU: ${e.message}")
            DebugEventLog.recordRateLimited(DebugEventCategory.ERROR, "decoder-feed-error", "Video decode error: ${e.message ?: "unknown"}")
            signalVideoLoss()
            releaseVideoCodec()
        }
        return true
    }

    private fun drainVideo() {
        val codec = videoCodec ?: return
        // One owned output buffer bounds SurfaceTexture submissions and codec backpressure.
        repeat(16) {
            if (pendingVideoOutput < 0) {
                val index = codec.dequeueOutputBuffer(outputInfo, 0L)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    reportVideoSize(codec.outputFormat)
                    return@repeat
                }
                if (index < 0) return
                if (!videoFormatReported) reportVideoSize(codec.outputFormat)
                pendingVideoOutput = index
                pendingVideoPts = outputInfo.presentationTimeUs
                pendingVideoGeneration = -1L
                for (i in 0 until submittedCount) {
                    if (submittedPts[i] == pendingVideoPts) {
                        pendingVideoGeneration = submittedGenerations[i]
                        submittedCount--
                        submittedPts[i] = submittedPts[submittedCount]
                        submittedGenerations[i] = submittedGenerations[submittedCount]
                        break
                    }
                }
            }
            val now = System.nanoTime() / 1000
            refreshAudioClock(now)
            val target = synchronized(clock) {
                val time = clock.reanchorIfAheadUs(pendingVideoPts, now, pendingVideoGeneration)
                if (time != PlaybackClock.STALE_TARGET) pendingVideoGeneration = clock.generation
                time
            }
            if (target != PlaybackClock.STALE_TARGET && target > now) return
            val render = target != PlaybackClock.STALE_TARGET && now - target <= PlaybackClock.MAX_LATENESS_US
            val index = pendingVideoOutput
            pendingVideoOutput = -1
            // Timed release is only guaranteed for SurfaceView, not this TextureView.
            codec.releaseOutputBuffer(index, render)
        }
    }

    private fun reportVideoSize(format: MediaFormat) {
        fun value(key: String): Int? = if (format.containsKey(key)) format.getInteger(key) else null
        val dimensions = visibleVideoSize(
            format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT),
            value("crop-left"), value("crop-right"), value("crop-top"), value("crop-bottom"),
        )
        videoFormatReported = true
        onVideoSizeChanged?.invoke(dimensions.first, dimensions.second)
    }

    fun playPcmAudio(pcmData: ByteArray, sampleRate: Int, channels: Int, ptsUs: Long) {
        if (!running || sampleRate <= 0 || channels !in 1..2 ||
            pcmData.isEmpty() || pcmData.size > sampleRate.toLong() * channels * 2 / 5 ||
            pcmData.size % (channels * 2) != 0) return
        val nowNs = System.nanoTime()
        synchronized(clock) {
            clock.targetTimeUs(ptsUs, nowNs / 1000)
            val frame = AudioFrame(pcmData, sampleRate, channels, ptsUs, audioEpoch.get(), clock.generation, nowNs)
            synchronized(audioQueueLock) {
                newestAudioPtsUs = ptsUs
                newestAudioGeneration = frame.generation
                while (true) {
                    val oldest = audioQueue.peek() ?: break
                    if (audioQueue.remainingCapacity() > 0 &&
                        PcmTiming.fitsQueue(queuedAudioUs, frame.durationUs) &&
                        oldest.generation == frame.generation &&
                        !PcmTiming.superseded(oldest.ptsUs, ptsUs) &&
                        nowNs - oldest.enqueuedNs < PcmTiming.WRITE_DEADLINE_NS) break
                    queuedAudioUs -= audioQueue.remove().durationUs
                    timingDiagnostic("audio-queue-drop") { "evicting oldest PCM; retaining fresh work" }
                }
                if (audioQueue.offer(frame)) queuedAudioUs += frame.durationUs
            }
        }
    }

    private fun writePcmAudio(frame: AudioFrame, offset: Int, deadlineNs: Long): Int = synchronized(audioLock) {
        if (!running) return@synchronized PcmTiming.DROP_FRAME
        val pcmData = frame.data
        val sampleRate = frame.sampleRate
        val channels = frame.channels
        val ptsUs = frame.ptsUs
        val nowNs = System.nanoTime()
        // Reject stale work before it can mutate/reset the current clock.
        val stale = frame.epoch != audioEpoch.get() || frame.generation != clock.generation ||
            (frame.generation == newestAudioGeneration &&
                PcmTiming.superseded(ptsUs + offset / (channels * 2) * 1_000_000L / sampleRate, newestAudioPtsUs))
        val earlyDrop = PcmTiming.dropReason(stale, nowNs, deadlineNs, 0, 0)
        if (earlyDrop != null) {
            val reason = if (earlyDrop == "stale-epoch") "audio-drop-stale-epoch" else "audio-drop-write-deadline"
            timingDiagnostic(reason) { "offset=$offset bytes=${pcmData.size}" }
            return@synchronized PcmTiming.DROP_FRAME
        }
        val now = nowNs / 1000
        val positionPts = ptsUs + offset / (channels * 2) * 1_000_000L / sampleRate
        val targetGeneration = frame.generation
        var target = clock.sourceTimeUs(positionPts, targetGeneration)
        if (target == PlaybackClock.STALE_TARGET) return@synchronized PcmTiming.DROP_FRAME
        if (audioTrack != null && (audioSampleRate != sampleRate || audioChannels != channels || audioGeneration != targetGeneration)) {
            releaseAudio(if (audioGeneration != targetGeneration) "clock-generation" else "format-change")
            target = clock.sourceTimeUs(positionPts, targetGeneration)
            if (target == PlaybackClock.STALE_TARGET) return@synchronized PcmTiming.DROP_FRAME
        }
        val lateDrop = PcmTiming.dropReason(false, nowNs, deadlineNs, now, target)
        if (lateDrop != null) {
            timingDiagnostic("audio-drop-late") { "deltaUs=${target - now} offset=$offset" }
            return@synchronized PcmTiming.DROP_FRAME
        }
        // Feed ahead of presentation so the device can prime. Its delayed/stalled head
        // must never gate the PCM producer that is required to make that head advance.
        val feedAheadUs = maxOf(PcmTiming.MAX_QUEUE_US,
            audioStartThresholdFrames * 1_000_000L / sampleRate)
        if (target - now > feedAheadUs) return@synchronized 0
        if (offset == 0 && audioTrack != null) {
            val expected = audioBasePtsUs + audioWrittenFrames * 1_000_000L / sampleRate
            when (PcmTiming.continuity(ptsUs, expected)) {
                PcmTiming.Continuity.DROP_OVERLAP -> {
                    timingDiagnostic("audio-drop-overlap") { "deltaUs=${PcmTiming.ptsDeltaUs(ptsUs, expected)}" }
                    return@synchronized PcmTiming.DROP_FRAME
                }
                PcmTiming.Continuity.REBASE_AFTER_GAP -> {
                    // Keep the healthy device. Its queued samples still use the old mapping,
                    // so do not publish the new anchor until this boundary is audible.
                    audioBasePtsUs += PcmTiming.ptsDeltaUs(ptsUs, expected)
                    audioClockResumeFrame = audioWrittenFrames
                    clock.clearAudio()
                    timingDiagnostic("audio-pts-gap") { "deltaUs=${PcmTiming.ptsDeltaUs(ptsUs, expected)}" }
                }
                PcmTiming.Continuity.CONTINUE -> Unit
            }
        }
        if (audioTrack == null) {
            val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minimum = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) return@synchronized -1
            audioTrack = AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, sampleRate * channels * 2 / 10), AudioTrack.MODE_STREAM)
            val created = audioTrack!!
            // The platform may round the constructor's size upward. On older APIs
            // the effective buffer is also the streaming start threshold.
            created.setBufferSizeInFrames(minOf(created.bufferSizeInFrames, sampleRate / 5))
            if (Build.VERSION.SDK_INT >= 31) {
                created.setStartThresholdInFrames(minOf(created.bufferSizeInFrames, sampleRate / 10))
                audioStartThresholdFrames = created.startThresholdInFrames
            } else audioStartThresholdFrames = created.bufferSizeInFrames
            audioStartTimeUs = target
            Log.d("MediaDecoderPipeline", "PCM device bufferFrames=${created.bufferSizeInFrames} " +
                "startThresholdFrames=$audioStartThresholdFrames sampleRate=$sampleRate")
            audioSampleRate = sampleRate; audioChannels = channels
            audioBasePtsUs = positionPts
            audioWrittenFrames = 0; audioLastHead = 0; audioHeadTurns = 0; audioClockResumeFrame = 0
            audioGeneration = targetGeneration
        }
        val track = audioTrack ?: return@synchronized -1
        val frameBytes = channels * 2
        val head = playbackHead(track)
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING &&
            PcmTiming.readyToStart(audioWrittenFrames, audioStartThresholdFrames, now, audioStartTimeUs)) track.play()
        // Never cap below the device's actual priming requirement (including old ROMs).
        val writable = PcmTiming.writableFrames(audioWrittenFrames, head, sampleRate, audioStartThresholdFrames)
        val byteCount = minOf((pcmData.size - offset).toLong(), writable * frameBytes).toInt()
        if (byteCount == 0) return@synchronized 0
        val written = synchronized(clock) {
            if (frame.generation != clock.generation) return@synchronized PcmTiming.DROP_FRAME
            val result = track.write(pcmData, offset, byteCount, AudioTrack.WRITE_NON_BLOCKING)
            if (result > 0) audioWrittenFrames += result / frameBytes
            if (result >= 0 && track.playState != AudioTrack.PLAYSTATE_PLAYING &&
                PcmTiming.readyToStart(audioWrittenFrames, audioStartThresholdFrames, now, audioStartTimeUs)) track.play()
            result
        }
        timingDiagnostic("audio-device") {
            val timestampValid = track.getTimestamp(audioTimestamp)
            val underruns = track.underrunCount
            "underruns=$underruns queuedFrames=${(audioWrittenFrames - playbackHead(track)).coerceAtLeast(0)} " +
                "timestampValid=$timestampValid generation=$audioGeneration"
        }
        refreshAudioClock(System.nanoTime() / 1000)
        written
    }

    private fun playbackHead(track: AudioTrack): Long {
        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
        if (head < audioLastHead) audioHeadTurns += 1L shl 32
        audioLastHead = head
        return audioHeadTurns + head
    }

    private fun refreshAudioClock(nowUs: Long) = synchronized(audioLock) {
        val track = audioTrack ?: return@synchronized
        if (audioGeneration != clock.generation || audioBasePtsUs == Long.MIN_VALUE ||
            audioSampleRate <= 0 || audioWrittenFrames == 0L) {
            clock.clearAudio()
            return@synchronized
        }
        val head = playbackHead(track)
        if (!PcmTiming.canAnchor(head, audioWrittenFrames, audioClockResumeFrame)) {
            clock.clearAudio()
            return@synchronized
        }
        if (track.getTimestamp(audioTimestamp)) {
            if (!PcmTiming.canAnchor(audioTimestamp.framePosition, audioWrittenFrames, audioClockResumeFrame)) {
                clock.clearAudio()
                return@synchronized
            }
            clock.updateAudio(audioBasePtsUs + audioTimestamp.framePosition * 1_000_000L / audioSampleRate,
                audioTimestamp.nanoTime / 1000, nowUs, audioGeneration)
        } else {
            if (head > 0)
                clock.updateAudio(audioBasePtsUs + head * 1_000_000L / audioSampleRate, nowUs, nowUs, audioGeneration)
        }
    }

    fun resetTiming() = synchronized(audioLock) {
        audioEpoch.incrementAndGet()
        synchronized(audioQueueLock) {
            audioQueue.clear()
            queuedAudioUs = 0
            newestAudioPtsUs = Long.MIN_VALUE
            newestAudioGeneration = -1
        }
        clock.reset()
        releaseAudio("timing-reset")
    }

    private fun releaseAudio(reason: String) {
        val track = audioTrack
        audioTrack = null
        clock.clearAudio()
        if (track != null) timingDiagnostic("audio-track-reset") { "reason=$reason frames=$audioWrittenFrames" }
        try { track?.pause() } catch (_: Exception) {}
        try { track?.flush() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        audioBasePtsUs = Long.MIN_VALUE
    }

    private fun releaseVideoCodec() {
        isConfigured = false
        videoFormatReported = false
        val ownedOutput = pendingVideoOutput
        pendingVideoOutput = -1
        submittedCount = 0
        if (ownedOutput >= 0) {
            try { videoCodec?.releaseOutputBuffer(ownedOutput, false) } catch (_: Exception) {}
        }
        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null
    }

    fun release() {
        running = false
        videoWorker.interrupt()
        audioWorker.interrupt()
        clearVideoQueue()
        synchronized(videoLock) { releaseVideoCodec() }
        configuredWidth = 0
        configuredHeight = 0
        lastSpsHash = 0
        resetTiming()
        DebugEventLog.record(DebugEventCategory.MEDIA, "Media decoder pipeline released")
        Log.d("MediaDecoderPipeline", "MediaDecoderPipeline released")
    }

    // ------------------------------------------------------------------
    // Minimal Annex-B / SPS parsing for mid-stream resolution changes
    // ------------------------------------------------------------------

    private val NAL_SPS = 7

    private fun containsNal(data: ByteArray, type: Int): Boolean {
        for (i in 0 until data.size - 3)
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 1.toByte() && data[i + 3].toInt() and 0x1f == type) return true
        return false
    }

    private fun findSps(data: ByteArray): ByteArray? {
        var start = -1
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                if (start >= 0) return data.copyOfRange(start, i)
                if (data[i + 3].toInt() and 0x1f == NAL_SPS) start = i
                i += 3
            } else i++
        }
        return if (start >= 0) data.copyOfRange(start, data.size) else null
    }

    /** Extract (width, height) from an SPS NAL unit, or null on parse failure. */
    private fun parseSpsDimensions(nalu: ByteArray): Pair<Int, Int>? {
        return try {
            // Strip start code + NAL header, then remove emulation-prevention bytes.
            var start = 0
            while (start + 3 < nalu.size && nalu[start].toInt() == 0 && nalu[start + 1].toInt() == 0) {
                if (nalu[start + 2].toInt() == 1) { start += 3; break }
                if (start + 4 < nalu.size && nalu[start + 2].toInt() == 0 && nalu[start + 3].toInt() == 1) { start += 4; break }
                start++
            }
            val rbsp = ByteArray(nalu.size)
            var rbspLen = 0
            var i = start + 1 // skip NAL header byte
            while (i < nalu.size) {
                if (i + 2 < nalu.size && nalu[i].toInt() == 0 && nalu[i + 1].toInt() == 0 && nalu[i + 2].toInt() == 3) {
                    rbsp[rbspLen++] = 0; rbsp[rbspLen++] = 0
                    i += 3
                } else {
                    rbsp[rbspLen++] = nalu[i++]
                }
            }

            val br = BitReader(rbsp, rbspLen)
            val profileIdc = br.u(8)
            br.u(8) // constraint flags + reserved
            br.u(8) // level_idc
            br.ue() // seq_parameter_set_id

            // High-profile chroma fields (not present for CBP 66, but handle anyway)
            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244 ||
                profileIdc == 44 || profileIdc == 83 || profileIdc == 86 || profileIdc == 118 ||
                profileIdc == 128 || profileIdc == 138 || profileIdc == 139 || profileIdc == 134 || profileIdc == 135) {
                val chromaFormatIdc = br.ue()
                if (chromaFormatIdc == 3) br.u(1)
                br.ue(); br.ue(); br.u(1)
                if (br.u(1) == 1) { // seq_scaling_matrix_present
                    val n = if (chromaFormatIdc != 3) 8 else 12
                    for (j in 0 until n) {
                        if (br.u(1) == 1) {
                            val size = if (j < 6) 16 else 64
                            var lastScale = 8; var nextScale = 8
                            for (k in 0 until size) {
                                if (nextScale != 0) { nextScale = (lastScale + br.se() + 256) % 256 }
                                lastScale = if (nextScale == 0) lastScale else nextScale
                            }
                        }
                    }
                }
            }

            br.ue() // log2_max_frame_num_minus4
            val pocType = br.ue()
            if (pocType == 0) {
                br.ue()
            } else if (pocType == 1) {
                br.u(1); br.se(); br.se()
                val cycles = br.ue()
                for (k in 0 until cycles) br.se()
            }
            br.ue() // num_ref_frames
            br.u(1) // gaps_in_frame_num_value_allowed_flag

            val picWidthMbs = br.ue() + 1
            val picHeightMapUnits = br.ue() + 1
            val frameMbsOnly = br.u(1)
            if (frameMbsOnly == 0) br.u(1) // mb_adaptive_frame_field_flag
            br.u(1) // direct_8x8_inference_flag

            var cropLeft = 0; var cropRight = 0; var cropTop = 0; var cropBottom = 0
            if (br.u(1) == 1) { // frame_cropping_flag
                cropLeft = br.ue(); cropRight = br.ue(); cropTop = br.ue(); cropBottom = br.ue()
            }

            val width = picWidthMbs * 16
            val height = picHeightMapUnits * 16 * (2 - frameMbsOnly)
            // CBP is 4:2:0 → crop unit x=2, y=2*frameMbsOnly factor
            val cropUnitX = 2
            val cropUnitY = 2 * (2 - frameMbsOnly)
            val finalW = width - (cropLeft + cropRight) * cropUnitX
            val finalH = height - (cropTop + cropBottom) * cropUnitY
            if (finalW > 0 && finalH > 0) Pair(finalW, finalH) else Pair(width, height)
        } catch (e: Exception) {
            Log.w("MediaDecoderPipeline", "SPS parse failed: ${e.message}")
            null
        }
    }

    /** Exp-Golomb bit reader for SPS parsing. */
    private class BitReader(private val data: ByteArray, private val length: Int) {
        private var bitPos = 0

        fun u(n: Int): Int {
            var value = 0
            for (i in 0 until n) {
                value = (value shl 1) or readBit()
            }
            return value
        }

        fun ue(): Int {
            var zeros = 0
            while (readBit() == 0 && zeros < 32) zeros++
            if (zeros == 0) return 0
            return ((1 shl zeros) - 1) + u(zeros)
        }

        fun se(): Int {
            val k = ue()
            return if (k % 2 == 0) -(k / 2) else (k + 1) / 2
        }

        private fun readBit(): Int {
            if (bitPos >= length * 8) return 0
            val byteVal = data[bitPos / 8].toInt() and 0xFF
            val bit = (byteVal shr (7 - (bitPos % 8))) and 1
            bitPos++
            return bit
        }
    }
}
