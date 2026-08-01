package foxlost.miracast.sink.p2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pManager

class P2pReceiver(private val p2pManager: P2pManager) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                if (networkInfo?.isConnected == true) {
                    p2pManager.requestConnectionInfo()
                } else {
                    p2pManager.requestConnectionInfo() // will trigger disconnected callback
                }
            }
        }
    }
}
