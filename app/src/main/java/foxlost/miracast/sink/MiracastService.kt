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
import foxlost.miracast.sink.media.RtpEndpointRegistry
import foxlost.miracast.sink.rtsp.LocalRtpEndpoint
import foxlost.miracast.sink.rtsp.NegotiatedTransport
import foxlost.miracast.sink.rtsp.RtspConnection
import foxlost.miracast.sink.rtsp.RtspServer
import foxlost.miracast.sink.rtsp.RtspSessionCallbacks
import kotlin.concurrent.thread
import java.util.concurrent.TimeUnit

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
            onFailure(IllegalStateException("main looper rejected PlayerActivity launch"))
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
    private lateinit var p2pReceiver: P2pReceiver
    private lateinit var sessionController: AdaptiveSessionController
    private var rtspServer: RtspServer? = null
    private var activeRtspConnection: RtspConnection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectedDeviceName: String = ""
    private var connectedPeerAddress: String? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_DISCONNECT = "ACTION_DISCONNECT"
        const val CHANNEL_ID = "MiracastSinkChannel"

        /** Intent extras carrying negotiated local RTP/RTCP endpoints. */
        const val EXTRA_RTP_PORT = "foxlost.miracast.RTP_PORT"
        const val EXTRA_SESSION_GENERATION = "foxlost.miracast.SESSION_GENERATION"
        const val EXTRA_RTCP_PORT = "foxlost.miracast.RTCP_PORT"
        const val EXTRA_SSRC = "foxlost.miracast.SSRC"
        const val ACTION_TRANSPORT_NEGOTIATED = "foxlost.miracast.TRANSPORT_NEGOTIATED"
        var isActive = false
    }

    override fun onCreate() {
        super.onCreate()
        DebugEventLog.record(DebugEventCategory.SINK, "Miracast service created")
        SettingsManager.init(this)
        sessionController = AdaptiveSessionController(object : AdaptiveSessionController.Listener {
            override fun onStateChanged(generation: Long, state: SessionState, context: SessionContext?) {
                Log.d(TAG, "Session generation=$generation state=$state role=${context?.role}")
            }

            override fun onCleanup(generation: Long, reason: String) {
                Log.i(TAG, "Session generation=$generation cleanup: $reason")
            }
        })
        p2pManager = P2pManager(this, this)
        p2pReceiver = P2pReceiver(p2pManager)
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION).apply {
            // Framework connection/peer broadcasts are the primary group-state
            // path. The supplicant P2P-GROUP-STARTED monitor in P2pManager is
            // retained only for vendor builds that omit connection-changed.
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            // Vendor-only headless authorization hooks.
            addAction("android.net.wifi.p2p.CONNECTION_REQUEST_ACCEPT")
            addAction("android.net.wifi.p2p.CONNECTION_REQUEST")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(p2pReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try { startForeground(1, createNotification("Miracast Sink is active", false, null)) } catch (_: Exception) {}
        when (intent?.action) {
            ACTION_START -> {
                DebugEventLog.clear()
                isActive = true
                stopping = false
                playerOpened = false
                activeRtspConnection?.close("new session start")
                activeRtspConnection = null
                rtspServer?.stop()
                rtspServer = null
                val generation = sessionController.start()
                DebugEventLog.record(DebugEventCategory.SINK, "Sink session started generation=$generation")
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "MiracastSink:WakeLock")
                    wakeLock?.acquire()
                }
                startForeground(1, createNotification("Miracast Sink is active", false, null))

                val rtspPort = SettingsManager.effectiveRtspPort()
                rtspServer = RtspServer(rtspPort) { socket ->
                    createInboundConnection(socket, generation)
                }
                if (!rtspServer!!.start()) {
                    Log.e(TAG, "RTSP server bind failed on $rtspPort")
                    DebugEventLog.record(DebugEventCategory.ERROR, "RTSP server bind failed on port $rtspPort")
                    sessionController.stop("RTSP server bind failed")
                } else {
                    DebugEventLog.record(DebugEventCategory.RTSP, "RTSP server listening on port $rtspPort")
                }

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
        stopSinkAndCleanup()
        unregisterReceiver(p2pReceiver)
    }

    private var stopping = false
    private var playerOpened = false

    private fun stopSinkAndCleanup(
        reason: String = "service stopped",
        keepSinkRunning: Boolean = false,
    ) {
        if (stopping) return
        stopping = true
        DebugEventLog.record(DebugEventCategory.SINK, "Sink session stopping: $reason")
        if (!keepSinkRunning) isActive = false

        // Invalidate the generation before closing sockets so late RTSP callbacks
        // cannot start a second cleanup or reopen media for a disconnected group.
        sessionController.stop(reason)
        activeRtspConnection?.close(reason)
        activeRtspConnection = null
        rtspServer?.stop()
        rtspServer = null
        sendBroadcast(Intent("foxlost.miracast.SESSION_END"))
        playerOpened = false
        wakeLock?.apply { if (isHeld) release() }
        wakeLock = null

        if (!keepSinkRunning) {
            p2pManager.stopSink()
            stopSelf()
        } else {
            stopping = false
        }
    }

    private fun openPlayer(generation: Long, rtpPort: Int, rtcpPort: Int?): Boolean {
        synchronized(this) {
            if (playerOpened) return false
            playerOpened = true
        }
        val safeRtp = rtpPort.takeIf { it in 1..65534 } ?: SettingsManager.DEFAULT_RTP_VIDEO_PORT
        val safeRtcp = rtcpPort?.takeIf { it in 1..65535 } ?: (safeRtp + 1).takeIf { it <= 65535 }
        val playerIntent = Intent(this, PlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_RTP_PORT, safeRtp)
            putExtra(EXTRA_RTCP_PORT, safeRtcp ?: 0)
            putExtra(EXTRA_SESSION_GENERATION, generation)
        }
        val onFailure: (Exception) -> Unit = { error ->
            synchronized(this) { playerOpened = false }
            RtpEndpointRegistry.clear(generation.toString())
            Log.e(TAG, "PlayerActivity launch failed generation=$generation", error)
        }
        return postPlayerLaunch(
            post = { runnable -> Handler(Looper.getMainLooper()).post(runnable) },
            launch = { startActivity(playerIntent) },
            onFailure = onFailure,
        )
    }

    private fun prepareMedia(generation: Long): LocalRtpEndpoint? {
        val rtp = try {
            SettingsManager.rtpVideoPort.takeIf { it in 1..65534 }
        } catch (_: Exception) {
            null
        } ?: SettingsManager.DEFAULT_RTP_VIDEO_PORT
        val rtcp = (rtp + 1).takeIf { it <= 65535 }
        val token = generation.toString()
        RtpEndpointRegistry.clear(token)
        sessionController.markMediaStarting(generation)
        DebugEventLog.record(DebugEventCategory.MEDIA, "Media receiver starting generation=$generation RTP=$rtp")
        if (!openPlayer(generation, rtp, rtcp)) {
            Log.e(TAG, "PlayerActivity launch was not queued generation=$generation")
            DebugEventLog.record(DebugEventCategory.ERROR, "PlayerActivity launch failed generation=$generation")
            return null
        }
        if (!RtpEndpointRegistry.await(token, rtp, rtcp, 5000L)) {
            Log.e(TAG, "RTP/RTCP receiver did not bind before SETUP generation=$generation")
            DebugEventLog.record(DebugEventCategory.ERROR, "RTP/RTCP receiver bind timeout generation=$generation")
            return null
        }
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
        val targetIntent = if (openPlayer) {
            Intent(this, PlayerActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        } else {
            Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        }
        val pi = PendingIntent.getActivity(this, 0, targetIntent, PendingIntent.FLAG_IMMUTABLE)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, createNotification(text, showDisconnect, pi))
    }
    override fun onP2pGroupConnected(group: WifiP2pGroup, info: WifiP2pInfo, attemptId: Long) {
        val generation = sessionController.generation
        val peer = if (info.isGroupOwner) group.clientList.firstOrNull() else group.owner
        if (peer == null) {
            p2pManager.reportAttemptFailure(attemptId, "P2P group has no WFD peer")
            return
        }
        val role = if (info.isGroupOwner) P2pRole.SinkGoSourceClient else P2pRole.SinkClientSourceGo
        val controlPort = peer.wfdInfo?.controlPort
            ?.takeIf { it in 1..65535 }
            ?: SettingsManager.effectiveRtspPort()
        val groupOwnerIp = info.groupOwnerAddress?.hostAddress
        val context = sessionController.onGroupFormed(
            generation = generation,
            peer = PeerIdentity(
                address = peer.deviceAddress,
                name = peer.deviceName ?: "Unknown",
                controlPort = controlPort,
                wfdEnabled = peer.wfdInfo?.isEnabled == true,
            ),
            role = role,
            groupOwnerAddress = groupOwnerIp,
            localAddress = null,
            attemptId = attemptId,
        ) ?: return

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
                p2pManager.reportAttemptFailure(attemptId, "P2P source GO address unavailable")
                return
            }
            startOutboundConnection(context, sourceIp)
        } else {
            // Android's WFD source keeps the RTSP server on its own P2P
            // address even when it becomes the group client. Resolve that
            // address after DHCP/ARP has populated the kernel table, then
            // connect as the RTSP client.
            resolveSourceClientAndConnect(context)
        }
    }

    private fun resolveSourceClientAndConnect(context: SessionContext) {
        thread(name = "P2P-ResolveSource-${context.generation}") {
            DebugEventLog.record(DebugEventCategory.DHCP, "Resolving source address from ARP peer=${context.peer.address}")
            val sourceIp = P2pPeerAddressResolver.resolveFromArp(
                peerMac = context.peer.address,
                readTable = {
                    P2pPeerAddressResolver.readTableWithFallback(
                        directReader = {
                            try {
                                java.io.File("/proc/net/arp").readText()
                            } catch (e: Exception) {
                                Log.d(TAG, "ARP table direct read failed: ${e.message}")
                                null
                            }
                        },
                        rootReader = { readArpTableAsRoot() },
                    )
                },
            )
            if (!sessionController.isCurrent(context.generation, context.attemptId)) return@thread
            if (sourceIp == null) {
                DebugEventLog.record(DebugEventCategory.ERROR, "DHCP/ARP source address unavailable")
                p2pManager.reportAttemptFailure(
                    context.attemptId,
                    "P2P source client address unavailable",
                )
                return@thread
            }
            DebugEventLog.record(DebugEventCategory.DHCP, "Source address resolved via ARP: $sourceIp")
            Log.i(TAG, "Resolved source client ${context.peer.address} to $sourceIp")
            startOutboundConnection(context, sourceIp)
        }
    }
    private fun readArpTableAsRoot(): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/net/arp"))
            if (!process.waitFor(500L, TimeUnit.MILLISECONDS)) {
                process.destroy()
                Log.d(TAG, "ARP table root read timed out")
                return null
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            output.takeIf { process.exitValue() == 0 && it.isNotBlank() }
        } catch (e: Exception) {
            Log.d(TAG, "ARP table root read unavailable: ${e.message}")
            null
        }
    }

    private fun startOutboundConnection(context: SessionContext, sourceIp: String) {
        if (!sessionController.isCurrent(context.generation, context.attemptId)) return
        val outbound = sessionController.beginOutbound(context.generation, context.attemptId) ?: return
        thread(name = "RTSP-Connect-${outbound.generation}") {
            repeat(8) { attempt ->
                if (!sessionController.isCurrent(outbound.generation, outbound.attemptId)) return@thread
                val socket = java.net.Socket()
                try {
                    socket.connect(java.net.InetSocketAddress(sourceIp, outbound.peer.controlPort), 5000)
                    Log.i(TAG, "RTSP source reachable at $sourceIp:${outbound.peer.controlPort}")
                    DebugEventLog.record(DebugEventCategory.RTSP, "RTSP source connected ${sourceIp}:${outbound.peer.controlPort}")
                    val connection = createConnection(socket, outbound, RtspEntry.Outbound)
                    activeRtspConnection = connection
                    connection.start()
                    return@thread
                } catch (e: Exception) {
                    try { socket.close() } catch (_: Exception) {}
                    Log.d(TAG, "RTSP connect retry ${attempt + 1}/8 failed: ${e.message}")
                    if (attempt < 7) {
                        try { Thread.sleep(1000L) } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@thread
                        }
                    }
                }
            }
            DebugEventLog.record(DebugEventCategory.ERROR, "RTSP source unreachable after retries")
            p2pManager.reportAttemptFailure(
                outbound.attemptId,
                "RTSP source unreachable after retries",
            )
        }
    }

    private fun createInboundConnection(socket: java.net.Socket, generation: Long): RtspConnection? {
        val sourceIp = socket.inetAddress.hostAddress ?: return null
        val context = sessionController.acceptInbound(generation, sourceIp) ?: return null
        return createConnection(socket, context, RtspEntry.Inbound).also {
            activeRtspConnection = it
        }
    }

    private fun createConnection(
        socket: java.net.Socket,
        context: SessionContext,
        entry: RtspEntry,
    ): RtspConnection {
        val callbacks = object : RtspSessionCallbacks {
            override fun onMediaStarting(generation: Long) {
                sessionController.markMediaStarting(generation)
            }

            override fun prepareMedia(generation: Long): LocalRtpEndpoint? =
                this@MiracastService.prepareMedia(generation)

            override fun onTransportReady(generation: Long, transport: NegotiatedTransport) {
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
                val broadcast = Intent(ACTION_TRANSPORT_NEGOTIATED)
                    .putExtra(EXTRA_SESSION_GENERATION, generation)
                    .putExtra(EXTRA_RTP_PORT, transport.local.rtpPort)
                    .putExtra(EXTRA_RTCP_PORT, transport.local.rtcpPort ?: 0)
                transport.ssrc?.let { broadcast.putExtra(EXTRA_SSRC, it) }
                sendBroadcast(broadcast)
            }

            override fun onPlay(generation: Long) {
                sessionController.markStreaming(generation)
                DebugEventLog.record(DebugEventCategory.MEDIA, "RTSP PLAY accepted; media streaming")
                updateNotification("Streaming from: $connectedDeviceName", true, true)
            }

            override fun onProfile(generation: Long, profile: CompatibilityProfile) {
                Log.i(TAG, "RTSP profile generation=$generation source=${profile.sourceKind} entry=${profile.entry}")
                sessionController.applyProfile(generation, profile)
            }

            override fun onSessionEnded(generation: Long, reason: String) {
                if (!sessionController.isCurrent(generation)) return
                DebugEventLog.record(DebugEventCategory.RTSP, "RTSP session ended: $reason")
                finishSession(generation, reason)
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

    private fun finishSession(generation: Long, reason: String) {
        if (!sessionController.isCurrent(generation) || sessionController.context == null) return
        Log.i(TAG, "RTSP session ended generation=$generation reason=$reason")
        Handler(Looper.getMainLooper()).post {
            updateNotification("Miracast Sink is active", false, false)
        }
        stopSinkAndCleanup(reason)
    }

    override fun onP2pGroupDisconnected(attemptId: Long) {
        val generation = sessionController.generation
        if (sessionController.isCurrent(generation, attemptId)) {
            sessionController.resetAttempt(generation, attemptId, "P2P group disconnected")
        }
        connectedPeerAddress = null
        Log.d(TAG, "Disconnected from P2P Group attempt=$attemptId; closing session resources")
        DebugEventLog.record(DebugEventCategory.P2P, "P2P group disconnected attempt=$attemptId")
        activeRtspConnection?.close("P2P group disconnected")
        activeRtspConnection = null
        updateNotification("Miracast Sink is active", false, false)
    }

    override fun onP2pAttemptFailed(attemptId: Long, reason: String) {
        val generation = sessionController.generation
        if (!sessionController.isCurrent(generation, attemptId)) return
        DebugEventLog.record(DebugEventCategory.ERROR, "P2P attempt failed: $reason")
        Log.w(TAG, "P2P attempt=$attemptId failed generation=$generation: $reason")
        sessionController.resetAttempt(generation, attemptId, reason)
        activeRtspConnection?.close(reason)
        activeRtspConnection = null
        playerOpened = false
        RtpEndpointRegistry.clear(generation.toString())
        connectedPeerAddress = null
        updateNotification("Miracast Sink is active", false, false)
    }

    override fun onP2pAttemptExhausted(attemptId: Long, reason: String) {
        DebugEventLog.record(DebugEventCategory.ERROR, "P2P attempts exhausted: $reason")
        val generation = sessionController.generation
        Log.e(TAG, "P2P attempts exhausted generation=$generation lastAttempt=$attemptId: $reason")
        finishSession(generation, "P2P attempts exhausted: $reason")
    }

    override fun onDiscoveryStateChanged(active: Boolean) {
        DebugEventLog.recordRateLimited(DebugEventCategory.P2P, "discovery-state", "P2P discovery active=$active", 1_000L)
        Log.d(TAG, "Discovery state: $active")
    }
}
