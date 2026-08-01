package foxlost.miracast.sink.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread

class P2pManager(private val context: Context, private val listener: P2pListener) {
    private val TAG = "MiracastP2P"
    private val wifiP2pManager: WifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel = wifiP2pManager.initialize(context, context.mainLooper, null)
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isMiracastEnabled = false
    private var discoveryRunnable: Runnable? = null

    interface P2pListener {
        fun onP2pGroupConnected(group: WifiP2pGroup, info: WifiP2pInfo)
        fun onP2pGroupDisconnected()
        fun onDiscoveryStateChanged(active: Boolean)
    }

    @SuppressLint("MissingPermission")
    fun startSink() {
        Log.d(TAG, "Starting Miracast Sink...")
        isMiracastEnabled = true
        
        // Disable framework WFD takeover so WifiDisplayController doesn't hijack our session
        try {
            android.provider.Settings.Global.putInt(context.contentResolver, "wifi_display_on", 0)
            Log.d(TAG, "Disabled framework WifiDisplayController")
        } catch (e: Exception) {
            Log.d(TAG, "Failed to disable WifiDisplayController: ${e.message}")
        }
        
        chainWfdInfo()
    }

    @SuppressLint("MissingPermission")
    fun stopSink() {
        Log.d(TAG, "Stopping Miracast Sink...")
        isMiracastEnabled = false
        stopDiscoveryLoop()
        try { wifiP2pManager.removeGroup(channel, null) } catch (e: Exception) {}
    }

    private fun chainWfdInfo() {
        var setWfdSucceeded = false
        
        // Method A: Public setWfdInfo (works if signed with platform key / system priv-app)
        try {
            val wfdInfoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val ctor = wfdInfoClass.getDeclaredConstructor(
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            val wfdInfo = ctor.newInstance(0x0151, 7236, 50)

            val method = try {
                WifiP2pManager::class.java.getMethod("setWfdInfo", WifiP2pManager.Channel::class.java, wfdInfoClass, WifiP2pManager.ActionListener::class.java)
            } catch (e: Exception) {
                WifiP2pManager::class.java.getMethod("setWFDInfo", WifiP2pManager.Channel::class.java, wfdInfoClass, WifiP2pManager.ActionListener::class.java)
            }
            method.invoke(wifiP2pManager, channel, wfdInfo, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "setWfdInfo SUCCESS!")
                    setWfdSucceeded = true
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "setWfdInfo FAILED reason=$reason")
                }
            })
        } catch (e: Exception) {
            val err = if (e is java.lang.reflect.InvocationTargetException) e.targetException?.toString() else e.toString()
            Log.e(TAG, "WFD Info: $err")
        }

        // Method B: Fallback – app_process root injection (DGRAM supplicant)
        if (!setWfdSucceeded) tryRootWfdSetup()

        // Force autonomous GO so sink is always the Group Owner
        var createGroupDone = false
        try {
            @SuppressLint("MissingPermission")
            Log.d(TAG, "Calling createGroup to force GO role...")
            wifiP2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "createGroup SUCCESS — sink is now GO")
                    createGroupDone = true
                    startDiscoveryLoop()
                }
                override fun onFailure(reason: Int) {
                    Log.e(TAG, "createGroup FAILED reason=$reason")
                    createGroupDone = true
                    startDiscoveryLoop()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "createGroup error: ${e.message}")
            createGroupDone = true
            startDiscoveryLoop()
        }
        // Timeout: if createGroup doesn't respond in 5s, start discovery anyway
        mainHandler.postDelayed({
            if (!createGroupDone) {
                Log.w(TAG, "createGroup timed out — starting discovery anyway")
                startDiscoveryLoop()
            }
        }, 5000)
    }
    
    private fun tryRootWfdSetup() {
        mainHandler.postDelayed({
            thread {
                try {
                    for (c in arrayOf(
                        "settings put global wifi_display_certification_on 1",
                        "settings put global hidden_api_policy 0",
                        "setprop persist.debug.wfd.enable 0",
                    )) { Runtime.getRuntime().exec(arrayOf("su", "-c", c)).waitFor() }
                } catch (e: Exception) {}

                val apkPath = context.packageCodePath
                val wfdHex = "00060151001C4432"
                for (sock in listOf(
                    "/data/vendor/wifi/wpa/sockets/p2p0",
                    "/data/vendor/wifi/wpa/sockets/wlan0",
                )) {
                    try {
                        val p = Runtime.getRuntime().exec(arrayOf(
                            "su", "-c",
                            "CLASSPATH=$apkPath app_process / foxlost.miracast.sink.p2p.SupplicantWriter $sock 'IFNAME=p2p0 SET wifi_display 1' 'IFNAME=p2p0 WFD_SUBELEM_SET 0 $wfdHex' 2>&1"
                        ))
                        p.waitFor()
                        val out = p.inputStream.bufferedReader().readText().trim()
                        Log.d(TAG, "Root writer $sock: $out")
                        if (out.contains("OK")) break
                    } catch (e: Exception) {}
                }
                Log.d(TAG, "Root WFD injection complete")
            }
        }, 3000)
    }

    @SuppressLint("MissingPermission")
    private fun startDiscoveryLoop() {
        stopDiscoveryLoop()
        if (!isMiracastEnabled) return
        
        discoveryRunnable = object : Runnable {
            override fun run() {
                if (!isMiracastEnabled) return
                wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        listener.onDiscoveryStateChanged(true)
                    }
                    override fun onFailure(reason: Int) {
                        listener.onDiscoveryStateChanged(false)
                    }
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
        listener.onDiscoveryStateChanged(false)
    }
    
    @SuppressLint("MissingPermission")
    fun requestConnectionInfo() {
        wifiP2pManager.requestGroupInfo(channel) { group ->
            if (group != null) {
                wifiP2pManager.requestConnectionInfo(channel) { info ->
                    if (info != null && info.groupFormed) {
                        stopDiscoveryLoop()
                        listener.onP2pGroupConnected(group, info)
                    } else {
                        listener.onP2pGroupDisconnected()
                        if (isMiracastEnabled) startDiscoveryLoop()
                    }
                }
            } else {
                listener.onP2pGroupDisconnected()
                if (isMiracastEnabled) startDiscoveryLoop()
            }
        }
    }
}
