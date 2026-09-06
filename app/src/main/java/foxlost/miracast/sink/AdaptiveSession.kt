package foxlost.miracast.sink

/** Controller lifecycle owned by one WFD session generation. */
enum class SessionState {
    Idle,
    P2pStarting,
    Discovering,
    GroupForming,
    GroupFormed,
    ControlConnecting,
    ControlAccepted,
    Negotiating,
    TransportReady,
    MediaStarting,
    Streaming,
    Stopping,
    Failed,
}

enum class P2pRole {
    SinkClientSourceGo,
    SinkGoSourceClient,
}

enum class SourceKind {
    Windows,
    Android,
    Unknown,
}

enum class RtspEntry {
    Outbound,
    Inbound,
}

data class PeerIdentity(
    val address: String,
    val name: String,
    val controlPort: Int,
    val wfdEnabled: Boolean = true,
    val manufacturer: String? = null,
)

data class SourceEvidence(
    val role: P2pRole,
    val entry: RtspEntry,
    val firstMethod: String? = null,
    val server: String? = null,
    val userAgent: String? = null,
    val requestedParameters: Set<String> = emptySet(),
    val selectedTransport: String? = null,
    val hasMicrosoftParameters: Boolean = false,
    val hasIntelParameters: Boolean = false,
    val hasWfd2Parameters: Boolean = false,
)

data class CompatibilityProfile(
    val sourceKind: SourceKind,
    val entry: RtspEntry,
    val restrictedPublic: Boolean,
    val secondPlay: Boolean,
    val setupDelayMs: Long,
    val firstPlayDelayMs: Long,
    val secondPlayDelayMs: Long,
    val normalizeBroadcastUrl: Boolean,
) {
    companion object {
        fun forRole(role: P2pRole, secondPlay: Boolean): CompatibilityProfile =
            if (role == P2pRole.SinkClientSourceGo) {
                windows(secondPlay, RtspEntry.Outbound)
            } else {
                android(secondPlay, RtspEntry.Outbound)
            }

        fun windows(secondPlay: Boolean = true, entry: RtspEntry = RtspEntry.Outbound) =
            CompatibilityProfile(
                sourceKind = SourceKind.Windows,
                entry = entry,
                restrictedPublic = true,
                secondPlay = secondPlay,
                setupDelayMs = 80L,
                firstPlayDelayMs = 150L,
                secondPlayDelayMs = 200L,
                normalizeBroadcastUrl = true,
            )

        fun android(secondPlay: Boolean = true, entry: RtspEntry = RtspEntry.Outbound) =
            CompatibilityProfile(
                sourceKind = SourceKind.Android,
                entry = entry,
                restrictedPublic = true,
                secondPlay = secondPlay,
                setupDelayMs = 80L,
                firstPlayDelayMs = 150L,
                secondPlayDelayMs = 200L,
                normalizeBroadcastUrl = true,
            )

        fun unknown(entry: RtspEntry) =
            CompatibilityProfile(
                sourceKind = SourceKind.Unknown,
                entry = entry,
                restrictedPublic = true,
                secondPlay = false,
                setupDelayMs = 0L,
                firstPlayDelayMs = 0L,
                secondPlayDelayMs = 0L,
                normalizeBroadcastUrl = true,
            )
    }
}

object AdaptiveProfileSelector {
    /**
     * The source owns the RTSP server in both P2P role arrangements. P2P GO
     * ownership only changes which side gets the source's IPv4 address.
     */
    fun entryForRole(role: P2pRole): RtspEntry = RtspEntry.Outbound

    fun select(evidence: SourceEvidence): CompatibilityProfile {
        val microsoft = evidence.hasMicrosoftParameters ||
            evidence.requestedParameters.any { it.startsWith("microsoft_", ignoreCase = true) }
        val intel = evidence.hasIntelParameters ||
            evidence.requestedParameters.any { it.startsWith("intel_", ignoreCase = true) }
        val windowsShape = evidence.requestedParameters.size >= 20 ||
            evidence.requestedParameters.any { it.equals("microsoft_video_formats", true) }
        val androidShape =
            evidence.firstMethod.equals("OPTIONS", true) ||
                evidence.requestedParameters.isNotEmpty() ||
                evidence.selectedTransport?.contains("RTP/AVP/UDP", true) == true
        return when {
            evidence.role == P2pRole.SinkClientSourceGo && (microsoft || intel || windowsShape) ->
                CompatibilityProfile.windows(entry = RtspEntry.Outbound)
            evidence.role == P2pRole.SinkGoSourceClient && androidShape ->
                CompatibilityProfile.android(entry = RtspEntry.Outbound)
            else -> CompatibilityProfile.unknown(entryForRole(evidence.role))
        }
    }
}

