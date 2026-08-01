package foxlost.miracast.sink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
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
import foxlost.miracast.sink.rtsp.RtspServer
import kotlin.concurrent.thread

class MiracastService : Service(), P2pManager.P2pListener {
    private val TAG = "MiracastApp"
    private lateinit var p2pManager: P2pManager
    private lateinit var p2pReceiver: P2pReceiver
    private var rtspServer: RtspServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectedDeviceName: String = ""

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_DISCONNECT = "ACTION_DISCONNECT"
        const val CHANNEL_ID = "MiracastSinkChannel"
        var isActive = false
    }

    override fun onCreate() {
        super.onCreate()
        p2pManager = P2pManager(this, this)
        p2pReceiver = P2pReceiver(p2pManager)
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        registerReceiver(p2pReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try { startForeground(1, createNotification("Miracast Sink is active", false, null)) } catch (_: Exception) {}
        when (intent?.action) {
            ACTION_START -> {
                isActive = true
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "MiracastSink:WakeLock")
                    wakeLock?.acquire()
                }
                startForeground(1, createNotification("Miracast Sink is active", false, null))
                
                rtspServer = RtspServer(7236) {
                    val playerIntent = Intent(this, PlayerActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(playerIntent)
                }
                if (!rtspServer!!.start()) {
                    // Fallback: try to kill conflicting process and try again
                    thread {
                        try {
                            Runtime.getRuntime().exec(arrayOf("su", "-c", "fuser -k 7236/tcp")).waitFor()
                        } catch (e: Exception) {}
                        Thread.sleep(500)
                        Handler(Looper.getMainLooper()).post {
                            rtspServer?.start()
                            Log.d(TAG, "Retrying RTSP server bind...")
                        }
                    }
                }

                // Root routing IP setup helper and Hidden API bypass
                thread {
                    try {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put global hidden_api_policy 0; settings put global hidden_api_policy_pre_p_apps 0; settings put global hidden_api_policy_p_apps 0; setenforce 0; iptables -F; setprop persist.debug.wfd.enable 1; settings put global wifi_display_on 1")).waitFor()
                    } catch (e: Exception) {}
                    
                    // Start P2P after hidden APIs are bypassed
                    Handler(Looper.getMainLooper()).post {
                        p2pManager.startSink()
                    }
                }
            }
            ACTION_STOP -> {
                stopSinkAndCleanup()
            }
            ACTION_DISCONNECT -> {
                sendBroadcast(Intent("foxlost.miracast.SESSION_END"))
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

    private fun stopSinkAndCleanup() {
        if (stopping) return
        stopping = true
        isActive = false
        p2pManager.stopSink()
        rtspServer?.stop()
        wakeLock?.apply { if (isHeld) release() }
        wakeLock = null
        stopSelf()
    }

    private fun getDeviceName(): String {
        try {
            val dump = Runtime.getRuntime().exec(arrayOf("dumpsys", "wifi")).inputStream.bufferedReader().readText()
            Regex("wifi_p2p_device_name=([^\n\r]+)").find(dump)?.let {
                val name = it.groupValues[1].trim()
                if (name.isNotBlank()) return name
            }
        } catch (_: Exception) {}
        return Build.MODEL
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

    override fun onP2pGroupConnected(group: WifiP2pGroup, info: WifiP2pInfo) {
        val goIp = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
        Log.d(TAG, "Connected to P2P Group! GO IP: $goIp, IsGO: ${info.isGroupOwner}")
        
        // Restart RTSP server to bind after p2p0 interface is up
        thread {
            Thread.sleep(500)
            rtspServer?.stop()
            Thread.sleep(200)
            rtspServer = RtspServer(7236) {
                val playerIntent = Intent(this@MiracastService, PlayerActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(playerIntent)
            }
            rtspServer?.start()
            Log.d(TAG, "RTSP server restarted after P2P group formed")
        }
        
        // Self-test to verify RTSP is reachable
        thread {
            Thread.sleep(2000)
            try {
                val sock = java.net.Socket()
                sock.connect(java.net.InetSocketAddress(goIp, 7236), 3000)
                Log.i(TAG, "SELF-TEST: RTSP reachable on $goIp:7236")
                sock.close()
            } catch (e: Exception) {
                Log.e(TAG, "SELF-TEST FAIL: Cannot reach RTSP on $goIp:7236 - ${e.message}")
            }
        }
        
        // Get source device name from client list (source joins as P2P client)
        connectedDeviceName = group.clientList.firstOrNull()?.deviceName
            ?: group.owner?.deviceName
            ?: "Unknown"
        updateNotification("Connected to: $connectedDeviceName", true, false)
        
        // Open iptables so source can reach our RTSP server on p2p0
        thread {
            try {
                for (cmd in arrayOf(
                    "iptables -I INPUT -i p2p0 -p tcp --dport 7236 -j ACCEPT",
                    "iptables -I INPUT -i p2p0 -j ACCEPT",
                    "iptables -I FORWARD -i p2p0 -j ACCEPT",
                    "iptables -I FORWARD -o p2p0 -j ACCEPT",
                )) {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
                }
                Log.d(TAG, "iptables rules added for p2p0 (port 7236 open)")
            } catch (e: Exception) {
                Log.d(TAG, "iptables failed: ${e.message}")
            }
        }
        
        // Connect to source's RTSP and drive WFD handshake
        thread {
            Thread.sleep(1500)
            if (info.isGroupOwner) {
                // Sink is GO — find source (client) IP via ARP
                for (retry in 0..2) {
                    if (retry > 0) Thread.sleep(1000)
                    try {
                    val arpLines = java.io.File("/proc/net/arp").readLines()
                    for (line in arpLines) {
                        val parts = line.trim().split("\\s+".toRegex())
                        if (parts.size >= 4) {
                            val ip = parts[0]; val mac = parts[3].uppercase()
                            for (c in group.clientList) {
                                if (c.deviceAddress.uppercase() == mac && ip.startsWith("192.168.")) {
                                    Log.i(TAG, "Found source at $ip (ARP), starting RTSP handshake")
                                    startRtspHandshake(ip, goIp); return@thread
                                }
                            }
                        }
                    }
                } catch (e: Exception) { Log.e(TAG, "ARP failed: ${e.message}") }
                }
            } else {
                // Sink is client — source IS the GO, IP is known
                val sourceIp = goIp
                Log.i(TAG, "Sink is client, source (GO) at $sourceIp, starting RTSP handshake")
                startRtspHandshake(sourceIp, goIp)
            }
        }
    }

    private fun startRtspHandshake(sourceIp: String, sinkIp: String) {
        val sock = java.net.Socket()
        try {
            sock.connect(java.net.InetSocketAddress(sourceIp, 7236), 5000)
            sock.soTimeout = 10000
            Log.i(TAG, "Connected to source Miracast at $sourceIp:7236")
            val input = sock.getInputStream().bufferedReader(java.nio.charset.StandardCharsets.UTF_8)
            val output = sock.getOutputStream()
            var cseq = 1; var streamUrl = "rtsp://$sourceIp/wfd1.0/streamid=0"
            var setupSent = false; var playSent = false; var sessionId = "1"

            fun send(msg: String) { Log.d(TAG, ">> ${msg.replace("\r\n", " | ").take(150)}"); output.write(msg.toByteArray()); output.flush() }
            fun readMsg(): Triple<String, Map<String, String>, String> {
                val line = input.readLine() ?: return Triple("", emptyMap(), "")
                val hdrs = mutableMapOf<String, String>(); var cl = 0
                while (true) { val h = input.readLine() ?: break; if (h.isEmpty()) break; val kv = h.split(":", limit = 2); if (kv.size == 2) { hdrs[kv[0].trim()] = kv[1].trim(); if (kv[0].equals("Content-Length", true)) cl = kv[1].trim().toIntOrNull() ?: 0 } }
                val body = if (cl > 0) { val b = CharArray(cl); var t = 0; while (t < cl) { val n = input.read(b, t, cl - t); if (n <= 0) break; t += n }; String(b, 0, t) } else ""
                Log.d(TAG, "<< $line${if (body.isNotEmpty()) " [${body.take(100)}]" else ""}"); return Triple(line, hdrs, body)
            }

            val sinkCap = "wfd_video_formats: 00 00 01 10 0001bde1 00300000 000003c0\r\nwfd_audio_codecs: LPCM 00000002 00\r\nwfd_client_rtp_ports: RTP/AVP/UDP;unicast 15550 0 mode=play\r\nwfd_uibc_capability: none\r\n"
            var myOptionsSent = false; var gotM4 = false

            while (true) {
                val (line, hdrs, body) = readMsg()
                if (line.isEmpty()) break
                when {
                    line.startsWith("OPTIONS") -> {
                        send("RTSP/1.0 200 OK\r\nCSeq: ${hdrs["CSeq"]}\r\nPublic: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER\r\n\r\n")
                        if (!myOptionsSent) { Thread.sleep(80)
                            send("OPTIONS * RTSP/1.0\r\nCSeq: ${cseq++}\r\nRequire: org.wfa.wfd1.0\r\n\r\n"); myOptionsSent = true }
                    }
                    line.startsWith("RTSP/1.0 200") && !gotM4 -> { gotM4 = true }
                    line.startsWith("GET_PARAMETER") -> send("RTSP/1.0 200 OK\r\nCSeq: ${hdrs["CSeq"]}\r\nContent-Type: text/parameters\r\nContent-Length: ${sinkCap.length}\r\n\r\n$sinkCap")
                    line.startsWith("SET_PARAMETER") -> {
                        send("RTSP/1.0 200 OK\r\nCSeq: ${hdrs["CSeq"]}\r\n\r\n")
                        if (body.contains("wfd_trigger_method: TEARDOWN")) { Log.i(TAG, "Source requested TEARDOWN"); break }
                        if (body.contains("wfd_presentation_URL:")) { streamUrl = body.substringAfter("wfd_presentation_URL:").trim().lineSequence().firstOrNull()?.trim()?.replace(" none", "") ?: streamUrl; if (streamUrl.contains("255.255.255.255")) streamUrl = streamUrl.replace("255.255.255.255", sourceIp) }
                        if (body.contains("wfd_trigger_method: SETUP")) {
                            if (!setupSent) { Thread.sleep(80); send("SETUP $streamUrl RTSP/1.0\r\nCSeq: ${cseq++}\r\nTransport: RTP/AVP/UDP;unicast;client_port=15550-15551\r\n\r\n"); setupSent = true }
                        }
                    }
                    line.startsWith("RTSP/1.0 200") && setupSent && !playSent -> {
                        val sess = hdrs["Session"]; if (sess != null) { sessionId = sess.split(";").first().trim() }
                        val transport = hdrs["Transport"]
                        Log.i(TAG, "SETUP OK — Session: $sessionId, Transport: ${transport?.take(60) ?: "N/A"}")
                        startActivity(Intent(this@MiracastService, PlayerActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        Thread.sleep(200)
                        send("PLAY $streamUrl RTSP/1.0\r\nCSeq: ${cseq++}\r\nSession: $sessionId\r\n\r\n"); playSent = true
                    }
                    line.startsWith("RTSP/1.0 200") && playSent -> {
                        Log.i(TAG, "RTSP handshake complete — playback active")
                        sock.soTimeout = 0
                        Handler(Looper.getMainLooper()).post {
                            updateNotification("Streaming from: $connectedDeviceName", true, true)
                        }
                    }
                    line.startsWith("TEARDOWN") -> { send("RTSP/1.0 200 OK\r\nCSeq: ${hdrs["CSeq"]}\r\n\r\n"); break }
                }
            }
            Log.i(TAG, "RTSP session ended — returning to dashboard")
            Handler(Looper.getMainLooper()).post {
                updateNotification("Miracast Sink is active", false, false)
            }
            sendBroadcast(Intent("foxlost.miracast.SESSION_END"))
            startActivity(Intent(this@MiracastService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) { Log.e(TAG, "RTSP error: ${e.message}") }
        finally { try { sock.close() } catch (e: Exception) {} }
    }

    override fun onP2pGroupDisconnected() {
        Log.d(TAG, "Disconnected from P2P Group")
        updateNotification("Miracast Sink is active", false, false)
    }

    override fun onDiscoveryStateChanged(active: Boolean) {
        Log.d(TAG, "Discovery state: $active")
    }
}
