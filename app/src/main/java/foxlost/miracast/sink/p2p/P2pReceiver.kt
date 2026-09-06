package foxlost.miracast.sink.p2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager

class P2pReceiver(private val p2pManager: P2pManager) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Framework's authoritative group/network transition. Some
            // vendor builds omit this for persistent invitations; the
            // supplicant P2P-GROUP-STARTED fallback is handled by P2pManager.
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                p2pManager.requestConnectionInfo()
            }
            // Real framework peer-list signal used for WFD discovery
            // telemetry. It must not issue an implicit connect because an
            // incoming persistent invitation may already be in flight.
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                p2pManager.onPeersChanged()
            }
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(
                    WifiP2pManager.EXTRA_WIFI_STATE,
                    WifiP2pManager.WIFI_P2P_STATE_DISABLED,
                )
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    p2pManager.requestConnectionInfo()
                }
            }
            WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                val state = intent.getIntExtra(
                    WifiP2pManager.EXTRA_DISCOVERY_STATE,
                    WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED,
                )
                android.util.Log.d(
                    "MiracastP2P",
                    "Framework P2P discovery ${if (state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED) "started" else "stopped"}",
                )
            }
            // Vendor-only connection-request notifications retained as a
            // fallback for builds that expose the headless authorization hook.
            "android.net.wifi.p2p.CONNECTION_REQUEST_ACCEPT",
            "android.net.wifi.p2p.CONNECTION_REQUEST" -> {
                android.util.Log.i("MiracastP2P", "Incoming connection request — auto-accepting")
                p2pManager.autoAcceptConnection()
            }
        }
    }
}
