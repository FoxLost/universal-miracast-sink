package foxlost.miracast.sink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import foxlost.miracast.sink.p2p.P2pManager
import foxlost.miracast.sink.p2p.P2pReceiver
import foxlost.miracast.sink.p2p.readWfdPeerInfo
import foxlost.miracast.sink.p2p.remoteWfdSource
import foxlost.miracast.sink.media.RtpEndpointRegistry
import foxlost.miracast.sink.rtsp.LocalRtpEndpoint
import foxlost.miracast.sink.rtsp.NegotiatedTransport
import foxlost.miracast.sink.rtsp.RtspConnection
import foxlost.miracast.sink.rtsp.RtspSessionCallbacks
import foxlost.miracast.sink.uibc.TcpUibcSession
import foxlost.miracast.sink.uibc.UibcEndpoint
import foxlost.miracast.sink.uibc.UibcSession
import foxlost.miracast.sink.uibc.UibcSessionRegistry
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun postPlayerLaunch(
    post: (Runnable) -> Boolean,
    launch: () -> Unit,
    onFailure: (Exception) -> Unit,
): Boolean {
    val runnable = Runnable {
        try {
            launch()
        } catch (e: Exception) {
            onFailure(e)
        }
    }
    return try {
        if (post(runnable)) {
            true
        } else {
            onFailure(IllegalStateException("PlayerActivity launch executor rejected request"))
            false
        }
    } catch (e: Exception) {
        onFailure(e)
        false
    }
}

class MiracastService : Service(), P2pManager.P2pListener {
    private val TAG = "MiracastApp"
    private lateinit var p2pManager: P2pManager
    private lateinit var systemP2pReceiver: P2pReceiver
    private lateinit var vendorP2pReceiver: P2pReceiver
    private lateinit var sessionController: AdaptiveSessionController
    private var activeRtspConnection: RtspConnection? = null
    private var connectingSocket: java.net.Socket? = null
    private var resolverThread: Thread? = null
    private var activeUibcSession: UibcSession? = null
    private var activeUibcGeneration: Long = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectedDeviceName: String = ""
    private var connectedPeerAddress: String? = null
    private var failedAttemptGeneration: Long = 0L
    private var failedAttemptId: Long = 0L
    private val navigation = SessionNavigation()

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_DISCONNECT = "ACTION_DISCONNECT"
        const val ACTION_REQUEST_IDR = "foxlost.miracast.REQUEST_IDR"
        const val ACTION_RESET_FOR_NAVIGATION = "foxlost.miracast.RESET_FOR_NAVIGATION"
        const val EXTRA_RESET_RECEIVER = "foxlost.miracast.RESET_RECEIVER"
        const val RESET_OK = 1
        const val RESET_FAILED = 0
        const val CHANNEL_ID = "MiracastSinkChannel"

        /** Intent extras carrying negotiated local RTP/RTCP endpoints. */
        const val EXTRA_RTP_PORT = "foxlost.miracast.RTP_PORT"
        const val EXTRA_SESSION_GENERATION = "foxlost.miracast.SESSION_GENERATION"
        const val EXTRA_RTCP_PORT = "foxlost.miracast.RTCP_PORT"
        const val EXTRA_SSRC = "foxlost.miracast.SSRC"
        const val ACTION_TRANSPORT_NEGOTIATED = "foxlost.miracast.TRANSPORT_NEGOTIATED"
        @Volatile private var currentService: MiracastService? = null
        private val _playerSession = MutableStateFlow<PlayerSessionSnapshot?>(null)
        val playerSession: StateFlow<PlayerSessionSnapshot?> = _playerSession.asStateFlow()

        internal fun ownsPlayer(generation: Long, lease: Long): Boolean {
            val service = currentService ?: return false
            return synchronized(service) { service.navigation.owns(generation, lease) }
        }

        internal fun playerSnapshot(generation: Long, lease: Long): PlayerSessionSnapshot? {
            val service = currentService ?: return null
            return synchronized(service) {
                service.navigation.snapshot.takeIf { service.navigation.owns(generation, lease) }
            }
        }

        internal fun playerFailed(generation: Long, lease: Long, reason: String) {
            val service = currentService ?: return
            synchronized(service) {
                if (service.navigation.owns(generation, lease)) service.stopSinkAndCleanup(reason)
            }
        }