data class SessionContext(
    val generation: Long,
    val attemptId: Long = 0L,
    val peer: PeerIdentity,
    val role: P2pRole,
    val groupOwnerAddress: String?,
    val localAddress: String?,
    val entry: RtspEntry,
    val profile: CompatibilityProfile,
    val sourceHint: SourceKind = SourceKind.Unknown,
    val selectedUrl: String? = null,
    val sessionId: String? = null,
    val localRtpPort: Int? = null,
    val localRtcpPort: Int? = null,
    val remoteRtpPort: Int? = null,
    val remoteRtcpPort: Int? = null,
    val ssrc: Long? = null,
)


class AdaptiveSessionController(
    private val listener: Listener = Listener.NONE,
) {
    interface Listener {
        fun onStateChanged(generation: Long, state: SessionState, context: SessionContext?)
        fun onCleanup(generation: Long, reason: String)

        companion object {
            val NONE = object : Listener {
                override fun onStateChanged(generation: Long, state: SessionState, context: SessionContext?) = Unit
                override fun onCleanup(generation: Long, reason: String) = Unit
            }
        }
    }

    private val lock = Any()
    private var nextGeneration = 0L
    private var currentGeneration = 0L
    private var currentState = SessionState.Idle
    private var currentContext: SessionContext? = null
    private var currentAttemptId = 0L
    private var lastInvalidAttemptId = 0L

    val state: SessionState
        get() = synchronized(lock) { currentState }

    val generation: Long
        get() = synchronized(lock) { currentGeneration }

    val context: SessionContext?
        get() = synchronized(lock) { currentContext }

    fun start(): Long = synchronized(lock) {
        if (currentState != SessionState.Idle) stopLocked("replaced by new start")
        currentGeneration = ++nextGeneration
        currentAttemptId = 0L
        lastInvalidAttemptId = 0L
        currentState = SessionState.P2pStarting
        DebugEventLog.record(DebugEventCategory.SINK, "Session $currentGeneration starting")
        listener.onStateChanged(currentGeneration, currentState, null)
        currentState = SessionState.Discovering
        DebugEventLog.record(DebugEventCategory.P2P, "P2P discovery started (generation=$currentGeneration)")
        listener.onStateChanged(currentGeneration, currentState, null)
        currentGeneration
    }

    fun markGroupForming(generation: Long): Boolean = transition(generation, SessionState.GroupForming)

    fun onGroupFormed(
        generation: Long,
        peer: PeerIdentity,
        role: P2pRole,
        groupOwnerAddress: String?,
        localAddress: String?,
        attemptId: Long = 0L,
    ): SessionContext? = synchronized(lock) {
        if (generation != currentGeneration || currentState !in setOf(
                SessionState.P2pStarting,
                SessionState.Discovering,
                SessionState.GroupForming,
            )
        ) return@synchronized null
        if (attemptId != 0L && attemptId <= lastInvalidAttemptId) {
            return@synchronized null
        }
        if (currentAttemptId != 0L && attemptId != 0L && currentAttemptId != attemptId) {
            return@synchronized null
        }
        if (attemptId != 0L) currentAttemptId = attemptId
        val entry = AdaptiveProfileSelector.entryForRole(role)
        val context = SessionContext(
            generation = generation,
            attemptId = attemptId,
            peer = peer,
            role = role,
            groupOwnerAddress = groupOwnerAddress,
            localAddress = localAddress,
            entry = entry,
            profile = CompatibilityProfile.unknown(entry),
        )
        currentContext = context
        currentState = SessionState.GroupFormed
        DebugEventLog.record(
            DebugEventCategory.P2P,
            "P2P group formed role=$role attempt=$attemptId",
        )
        listener.onStateChanged(generation, currentState, context)
        context
    }
    fun resetAttempt(generation: Long, attemptId: Long, reason: String): Boolean = synchronized(lock) {
        if (generation != currentGeneration || currentState == SessionState.Idle ||
            currentState == SessionState.Stopping ||
            (currentAttemptId != 0L && currentAttemptId != attemptId)
        ) return@synchronized false
        if (attemptId != 0L) lastInvalidAttemptId = maxOf(lastInvalidAttemptId, attemptId)
        currentAttemptId = 0L
        currentContext = null
        currentState = SessionState.Discovering
        DebugEventLog.record(DebugEventCategory.P2P, "P2P attempt reset: $reason")
        listener.onStateChanged(generation, currentState, null)
        true
    }

    fun isCurrent(generation: Long, attemptId: Long): Boolean = synchronized(lock) {
        generation == currentGeneration &&
            currentState != SessionState.Idle &&
            (attemptId == 0L || (attemptId > lastInvalidAttemptId &&
                (currentAttemptId == 0L || currentAttemptId == attemptId)))
    }

    fun applyProfile(generation: Long, profile: CompatibilityProfile): SessionContext? = synchronized(lock) {
        val context = currentContext ?: return@synchronized null
        if (generation != currentGeneration) return@synchronized null
        val updated = context.copy(profile = profile, sourceHint = profile.sourceKind)
        currentContext = updated
        DebugEventLog.record(
            DebugEventCategory.WFD,
            "WFD profile selected ${profile.sourceKind} (${profile.entry})",
        )
        listener.onStateChanged(generation, currentState, updated)
        updated
    }

    fun updateProfile(generation: Long, evidence: SourceEvidence): SessionContext? = synchronized(lock) {
        val context = currentContext ?: return@synchronized null
        if (generation != currentGeneration) return@synchronized null
        val profile = AdaptiveProfileSelector.select(evidence)
        val updated = context.copy(profile = profile, sourceHint = profile.sourceKind)
        currentContext = updated
        listener.onStateChanged(generation, currentState, updated)
        updated
    }

    fun beginOutbound(generation: Long, attemptId: Long = 0L): SessionContext? = synchronized(lock) {
        val context = currentContext ?: return@synchronized null
        if (generation != currentGeneration ||
            context.entry != RtspEntry.Outbound ||
            (attemptId != 0L && context.attemptId != attemptId)
        ) return@synchronized null
        currentState = SessionState.ControlConnecting
        DebugEventLog.record(DebugEventCategory.RTSP, "RTSP outbound control connecting")
        listener.onStateChanged(generation, currentState, context)
        currentState = SessionState.Negotiating
        DebugEventLog.record(DebugEventCategory.RTSP, "RTSP negotiation started")
        listener.onStateChanged(generation, currentState, context)
        context
    }


    fun onTransportReady(generation: Long, sessionId: String?, localRtp: Int, localRtcp: Int?, remoteRtp: Int?, remoteRtcp: Int?, ssrc: Long?): Boolean = synchronized(lock) {
        val context = currentContext ?: return@synchronized false
        if (generation != currentGeneration || currentState !in setOf(SessionState.Negotiating, SessionState.MediaStarting)) return@synchronized false
        currentContext = context.copy(
            sessionId = sessionId,
            localRtpPort = localRtp,
            localRtcpPort = localRtcp,
            remoteRtpPort = remoteRtp,
            remoteRtcpPort = remoteRtcp,
            ssrc = ssrc,
        )
        currentState = SessionState.TransportReady
        DebugEventLog.record(
            DebugEventCategory.RTSP,
            "RTSP transport ready RTP=$localRtp RTCP=${localRtcp ?: "none"}",
        )
        listener.onStateChanged(generation, currentState, currentContext)
        true
    }
    fun acceptInbound(generation: Long, peerAddress: String): SessionContext? = synchronized(lock) {
        val context = currentContext ?: return@synchronized null
        if (generation != currentGeneration || context.role != P2pRole.SinkGoSourceClient) return@synchronized null
        currentState = SessionState.ControlAccepted
        DebugEventLog.record(DebugEventCategory.RTSP, "RTSP inbound control accepted")
        listener.onStateChanged(generation, currentState, context)
        currentState = SessionState.Negotiating
        DebugEventLog.record(DebugEventCategory.RTSP, "RTSP negotiation started")
        listener.onStateChanged(generation, currentState, context)
        context
    }

    fun markMediaStarting(generation: Long): Boolean = transition(generation, SessionState.MediaStarting)

    fun markStreaming(generation: Long): Boolean = transition(generation, SessionState.Streaming)

    fun onGroupDisconnected(generation: Long): Boolean {
        if (!isCurrent(generation)) return false
        stop("P2P group disconnected")
        return true
    }

    fun isCurrent(generation: Long): Boolean = synchronized(lock) {
        generation == currentGeneration && currentState != SessionState.Idle
    }

    fun stop(reason: String) {
        synchronized(lock) { stopLocked(reason) }
    }

    private fun transition(generation: Long, target: SessionState): Boolean = synchronized(lock) {
        if (generation != currentGeneration || currentState == SessionState.Idle || currentState == SessionState.Stopping) return@synchronized false
        currentState = target
        val category = when (target) {
            SessionState.P2pStarting, SessionState.Discovering, SessionState.GroupForming,
            SessionState.GroupFormed -> DebugEventCategory.P2P
            SessionState.ControlConnecting, SessionState.ControlAccepted,
            SessionState.Negotiating, SessionState.TransportReady -> DebugEventCategory.RTSP
            SessionState.MediaStarting, SessionState.Streaming -> DebugEventCategory.MEDIA
            else -> DebugEventCategory.SINK
        }
        if (target == SessionState.MediaStarting) {
            DebugEventLog.recordOnce(
                category,
                "media-start-$generation",
                "Session state $target",
            )
        } else {
            DebugEventLog.record(category, "Session state $target")
        }
        listener.onStateChanged(generation, target, currentContext)
        true
    }

    private fun stopLocked(reason: String) {
        if (currentState == SessionState.Idle || currentState == SessionState.Stopping) return
        val generation = currentGeneration
        currentState = SessionState.Stopping
        DebugEventLog.record(DebugEventCategory.SINK, "Session stopping: $reason")
        listener.onStateChanged(generation, currentState, currentContext)
        listener.onCleanup(generation, reason)
        currentContext = null
        currentAttemptId = 0L
        lastInvalidAttemptId = 0L
        currentState = SessionState.Idle
        listener.onStateChanged(generation, currentState, null)
    }
}
