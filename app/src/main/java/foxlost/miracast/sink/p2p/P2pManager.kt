package foxlost.miracast.sink.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import foxlost.miracast.sink.DebugEventCategory
import foxlost.miracast.sink.DebugEventLog
import kotlin.concurrent.thread

class P2pManager(private val context: Context, private val listener: P2pListener) {
    private val TAG = "MiracastP2P"
    private val wifiP2pManager: WifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel = wifiP2pManager.initialize(context, context.mainLooper, null)

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var rootBridge: RootBridgeClient? = null
    private var stopCompletion: java.util.concurrent.CompletableFuture<Boolean>? = null
    private val retiredBridges = mutableListOf<RootBridgeClient>()
    private data class AppConnect(val operation: P2pConnectOperation, val inspector: RootBridgeClient)
    private val appConnectLock = Any()
    private var appConnect: AppConnect? = null
    private var sessionBand = P2pBand.AUTO
    private val startRequestSerial = java.util.concurrent.atomic.AtomicLong()
    private var disposed = false
    @Volatile private var isMiracastEnabled = false
    @Volatile private var groupStarted = false
    private var discoveryRunnable: Runnable? = null
    private var listenRunnable: Runnable? = null
    private var keepAliveRunnable: Runnable? = null
    @Volatile private var extListenThread: Thread? = null
    private val attempts = P2pAttemptController()
    private val readinessGate = GroupReadinessGate(GROUP_READINESS_TIMEOUT_MS)
    @Volatile private var sessionGeneration = 0L
    @Volatile private var activeAttempt: P2pAttemptController.Token? = null
    private var cleanupBarrierRunnable: Runnable? = null
    private var invitationStallRunnable: Runnable? = null
    @Volatile private var monitorRunId = 0L
    private var frameworkListenActive = false
    private var wfdSetupState = WfdSetupState.Idle
    private enum class WfdSetupState { Idle, FrameworkPending, FallbackPending, Ready, Failed }
    private var cleanupBarrierToken: P2pAttemptController.Token? = null
    private var p2pModeStarted = false
    private var readinessRunnable: Runnable? = null
    private var readinessProbeInFlight = false
    // Group formation can be reported through several framework/vendor paths.
    // Keep one transition gate so a persistent invitation does not start
    // duplicate RTSP sessions when those paths overlap.
    private val groupStateLock = Any()
    private var activeGroupIdentity: String? = null
    private var groupTransitionInFlight = false
    private var groupTransitionPending = false
    private data class PendingInvitation(
        val attemptId: Long,
        val peerAddress: String,
        val groupSsid: String?,
        val groupBssid: String?,
        val opFrequency: Int?,
        val persistent: String?,
        val accepted: Boolean = false,
        val fallbackScheduled: Boolean = false,
    )

    private data class InvitationFallbackTarget(
        val attemptId: Long,
        val peerAddress: String,
    )

    private var nextInvitationAttemptId = 0L

    private var pendingInvitation: PendingInvitation? = null
    private var invitationFallbackLastAt = 0L
    private var invitationFallbackLastAddress: String? = null

    companion object {
        const val GO_INTENT_CLIENT = 0
        const val GO_INTENT_FALLBACK = 8
        const val INVITATION_FALLBACK_DELAY_MS = 500L
        const val INVITATION_FALLBACK_DEBOUNCE_MS = 10_000L
        // WifiP2pConfig's hidden/default value (-2) allows the framework to
        // match a peer to a stale persistent group and call reinvoke(). A
        // headless connection request must never take that path.
        private const val NETWORK_ID_TEMPORARY = -1
        // Android's WFD device-info bitfield for a primary, available sink
        // with no content-protection or service-discovery extensions.
        const val WFD_DEVICE_INFO = 0x0011
        const val GROUP_READINESS_TIMEOUT_MS = 6_000L
        const val GROUP_READINESS_POLL_MS = 250L

        const val CLEANUP_BARRIER_TIMEOUT_MS = 2_000L
        const val CLEANUP_BARRIER_POLL_MS = 100L
        // A fresh GO/WPS negotiation uses Android's native two-minute formation window,
        // not the shorter IP/interface readiness deadline after GROUP-STARTED.
        const val INVITATION_FALLBACK_STALL_MS = 120_000L
        const val DEFAULT_P2P_INTERFACE = "p2p0"

        internal fun invitationFallbackPeer(
            peerAddress: String?,
            invitationAccepted: Boolean,
            fallbackScheduled: Boolean,
            activeGroup: Boolean,
        ): String? {
            if (activeGroup || !invitationAccepted || fallbackScheduled) return null
            return peerAddress?.takeIf { it.isNotBlank() }
        }

        internal fun isWfdPeerEligible(
            status: Int,
            wfdEnabled: Boolean,
            preferredAddressProvided: Boolean,
            preferredAddressMatches: Boolean,
        ): Boolean {
            if (!wfdEnabled) return false
            return if (preferredAddressProvided) {
                preferredAddressMatches
            } else {
                status == WifiP2pDevice.INVITED || status == WifiP2pDevice.AVAILABLE
            }
        }

        /**
         * Return possible P2P interface names without requiring /sys to be
         * readable. Some production SELinux policies deny an app's sysfs
         * lookup even though the framework and supplicant have p2p0 active.
         */
        internal fun p2pInterfaceCandidates(
            sysfsNames: List<String>?,
            procNetDev: String?,
            frameworkInterface: String = DEFAULT_P2P_INTERFACE,
        ): List<String> {
            val candidates = LinkedHashSet<String>()
            fun add(name: String?) {
                val value = name?.trim() ?: return
                if (value.startsWith("p2p") && value.length > 3 && '/' !in value) {
                    candidates += value
                }
            }
            sysfsNames.orEmpty().forEach(::add)
            procNetDev
                ?.lineSequence()
                ?.map { it.substringBefore(':').trim() }
                ?.forEach(::add)
            add(frameworkInterface)
            return candidates.toList()
        }
    }

    interface P2pListener {
        fun onP2pGroupConnected(group: WifiP2pGroup, info: WifiP2pInfo, attemptId: Long)
        fun onP2pGroupDisconnected(attemptId: Long)
        fun onP2pAttemptFailed(attemptId: Long, reason: String) {}
        fun onP2pAttemptExhausted(attemptId: Long, reason: String) {}
        fun onP2pStartupFailed(generation: Long, reason: String)
        fun onDiscoveryStateChanged(active: Boolean)
    }