        internal fun claimPlayer(generation: Long): Pair<Long, PlayerSessionSnapshot>? {
            val service = currentService ?: return null
            return synchronized(service) {
                if (service.stopping || !service.sessionController.isCurrent(generation)) return@synchronized null
                val lease = service.navigation.claim(generation) ?: return@synchronized null
                lease to requireNotNull(service.navigation.snapshot)
            }
        }

        internal fun playerBound(generation: Long, lease: Long) {
            val service = currentService ?: return
            synchronized(service) {
                if (service.navigation.markBound(generation, lease)) service.publishPlayerSession()
            }
        }

        internal fun playerDestroyed(generation: Long, lease: Long) {
            val service = currentService ?: return
            synchronized(service) {
                val repair = service.navigation.release(generation, lease)
                service.publishPlayerSession()
                if (!repair || service.stopping || !service.sessionController.isCurrent(generation)) return
                val snapshot = service.navigation.snapshot ?: return
                service.playerOpened = false
                RtpEndpointRegistry.begin(generation.toString())
                service.openPlayer(generation, snapshot.attemptId, snapshot.rtpPort, snapshot.rtcpPort)
            }
        }

        /** Stale dashboard/notification intents cannot bootstrap another stream. */
        fun returnToPlayer(context: Context, generation: Long): Boolean {
            val service = currentService ?: return false
            return synchronized(service) {
                val snapshot = service.navigation.resumable ?: return@synchronized false
                if (snapshot.generation != generation || service.stopping ||
                    !service.sessionController.isCurrent(generation, snapshot.attemptId)) return@synchronized false
                context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_SESSION_GENERATION, snapshot.generation)
                })
                true
            }
        }
        private val _activeState = MutableStateFlow(false)
        val activeState: StateFlow<Boolean> = _activeState.asStateFlow()
        var isActive: Boolean
            get() = _activeState.value
            private set(value) { _activeState.value = value }
    }

    override fun onCreate() {
        super.onCreate()
        currentService = this
        _playerSession.value = null
        DebugEventLog.record(DebugEventCategory.SINK, "Miracast service created")
        SettingsManager.init(this)
        sessionController = AdaptiveSessionController(object : AdaptiveSessionController.Listener {
            override fun onStateChanged(generation: Long, state: SessionState, context: SessionContext?) {
                Log.d(TAG, "Session generation=$generation state=$state role=${context?.role}")
                if (state != SessionState.Streaming && state != SessionState.TransportReady &&
                    state != SessionState.MediaStarting) {
                    navigation.clear()
                    publishPlayerSession()
                }
            }

            override fun onCleanup(generation: Long, reason: String) {
                Log.i(TAG, "Session generation=$generation cleanup: $reason")
            }
        })
        p2pManager = P2pManager(this, this)
        systemP2pReceiver = P2pReceiver(p2pManager, allowVendorAuthorization = false)
        vendorP2pReceiver = P2pReceiver(p2pManager, allowVendorAuthorization = true)
        val systemFilter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION).apply {
            // Framework connection/peer broadcasts are the primary group-state
            // path. The supplicant P2P-GROUP-STARTED monitor in P2pManager is
            // retained only for vendor builds that omit connection-changed.
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, systemP2pReceiver, systemFilter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        val vendorFilter = IntentFilter("android.net.wifi.p2p.CONNECTION_REQUEST_ACCEPT").apply {
            // Private vendor-only headless authorization hooks.
            addAction("android.net.wifi.p2p.CONNECTION_REQUEST")
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, vendorP2pReceiver, vendorFilter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    @Synchronized
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REQUEST_IDR) {
            if (!isActive) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            val generation = intent.getLongExtra(EXTRA_SESSION_GENERATION, -1L)
            if (sessionController.isCurrent(generation) && !stopping) {
                activeRtspConnection?.requestIdr()
            }
            return START_STICKY
        }
        try { startForeground(1, createNotification("Miracast Sink is active", false, null)) } catch (_: Exception) {}
        when (intent?.action) {
            ACTION_RESET_FOR_NAVIGATION -> {
                @Suppress("DEPRECATION")
                val receiver = intent.getParcelableExtra<android.os.ResultReceiver>(EXTRA_RESET_RECEIVER)
                stopSinkAndCleanup("dashboard/settings navigation", onComplete = { success ->
                    receiver?.send(if (success) RESET_OK else RESET_FAILED, null)
                })
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (stopping) return START_NOT_STICKY
                if (isActive && sessionController.isCurrent(sessionController.generation)) return START_STICKY
                DebugEventLog.clear()
                DebugEventLog.record(
                    DebugEventCategory.WFD,
                    "Ordinary app UID=${android.os.Process.myUid()}; requesting session-scoped root Wi-Fi bridge (no platform signature or framework hooks)",
                )
                isActive = true
                stopping = false
                playerOpened = false
                closeUibc("new session start")
                closeControlConnection("new session start")
                val generation = sessionController.start()
                failedAttemptGeneration = 0L
                failedAttemptId = 0L
                DebugEventLog.record(DebugEventCategory.SINK, "Sink session started generation=$generation")
                val settingsSnapshot = "Startup settings generation=$generation ${SettingsManager.diagnosticSnapshot()}"
                Log.i(TAG, settingsSnapshot)
                DebugEventLog.record(DebugEventCategory.WFD, settingsSnapshot)
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "MiracastSink:WakeLock")
                    wakeLock?.acquire()
                }
                startForeground(1, createNotification("Miracast Sink is active", false, null))


                Handler(Looper.getMainLooper()).post {
                    if (!isActive || !sessionController.isCurrent(generation)) {
                        Log.d(TAG, "Ignoring stale P2P start generation=$generation")
                        return@post
                    }
                    p2pManager.startSink(generation)
                }
            }

            ACTION_STOP -> {
                stopSinkAndCleanup()
            }
            ACTION_DISCONNECT -> {
                startActivity(Intent(this@MiracastService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                stopSinkAndCleanup()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (currentService === this) {
            currentService = null
            _playerSession.value = null
        }
        stopSinkAndCleanup()
        p2pManager.dispose()
        unregisterReceiver(systemP2pReceiver)
        unregisterReceiver(vendorP2pReceiver)
    }

    private fun closeUibc(reason: String) {
        val generation = activeUibcGeneration
        val session = activeUibcSession
        activeUibcSession = null
        activeUibcGeneration = 0L
        if (generation != 0L) UibcSessionRegistry.unregister(generation, session)
        try { session?.close(reason) } catch (_: Exception) {}
    }

    private var stopping = false
    private var playerOpened = false
    private val cleanupCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var cleanupResult: Boolean? = null

    @Synchronized
    private fun stopSinkAndCleanup(
        reason: String = "service stopped",
        keepSinkRunning: Boolean = false,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        if (stopping) {
            val result = cleanupResult
            if (result != null) onComplete?.invoke(result)
            else if (onComplete != null) cleanupCallbacks.add(onComplete)
            return
        }
        if (onComplete != null) cleanupCallbacks.add(onComplete)
        stopping = true
        cleanupResult = null
        DebugEventLog.record(DebugEventCategory.SINK, "Sink session stopping: $reason")
        navigation.clear()
        publishPlayerSession()
        if (!keepSinkRunning) isActive = false
        closeUibc(reason)

        // Invalidate the generation before closing sockets so late RTSP callbacks
        // cannot start a second cleanup or reopen media for a disconnected group.
        val generation = sessionController.generation
        sessionController.stop(reason)
        RtpEndpointRegistry.end(generation.toString())
        closeControlConnection(reason)
        notifyPlayerSessionEnded(generation)
        playerOpened = false
        wakeLock?.apply { if (isHeld) release() }
        wakeLock = null

        if (!keepSinkRunning) {
            val main = Handler(Looper.getMainLooper())
            var mediaResult: Boolean? = null
            var groupResult: Boolean? = null
            fun finishIfRetired() {
                val mediaOk = mediaResult ?: return
                val groupOk = groupResult ?: return
                synchronized(this) {
                    val success = mediaOk && groupOk
                    cleanupResult = success
                    val callbacks = cleanupCallbacks.toList()
                    cleanupCallbacks.clear()
                    callbacks.forEach { it(success) }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            PlayerActivity.retireSession(generation) { success ->
                main.post { mediaResult = success; finishIfRetired() }
            }
            p2pManager.stopSink { success ->
                main.post { groupResult = success; finishIfRetired() }
            }
        } else {
            stopping = false
        }
    }

    private fun notifyPlayerSessionEnded(generation: Long) {
        sendBroadcast(Intent("foxlost.miracast.SESSION_END")
            .setPackage(packageName)
            .putExtra(EXTRA_SESSION_GENERATION, generation))
    }

    private fun openPlayer(generation: Long, attemptId: Long, rtpPort: Int, rtcpPort: Int?): Boolean {
        synchronized(this) {
            if (!sessionController.isCurrent(generation, attemptId)) return false
            if (playerOpened) return false
            playerOpened = true
        }
        val safeRtp = rtpPort.takeIf { it in 1..65534 } ?: SettingsManager.DEFAULT_RTP_VIDEO_PORT
        val safeRtcp = rtcpPort?.takeIf { it in 1..65535 }
        val onFailure: (Exception) -> Unit = { error ->
            synchronized(this) {
                if (sessionController.isCurrent(generation, attemptId)) {
                    playerOpened = false
                    RtpEndpointRegistry.failLaunch(generation.toString(), error)
                }
            }
            Log.e(TAG, "PlayerActivity launch failed generation=$generation", error)
        }
        return postPlayerLaunch(
            post = { runnable -> thread(name = "Player-RootLaunch-$generation") { runnable.run() }; true },
            launch = {
                synchronized(this@MiracastService) {
                    check(sessionController.isCurrent(generation, attemptId)) { "Stale player attempt" }
                }
                // Root start uses -W: the Activity must be able to claim its lease
                // while the launcher waits for onCreate to finish.
                check(p2pManager.launchPlayer(generation, safeRtp, safeRtcp ?: 0)) { "Root player launch rejected" }
            },
            onFailure = onFailure,
        )
    }

    private fun publishPlayerSession() {
        _playerSession.value = navigation.resumable
    }

    private fun prepareMedia(generation: Long, attemptId: Long): LocalRtpEndpoint? {
        if (!sessionController.isCurrent(generation, attemptId)) return null
        val rtp = try {
            SettingsManager.rtpVideoPort.takeIf { it in 1..65534 }
        } catch (_: Exception) {
            null
        } ?: SettingsManager.DEFAULT_RTP_VIDEO_PORT
        val rtcp: Int? = null
        val token = generation.toString()
        synchronized(this) {
            if (!sessionController.isCurrent(generation, attemptId)) return null
            if (!sessionController.markMediaStarting(generation)) return null
            navigation.prepare(PlayerSessionSnapshot(generation, attemptId, rtp, rtcp))
            RtpEndpointRegistry.begin(token)
            DebugEventLog.record(DebugEventCategory.MEDIA, "Media receiver starting generation=$generation RTP=$rtp")
            if (!openPlayer(generation, attemptId, rtp, rtcp)) {
                Log.e(TAG, "PlayerActivity launch was not queued generation=$generation")
                DebugEventLog.record(DebugEventCategory.ERROR, "PlayerActivity launch failed generation=$generation")
                throw IllegalStateException("PlayerActivity launch failed generation=$generation: launch was not queued")
            }
        }
        val ready = try {
            RtpEndpointRegistry.await(token, rtp, rtcp, 5000L)
        } catch (error: IllegalStateException) {
            DebugEventLog.record(DebugEventCategory.ERROR, "PlayerActivity launch failed generation=$generation: ${error.message}")
            throw error
        }
        if (!ready) {
            Log.e(TAG, "RTP/RTCP receiver did not bind before SETUP generation=$generation")
            DebugEventLog.record(DebugEventCategory.ERROR, "RTP/RTCP receiver bind timeout generation=$generation")
            return null
        }
        if (!sessionController.isCurrent(generation, attemptId)) return null
        DebugEventLog.record(DebugEventCategory.RTP, "RTP/RTCP receiver bound RTP=$rtp RTCP=${rtcp ?: "none"}")
        return LocalRtpEndpoint(rtp, rtcp)
    }


    private fun createNotification(text: String, showDisconnect: Boolean, contentIntent: PendingIntent?): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Miracast Sink Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Miracast Sink")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        if (showDisconnect) {
            val disconnectI = Intent(this, MiracastService::class.java).apply {
                action = ACTION_DISCONNECT
                setPackage(packageName)
            }
            val pi = PendingIntent.getForegroundService(this, 1, disconnectI,
                PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", pi)
        }
        return builder.build()
    }

    private fun updateNotification(text: String, showDisconnect: Boolean, openPlayer: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            DebugEventLog.recordOnce(DebugEventCategory.SINK, "notification-permission", "Notification permission denied; foreground playback remains active")
            return
        }
        // Always enter through the dashboard, which validates the current service epoch.
        // A PendingIntent surviving stop/retry must never launch a bare player.
        val targetIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (openPlayer) navigation.resumable?.let { putExtra(EXTRA_SESSION_GENERATION, it.generation) }
        }
        val pi = PendingIntent.getActivity(this, 0, targetIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, createNotification(text, showDisconnect, pi))
    }
    @Synchronized
    override fun onP2pGroupConnected(group: WifiP2pGroup, info: WifiP2pInfo, attemptId: Long) {
        val generation = sessionController.generation
        if (stopping || !sessionController.isCurrent(generation, attemptId) ||
            sessionController.context != null
        ) return
        val startedNanos = System.nanoTime()
        val peer = group.remoteWfdSource(info.isGroupOwner)
        if (peer == null) {
            p2pManager.reportAttemptFailure(generation, attemptId, "P2P group has no WFD peer")
            return
        }
        val role = if (info.isGroupOwner) P2pRole.SinkGoSourceClient else P2pRole.SinkClientSourceGo
        val peerWfd = peer.readWfdPeerInfo()
        val controlPort = peerWfd.controlPort ?: 7236
        val groupOwnerIp = info.groupOwnerAddress?.hostAddress
        val context = sessionController.onGroupFormed(
            generation = generation,
            peer = PeerIdentity(
                address = peer.deviceAddress,
                name = peer.deviceName ?: "Unknown",
                controlPort = controlPort,
                wfdEnabled = peerWfd.enabled,
            ),
            role = role,
            groupOwnerAddress = groupOwnerIp,
            localAddress = null,
            attemptId = attemptId,
        ) ?: return
        failedAttemptGeneration = 0L
        failedAttemptId = 0L

        connectedDeviceName = peer.deviceName ?: "Unknown"
        connectedPeerAddress = peer.deviceAddress
        updateNotification("Connected to: $connectedDeviceName", true, false)
        Log.i(
            TAG,
            "P2P group formed generation=$generation attempt=$attemptId role=$role " +
                "go=${groupOwnerIp ?: "unknown"} controlPort=$controlPort",
        )
        DebugEventLog.record(
            DebugEventCategory.P2P,
            "P2P group ready role=$role controlPort=$controlPort",
        )

        if (role == P2pRole.SinkClientSourceGo) {
            val sourceIp = context.groupOwnerAddress
            if (sourceIp == null) {
                p2pManager.reportAttemptFailure(generation, attemptId, "P2P source GO address unavailable")
                return
            }
            startOutboundConnection(context, sourceIp, startedNanos)
        } else {
            // Android's WFD source keeps the RTSP server on its own P2P
            // address even when it becomes the group client. Resolve that
            // address after DHCP/ARP has populated the kernel table, then
            // connect as the RTSP client.
            val interfaceName = group.`interface`?.takeIf { it.isNotBlank() }
            if (interfaceName == null) {
                p2pManager.reportAttemptFailure(generation, attemptId, "P2P interface unavailable for source lookup")
                return
            }
            resolveSourceClientAndConnect(context, interfaceName, startedNanos)
        }
    }

    private fun resolveSourceClientAndConnect(context: SessionContext, interfaceName: String, startedNanos: Long) {
        val resolver = thread(start = false, name = "P2P-ResolveSource-${context.generation}") {
            try {
                DebugEventLog.record(DebugEventCategory.DHCP, "Resolving source address from ARP peer=${context.peer.address}")
                val sourceIp = P2pPeerAddressResolver.resolveFromArp(
                    peerMac = context.peer.address,
                    interfaceName = interfaceName,
                    groupOwnerAddress = context.groupOwnerAddress,
                    startedNanos = startedNanos,
                    readInterfaceMac = {
                        p2pManager.readPeerInterfaceAddress(context.generation, context.attemptId, interfaceName, context.peer.address)
                    },
                    readTable = { p2pManager.readArpTable(context.generation, context.attemptId) },
                    isCurrent = { sessionController.isCurrent(context.generation, context.attemptId) },
                )
                if (!sessionController.isCurrent(context.generation, context.attemptId) ||
                    Thread.currentThread().isInterrupted) return@thread
                if (sourceIp == null) {
                    DebugEventLog.record(DebugEventCategory.ERROR, "DHCP/ARP source address unavailable")
                    p2pManager.reportAttemptFailure(context.generation, context.attemptId, "P2P source client address unavailable")
                    return@thread
                }
                DebugEventLog.record(DebugEventCategory.DHCP, "Source address resolved via ARP: $sourceIp")
                Log.i(TAG, "Resolved source client ${context.peer.address} to $sourceIp")
                startOutboundConnection(context, sourceIp, startedNanos)
            } catch (e: Exception) {
                if (e is InterruptedException) Thread.currentThread().interrupt()
                if (!sessionController.isCurrent(context.generation, context.attemptId) ||
                    Thread.currentThread().isInterrupted) return@thread
                Log.e(TAG, "P2P source address lookup failed", e)
                p2pManager.reportAttemptFailure(context.generation, context.attemptId, "P2P source address lookup failed: ${e.message}")
            } finally {
                synchronized(this@MiracastService) {
                    if (resolverThread === Thread.currentThread()) resolverThread = null
                }
            }
        }
        synchronized(this) {
            if (!sessionController.isCurrent(context.generation, context.attemptId)) return
            resolverThread?.interrupt()
            resolverThread = resolver
            resolver.start()
        }
    }

    /** Caller holds the service lifecycle monitor. Closing also cancels connect(). */
    private fun closeControlConnection(reason: String) {
        resolverThread?.interrupt()
        resolverThread = null
        try { connectingSocket?.close() } catch (_: Exception) {}
        connectingSocket = null
        activeRtspConnection?.close(reason)
        activeRtspConnection = null
    }

    private fun startOutboundConnection(context: SessionContext, sourceIp: String, startedNanos: Long) {
        if (!sessionController.isCurrent(context.generation, context.attemptId)) return
        val outbound = sessionController.beginOutbound(context.generation, context.attemptId) ?: return
        thread(name = "RTSP-Connect-${outbound.generation}") {
            // Address readiness and TCP attempts share one monotonic source-startup window.
            var attempt = 0
            while (true) {
                val remainingMs = remainingControlConnectMs(startedNanos, System.nanoTime())
                if (remainingMs <= 0L) break
                attempt += 1
                if (!sessionController.isCurrent(outbound.generation, outbound.attemptId)) return@thread
                val socket = java.net.Socket()
                try {
                    synchronized(this@MiracastService) {
                        if (!sessionController.isCurrent(outbound.generation, outbound.attemptId)) {
                            socket.close()
                            return@thread
                        }
                        connectingSocket = socket
                    }
                    val connectTimeoutMs = minOf(5000L, remainingControlConnectMs(startedNanos, System.nanoTime())).toInt()
                    if (connectTimeoutMs <= 0) {
                        socket.close()
                        break
                    }
                    socket.connect(java.net.InetSocketAddress(sourceIp, outbound.peer.controlPort), connectTimeoutMs)
                    if (!sessionController.isCurrent(outbound.generation, outbound.attemptId)) {
                        socket.close()
                        return@thread
                    }
                    Log.i(TAG, "RTSP source reachable at $sourceIp:${outbound.peer.controlPort}")
                    DebugEventLog.record(DebugEventCategory.RTSP, "RTSP source connected ${sourceIp}:${outbound.peer.controlPort}")
                    val connection = createConnection(socket, outbound, RtspEntry.Outbound)
                    synchronized(this@MiracastService) {
                        if (!sessionController.isCurrent(outbound.generation, outbound.attemptId)) {
                            connection.close("stale connection attempt")
                            return@thread
                        }
                        activeRtspConnection = connection
                        connectingSocket = null
                        connection.start()
                    }
                    return@thread
                } catch (e: Exception) {
                    try { socket.close() } catch (_: Exception) {}
                    if (!sessionController.isCurrent(outbound.generation, outbound.attemptId)) return@thread
                    Log.d(TAG, "RTSP connect attempt $attempt failed: ${e.message}")
                    val delayMs = minOf(1000L, remainingControlConnectMs(startedNanos, System.nanoTime()))
                    if (delayMs > 0L) {
                        try { Thread.sleep(delayMs) } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@thread
                        }
                    }
                } finally {
                    synchronized(this@MiracastService) {
                        if (connectingSocket === socket) connectingSocket = null
                    }
                }
            }
            synchronized(this@MiracastService) {
                if (!sessionController.isCurrent(outbound.generation, outbound.attemptId)) return@thread
                DebugEventLog.record(DebugEventCategory.ERROR, "RTSP source unreachable after retries")
                p2pManager.reportAttemptFailure(
                    outbound.generation,
                    outbound.attemptId,
                    "RTSP source unreachable after retries",
                )
            }
        }
    }


    private fun createConnection(
        socket: java.net.Socket,
        context: SessionContext,
        entry: RtspEntry,
    ): RtspConnection {
        val callbacks = object : RtspSessionCallbacks {
            override fun onMediaStarting(generation: Long) {
                synchronized(this@MiracastService) {
                if (sessionController.isCurrent(generation, context.attemptId)) sessionController.markMediaStarting(generation)
                }
            }

            override fun prepareMedia(generation: Long): LocalRtpEndpoint? =
                if (sessionController.isCurrent(generation, context.attemptId))
                    this@MiracastService.prepareMedia(generation, context.attemptId) else null

            override fun onTransportReady(generation: Long, transport: NegotiatedTransport) {
                synchronized(this@MiracastService) {
                if (!sessionController.isCurrent(generation, context.attemptId)) return
                if (!sessionController.onTransportReady(
                        generation = generation,
                        sessionId = transport.sessionId,
                        localRtp = transport.local.rtpPort,
                        localRtcp = transport.local.rtcpPort,
                        remoteRtp = transport.remoteRtpPort,
                        remoteRtcp = transport.remoteRtcpPort,
                        ssrc = transport.ssrc,
                    )
                ) return
                navigation.transport(PlayerSessionSnapshot(
                    generation, context.attemptId, transport.local.rtpPort,
                    transport.local.rtcpPort, transport.ssrc,
                ))
                publishPlayerSession()
                val broadcast = Intent(ACTION_TRANSPORT_NEGOTIATED)
                    .setPackage(packageName)
                    .putExtra(EXTRA_SESSION_GENERATION, generation)
                    .putExtra(EXTRA_RTP_PORT, transport.local.rtpPort)
                    .putExtra(EXTRA_RTCP_PORT, transport.local.rtcpPort ?: 0)
                transport.ssrc?.let { broadcast.putExtra(EXTRA_SSRC, it) }
                sendBroadcast(broadcast)
                }
            }

            override fun onPlay(generation: Long) {
                synchronized(this@MiracastService) {
                if (!sessionController.isCurrent(generation, context.attemptId)) return
                sessionController.markStreaming(generation)
                navigation.markStreaming(generation, context.attemptId)
                publishPlayerSession()
                DebugEventLog.record(DebugEventCategory.MEDIA, "RTSP PLAY accepted; media streaming")
                updateNotification("Streaming from: $connectedDeviceName", true, true)
                }
            }

            override fun onProfile(generation: Long, profile: CompatibilityProfile) {
                synchronized(this@MiracastService) {
                if (!sessionController.isCurrent(generation, context.attemptId)) return
                Log.i(TAG, "RTSP profile generation=$generation source=${profile.sourceKind} entry=${profile.entry}")
                sessionController.applyProfile(generation, profile)
                }
            }

            override fun onUibcEndpoint(generation: Long, endpoint: UibcEndpoint) {
                synchronized(this@MiracastService) {
                if (!sessionController.isCurrent(generation, context.attemptId)) {
                    Log.w(TAG, "Ignoring stale UIBC endpoint generation=$generation port=${endpoint.port}")
                    return
                }
                Log.i(TAG, "UIBC endpoint negotiated generation=$generation source=${endpoint.sourceAddress} port=${endpoint.port} category=${endpoint.category} path=${endpoint.path} type=${endpoint.hidType}")
                closeUibc("new endpoint")
                sessionController.applyUibcEndpoint(generation, endpoint)
                if (!SettingsManager.uibcEnabled) {
                    Log.w(TAG, "UIBC negotiated but disabled in settings generation=$generation")
                    DebugEventLog.record(DebugEventCategory.UIBC, "UIBC negotiated but disabled in settings")
                    sessionController.disableUibc(generation, "sink setting disabled")
                    return
                }
                lateinit var session: TcpUibcSession
                session = TcpUibcSession(generation, onClosed = { reason ->
                    Handler(Looper.getMainLooper()).post {
                        if (sessionController.isCurrent(generation) && activeUibcSession === session) {
                            activeUibcSession = null
                            activeUibcGeneration = 0L
                            sessionController.disableUibc(generation, reason)
                        }
                    }
                })
                activeUibcSession = session
                activeUibcGeneration = generation
                UibcSessionRegistry.register(generation, session)
                session.start(endpoint)
                Log.i(TAG, "UIBC session registered generation=$generation")
                DebugEventLog.record(DebugEventCategory.UIBC, "UIBC session registered generation=$generation")
                }
            }

            override fun onUibcDisabled(generation: Long, reason: String) {
                synchronized(this@MiracastService) {
                if (!sessionController.isCurrent(generation, context.attemptId)) {
                    Log.w(TAG, "Ignoring stale UIBC disable generation=$generation reason=$reason")
                    return
                }
                Log.i(TAG, "UIBC disabled generation=$generation reason=$reason")
                sessionController.disableUibc(generation, reason)
                closeUibc(reason)
                DebugEventLog.record(DebugEventCategory.UIBC, "UIBC disabled: $reason")
                }
            }
            override fun onSessionEnded(generation: Long, reason: String) {
                synchronized(this@MiracastService) {
                if (!sessionController.isCurrent(generation, context.attemptId)) return
                DebugEventLog.record(DebugEventCategory.RTSP, "RTSP session ended: $reason")
                finishSession(generation, reason)
                }
            }
        }
        return RtspConnection(
            socket = socket,
            generation = context.generation,
            entry = entry,
            profile = context.profile,
            callbacks = callbacks,
        )
    }

    @Synchronized
    private fun finishSession(generation: Long, reason: String) {
        if (!sessionController.isCurrent(generation)) return
        Log.i(TAG, "RTSP session ended generation=$generation reason=$reason")
        stopSinkAndCleanup(reason)
    }

    @Synchronized
    override fun onP2pGroupDisconnected(attemptId: Long) {
        val generation = sessionController.generation
        if (!sessionController.isCurrent(generation, attemptId)) return
        closeUibc("P2P group disconnected")
        sessionController.resetAttempt(generation, attemptId, "P2P group disconnected")
        failedAttemptGeneration = generation
        failedAttemptId = attemptId
        notifyPlayerSessionEnded(generation)
        playerOpened = false
        RtpEndpointRegistry.end(generation.toString())
        connectedPeerAddress = null
        Log.d(TAG, "Disconnected from P2P Group attempt=$attemptId; closing session resources")
        DebugEventLog.record(DebugEventCategory.P2P, "P2P group disconnected attempt=$attemptId")
        closeControlConnection("P2P group disconnected")
        updateNotification("Miracast Sink is active", false, false)
    }

    @Synchronized
    override fun onP2pStartupFailed(generation: Long, reason: String) {
        if (!stopping && sessionController.isCurrent(generation)) finishSession(generation, reason)
    }

    @Synchronized
    override fun onP2pAttemptFailed(attemptId: Long, reason: String) {
        val generation = sessionController.generation
        if (!sessionController.isCurrent(generation, attemptId)) return
        closeUibc(reason)
        DebugEventLog.record(DebugEventCategory.ERROR, "P2P attempt failed: $reason")
        Log.w(TAG, "P2P attempt=$attemptId failed generation=$generation: $reason")
        sessionController.resetAttempt(generation, attemptId, reason)
        failedAttemptGeneration = generation
        failedAttemptId = attemptId
        closeControlConnection(reason)
        playerOpened = false
        notifyPlayerSessionEnded(generation)
        RtpEndpointRegistry.end(generation.toString())
        connectedPeerAddress = null
        updateNotification("Miracast Sink is active", false, false)
    }

    @Synchronized
    override fun onP2pAttemptExhausted(attemptId: Long, reason: String) {
        val generation = sessionController.generation
        if (stopping || !sessionController.isCurrent(generation) ||
            failedAttemptGeneration != generation || failedAttemptId != attemptId
        ) return
        DebugEventLog.record(DebugEventCategory.ERROR, "P2P attempts exhausted: $reason")
        Log.e(TAG, "P2P attempts exhausted generation=$generation lastAttempt=$attemptId: $reason")
        finishSession(generation, "P2P attempts exhausted: $reason")
    }

    override fun onDiscoveryStateChanged(active: Boolean) {
        DebugEventLog.recordRateLimited(DebugEventCategory.P2P, "discovery-state", "P2P discovery active=$active", 1_000L)
        Log.d(TAG, "Discovery state: $active")
    }
}
