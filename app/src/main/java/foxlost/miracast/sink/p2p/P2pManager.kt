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
    private var isMiracastEnabled = false
    @Volatile private var groupStarted = false
    private var discoveryRunnable: Runnable? = null
    private var listenRunnable: Runnable? = null
    private var keepAliveRunnable: Runnable? = null
    @Volatile private var extListenThread: Thread? = null
    private val attempts = P2pAttemptController()
    private var sessionGeneration = 0L
    @Volatile private var activeAttempt: P2pAttemptController.Token? = null
    private var cleanupBarrierRunnable: Runnable? = null
    private var invitationStallRunnable: Runnable? = null
    @Volatile private var monitorRunId = 0L
    private var frameworkListenActive = false
    private var wfdSetupState = WfdSetupState.Idle
    private enum class WfdSetupState { Idle, FrameworkPending, FallbackPending, Ready, Failed }
    private var cleanupBarrierToken: P2pAttemptController.Token? = null
    private var frameworkListenFallbackStarted = false
    private var p2pModeStarted = false
    private var readinessRunnable: Runnable? = null
    private var readinessProbeInFlight = false
    private var readinessGroup: WifiP2pGroup? = null
    private var readinessInfo: WifiP2pInfo? = null
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
        val persistent: Boolean?,
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
        const val NETWORK_ID_TEMPORARY = WifiP2pGroup.NETWORK_ID_TEMPORARY
        // Android's WFD device-info bitfield for a primary, available sink
        // with no content-protection or service-discovery extensions.
        const val WFD_DEVICE_INFO = 0x0011
        const val GROUP_READINESS_TIMEOUT_MS = 6_000L
        const val GROUP_READINESS_POLL_MS = 250L

        const val CLEANUP_BARRIER_TIMEOUT_MS = 2_000L
        const val CLEANUP_BARRIER_POLL_MS = 100L
        const val INVITATION_FALLBACK_STALL_MS = 6_000L
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
        fun onDiscoveryStateChanged(active: Boolean)
    }



    @SuppressLint("MissingPermission")
    fun startSink(generation: Long = 0L) {
        Log.d(TAG, "Starting Miracast Sink generation=$generation...")
        DebugEventLog.record(DebugEventCategory.P2P, "P2P sink start generation=$generation")
        sessionGeneration = generation
        wfdSetupState = WfdSetupState.Idle
        p2pModeStarted = false
        groupStarted = false
        isMiracastEnabled = true
        val token = attempts.start(generation)
        activeAttempt = token
        Log.i(TAG, "Starting P2P attempt ${token.number}/3 id=${token.attemptId}")
        foxlost.miracast.sink.SettingsManager.init(context)

        try {
            android.provider.Settings.Global.putInt(context.contentResolver, "wifi_display_on", 0)
            Log.d(TAG, "Disabled framework WifiDisplayController")
        } catch (e: Exception) {
            Log.d(TAG, "Failed to disable WifiDisplayController: ${e.message}")
        }

        // Disconnect Wi-Fi STA to eliminate single-radio channel conflict.
        // When STA is on ch5 and P2P listens on ch11, the radio time-shares
        // between channels, causing extended-listen windows to be skipped
        // and GO-neg frames to arrive during IDLE gaps. Disconnecting gives
        // P2P the full radio on the listen channel.
        val disconnectWifi = try { foxlost.miracast.sink.SettingsManager.disconnectWifiInSinkMode } catch (e: Exception) { true }
        if (disconnectWifi) {
            disconnectWifiSta()
        }
        setMiracastModeSink()
        chainWfdInfo()
    }
    @SuppressLint("MissingPermission")
    fun stopSink() {
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
        readinessGroup = null
        readinessInfo = null
        stopDiscoveryLoop()
        cancelExtendedListen()
        listenRunnable?.let { mainHandler.removeCallbacks(it) }
        listenRunnable = null
        keepAliveRunnable?.let { mainHandler.removeCallbacks(it) }
        keepAliveRunnable = null
        stopSupplicantMonitor()
        stopListeningFramework()
        try { wpaCli("P2P_EXT_LISTEN 0 0") } catch (_: Exception) {}
        activeAttempt = null
        synchronized(groupStateLock) {
            activeGroupIdentity = null
            groupTransitionInFlight = false
            groupTransitionPending = false
            pendingInvitation = null
        }
        try {
            wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d(TAG, "removeGroup SUCCESS") }
                override fun onFailure(reason: Int) { Log.d(TAG, "removeGroup ignored reason=$reason") }
            })
        } catch (e: Exception) {
            Log.d(TAG, "removeGroup unavailable: ${e.message}")
        }
        reconnectWifiSta()
    }
    fun reportAttemptFailure(attemptId: Long, reason: String) {
        mainHandler.post {
            val token = activeAttempt?.takeIf { it.attemptId == attemptId } ?: return@post
            failAttempt(token, reason)
        }
    }

    private fun failAttempt(token: P2pAttemptController.Token, reason: String) {
        if (!attempts.accepts(token)) return
        val decision = attempts.fail(token)
        DebugEventLog.record(DebugEventCategory.ERROR, "P2P attempt ${token.number}/3 failed: $reason")
        activeAttempt = null
        listener.onP2pAttemptFailed(token.attemptId, reason)
        cleanupAttempt(token) { barrierOk ->
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
        readinessGroup = null
        readinessInfo = null
        synchronized(groupStateLock) {
            activeGroupIdentity = null
            groupTransitionInFlight = false
            groupTransitionPending = false
            pendingInvitation = null
        }
        Log.i(TAG, "Starting P2P attempt ${token.number}/3 id=${token.attemptId}")
        enterListenMode()
    }
    private fun cleanupAttempt(
        token: P2pAttemptController.Token,
        onComplete: (Boolean) -> Unit,
    ) {
        cleanupBarrierToken = token
        readinessRunnable?.let { mainHandler.removeCallbacks(it) }
        readinessRunnable = null
        readinessProbeInFlight = false
        readinessGroup = null
        readinessInfo = null
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
        try { wpaCli("P2P_EXT_LISTEN 0 0") } catch (_: Exception) {}
        synchronized(groupStateLock) {
            activeGroupIdentity = null
            groupTransitionInFlight = false
            groupTransitionPending = false
            pendingInvitation = null
        }
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
        startedAt: Long = System.currentTimeMillis(),
    ) {
        val deadline = startedAt + CLEANUP_BARRIER_TIMEOUT_MS
        cleanupBarrierRunnable?.let { mainHandler.removeCallbacks(it) }
        cleanupBarrierRunnable = object : Runnable {
            override fun run() {
                if (cleanupBarrierToken != token || cleanupBarrierRunnable == null || !isMiracastEnabled) {
                    return
                }
                try {
                    wifiP2pManager.requestGroupInfo(channel) { group ->
                        if (cleanupBarrierToken != token || cleanupBarrierRunnable == null || !isMiracastEnabled) {
                            return@requestGroupInfo
                        }
                        if (group == null) {
                            cleanupBarrierRunnable = null
                            cleanupBarrierToken = null
                            onComplete(true)
                        } else if (System.currentTimeMillis() >= deadline) {
                            cleanupBarrierRunnable = null
                            cleanupBarrierToken = null
                            onComplete(false)
                        } else {
                            mainHandler.postDelayed(this, CLEANUP_BARRIER_POLL_MS)
                        }
                    }
                } catch (_: Exception) {
                    if (System.currentTimeMillis() >= deadline) {
                        cleanupBarrierRunnable = null
                        cleanupBarrierToken = null
                        onComplete(false)
                    } else {
                        mainHandler.postDelayed(this, CLEANUP_BARRIER_POLL_MS)
                    }
                }
            }
        }
        mainHandler.post(cleanupBarrierRunnable!!)
    }

    /**
     * Disconnect Wi-Fi STA to give P2P the full radio (no channel conflict).
     * Uses WifiManager.disconnect() which is available to system apps.
     *
     * CRITICAL: a one-shot disconnect() is NOT enough — the framework's
     * auto-connect immediately re-associates to the saved AP (observed:
     * "Associated with b4:fb:e4:91:f2:b0 ... CTRL-EVENT-CONNECTED" right
     * after we entered listen mode). The STA then sits on ch13 (2472) while
     * P2P listens on ch1 (2412); the single radio time-shares and the
     * Provision Discovery Response action frame is dropped (TX ack=0), so
     * Windows never gets our reply and never sends GO-neg.
     *
     * Fix: disconnect, then disable every saved network AND turn off the
     * framework's auto-connect so it cannot re-associate. We re-enable on
     * stopSink().
     */
    private val disabledNetworkIds = mutableListOf<Int>()

    @SuppressLint("MissingPermission")
    private fun disconnectWifiSta() {
        try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            // Disable all saved networks + auto-connect so the radio stays free.
            try {
                @Suppress("DEPRECATION")
                for (conf in wifiManager.configuredNetworks ?: emptyList()) {
                    @Suppress("DEPRECATION")
                    if (wifiManager.disableNetwork(conf.networkId)) {
                        disabledNetworkIds.add(conf.networkId)
                    }
                }
                if (disabledNetworkIds.isNotEmpty()) {
                    Log.d(TAG, "Disabled ${disabledNetworkIds.size} saved network(s) to prevent STA reconnect")
                }
            } catch (e: Exception) {
                Log.d(TAG, "disableNetwork not available: ${e.message}")
            }
            wifiManager.disconnect()
            Log.d(TAG, "Disconnected Wi-Fi STA — P2P has full radio")
        } catch (e: Exception) {
            Log.d(TAG, "Failed to disconnect Wi-Fi: ${e.message}")
        }
    }

    /** Re-enable networks we disabled on start (called from stopSink). */
    @SuppressLint("MissingPermission")
    private fun reconnectWifiSta() {
        if (disabledNetworkIds.isEmpty()) return
        try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            var restoredAll = true
            @Suppress("DEPRECATION")
            for (id in disabledNetworkIds) {
                @Suppress("DEPRECATION")
                if (!wifiManager.enableNetwork(id, false)) restoredAll = false
            }
            @Suppress("DEPRECATION")
            if (!wifiManager.reconnect()) restoredAll = false
            if (restoredAll) {
                Log.d(TAG, "Re-enabled ${disabledNetworkIds.size} saved network(s)")
                disabledNetworkIds.clear()
            } else {
                Log.w(TAG, "STA restore incomplete; retaining disabled network IDs for retry")
            }
        } catch (e: Exception) {
            Log.w(TAG, "reconnect failed; retaining disabled network IDs: ${e.message}")
        }
    }

    private fun setMiracastModeSink() {
        try {
            val m = WifiP2pManager::class.java.getMethod(
                "setMiracastMode", WifiP2pManager.Channel::class.java, Int::class.javaPrimitiveType,
            )
            m.invoke(wifiP2pManager, channel, 2)
            Log.d(TAG, "setMiracastMode(2) — MIRACAST_SINK active")
        } catch (e: Exception) {
            try {
                val m2 = WifiP2pManager::class.java.getMethod("setMiracastMode", Int::class.javaPrimitiveType)
                m2.invoke(wifiP2pManager, 2)
                Log.d(TAG, "setMiracastMode(2) (single-arg) — MIRACAST_SINK active")
            } catch (e2: Exception) {
                Log.d(TAG, "setMiracastMode not available: ${e2.message}")
            }
        }
    }

    private fun chainWfdInfo() {
        synchronized(groupStateLock) {
            if (wfdSetupState != WfdSetupState.Idle) return
            wfdSetupState = WfdSetupState.FrameworkPending
        }
        try {
            val wfdInfoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val ctor = wfdInfoClass.getDeclaredConstructor(
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            val maxTput = try { foxlost.miracast.sink.SettingsManager.maxThroughput } catch (_: Exception) { 50 }
            val wfdInfo = ctor.newInstance(
                WFD_DEVICE_INFO,
                foxlost.miracast.sink.SettingsManager.effectiveRtspPort(),
                maxTput,
            )
            val method = try {
                WifiP2pManager::class.java.getMethod(
                    "setWfdInfo",
                    WifiP2pManager.Channel::class.java,
                    wfdInfoClass,
                    WifiP2pManager.ActionListener::class.java,
                )
            } catch (_: Exception) {
                WifiP2pManager::class.java.getMethod(
                    "setWFDInfo",
                    WifiP2pManager.Channel::class.java,
                    wfdInfoClass,
                    WifiP2pManager.ActionListener::class.java,
                )
            }
            method.invoke(wifiP2pManager, channel, wfdInfo, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "setWfdInfo SUCCESS!")
                    DebugEventLog.record(DebugEventCategory.WFD, "WFD framework capabilities ready")
                    markWfdSetup(WfdSetupState.Ready)
                }

                override fun onFailure(reason: Int) {
                    DebugEventLog.record(DebugEventCategory.ERROR, "WFD framework setup failed reason=$reason")
                    Log.e(TAG, "setWfdInfo FAILED reason=$reason; trying supplicant fallback")
                    startWfdSupplicantFallback("framework failure=$reason")
                }
            })
        } catch (e: Exception) {
            DebugEventLog.record(DebugEventCategory.ERROR, "WFD framework setup exception")
            val err = if (e is java.lang.reflect.InvocationTargetException) e.targetException?.toString() else e.toString()
            Log.e(TAG, "WFD framework setup failed: $err; trying supplicant fallback")
            startWfdSupplicantFallback("framework exception")
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
        thread(name = "P2P-WfdFallback") {
            val ok = wpaCli("WFD_SUBELEM_SET 00111C440032")
            mainHandler.post {
                synchronized(groupStateLock) {
                    wfdSetupState = if (ok) WfdSetupState.Ready else WfdSetupState.Failed
                }
                if (ok) {
                    Log.i(TAG, "Root WFD fallback applied")
                    DebugEventLog.record(DebugEventCategory.WFD, "WFD supplicant fallback applied")
                } else {
                    Log.w(TAG, "Root WFD fallback unavailable; continuing framework P2P setup")
                    DebugEventLog.record(DebugEventCategory.ERROR, "WFD supplicant fallback unavailable")
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
        if (!isMiracastEnabled || p2pModeStarted) return
        p2pModeStarted = true
        val forceGo = try { foxlost.miracast.sink.SettingsManager.forceAutonomousGo } catch (_: Exception) { false }
        if (forceGo) {
            Log.d(TAG, "forceAutonomousGo enabled — becoming Group Owner")
            startAutonomousGo()
        } else {
            enterListenMode()
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(device: WifiP2pDevice, goIntent: Int = GO_INTENT_CLIENT, onResult: ((Boolean) -> Unit)? = null) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = goIntent
            wps.setup = android.net.wifi.WpsInfo.PBC
        }
        Log.d(TAG, "Connecting to ${device.deviceName} (${device.deviceAddress}) goIntent=$goIntent")
        wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
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
    // Discoverability — continuous listen (the GO-negotiation fix)
    //
    // The framework's startListening() runs wpa_supplicant Extended Listen
    // Timing at 500/500 ms. After Provision Discovery, the supplicant drops
    // to IDLE and the next extended-listen window fires up to 500 ms later.
    // Windows sends its GO Negotiation Request ~28 ms after PD — inside that
    // IDLE gap — and the supplicant rejects it with Status 1 ("not ready"),
    // deadlocking the exchange.
    //
    // FIX: The Xposed hook (XposedHook.hookExtListen) intercepts
    // WifiP2pNative.p2pExtListen() and forces a 10/10 ms interval so the
    // supplicant re-enters LISTEN within 10 ms of PD completion. Combined
    // with Wi-Fi STA disconnect (no channel conflict), the radio is always
    // on the P2P listen channel and the 10 ms windows are never skipped.
    //
    // We no longer fight extended listen from the socket layer — the Xposed
    // hook is the single source of truth for extended-listen timing.
    // ------------------------------------------------------------------

    /**
     * Optional vendor supplicant fallback. Framework WFD/P2P APIs are primary;
     * these paths are used only when a real P2P interface is present and the
     * device exposes a matching control socket.
     */
    private fun vendorP2pInterfaces(): List<String> {
        val sysfsNames = try {
            java.io.File("/sys/class/net").list()?.toList()
        } catch (_: Exception) {
            null
        }
        val procNetDev = try {
            java.io.File("/proc/net/dev").readText()
        } catch (_: Exception) {
            null
        }
        return p2pInterfaceCandidates(sysfsNames, procNetDev)
    }

    private fun vendorP2pControlSocket(): Pair<String, String>? {
        for (iface in vendorP2pInterfaces()) {
            val socket = wpaSocketCandidates(iface).firstOrNull { java.io.File(it).exists() }
            if (socket != null) return iface to socket
        }
        return null
    }

    private fun wpaSocketCandidates(iface: String): List<String> = listOf(
        "/data/vendor/wifi/wpa/sockets/$iface",
        "/data/misc/wifi/sockets/$iface",
        "/data/vendor/wifi/wpa/sockets/wlan0",
        "/data/misc/wifi/sockets/wlan0",
        "/dev/socket/wpa_wlan0",
    )

    /** Run the optional vendor writer against detected interfaces. */
    private fun wpaCli(vararg cmds: String): Boolean {
        val ifaces = vendorP2pInterfaces()
        if (ifaces.isEmpty()) {
            Log.d(TAG, "wpaCli: no P2P interface; framework path remains active")
            return false
        }
        val apkPath = context.packageCodePath
        for (iface in ifaces) {
            for (sock in wpaSocketCandidates(iface)) {
                val dedicated = sock.endsWith("/$iface")
                for (raw in cmds) {
                    val cmd = if (!dedicated && !raw.startsWith("IFNAME=")) "IFNAME=$iface $raw" else raw
                    try {
                        val p = Runtime.getRuntime().exec(arrayOf(
                            "su", "-c",
                            "CLASSPATH=$apkPath app_process / foxlost.miracast.sink.p2p.SupplicantWriter $sock '$cmd' 2>&1"
                        ))
                        p.waitFor()
                        val out = p.inputStream.bufferedReader().readText().trim()
                        if (out.contains("OK") || out.contains("SENT")) {
                            Log.d(TAG, "wpaCli[$sock] $cmd → ok")
                            return true
                        }
                        Log.d(TAG, "wpaCli[$sock] → ${out.take(60)}")
                    } catch (e: Exception) {
                        Log.d(TAG, "wpaCli[$sock] ex: ${e.message}")
                    }
                }
            }
        }
        Log.w(TAG, "wpaCli: no reachable control socket for ${ifaces.joinToString()}")
        return false
    }

    // ------------------------------------------------------------------
    // Supplicant event monitor — optional vendor fallback only.
    // ------------------------------------------------------------------

    private var monitorProcess: Process? = null
    private var monitorThread: Thread? = null
    private val goNegArmTimes = mutableMapOf<String, Long>()

    private fun startSupplicantMonitor() {
        if (monitorThread?.isAlive == true) return
        val runId = monitorRunId + 1L
        monitorRunId = runId
        val apkPath = context.packageCodePath
        monitorThread = thread(name = "P2P-EventMonitor") {
            while (isMiracastEnabled && !groupStarted && monitorRunId == runId) {
                val control = vendorP2pControlSocket()
                if (control == null) {
                    Log.d(TAG, "No usable vendor supplicant socket; framework path remains active")
                    try { Thread.sleep(2000L) } catch (_: InterruptedException) { break }
                    continue
                }
                val (iface, sock) = control
                try {
                    val p = Runtime.getRuntime().exec(arrayOf(
                        "su", "-c",
                        "CLASSPATH=$apkPath app_process / foxlost.miracast.sink.p2p.SupplicantEventMonitor $sock",
                    ))
                    monitorProcess = p
                    Log.i(TAG, "Supplicant event monitor attached to $iface run=$runId")
                    p.inputStream.bufferedReader().forEachLine { line ->
                        if (monitorRunId == runId && isMiracastEnabled && !groupStarted) {
                            handleSupplicantEvent(line)
                        }
                    }
                    p.waitFor()
                } catch (e: Exception) {
                    if (monitorRunId == runId) Log.w(TAG, "monitor error: ${e.message}")
                }
                if (monitorRunId == runId) monitorProcess = null
                if (!isMiracastEnabled || groupStarted || monitorRunId != runId) break
                try { Thread.sleep(2000L) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun stopSupplicantMonitor() {
        monitorRunId += 1L
        val process = monitorProcess
        val thread = monitorThread
        monitorProcess = null
        monitorThread = null
        try { process?.destroy() } catch (_: Exception) {}
        thread?.interrupt()
        if (thread != null && thread !== Thread.currentThread()) {
            try { thread.join(500L) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
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
        Regex("""(?:^|\s)$key='([^']*)'""").find(line)?.groupValues?.getOrNull(1)?.let { return it }
        return Regex("""(?:^|\s)$key=([^\s]+)""").find(line)?.groupValues?.getOrNull(1)
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
        val persistent = eventValue(line, "persistent")?.let { it == "1" || it.equals("true", true) }
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
                    persistent = eventValue(line, "persistent")?.let { value ->
                        value == "1" || value.equals("true", true)
                    },
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
                        wfdEnabled = device.wfdInfo?.isEnabled == true,
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
                val config = WifiP2pConfig().apply {
                    deviceAddress = peer.deviceAddress
                    groupOwnerIntent = goIntent
                    wps.setup = android.net.wifi.WpsInfo.PBC
                }
                if (!setTemporaryNetworkId(config, "invitationFallback")) {
                    currentAttemptToken()?.let { failAttempt(it, "invitation fallback network setup failed") }
                    return@requestPeers
                }
                Log.i(
                    TAG,
                    "Invitation fallback: fresh WFD connect to ${peer.deviceName} (${peer.deviceAddress}) " +
                        "goIntent=$goIntent netId=$NETWORK_ID_TEMPORARY wps=PBC attempt=${target.attemptId}",
                )
                val token = currentAttemptToken()
                wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
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
            mainHandler.post { requestConnectionInfo("supplicant-group-started", 0) }
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
                thread { wpaCli("P2P_CONNECT $addr pbc go_intent=0") }
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
        try {
            wpaCli("P2P_EXT_LISTEN 0 0")
        } catch (e: Exception) {
            // Missing root/socket support is expected on non-system builds.
            Log.d(TAG, "Unable to disable vendor Extended Listen: ${e.message}")
        }
        listener.onDiscoveryStateChanged(false)
        Log.i(TAG, "Paused discovery, LISTEN, and Extended Listen after GROUP-STARTED")
    }

    private fun cancelExtendedListen() {
        extListenThread?.interrupt()
        extListenThread = null
    }

    private fun enterListenMode() {
        val useListen = try { foxlost.miracast.sink.SettingsManager.usePersistentListen } catch (e: Exception) { true }
        if (useListen) {
            // Framework startListening() drives proper probe responses + WFD IE
            // advertisement so Windows can FIND us. Extended-listen timing is
            // left at the framework default unless SettingsManager.extListenMs
            // is set — the supplicant event monitor arms GO-neg on demand,
            frameworkListenFallbackStarted = false
            if (startListeningFramework()) {
                Log.i(TAG, "Framework LISTEN active")
                listener.onDiscoveryStateChanged(true)
            } else {
                Log.w(TAG, "startListening unavailable — falling back to discoverPeers loop")
                startDiscoveryLoop()
                return
            }
            // Extended-listen override. Default OFF: the supplicant event
            // monitor (below) arms GO-neg the instant a request arrives, which
            // is the real Status-1 fix. Leave the framework's native timing
            // alone unless the user explicitly enables the override.
            val extListenMs = try { foxlost.miracast.sink.SettingsManager.extListenMs } catch (e: Exception) { 0 }
            if (extListenMs > 0) {
                extListenThread = thread(name = "P2P-ExtListen-Once") {
                    try {
                        Thread.sleep(500)  // let startListening() settle first
                        if (isMiracastEnabled && !groupStarted) {
                            wpaCli("P2P_EXT_LISTEN $extListenMs $extListenMs")
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        extListenThread = null
                    }
                }
                listenRunnable = object : Runnable {
                    override fun run() {
                        if (!isMiracastEnabled || groupStarted) return
                        thread { wpaCli("P2P_EXT_LISTEN $extListenMs $extListenMs") }
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

    /**
     * Framework-native listen-only mode. Returns true if the call was accepted.
     */
    private fun fallbackToDiscoveryAfterListenFailure(reason: Int) {
        if (!isMiracastEnabled || groupStarted || frameworkListenFallbackStarted) return
        frameworkListenFallbackStarted = true
        Log.w(TAG, "Framework LISTEN failed asynchronously reason=$reason — falling back to discovery")
        cancelExtendedListen()
        keepAliveRunnable?.let { mainHandler.removeCallbacks(it) }
        keepAliveRunnable = null
        listenRunnable?.let { mainHandler.removeCallbacks(it) }
        listenRunnable = null
        stopSupplicantMonitor()
        startDiscoveryLoop()
    }

    @SuppressLint("MissingPermission")
    private fun startListeningFramework(): Boolean {
        return try {
            val method = WifiP2pManager::class.java.getMethod(
                "startListening",
                WifiP2pManager.Channel::class.java,
                WifiP2pManager.ActionListener::class.java,
            )
            method.invoke(wifiP2pManager, channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    frameworkListenActive = true
                    Log.d(TAG, "startListening onSuccess")
                }
                override fun onFailure(reason: Int) {
                    frameworkListenActive = false
                    Log.w(TAG, "startListening onFailure reason=$reason")
                    mainHandler.post { fallbackToDiscoveryAfterListenFailure(reason) }
                }
            })
            true
        } catch (e: Exception) {
            Log.w(TAG, "startListening reflection failed: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopListeningFramework(): Boolean {
        return try {
            val method = WifiP2pManager::class.java.getMethod(
                "stopListening",
                WifiP2pManager.Channel::class.java,
                WifiP2pManager.ActionListener::class.java,
            )
            method.invoke(wifiP2pManager, channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d(TAG, "stopListening onSuccess") }
                override fun onFailure(reason: Int) { Log.w(TAG, "stopListening onFailure reason=$reason") }
            })
            true
        } catch (e: Exception) {
            Log.w(TAG, "stopListening reflection failed: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscoveryLoop() {
        stopDiscoveryLoop()
        if (!isMiracastEnabled) return
        discoveryRunnable = object : Runnable {
            override fun run() {
                if (!isMiracastEnabled) return
                wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { listener.onDiscoveryStateChanged(true) }
                    override fun onFailure(reason: Int) { listener.onDiscoveryStateChanged(false) }
                })
                mainHandler.postDelayed(this, 10000)
            }
        }
        mainHandler.post(discoveryRunnable!!)
    }

    private fun stopDiscoveryLoop() {
        discoveryRunnable?.let { mainHandler.removeCallbacks(it) }
        discoveryRunnable = null
        @SuppressLint("MissingPermission")
        wifiP2pManager.stopPeerDiscovery(channel, null)
    }

    private fun groupIdentity(group: WifiP2pGroup): String {
        val owner = group.owner?.deviceAddress
            ?: group.clientList.firstOrNull()?.deviceAddress
            ?: "unknown"
        return listOf(group.`interface`, group.networkName, owner)
            .joinToString("|") { it ?: "" }
    }

    private fun currentAttemptToken(): P2pAttemptController.Token? = activeAttempt

    private fun interfaceReadiness(group: WifiP2pGroup): Pair<Boolean, Boolean> {
        val iface = group.`interface`?.takeIf { it.isNotBlank() } ?: return false to false
        val networkInterface = try {
            java.net.NetworkInterface.getByName(iface)
        } catch (_: Exception) {
            null
        } ?: return false to false
        val up = networkInterface.isUp && !networkInterface.isLoopback
        val ipv4 = networkInterface.inetAddresses.toList().any {
            it is java.net.Inet4Address && !it.isLoopbackAddress
        }
        return up to ipv4
    }

    @SuppressLint("MissingPermission")
    private fun beginGroupReadiness(group: WifiP2pGroup, info: WifiP2pInfo, token: P2pAttemptController.Token) {
        readinessGroup = group
        readinessInfo = info
        readinessProbeInFlight = false
        val startedAt = System.currentTimeMillis()
        readinessRunnable?.let { mainHandler.removeCallbacks(it) }
        val poll = object : Runnable {
            override fun run() {
                if (!isMiracastEnabled || !attempts.accepts(token) || readinessRunnable !== this) return
                if (readinessProbeInFlight) return
                readinessProbeInFlight = true
                try {
                    wifiP2pManager.requestGroupInfo(channel) { freshGroup ->
                        if (!isMiracastEnabled || !attempts.accepts(token) || readinessRunnable !== this) {
                            readinessProbeInFlight = false
                            return@requestGroupInfo
                        }
                        readinessGroup = freshGroup
                        if (freshGroup == null) {
                            readinessInfo = null
                            readinessProbeInFlight = false
                            evaluateGroupReadiness(token, startedAt)
                            return@requestGroupInfo
                        }
                        try {
                            wifiP2pManager.requestConnectionInfo(channel) { freshInfo ->
                                readinessProbeInFlight = false
                                if (!isMiracastEnabled || !attempts.accepts(token) || readinessRunnable !== this) {
                                    return@requestConnectionInfo
                                }
                                readinessInfo = freshInfo
                                evaluateGroupReadiness(token, startedAt)
                            }
                        } catch (_: Exception) {
                            readinessProbeInFlight = false
                            evaluateGroupReadiness(token, startedAt)
                        }
                    }
                } catch (_: Exception) {
                    readinessProbeInFlight = false
                    evaluateGroupReadiness(token, startedAt)
                }
            }
        }
        readinessRunnable = poll
        mainHandler.post(poll)
    }

    private fun evaluateGroupReadiness(
        token: P2pAttemptController.Token,
        startedAt: Long,
    ) {
        if (!isMiracastEnabled || !attempts.accepts(token)) return
        val currentGroup = readinessGroup
        val currentInfo = readinessInfo
        val peerPresent = currentGroup?.owner != null || currentGroup?.clientList?.isNotEmpty() == true
        val (interfaceUp, ipv4Present) = currentGroup?.let(::interfaceReadiness)
            ?: (false to false)
        when (GroupReadinessGate().evaluate(
            elapsedMs = System.currentTimeMillis() - startedAt,
            groupFormed = currentInfo?.groupFormed == true,
            peerPresent = peerPresent,
            interfaceUp = interfaceUp,
            ipv4Present = ipv4Present,
        )) {
            GroupReadinessGate.Result.Ready -> {
                readinessRunnable = null
                readinessProbeInFlight = false
                Log.i(
                    TAG,
                    "P2P network ready attempt=${token.attemptId} " +
                        "iface=${currentGroup?.`interface` ?: "unknown"} " +
                        "go=${currentInfo?.groupOwnerAddress?.hostAddress ?: "local"}",
                )
                DebugEventLog.record(DebugEventCategory.DHCP, "P2P network ready; group interface has IPv4")
                listener.onP2pGroupConnected(currentGroup!!, currentInfo!!, token.attemptId)
            }
            GroupReadinessGate.Result.TimedOut -> {
                readinessRunnable = null
                readinessProbeInFlight = false
                failAttempt(token, "P2P group/network readiness timeout")
            }
            GroupReadinessGate.Result.Waiting -> {
                readinessRunnable?.let {
                    mainHandler.postDelayed(it, GROUP_READINESS_POLL_MS)
                }
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
            beginGroupReadiness(group, info, token)
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
        readinessGroup = null
        readinessInfo = null
        val hadGroupStarted = groupStarted
        groupStarted = false
        if (wasConnected || hadGroupStarted) {
            listener.onP2pGroupDisconnected(token.attemptId)
            failAttempt(token, "P2P group disconnected")
        }
    }

    private fun scheduleSupplicantGroupRetry(reason: String, attempt: Int) {
        if (reason == "supplicant-group-started" && attempt < 3) {
            mainHandler.postDelayed({
                requestConnectionInfo(reason, attempt + 1)
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

        try {
            wifiP2pManager.requestGroupInfo(channel) { group ->
                if (group == null) {
                    completeGroupTransition(null, null, reason, attempt, token)
                    return@requestGroupInfo
                }
                wifiP2pManager.requestConnectionInfo(channel) { info ->
                    completeGroupTransition(group, info, reason, attempt, token)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "P2P group probe failed: ${e.message}")
            completeGroupTransition(null, null, reason, attempt, token)
        }
    }

    private fun completeGroupTransition(
        group: WifiP2pGroup?,
        info: WifiP2pInfo?,
        reason: String,
        attempt: Int,
        token: P2pAttemptController.Token,
    ) {
        if (!attempts.accepts(token)) return
        val replayPending = synchronized(groupStateLock) {
            groupTransitionInFlight = false
            val pending = groupTransitionPending
            groupTransitionPending = false
            pending
        }
        if (group != null && info?.groupFormed == true) {
            handleGroupConnected(group, info)
        } else if (groupStarted) {
            scheduleSupplicantGroupRetry(reason, attempt)
        } else {
            handleGroupDisconnected()
            scheduleSupplicantGroupRetry(reason, attempt)
        }
        if (replayPending && isMiracastEnabled && attempts.accepts(token)) {
            mainHandler.post { requestConnectionInfo("framework", 0) }
        }
    }

    /**
     * A peer-list broadcast is discovery telemetry, not a connection request.
     * Calling connect() here can turn a source's persistent invitation into a
     * stale reinvoke before the framework finishes processing that invitation.
     */
    @SuppressLint("MissingPermission")
    fun onPeersChanged() {
        if (!isMiracastEnabled) return
        wifiP2pManager.requestPeers(channel) { peers ->
            peers.deviceList.forEach { device ->
                Log.d(
                    TAG,
                    "P2P peer ${device.deviceName} ${device.deviceAddress} " +
                        "status=${device.status} wfd=${device.wfdInfo?.isEnabled == true}",
                )
            }
            val peer = peers.deviceList.firstOrNull { device ->
                isWfdPeerEligible(
                    status = device.status,
                    wfdEnabled = device.wfdInfo?.isEnabled == true,
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

    private fun setTemporaryNetworkId(config: WifiP2pConfig, source: String = "autoAccept"): Boolean {
        return try {
            WifiP2pConfig::class.java.getDeclaredField("netId").apply {
                isAccessible = true
                setInt(config, NETWORK_ID_TEMPORARY)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "$source: cannot force temporary P2P network; refusing stale reinvoke", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun autoAcceptConnection(preferredAddr: String? = null) {
        // Debounce: don't spam connect() while one is already in flight.
        val now = System.currentTimeMillis()
        if (now - lastAutoAcceptAt < 4000 && preferredAddr != null && preferredAddr == lastAutoAcceptAddr) return
        wifiP2pManager.requestPeers(channel) { peers ->
            val peer = peers.deviceList.firstOrNull { device ->
                val preferredMatch = preferredAddr != null &&
                    device.deviceAddress.equals(preferredAddr, ignoreCase = true)
                isWfdPeerEligible(
                    status = device.status,
                    wfdEnabled = device.wfdInfo?.isEnabled == true,
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
            val config = WifiP2pConfig().apply {
                deviceAddress = peer.deviceAddress
                groupOwnerIntent = try { foxlost.miracast.sink.SettingsManager.goIntentClient } catch (e: Exception) { GO_INTENT_CLIENT }
                wps.setup = android.net.wifi.WpsInfo.PBC
            }
            if (!setTemporaryNetworkId(config)) return@requestPeers
            Log.i(
                TAG,
                "autoAccept: connecting to ${peer.deviceName} (${peer.deviceAddress}) " +
                    "status=${peer.status} netId=$NETWORK_ID_TEMPORARY"
            )
            wifiP2pManager.connect(channel, config, object : WifiP2pManager.ActionListener {
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