    @SuppressLint("MissingPermission")
    fun startSink(generation: Long = 0L) {
        if (disposed) return
        val stopping = stopCompletion
        if (isMiracastEnabled || rootBridge != null || cleanupBarrierRunnable != null || stopping?.isDone == false) {
            stopSink()
            val request = startRequestSerial.get()
            checkNotNull(stopCompletion).thenAccept { clean ->
                mainHandler.post {
                    if (disposed || startRequestSerial.get() != request) return@post
                    if (clean) startSink(generation)
                    else listener.onP2pStartupFailed(generation, "Previous P2P/root cleanup was not confirmed")
                }
            }
            return
        }
        foxlost.miracast.sink.SettingsManager.init(context)
        val requestedBand = foxlost.miracast.sink.SettingsManager.wifiBand
        if (requestedBand == P2pBand.GHZ_5) {
            val wifi = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            if (!requestedBand.supportedBy(wifi?.is5GHzBandSupported != false)) {
                val reason = "5 GHz Wi-Fi is not supported by this device. Select Auto or 2.4 GHz."
                Log.e(TAG, reason)
                DebugEventLog.record(DebugEventCategory.ERROR, reason)
                listener.onP2pStartupFailed(generation, reason)
                return
            }
        }
        startRequestSerial.incrementAndGet()
        Log.d(TAG, "Starting Miracast Sink generation=$generation...")
        DebugEventLog.record(DebugEventCategory.P2P, "P2P sink start generation=$generation")
        sessionGeneration = generation
        wfdSetupState = WfdSetupState.Idle
        p2pModeStarted = false
        groupStarted = false
        isMiracastEnabled = true
        val token = attempts.start(generation)
        activeAttempt = token
        lastAutoAcceptAt = 0L
        lastAutoAcceptAddr = null
        Log.i(TAG, "Starting P2P attempt ${token.number}/3 id=${token.attemptId}")
        sessionBand = requestedBand
        val bandPolicy = when (sessionBand) {
            P2pBand.AUTO -> 0
            P2pBand.GHZ_2_4 -> 1
            P2pBand.GHZ_5 -> 2
        }
        DebugEventLog.record(DebugEventCategory.P2P,
            "P2P band policy generation=$generation policy=$bandPolicy (0=auto,1=2.4GHz,2=5GHz)")

        // A restarted app can inherit a native P2P group even when no Java
        // session remains. Do not advertise or accept it as a fresh session.
        cleanupAttempt(token) { clean ->
            if (!isMiracastEnabled || !attempts.accepts(token)) return@cleanupAttempt
            if (!clean) {
                failAttempt(token, "P2P previous group cleanup was not confirmed")
                return@cleanupAttempt
            }
            rootBridge = RootBridgeClient(context)
            chainWfdInfo()
        }
    }
    @SuppressLint("MissingPermission")
    fun stopSink(onComplete: ((Boolean) -> Unit)? = null) {
        startRequestSerial.incrementAndGet()
        stopCompletion?.takeIf { !it.isDone }?.let { pending ->
            if (onComplete != null) pending.thenAccept { clean -> mainHandler.post { onComplete(clean) } }
            return
        }
        val completion = java.util.concurrent.CompletableFuture<Boolean>()
        stopCompletion = completion
        if (onComplete != null) completion.thenAccept { clean -> mainHandler.post { onComplete(clean) } }
        groupStarted = false
        Log.d(TAG, "Stopping Miracast Sink...")
        DebugEventLog.record(DebugEventCategory.P2P, "P2P sink stopping")
        isMiracastEnabled = false
        val attemptToCancel = activeAttempt ?: cleanupBarrierToken
        cleanupBarrierRunnable?.let { mainHandler.removeCallbacks(it) }
        cleanupBarrierRunnable = null
        attempts.cancel(attemptToCancel)
        cleanupBarrierToken = null
        invitationStallRunnable?.let { mainHandler.removeCallbacks(it) }
        invitationStallRunnable = null
        readinessRunnable?.let { mainHandler.removeCallbacks(it) }
        readinessRunnable = null
        readinessProbeInFlight = false
        stopDiscoveryLoop()
        cancelExtendedListen()
        listenRunnable?.let { mainHandler.removeCallbacks(it) }
        listenRunnable = null
        keepAliveRunnable?.let { mainHandler.removeCallbacks(it) }
        keepAliveRunnable = null
        stopSupplicantMonitor()
        stopListeningFramework()
        val frameworkOwner = rootBridge
        frameworkOwner?.let { retiredBridges += it }
        rootBridge = null
        activeAttempt = null
        synchronized(groupStateLock) {
            activeGroupIdentity = null
            groupTransitionInFlight = false
            groupTransitionPending = false
            pendingInvitation = null
        }
        // This gate is deliberately independent of attempt tokens: stop cancels those tokens.
        val retiring = retiredBridges.toList()
        val appOwner = synchronized(appConnectLock) { appConnect }
        thread(name = "P2P-StopBarrier") {
            val appRestored = settleAppConnect(appOwner)
            frameworkOwner?.close()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45)
            var restored = true
            retiring.forEach { bridge ->
                val clean = try {
                    bridge.closedAndRestored.get(
                        (deadline - System.nanoTime()).coerceAtLeast(1),
                        java.util.concurrent.TimeUnit.NANOSECONDS,
                    )
                } catch (_: Exception) { false }
                restored = clean && restored
            }
            val remainingOwnersRestored = RootBridgeClient.awaitCleanup(
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(1)),
            )
            val allOwnersRestored = restored && appRestored && remainingOwnersRestored
            mainHandler.post {
                // Remove only after the owner has settled CONNECT: formation can
                // race EOF/cancellation and create a group after an earlier query.
                // Still attempt group cleanup if helper restoration was unsuccessful.
                try {
                    wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { Log.d(TAG, "removeGroup SUCCESS") }
                        override fun onFailure(reason: Int) { Log.d(TAG, "removeGroup request failed reason=$reason") }
                    })
                } catch (e: Exception) {
                    Log.d(TAG, "removeGroup unavailable: ${e.message}")
                }
                if (!allOwnersRestored) {
                    DebugEventLog.record(DebugEventCategory.ERROR, "P2P root restoration unconfirmed")
                    completion.complete(false)
                } else {
                    retiredBridges.removeAll(retiring.toSet())
                    confirmStoppedGroup(completion)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun confirmStoppedGroup(completion: java.util.concurrent.CompletableFuture<Boolean>) {
        val deadline = android.os.SystemClock.elapsedRealtime() + CLEANUP_BARRIER_TIMEOUT_MS
        val poll = object : Runnable {
            override fun run() {
                if (completion.isDone) return
                try {
                    wifiP2pManager.requestGroupInfo(channel) { group ->
                        if (completion.isDone) return@requestGroupInfo
                        if (group == null) completion.complete(true)
                        else if (android.os.SystemClock.elapsedRealtime() < deadline)
                            mainHandler.postDelayed(this, CLEANUP_BARRIER_POLL_MS)
                        else completion.complete(false)
                    }
                } catch (_: Exception) { completion.complete(false) }
            }
        }
        mainHandler.post(poll)
        mainHandler.postDelayed({ completion.complete(false) }, CLEANUP_BARRIER_TIMEOUT_MS)
    }

    /** Retire the app's framework channel only after its final cleanup query. */
    fun dispose() {
        disposed = true
        startRequestSerial.incrementAndGet()
        val pending = stopCompletion
        if (pending == null) stopSink { channel.close() }
        else pending.thenAccept { mainHandler.post { channel.close() } }
    }
    fun reportAttemptFailure(generation: Long, attemptId: Long, reason: String) {
        val token = activeAttempt?.takeIf { it.generation == generation && it.attemptId == attemptId } ?: return
        mainHandler.post {
            if (!isMiracastEnabled || !attempts.accepts(token)) return@post
            failAttempt(token, reason)
        }
    }

    private fun failAttempt(token: P2pAttemptController.Token, reason: String) {
        if (!attempts.accepts(token)) return
        val decision = attempts.fail(token)
        DebugEventLog.record(DebugEventCategory.ERROR, "P2P attempt ${token.number}/3 failed: $reason")
        activeAttempt = null
        listener.onP2pAttemptFailed(token.attemptId, reason)
        if (!isMiracastEnabled || !attempts.isCleanupPending(token)) return
        cleanupAttempt(token) { barrierOk ->
            if (!isMiracastEnabled || !attempts.isCleanupPending(token)) return@cleanupAttempt
            if (!barrierOk) {
                attempts.cancel(token)
                val barrierReason = "P2P cleanup barrier timed out after failure: $reason"
                Log.e(TAG, barrierReason)
                listener.onP2pAttemptExhausted(token.attemptId, barrierReason)
                return@cleanupAttempt
            }
            when (decision) {
                is P2pAttemptController.FailureDecision.Retry -> {
                    Log.w(
                        TAG,
                        "P2P attempt ${token.number}/3 failed: $reason; " +
                            "retrying in ${decision.delayMs}ms",
                    )
                    mainHandler.postDelayed(
                        { beginNextAttempt(token) },
                        decision.delayMs,
                    )
                }
                P2pAttemptController.FailureDecision.Exhausted -> {
                    Log.e(TAG, "P2P attempts exhausted after ${token.number}/3: $reason")
                    listener.onP2pAttemptExhausted(token.attemptId, reason)
                }
                P2pAttemptController.FailureDecision.Ignored -> Unit
            }
        }
    }

    private fun beginNextAttempt(previous: P2pAttemptController.Token) {
        if (!isMiracastEnabled) return
        if (!attempts.completeCleanup(previous)) return
        val token = attempts.beginNext() ?: return
        activeAttempt = token
        groupStarted = false
        lastAutoAcceptAt = 0L
        lastAutoAcceptAddr = null
        synchronized(groupStateLock) {
            activeGroupIdentity = null
            groupTransitionInFlight = false
            groupTransitionPending = false
            pendingInvitation = null
        }
        Log.i(TAG, "Starting P2P attempt ${token.number}/3 id=${token.attemptId}")
        rootBridge?.let { bridge -> bridge.close(); retiredBridges += bridge }
        rootBridge = RootBridgeClient(context)
        p2pModeStarted = false
        wfdSetupState = WfdSetupState.Idle
        chainWfdInfo()
    }
    private fun cleanupAttempt(
        token: P2pAttemptController.Token,
        onComplete: (Boolean) -> Unit,
    ) {
        cleanupBarrierToken = token
        readinessRunnable?.let { mainHandler.removeCallbacks(it) }
        readinessRunnable = null
        readinessProbeInFlight = false
        invitationStallRunnable?.let { mainHandler.removeCallbacks(it) }
        invitationStallRunnable = null
        groupStarted = false
        stopDiscoveryLoop()
        cancelExtendedListen()
        listenRunnable?.let { mainHandler.removeCallbacks(it) }
        listenRunnable = null
        keepAliveRunnable?.let { mainHandler.removeCallbacks(it) }
        keepAliveRunnable = null
        stopSupplicantMonitor()
        stopListeningFramework()
        clearExtendedListen()
        synchronized(groupStateLock) {
            activeGroupIdentity = null
            groupTransitionInFlight = false
            groupTransitionPending = false
            pendingInvitation = null
        }
        // EOF retires the exact helper/channel which dispatched CONNECT. Its
        // CLEANUP ACK includes cancellation/completion, WFD/mode and STA restoration.
        // No successor may be launched merely because requestGroupInfo returns null.
        val frameworkOwner = rootBridge
        frameworkOwner?.let { retiredBridges += it }
        rootBridge = null
        val retiring = retiredBridges.toList()
        val appOwner = synchronized(appConnectLock) { appConnect }
        thread(name = "P2P-AttemptRestore") {
            val appRestored = settleAppConnect(appOwner)
            frameworkOwner?.close()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45)
            var restored = true
            retiring.forEach { bridge ->
                val clean = try {
                    bridge.closedAndRestored.get(
                        (deadline - System.nanoTime()).coerceAtLeast(1),
                        java.util.concurrent.TimeUnit.NANOSECONDS,
                    )
                } catch (_: Exception) { false }
                restored = clean && restored
            }
            val ownersRestored = RootBridgeClient.awaitCleanup(
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(1)),
            )
            mainHandler.post {
                if (cleanupBarrierToken != token || !isMiracastEnabled) return@post
                if (!appRestored || !restored || !ownersRestored) {
                    DebugEventLog.record(DebugEventCategory.ERROR,
                        "P2P owned negotiation/root restoration unconfirmed attempt=${token.attemptId}")
                    // Group removal must still run, but can never convert an
                    // unfinished owner restoration into successful cleanup.
                    removeAttemptGroup(token) { onComplete(false) }
                } else {
                    retiredBridges.removeAll(retiring.toSet())
                    removeAttemptGroup(token, onComplete)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun removeAttemptGroup(token: P2pAttemptController.Token, onComplete: (Boolean) -> Unit) {
        try {
            wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "attempt=${token.attemptId} removeGroup request accepted")
                }
                override fun onFailure(reason: Int) {
                    Log.d(TAG, "attempt=${token.attemptId} removeGroup request failed reason=$reason")
                }
            })
        } catch (e: Exception) {
            Log.d(TAG, "attempt=${token.attemptId} removeGroup unavailable: ${e.message}")
        }
        pollCleanupBarrier(token, onComplete)
    }

    private fun pollCleanupBarrier(
        token: P2pAttemptController.Token,
        onComplete: (Boolean) -> Unit,
        startedAt: Long = android.os.SystemClock.elapsedRealtime(),
    ) {
        val deadline = startedAt + CLEANUP_BARRIER_TIMEOUT_MS
        cleanupBarrierRunnable?.let { mainHandler.removeCallbacks(it) }
        val poll = object : Runnable {
            override fun run() {
                if (cleanupBarrierToken != token || cleanupBarrierRunnable !== this || !isMiracastEnabled) {
                    return
                }
                val permission = if (android.os.Build.VERSION.SDK_INT >= 33)
                    android.Manifest.permission.NEARBY_WIFI_DEVICES else android.Manifest.permission.ACCESS_FINE_LOCATION
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    cleanupBarrierRunnable = null
                    cleanupBarrierToken = null
                    DebugEventLog.record(DebugEventCategory.P2P, "Cannot inspect P2P cleanup: Wi-Fi permission denied")
                    onComplete(false)
                    return
                }
                try {
                    wifiP2pManager.requestGroupInfo(channel) { group ->
                        if (cleanupBarrierToken != token || cleanupBarrierRunnable !== this || !isMiracastEnabled) {
                            return@requestGroupInfo
                        }
                        if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                            cleanupBarrierRunnable = null
                            cleanupBarrierToken = null
                            onComplete(false)
                        } else if (group == null) {
                            cleanupBarrierRunnable = null
                            cleanupBarrierToken = null
                            onComplete(true)
                        } else {
                            mainHandler.postDelayed(this, CLEANUP_BARRIER_POLL_MS)
                        }
                    }
                } catch (e: SecurityException) {
                    cleanupBarrierRunnable = null
                    cleanupBarrierToken = null
                    DebugEventLog.record(DebugEventCategory.P2P, "P2P cleanup permission revoked: ${e.message}")
                    onComplete(false)
                } catch (_: Exception) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        cleanupBarrierRunnable = null
                        cleanupBarrierToken = null
                        onComplete(false)
                    } else {
                        mainHandler.postDelayed(this, CLEANUP_BARRIER_POLL_MS)
                    }
                }
            }
        }
        cleanupBarrierRunnable = poll
        mainHandler.post(poll)
        mainHandler.postDelayed({
            if (cleanupBarrierToken == token && cleanupBarrierRunnable === poll && isMiracastEnabled) {
                cleanupBarrierRunnable = null
                cleanupBarrierToken = null
                onComplete(false)
            }
        }, CLEANUP_BARRIER_TIMEOUT_MS)
    }

    /** ARP is a fixed root operation belonging to the current sink session. */
    fun readArpTable(generation: Long, attemptId: Long): String? {
        val bridge = rootBridge ?: return null
        val token = activeAttempt ?: return null
        if (!isMiracastEnabled || generation != token.generation || attemptId != token.attemptId || !attempts.accepts(token)) return null
        return bridge.readArp().takeIf { rootBridge === bridge && attempts.accepts(token) && isMiracastEnabled }
    }

    fun readPeerInterfaceAddress(generation: Long, attemptId: Long, interfaceName: String, peerMac: String): String? {
        val bridge = rootBridge ?: return null
        val token = activeAttempt ?: return null
        if (!isMiracastEnabled || generation != token.generation || attemptId != token.attemptId || !attempts.accepts(token)) return null
        return bridge.readPeerInterfaceAddress(interfaceName, peerMac)
            .takeIf { rootBridge === bridge && attempts.accepts(token) && isMiracastEnabled }
    }

    fun launchPlayer(generation: Long, rtp: Int, rtcp: Int): Boolean {
        val bridge = rootBridge ?: return false
        if (!isMiracastEnabled || generation != sessionGeneration) return false
        return try {
            bridge.launchPlayer(generation, rtp, rtcp)
            rootBridge === bridge && isMiracastEnabled && generation == sessionGeneration
        } catch (e: Exception) {
            Log.e(TAG, "Privileged player launch failed", e)
            false
        }
    }

    private fun chainWfdInfo() {
        val bridge = rootBridge ?: return
        val token = activeAttempt ?: return
        wfdSetupState = WfdSetupState.FrameworkPending
        thread(name = "P2P-RootSetup") {
            try {
                val frameworkReady = bridge.start(
                    token.generation,
                    foxlost.miracast.sink.SettingsManager.effectiveRtspPort(),
                    foxlost.miracast.sink.SettingsManager.maxThroughput,
                    foxlost.miracast.sink.SettingsManager.disconnectWifiInSinkMode,
                    sessionBand,
                )
                mainHandler.post {
                    if (rootBridge !== bridge || !attempts.accepts(token) || !isMiracastEnabled) return@post
                    if (frameworkReady) {
                        markWfdSetup(WfdSetupState.Ready)
                        applyWfdExtendedCapabilityBit()
                    } else {
                        startWfdSupplicantFallback("root framework WFD rejected")
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (rootBridge === bridge && attempts.accepts(token) && isMiracastEnabled) {
                        wfdSetupState = WfdSetupState.Failed
                        val reason = if (e is RootCleanupException) {
                            "Previous root cleanup blocked startup: ${e.message}"
                        } else {
                            "Root Wi-Fi setup unavailable: ${e.message}"
                        }
                        failAttempt(token, reason)
                    }
                }
            }
        }
    }

    private fun uibcWfdEnabled(): Boolean =
        try { foxlost.miracast.sink.SettingsManager.uibcEnabled } catch (_: Exception) { false }

    private fun hexU16(value: Int): String =
        value.coerceIn(0, 0xFFFF).toString(16).padStart(4, '0')

    /**
     * The framework WifiP2pWfdInfo API has no Extended Capability bitmap
     * setter. The supplicant control API does: its command is
     * WFD_SUBELEM_SET <decimal-id> <length+body-hex>.
     */
    private fun applyWfdExtendedCapabilityBit() {
        val enabled = uibcWfdEnabled()
        val command = if (enabled) "WFD_SUBELEM_SET 7 00020001" else "WFD_SUBELEM_SET 7 "
        val bridge = rootBridge ?: return
        thread(name = "P2P-WfdExtCapability") {
            val ok = bridge.supplicant(command)
            if (ok && enabled) {
                Log.i(TAG, "WFD Extended Capability UIBC bit enabled")
                DebugEventLog.record(DebugEventCategory.WFD, "WFD Extended Capability UIBC bit enabled")
            } else if (ok) {
                Log.i(TAG, "WFD Extended Capability UIBC bit cleared")
                DebugEventLog.record(DebugEventCategory.WFD, "WFD Extended Capability UIBC bit cleared")
            } else if (enabled) {
                Log.w(TAG, "Unable to set WFD Extended Capability UIBC bit; RTSP UIBC remains optional")
                DebugEventLog.record(DebugEventCategory.ERROR, "WFD Extended Capability UIBC bit unavailable")
            } else {
                Log.d(TAG, "WFD Extended Capability UIBC clear unavailable")
            }
        }
    }

    private fun fallbackWfdDeviceInfoCommand(available: Boolean = true): String {
        val port = try { foxlost.miracast.sink.SettingsManager.effectiveRtspPort() } catch (_: Exception) { 7236 }
        val throughput = try { foxlost.miracast.sink.SettingsManager.maxThroughput } catch (_: Exception) { 50 }
        return "WFD_SUBELEM_SET 0 0006${if (available) "0011" else "0001"}${hexU16(port)}${hexU16(throughput)}"
    }

    private fun updateSessionAvailability(available: Boolean) {
        val bridge = rootBridge ?: return
        val token = activeAttempt ?: return
        thread(name = "P2P-WfdAvailability") {
            val ok = try {
                bridge.availability(available) || bridge.supplicant(fallbackWfdDeviceInfoCommand(available))
            } catch (_: Exception) { false }
            mainHandler.post {
                if (!ok && rootBridge === bridge && attempts.accepts(token) && isMiracastEnabled) {
                    failAttempt(token, "WFD session availability update rejected")
                }
            }
        }
    }

    private fun startWfdSupplicantFallback(reason: String) {
        synchronized(groupStateLock) {
            if (wfdSetupState == WfdSetupState.FallbackPending ||
                wfdSetupState == WfdSetupState.Ready ||
                wfdSetupState == WfdSetupState.Failed
            ) return
            wfdSetupState = WfdSetupState.FallbackPending
        }
        Log.w(TAG, "Starting root WFD fallback: $reason")
        DebugEventLog.record(DebugEventCategory.WFD, "WFD supplicant fallback starting: $reason")
        val bridge = rootBridge ?: return
        val token = activeAttempt ?: return
        thread(name = "P2P-WfdFallback") {
            val deviceInfoApplied = bridge.supplicant(fallbackWfdDeviceInfoCommand())
            val extendedCapabilityCommand = if (uibcWfdEnabled()) "WFD_SUBELEM_SET 7 00020001" else "WFD_SUBELEM_SET 7 "
            val extendedCapabilityApplied = deviceInfoApplied && bridge.supplicant(extendedCapabilityCommand)
            val ok = deviceInfoApplied && extendedCapabilityApplied
            mainHandler.post {
                if (rootBridge !== bridge || !attempts.accepts(token) || !isMiracastEnabled) return@post
                synchronized(groupStateLock) {
                    wfdSetupState = if (ok) WfdSetupState.Ready else WfdSetupState.Failed
                }
                if (ok) {
                    Log.i(TAG, "Root WFD fallback applied")
                    DebugEventLog.record(DebugEventCategory.WFD, "WFD supplicant fallback applied")
                } else {
                    failAttempt(token, "Root framework and supplicant WFD setup failed")
                    return@post
                }
                startP2pAfterWfdSetup()
            }
        }
    }

    private fun markWfdSetup(state: WfdSetupState) {
        synchronized(groupStateLock) { wfdSetupState = state }
        startP2pAfterWfdSetup()
    }

    private fun startP2pAfterWfdSetup() {
        if (!isMiracastEnabled || p2pModeStarted || wfdSetupState != WfdSetupState.Ready) return
        p2pModeStarted = true
        val forceGo = try { foxlost.miracast.sink.SettingsManager.forceAutonomousGo } catch (_: Exception) { false }
        if (forceGo) {
            Log.d(TAG, "forceAutonomousGo enabled — becoming Group Owner")
            val token = activeAttempt ?: return
            startAutonomousGo { success ->
                if (!success && attempts.accepts(token)) failAttempt(token, "Autonomous P2P group creation rejected")
            }
        } else {
            enterListenMode()
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(device: WifiP2pDevice, goIntent: Int = GO_INTENT_CLIENT, onResult: ((Boolean) -> Unit)? = null) {
        Log.d(TAG, "Connecting to ${device.deviceName} (${device.deviceAddress}) goIntent=$goIntent")
        connectTemporaryPeer(device.deviceAddress, goIntent, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "connect() initiated"); onResult?.invoke(true) }
            override fun onFailure(reason: Int) { Log.e(TAG, "connect() FAILED reason=$reason"); onResult?.invoke(false) }
        })
    }

    @SuppressLint("MissingPermission")
    fun startAutonomousGo(onResult: ((Boolean) -> Unit)? = null) {
        wifiP2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "createGroup SUCCESS — sink is now GO"); onResult?.invoke(true) }
            override fun onFailure(reason: Int) { Log.e(TAG, "createGroup FAILED reason=$reason"); onResult?.invoke(false) }
        })
    }


    // ------------------------------------------------------------------
    // Supplicant event monitor — optional vendor fallback only.
    // ------------------------------------------------------------------

    private var monitorProcess: Process? = null
    private var monitorThread: Thread? = null
    private var monitoredGeneration = -1L
    private var monitoredAttempt = -1L
    private val goNegArmTimes = mutableMapOf<String, Long>()

    @Synchronized
    private fun startSupplicantMonitor() {
        if (monitorThread?.isAlive == true) return
        val token = activeAttempt ?: return
        if (monitoredGeneration == token.generation && monitoredAttempt == token.attemptId) return
        val bridge = rootBridge ?: return
        monitoredGeneration = token.generation
        monitoredAttempt = token.attemptId
        val runId = monitorRunId + 1L
        monitorRunId = runId
        monitorThread = thread(name = "P2P-EventMonitor") {
            var process: Process? = null
            try {
                val p = bridge.monitor()
                process = p
                val current = synchronized(this@P2pManager) {
                    if (isMiracastEnabled && !groupStarted && monitorRunId == runId && rootBridge === bridge) {
                        monitorProcess = p
                        true
                    } else false
                }
                if (!current) return@thread
                Log.i(TAG, "Root supplicant monitor starting run=$runId")
                p.inputStream.bufferedReader().forEachLine { line ->
                    mainHandler.post {
                        if (rootBridge === bridge && monitorRunId == runId && isMiracastEnabled && !groupStarted) {
                            handleSupplicantEvent(line)
                        }
                    }
                }
            } catch (e: Exception) {
                if (monitorRunId == runId) Log.w(TAG, "Optional monitor unavailable; framework broadcasts remain active: ${e.message}")
            } finally {
                try { process?.outputStream?.close() } catch (_: Exception) {}
                try { process?.waitFor() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                process?.let { if (!it.isAlive) bridge.releaseMonitor(it) }
                synchronized(this@P2pManager) {
                    if (monitorRunId == runId) monitorProcess = null
                }
            }
        }
    }

    private fun stopSupplicantMonitor() {
        val process: Process?
        val worker: Thread?
        synchronized(this) {
            monitorRunId += 1L
            process = monitorProcess
            worker = monitorThread
            monitorProcess = null
            monitorThread = null
        }
        try { process?.outputStream?.close() } catch (_: Exception) {}
        worker?.interrupt()
        // No join on the UI thread. The owning worker/root client reaps the helper.
    }

    private fun eventMac(line: String, vararg keys: String): String? {
        for (key in keys) {
            val value = Regex("""(?:^|\s)$key=([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})""")
                .find(line)?.groupValues?.getOrNull(1)
            if (value != null) return value
        }
        return null
    }

    private fun eventValue(line: String, key: String): String? {
        val match = Regex("""(?:^|\s)$key=(?:"([^"]*)"|'([^']*)'|([^\s]+))""").find(line) ?: return null
        return match.groups[1]?.value ?: match.groups[2]?.value ?: match.groups[3]?.value
    }

    private fun eventInt(line: String, key: String): Int? =
        eventValue(line, key)?.toIntOrNull()

    private fun invitationPeerAddress(line: String): String? =
        eventMac(line, "sa", "peer", "go_dev_addr")
            ?: Regex("""P2P-INVITATION-(?:RECEIVED|ACCEPTED)\s+([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})""")
                .find(line)?.groupValues?.getOrNull(1)

    private fun rememberInvitationReceived(line: String) {
        val peer = invitationPeerAddress(line)
        val groupSsid = eventValue(line, "ssid")
        val groupBssid = eventMac(line, "bssid")
        val opFrequency = eventInt(line, "op_freq") ?: eventInt(line, "freq")
        val persistent = eventValue(line, "persistent")
        Log.i(
            TAG,
            "P2P invitation received peer=${peer ?: "unknown"} " +
                "persistent=${persistent ?: "unknown"} targetSsid=${groupSsid ?: "unknown"} " +
                "targetBssid=${groupBssid ?: "unknown"} opFreq=${opFrequency ?: "unknown"}",
        )
        if (peer == null) return
        synchronized(groupStateLock) {
            if (activeGroupIdentity != null) return@synchronized
            val current = pendingInvitation?.takeIf {
                it.peerAddress.equals(peer, ignoreCase = true)
            }
            // Every received invitation starts a new attempt, even when its
            // metadata repeats. Preserve fields omitted by a later event.
            pendingInvitation = PendingInvitation(
                attemptId = ++nextInvitationAttemptId,
                peerAddress = peer,
                groupSsid = groupSsid ?: current?.groupSsid,
                groupBssid = groupBssid ?: current?.groupBssid,
                opFrequency = opFrequency ?: current?.opFrequency,
                persistent = persistent ?: current?.persistent,
            )
        }
    }

    private fun rememberInvitationAccepted(line: String) {
        val peer = invitationPeerAddress(line)
        val accepted = synchronized(groupStateLock) {
            val current = pendingInvitation?.takeIf {
                peer == null || it.peerAddress.equals(peer, ignoreCase = true)
            }
            val resolved = current ?: peer?.let {
                PendingInvitation(
                    attemptId = ++nextInvitationAttemptId,
                    peerAddress = it,
                    groupSsid = eventValue(line, "ssid"),
                    groupBssid = eventMac(line, "bssid"),
                    opFrequency = eventInt(line, "op_freq") ?: eventInt(line, "freq"),
                    persistent = eventValue(line, "persistent"),
                )
            }
            if (resolved == null || activeGroupIdentity != null) {
                null
            } else {
                pendingInvitation = resolved.copy(accepted = true)
                resolved
            }
        }
        Log.i(
            TAG,
            "P2P invitation accepted peer=${accepted?.peerAddress ?: peer ?: "unknown"} " +
                "persistent=${accepted?.persistent ?: "unknown"} targetSsid=${accepted?.groupSsid ?: "unknown"} " +
                "targetBssid=${accepted?.groupBssid ?: "unknown"} opFreq=${accepted?.opFrequency ?: "unknown"}",
        )
    }

    private fun scheduleInvitationFallback(event: String) {
        if (!isMiracastEnabled) return
        val now = System.currentTimeMillis()
        val target = synchronized(groupStateLock) {
            val current = pendingInvitation
            val candidate = current?.let {
                invitationFallbackPeer(
                    peerAddress = it.peerAddress,
                    invitationAccepted = it.accepted,
                    fallbackScheduled = it.fallbackScheduled,
                    activeGroup = activeGroupIdentity != null,
                )
            }
            if (candidate == null) {
                null
            } else if (
                now - invitationFallbackLastAt < INVITATION_FALLBACK_DEBOUNCE_MS &&
                candidate.equals(invitationFallbackLastAddress, ignoreCase = true)
            ) {
                null
            } else {
                pendingInvitation = current.copy(fallbackScheduled = true)
                invitationFallbackLastAt = now
                invitationFallbackLastAddress = candidate
                InvitationFallbackTarget(current.attemptId, candidate)
            }
        }
        if (target == null) {
            Log.w(TAG, "P2P invitation formation failure ($event) — no eligible fallback (active/duplicate/not accepted)")
            val hasAcceptedInvitation = synchronized(groupStateLock) {
                pendingInvitation?.accepted == true
            }
            if (!hasAcceptedInvitation) {
                currentAttemptToken()?.let { failAttempt(it, "P2P formation failure: $event") }
            }
            return
        }
        Log.w(
            TAG,
            "P2P invitation formation failure ($event) — scheduling fresh WFD connect " +
                "peer=${target.peerAddress} attempt=${target.attemptId} in ${INVITATION_FALLBACK_DELAY_MS}ms",
        )
        currentAttemptToken()?.let { scheduleInvitationStall(it, target) }
        mainHandler.postDelayed({ connectAfterInvitationFailure(target) }, INVITATION_FALLBACK_DELAY_MS)
    }

    private fun isCurrentInvitationFallback(target: InvitationFallbackTarget): Boolean =
        synchronized(groupStateLock) {
            pendingInvitation?.let {
                it.attemptId == target.attemptId &&
                    it.peerAddress.equals(target.peerAddress, ignoreCase = true) &&
                    it.accepted &&
                    it.fallbackScheduled
            } == true
        }

    @SuppressLint("MissingPermission")
    private fun connectAfterInvitationFailure(target: InvitationFallbackTarget, waitAttempt: Int = 0) {
        if (!isMiracastEnabled || !isCurrentInvitationFallback(target)) return
        val groupBusy = synchronized(groupStateLock) {
            activeGroupIdentity != null || groupTransitionInFlight
        }
        if (groupBusy) {
            if (waitAttempt < 4) {
                Log.d(TAG, "Invitation fallback: waiting for P2P group probe before connecting ($waitAttempt/4)")
                mainHandler.postDelayed(
                    { connectAfterInvitationFailure(target, waitAttempt + 1) },
                    300L,
                )
            } else {
                Log.w(
                    TAG,
                    "Skipping invitation fallback for ${target.peerAddress} — P2P group state remained busy",
                )
                currentAttemptToken()?.let {
                    failAttempt(it, "invitation fallback group state remained busy")
                }
            }
            return
        }
        try {
            wifiP2pManager.requestPeers(channel) { peers ->
                if (!isMiracastEnabled || !isCurrentInvitationFallback(target)) return@requestPeers
                val callbackGroupBusy = synchronized(groupStateLock) {
                    activeGroupIdentity != null || groupTransitionInFlight
                }
                if (callbackGroupBusy) {
                    Log.i(
                        TAG,
                        "Skipping invitation fallback for ${target.peerAddress} — a P2P group became active",
                    )
                    return@requestPeers
                }
                val peer = peers.deviceList.firstOrNull { device ->
                    isWfdPeerEligible(
                        status = device.status,
                        wfdEnabled = device.readWfdPeerInfo().enabled,
                        preferredAddressProvided = true,
                        preferredAddressMatches = device.deviceAddress.equals(target.peerAddress, true),
                    )
                }
                if (peer == null) {
                    Log.w(
                        TAG,
                        "Invitation fallback: peer ${target.peerAddress} is no longer an eligible WFD peer",
                    )
                    currentAttemptToken()?.let { failAttempt(it, "invitation fallback peer unavailable") }
                    return@requestPeers
                }
                val goIntent = GO_INTENT_FALLBACK
                Log.i(
                    TAG,
                    "Invitation fallback: fresh WFD connect to ${peer.deviceName} (${peer.deviceAddress}) " +
                        "goIntent=$goIntent netId=$NETWORK_ID_TEMPORARY wps=PBC attempt=${target.attemptId}",
                )
                val token = currentAttemptToken()
                connectTemporaryPeer(peer.deviceAddress, goIntent, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "Invitation fallback: connect() initiated")
                        if (token != null) scheduleInvitationStall(token, target)
                    }

                    override fun onFailure(reason: Int) {
                        Log.e(TAG, "Invitation fallback: connect() FAILED reason=$reason")
                        token?.let { failAttempt(it, "invitation fallback connect failed: $reason") }
                    }
                })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Invitation fallback: requestPeers failed: ${e.message}")
            currentAttemptToken()?.let { failAttempt(it, "invitation fallback peer query failed") }
        }
    }
    private fun scheduleInvitationStall(
        token: P2pAttemptController.Token,
        target: InvitationFallbackTarget,
    ) {
        invitationStallRunnable?.let { mainHandler.removeCallbacks(it) }
        invitationStallRunnable = Runnable {
            if (
                isMiracastEnabled &&
                activeAttempt == token &&
                isCurrentInvitationFallback(target) &&
                !groupStarted
            ) {
                failAttempt(token, "invitation fallback formation timeout")
            }
        }
        mainHandler.postDelayed(invitationStallRunnable!!, INVITATION_FALLBACK_STALL_MS)
    }

    private fun handleSupplicantEvent(line: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { handleSupplicantEvent(line) }
            return
        }
        if (line.contains("P2P-GROUP-STARTED")) {
            groupStarted = true
            pausePreGroupOperations()
            invitationStallRunnable?.let { mainHandler.removeCallbacks(it) }
            invitationStallRunnable = null
            synchronized(groupStateLock) { pendingInvitation = null }
            val iface = Regex("""P2P-GROUP-STARTED\s+(\S+)""")
                .find(line)?.groupValues?.getOrNull(1)
            val owner = Regex("""go_dev_addr=([0-9a-fA-F:]{17})""")
                .find(line)?.groupValues?.getOrNull(1)
            Log.i(TAG, "Supplicant P2P-GROUP-STARTED iface=${iface ?: "unknown"} owner=${owner ?: "unknown"}")
            // Some vendor builds do not emit WIFI_P2P_CONNECTION_CHANGED_ACTION.
            // Start the same serialized group probe used by the framework path;
            // its retry policy handles the short binder-state propagation delay.
            val token = currentAttemptToken() ?: return
            mainHandler.post {
                if (isMiracastEnabled && attempts.accepts(token)) {
                    requestConnectionInfo("supplicant-group-started", 0)
                }
            }
            return
        }
        when {
            line.contains("P2P-INVITATION-RECEIVED") -> rememberInvitationReceived(line)
            line.contains("P2P-INVITATION-ACCEPTED") -> rememberInvitationAccepted(line)
            line.contains("P2P-GROUP-FORMATION-FAILURE") -> scheduleInvitationFallback("GROUP-FORMATION-FAILURE")
            line.contains("P2P-GROUP-REMOVED") -> {
                val reason = eventValue(line, "reason") ?: "unknown"
                Log.w(TAG, "Supplicant P2P-GROUP-REMOVED reason=$reason event=$line")
                if (reason.equals("FORMATION_FAILED", ignoreCase = true)) {
                    scheduleInvitationFallback("GROUP-REMOVED reason=$reason")
                }
            }
            line.contains("P2P-GO-NEG-REQUEST") -> {
                // A new GO negotiation supersedes any invitation attempt. Its
                // later failure must not be mistaken for invitation failure.
                synchronized(groupStateLock) { pendingInvitation = null }
                val addr = Regex("P2P-GO-NEG-REQUEST ([0-9a-fA-F:]{17})")
                    .find(line)?.groupValues?.getOrNull(1) ?: return
                val now = System.currentTimeMillis()
                synchronized(goNegArmTimes) {
                    if (now - (goNegArmTimes[addr] ?: 0L) < 10_000) return
                    goNegArmTimes[addr] = now
                }
                Log.i(TAG, "GO-NEG-REQUEST from $addr — arming supplicant (P2P_CONNECT pbc go_intent=0)")
                val bridge = rootBridge ?: return
                val token = activeAttempt ?: return
                thread {
                    if (!bridge.supplicant("P2P_CONNECT $addr pbc go_intent=0")) {
                        mainHandler.post {
                            if (rootBridge === bridge && attempts.accepts(token)) failAttempt(token, "Root GO negotiation arm rejected")
                        }
                    }
                }
            }
        }
    }

    private fun pausePreGroupOperations() {
        stopDiscoveryLoop()
        cancelExtendedListen()
        listenRunnable?.let { mainHandler.removeCallbacks(it) }
        listenRunnable = null
        keepAliveRunnable?.let { mainHandler.removeCallbacks(it) }
        keepAliveRunnable = null
        stopSupplicantMonitor()
        stopListeningFramework()
        // The optional vendor override is independent of the framework
        // callback; explicitly disable it before handing the group to RTSP.
        clearExtendedListen()
        listener.onDiscoveryStateChanged(false)
        Log.i(TAG, "Paused discovery, LISTEN, and Extended Listen after GROUP-STARTED")
    }

    private fun cancelExtendedListen() {
        extListenThread?.interrupt()
        extListenThread = null
    }

    private fun enterListenMode() {
        val bridge = rootBridge ?: return
        val token = activeAttempt ?: return
        val useListen = try { foxlost.miracast.sink.SettingsManager.usePersistentListen } catch (_: Exception) { true }
        if (!useListen) { startDiscoveryLoop(); return }
        thread(name = "P2P-RootListen") {
            try {
                bridge.listening(true)
                mainHandler.post {
                    if (rootBridge === bridge && attempts.accepts(token) && isMiracastEnabled && !groupStarted) {
                        frameworkListenActive = true
                        enterListenModeReady(bridge, token)
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (rootBridge === bridge && attempts.accepts(token) && isMiracastEnabled) {
                        if (e is UnsupportedOperationException) {
                            Log.w(TAG, "Framework listen unsupported; using peer discovery")
                            startSupplicantMonitor()
                            startDiscoveryLoop()
                        } else {
                            failAttempt(token, "Root listen failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun enterListenModeReady(bridge: RootBridgeClient, token: P2pAttemptController.Token) {
        val useListen = try { foxlost.miracast.sink.SettingsManager.usePersistentListen } catch (e: Exception) { true }
        if (useListen) {
            // Framework startListening() drives proper probe responses + WFD IE
            // advertisement so Windows can FIND us. Extended-listen timing is
            // left at the framework default unless SettingsManager.extListenMs
            // is set — the supplicant event monitor arms GO-neg on demand,
            listener.onDiscoveryStateChanged(true)
            // Extended-listen override. Default OFF: the supplicant event
            // monitor (below) arms GO-neg the instant a request arrives, which
            // is the real Status-1 fix. Leave the framework's native timing
            // alone unless the user explicitly enables the override.
            val extListenMs = try { foxlost.miracast.sink.SettingsManager.extListenMs } catch (e: Exception) { 0 }
            if (extListenMs > 0) {
                extListenThread = thread(name = "P2P-ExtListen-Once") {
                    try {
                        Thread.sleep(500)  // let startListening() settle first
                        if (rootBridge === bridge && attempts.accepts(token) && isMiracastEnabled && !groupStarted) {
                            if (!bridge.supplicant("P2P_EXT_LISTEN $extListenMs $extListenMs")) {
                                mainHandler.post {
                                    if (rootBridge === bridge && attempts.accepts(token)) failAttempt(token, "Extended listen override rejected")
                                }
                            }
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        extListenThread = null
                    }
                }
                listenRunnable = object : Runnable {
                    override fun run() {
                        if (rootBridge !== bridge || !attempts.accepts(token) || !isMiracastEnabled || groupStarted) return
                        thread { bridge.supplicant("P2P_EXT_LISTEN $extListenMs $extListenMs") }
                        mainHandler.postDelayed(this, 5000)
                    }
                }
                mainHandler.postDelayed(listenRunnable!!, 5000)
                Log.i(TAG, "Framework LISTEN active — ext-listen override ${extListenMs}ms")
            } else {
                Log.i(TAG, "Framework LISTEN active — ext-listen left at framework default")
            }

            // Attach the supplicant event monitor so we can arm GO-neg the
            // instant a source knocks (headless accept path).
            startSupplicantMonitor()

            // Keep-alive: the framework auto-disables P2P after a period of
            // inactivity (observed: InactiveState → P2pDisablingState →
            // P2pDisabledState ~2.5 min after the last binder call). We send a
            // lightweight no-op binder transaction every 30 s so the sink
            // stays discoverable indefinitely.
            keepAliveRunnable = object : Runnable {
                override fun run() {
                    if (!isMiracastEnabled) return
                    try {
                        @SuppressLint("MissingPermission")
                        wifiP2pManager.requestDeviceInfo(channel) { }
                    } catch (_: Exception) {}
                    mainHandler.postDelayed(this, 30_000)
                }
            }
            mainHandler.postDelayed(keepAliveRunnable!!, 30_000)
        } else {
            startDiscoveryLoop()
        }
    }

    private fun clearExtendedListen() {
        val bridge = rootBridge ?: return
        thread(name = "P2P-ClearExtListen") { bridge.clearExtendedListen() }
    }

    private fun stopListeningFramework(): Boolean {
        if (!frameworkListenActive) return false
        frameworkListenActive = false
        val bridge = rootBridge ?: return false
        thread(name = "P2P-RootStopListen") {
            try { bridge.listening(false) } catch (e: Exception) { Log.w(TAG, "Stop listen: ${e.message}") }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startDiscoveryLoop() {
        stopDiscoveryLoop()
        val token = currentAttemptToken() ?: return
        discoveryRunnable = object : Runnable {
            fun current() = isMiracastEnabled && attempts.accepts(token) && discoveryRunnable === this && !groupStarted
            override fun run() {
                if (!current()) return
                try {
                    wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { if (current()) listener.onDiscoveryStateChanged(true) }
                        override fun onFailure(reason: Int) { if (current()) listener.onDiscoveryStateChanged(false) }
                    })
                } catch (e: Exception) {
                    failAttempt(token, "P2P discovery failed: ${e.message}")
                    return
                }
                if (current()) mainHandler.postDelayed(this, 10000)
            }
        }
        mainHandler.post(discoveryRunnable!!)
    }

    @SuppressLint("MissingPermission")
    private fun stopDiscoveryLoop() {
        discoveryRunnable?.let { mainHandler.removeCallbacks(it) }
        discoveryRunnable = null
        try {
            wifiP2pManager.stopPeerDiscovery(channel, null)
        } catch (e: Exception) {
            Log.w(TAG, "Stop discovery unavailable: ${e.message}")
        }
    }

    private fun groupIdentity(group: WifiP2pGroup): String {
        val owner = group.owner?.deviceAddress
            ?: group.clientList.firstOrNull()?.deviceAddress
            ?: "unknown"
        return listOf(group.`interface`, group.networkName, owner)
            .joinToString("|") { it ?: "" }
    }

    private fun currentAttemptToken(): P2pAttemptController.Token? =
        activeAttempt?.takeIf { isMiracastEnabled && cleanupBarrierToken == null && attempts.accepts(it) }

    private fun interfaceReadiness(group: WifiP2pGroup, info: WifiP2pInfo): Pair<Boolean, Boolean> {
        val iface = group.`interface`?.takeIf { it.isNotBlank() } ?: return false to false
        return try {
            val networkInterface = java.net.NetworkInterface.getByName(iface) ?: return false to false
            val up = networkInterface.isUp && !networkInterface.isLoopback
            val goAddress = info.groupOwnerAddress as? java.net.Inet4Address
            if (goAddress == null || goAddress.isAnyLocalAddress || goAddress.isLoopbackAddress ||
                goAddress.isMulticastAddress) return up to false
            val addresses = networkInterface.inetAddresses
            var ipv4Present = false
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is java.net.Inet4Address && !address.isLoopbackAddress && !address.isAnyLocalAddress &&
                    (!info.isGroupOwner || address == goAddress)) {
                    ipv4Present = true
                    break
                }
            }
            up to ipv4Present
        } catch (_: Exception) {
            // The interface can disappear between lookup and status/address reads.
            false to false
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginGroupReadiness(token: P2pAttemptController.Token) {
        readinessProbeInFlight = false
        val startedAt = android.os.SystemClock.elapsedRealtime()
        readinessRunnable?.let { mainHandler.removeCallbacks(it) }
        val poll = object : Runnable {
            override fun run() {
                if (!isMiracastEnabled || !attempts.accepts(token) || readinessRunnable !== this) return
                if (readinessProbeInFlight) return
                readinessProbeInFlight = true
                try {
                    wifiP2pManager.requestGroupInfo(channel) { freshGroup ->
                        if (!isMiracastEnabled || !attempts.accepts(token) || readinessRunnable !== this) {
                            return@requestGroupInfo
                        }
                        if (freshGroup == null) {
                            evaluateGroupReadiness(token, startedAt, this, null, null)
                            return@requestGroupInfo
                        }
                        try {
                            wifiP2pManager.requestConnectionInfo(channel) { freshInfo ->
                                evaluateGroupReadiness(token, startedAt, this, freshGroup, freshInfo)
                            }
                        } catch (_: Exception) {
                            evaluateGroupReadiness(token, startedAt, this, null, null)
                        }
                    }
                } catch (_: Exception) {
                    evaluateGroupReadiness(token, startedAt, this, null, null)
                }
            }
        }
        readinessRunnable = poll
        mainHandler.post(poll)
        mainHandler.postDelayed({
            if (isMiracastEnabled && attempts.accepts(token) && readinessRunnable === poll) {
                failAttempt(token, "P2P group/network readiness callback deadline exceeded")
            }
        }, GROUP_READINESS_TIMEOUT_MS)
    }

    private fun evaluateGroupReadiness(
        token: P2pAttemptController.Token,
        startedAt: Long,
        poll: Runnable,
        currentGroup: WifiP2pGroup?,
        currentInfo: WifiP2pInfo?,
    ) {
        if (!isMiracastEnabled || !attempts.accepts(token) || readinessRunnable !== poll) return
        readinessProbeInFlight = false
        val matchesGroup = currentGroup != null && currentInfo != null &&
            currentGroup.isGroupOwner == currentInfo.isGroupOwner &&
            synchronized(groupStateLock) { activeGroupIdentity == groupIdentity(currentGroup) }
        val peerPresent = matchesGroup && currentGroup!!.remoteWfdSource(currentInfo!!.isGroupOwner) != null
        val (interfaceUp, ipv4Present) = if (matchesGroup) interfaceReadiness(currentGroup!!, currentInfo!!)
            else false to false
        when (readinessGate.evaluate(
            elapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt,
            groupFormed = matchesGroup && currentInfo!!.groupFormed,
            peerPresent = peerPresent,
            interfaceUp = interfaceUp,
            ipv4Present = ipv4Present,
        )) {
            GroupReadinessGate.Result.Ready -> {
                if (sessionBand != P2pBand.AUTO) {
                    val frequency = currentGroup!!.frequency
                    if (!sessionBand.accepts(frequency)) {
                        readinessRunnable = null
                        mainHandler.removeCallbacks(poll)
                        failAttempt(token, "Requested ${sessionBand.name}, but group frequency is $frequency MHz; band enforcement not confirmed")
                        return
                    }
                    DebugEventLog.record(DebugEventCategory.P2P, "Forced band verified: $frequency MHz (${sessionBand.name})")
                }
                DebugEventLog.record(DebugEventCategory.P2P,
                    "P2P accepted group generation=${token.generation} attempt=${token.attemptId} " +
                        "policy=${sessionBand.ordinal} frequencyMhz=${currentGroup!!.frequency}")
                readinessRunnable = null
                mainHandler.removeCallbacks(poll)
                Log.i(
                    TAG,
                    "P2P network ready attempt=${token.attemptId} " +
                        "iface=${currentGroup?.`interface` ?: "unknown"} " +
                        "go=${currentInfo?.groupOwnerAddress?.hostAddress ?: "unknown"}",
                )
                DebugEventLog.record(DebugEventCategory.DHCP, "P2P network ready; remote WFD source and group IPv4 verified")
                listener.onP2pGroupConnected(currentGroup!!, currentInfo!!, token.attemptId)
            }
            GroupReadinessGate.Result.TimedOut -> {
                readinessRunnable = null
                mainHandler.removeCallbacks(poll)
                failAttempt(token, "P2P group/network readiness timeout")
            }
            GroupReadinessGate.Result.Waiting -> {
                mainHandler.postDelayed(poll, GROUP_READINESS_POLL_MS)
            }
        }
    }

    private fun handleGroupConnected(group: WifiP2pGroup, info: WifiP2pInfo) {
        val token = currentAttemptToken() ?: return
        if (!isMiracastEnabled || !info.groupFormed) return
        groupStarted = true
        pausePreGroupOperations()
        val identity = groupIdentity(group)
        val isNewGroup = synchronized(groupStateLock) {
            if (activeGroupIdentity == identity) {
                false
            } else {
                activeGroupIdentity = identity
                true
            }
        }
        Log.i(
            TAG,
            "P2P group candidate identity=$identity " +
                "interface=${group.`interface` ?: "unknown"} " +
                "groupOwnerAddress=${info.groupOwnerAddress?.hostAddress ?: "unknown"} " +
                "isGroupOwner=${info.isGroupOwner} attempt=${token.attemptId}",
        )
        DebugEventLog.recordRateLimited(
            DebugEventCategory.P2P,
            "group-candidate-$identity",
            "P2P group candidate role=${if (info.isGroupOwner) "GO" else "client"}",
            1_000L,
        )
        if (isNewGroup) {
            updateSessionAvailability(false)
            beginGroupReadiness(token)
        } else {
            Log.d(TAG, "Ignoring duplicate P2P group transition identity=$identity")
        }
    }

    private fun handleGroupDisconnected() {
        val token = currentAttemptToken() ?: return
        val wasConnected = synchronized(groupStateLock) {
            val connected = activeGroupIdentity != null
            activeGroupIdentity = null
            connected
        }
        readinessRunnable?.let { mainHandler.removeCallbacks(it) }
        readinessRunnable = null
        readinessProbeInFlight = false
        val hadGroupStarted = groupStarted
        groupStarted = false
        if (wasConnected || hadGroupStarted) {
            listener.onP2pGroupDisconnected(token.attemptId)
            failAttempt(token, "P2P group disconnected")
        }
    }

    private fun scheduleSupplicantGroupRetry(
        reason: String,
        attempt: Int,
        token: P2pAttemptController.Token,
    ) {
        if (reason == "supplicant-group-started" && attempt < 3) {
            mainHandler.postDelayed({
                if (isMiracastEnabled && attempts.accepts(token)) {
                    requestConnectionInfo(reason, attempt + 1)
                }
            }, 300L * (attempt + 1))
        }
    }

    @SuppressLint("MissingPermission")
    fun requestConnectionInfo() {
        requestConnectionInfo("framework", 0)
    }

    /**
     * Query the framework in its required order: group first, then connection
     * info. Broadcasts and the supplicant fallback can arrive together, so
     * serialize probes and replay one pending trigger after the current probe.
     */
    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo(reason: String, attempt: Int) {
        if (!isMiracastEnabled) return
        val token = currentAttemptToken() ?: return
        val startProbe = synchronized(groupStateLock) {
            if (groupTransitionInFlight) {
                groupTransitionPending = true
                false
            } else {
                groupTransitionInFlight = true
                true
            }
        }
        if (!startProbe) return
        var completed = false
        lateinit var timeout: Runnable
        fun finish(group: WifiP2pGroup?, info: WifiP2pInfo?) {
            if (completed || !isMiracastEnabled || !attempts.accepts(token)) return
            completed = true
            mainHandler.removeCallbacks(timeout)
            completeGroupTransition(group, info, reason, attempt, token)
        }
        timeout = Runnable {
            if (!completed && isMiracastEnabled && attempts.accepts(token)) {
                completed = true
                failAttempt(token, "P2P group probe callback deadline exceeded")
            }
        }
        mainHandler.postDelayed(timeout, GROUP_READINESS_TIMEOUT_MS)

        try {
            wifiP2pManager.requestGroupInfo(channel) { group ->
                if (completed || !isMiracastEnabled || !attempts.accepts(token)) return@requestGroupInfo
                if (group == null) {
                    finish(null, null)
                    return@requestGroupInfo
                }
                try {
                    wifiP2pManager.requestConnectionInfo(channel) { info -> finish(group, info) }
                } catch (e: Exception) {
                    Log.w(TAG, "P2P connection-info probe failed: ${e.message}")
                    finish(null, null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "P2P group probe failed: ${e.message}")
            finish(null, null)
        }
    }

    private fun completeGroupTransition(
        group: WifiP2pGroup?,
        info: WifiP2pInfo?,
        reason: String,
        attempt: Int,
        token: P2pAttemptController.Token,
    ) {
        if (!isMiracastEnabled || !attempts.accepts(token)) return
        val replayPending = synchronized(groupStateLock) {
            groupTransitionInFlight = false
            val pending = groupTransitionPending
            groupTransitionPending = false
            pending
        }
        if (group != null && info?.groupFormed == true) {
            handleGroupConnected(group, info)
        } else {
            // A start hint may precede framework state. Once the framework has
            // actually observed a group, a missing group is a real loss even
            // though groupStarted remains true for the duration of the session.
            if (synchronized(groupStateLock) { activeGroupIdentity != null }) handleGroupDisconnected()
            scheduleSupplicantGroupRetry(reason, attempt, token)
        }
        if (replayPending && isMiracastEnabled && attempts.accepts(token)) {
            mainHandler.post {
                if (isMiracastEnabled && attempts.accepts(token)) requestConnectionInfo("framework", 0)
            }
        }
    }

    /**
     * A peer-list broadcast is discovery telemetry, not a connection request.
     * Calling connect() here can turn a source's persistent invitation into a
     * stale reinvoke before the framework finishes processing that invitation.
     */
    @SuppressLint("MissingPermission")
    fun onPeersChanged() {
        val token = currentAttemptToken() ?: return
        wifiP2pManager.requestPeers(channel) { peers ->
            if (!isMiracastEnabled || !attempts.accepts(token)) return@requestPeers
            peers.deviceList.forEach { device ->
                Log.d(
                    TAG,
                    "P2P peer ${device.deviceName} ${device.deviceAddress} " +
                        "status=${device.status} wfd=${device.readWfdPeerInfo().enabled}",
                )
            }
            val peer = peers.deviceList.firstOrNull { device ->
                isWfdPeerEligible(
                    status = device.status,
                    wfdEnabled = device.readWfdPeerInfo().isSource,
                    preferredAddressProvided = false,
                    preferredAddressMatches = false,
                )
            }
            peer?.let { onPeerDiscovered(it.deviceAddress) }
        }
    }

    /**
     * Arm the supplicant for an explicit headless connection request.
     *
     * Peer discovery is intentionally not an implicit connect trigger:
     * persistent invitations are already processed by the framework, and a
     * default WifiP2pConfig can make it reinvoke an unrelated saved group.
     * The supplicant monitor handles GO negotiation, while this path remains
     * available for the framework/vendor connection-request broadcasts.
     */
    @Volatile private var lastAutoAcceptAt = 0L
    @Volatile private var lastAutoAcceptAddr: String? = null

    /** Called only on cleanup workers, before closing the existing inspection helper. */
    @SuppressLint("MissingPermission")
    private fun settleAppConnect(owned: AppConnect?): Boolean {
        if (owned == null) return true
        val operation = owned.operation
        val clean = operation.settle(
            inspect = {
                try {
                    owned.inspector.inspectPeer(operation.generation, it).also { state ->
                        Log.i(TAG, "App-channel CONNECT inspection generation=${operation.generation} state=$state")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "App-channel CONNECT inspection failed generation=${operation.generation}", e)
                    throw e
                }
            },
            cancel = {
                val acknowledgement = java.util.concurrent.CompletableFuture<Unit>()
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6)
                mainHandler.post {
                    if (acknowledgement.isDone || System.nanoTime() >= deadline) {
                        acknowledgement.completeExceptionally(
                            java.util.concurrent.TimeoutException("App-channel cancellation dispatch deadline"))
                        return@post
                    }
                    if (operation.wasRejected()) {
                        acknowledgement.complete(Unit)
                    } else try {
                        // CONNECT used this exact app channel, never the helper channel.
                        wifiP2pManager.cancelConnect(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                Log.i(TAG, "App-channel CONNECT cancellation ACK generation=${operation.generation}")
                                DebugEventLog.record(DebugEventCategory.P2P,
                                    "App-channel CONNECT cancellation ACK generation=${operation.generation}")
                                acknowledgement.complete(Unit)
                            }
                            override fun onFailure(reason: Int) {
                                Log.w(TAG, "App-channel cancelConnect failed generation=${operation.generation} reason=$reason")
                                acknowledgement.completeExceptionally(
                                    IllegalStateException("App-channel cancelConnect failed reason=$reason"))
                            }
                        })
                    } catch (e: Exception) { acknowledgement.completeExceptionally(e) }
                }
                try { acknowledgement.get(6, java.util.concurrent.TimeUnit.SECONDS) }
                finally {
                    if (!acknowledgement.isDone) {
                        Log.w(TAG, "App-channel cancelConnect callback timeout generation=${operation.generation}")
                        acknowledgement.completeExceptionally(
                            java.util.concurrent.TimeoutException("App-channel cancelConnect callback timeout"))
                    }
                }
            },
        )
        if (!clean) DebugEventLog.record(DebugEventCategory.ERROR,
            "App-channel owned CONNECT cleanup unconfirmed generation=${operation.generation}")
        return clean
    }

    @SuppressLint("MissingPermission")
    private fun connectTemporaryPeer(address: String, goIntent: Int, result: WifiP2pManager.ActionListener) {
        val token = currentAttemptToken() ?: return
        val bridge = rootBridge ?: return
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                val config = WifiP2pConfig.Builder()
                    .setDeviceAddress(android.net.MacAddress.fromString(address))
                    .enablePersistentMode(false)
                    .build().apply {
                        groupOwnerIntent = goIntent
                        wps.setup = android.net.wifi.WpsInfo.PBC
                    }
                val owned = synchronized(appConnectLock) {
                    if (appConnect?.operation?.canReplace() == false) null
                    else AppConnect(P2pConnectOperation(token.generation, address), bridge)
                        .also { bridge.trackAppConnect(it.operation); appConnect = it }
                }
                if (owned == null) {
                    result.onFailure(WifiP2pManager.BUSY)
                    return
                }
                // Ownership precedes Binder dispatch, including a throwing/lost callback.
                wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (isMiracastEnabled && attempts.accepts(token)) result.onSuccess()
                    }
                    override fun onFailure(reason: Int) {
                        owned.operation.rejected()
                        if (isMiracastEnabled && attempts.accepts(token)) result.onFailure(reason)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Temporary peer request failed", e)
                result.onFailure(WifiP2pManager.ERROR)
            }
            return
        }
        thread(name = "P2P-LegacyConnect") {
            if (rootBridge !== bridge || !isMiracastEnabled || !attempts.accepts(token)) return@thread
            val success = try {
                bridge.connectPeer(token.generation, address, goIntent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Legacy temporary peer request failed", e)
                false
            }
            mainHandler.post {
                if (rootBridge !== bridge || !attempts.accepts(token) || !isMiracastEnabled) return@post
                if (success) result.onSuccess() else result.onFailure(WifiP2pManager.ERROR)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun autoAcceptConnection(preferredAddr: String? = null) {
        val token = currentAttemptToken() ?: return
        // Debounce: don't spam connect() while one is already in flight.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAutoAcceptAt < 4000 && preferredAddr != null && preferredAddr == lastAutoAcceptAddr) return
        wifiP2pManager.requestPeers(channel) { peers ->
            if (!isMiracastEnabled || !attempts.accepts(token)) return@requestPeers
            val peer = peers.deviceList.firstOrNull { device ->
                val preferredMatch = preferredAddr != null &&
                    device.deviceAddress.equals(preferredAddr, ignoreCase = true)
                isWfdPeerEligible(
                    status = device.status,
                    wfdEnabled = device.readWfdPeerInfo().isSource,
                    preferredAddressProvided = preferredAddr != null,
                    preferredAddressMatches = preferredMatch,
                )
            }
            if (peer == null) {
                Log.w(TAG, "autoAccept: no eligible WFD peer found to accept")
                return@requestPeers
            }
            lastAutoAcceptAt = now
            lastAutoAcceptAddr = peer.deviceAddress
            val goIntent = try { foxlost.miracast.sink.SettingsManager.goIntentClient } catch (_: Exception) { GO_INTENT_CLIENT }
            Log.i(
                TAG,
                "autoAccept: connecting to ${peer.deviceName} (${peer.deviceAddress}) " +
                    "status=${peer.status} netId=$NETWORK_ID_TEMPORARY"
            )
            connectTemporaryPeer(peer.deviceAddress, goIntent, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.i(TAG, "autoAccept: connect() SUCCESS — supplicant armed for GO-neg") }
                override fun onFailure(reason: Int) { Log.e(TAG, "autoAccept: connect() FAILED reason=$reason") }
            })
        }
    }

    /**
     * Discovery alone must not call connect(): the source may have already
     * sent a persistent invitation, which the framework can finish without
     * an application-issued reinvoke.
     */
    fun onPeerDiscovered(deviceAddress: String) {
        if (!isMiracastEnabled) return
        Log.d(TAG, "Peer discovered: $deviceAddress — waiting for connection event")
    }
}
