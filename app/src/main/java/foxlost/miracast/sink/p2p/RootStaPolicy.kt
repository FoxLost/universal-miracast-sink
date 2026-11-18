package foxlost.miracast.sink.p2p

import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager

/** Uses the same current-connection API on legacy and current Android; no hidden getter. */
@Suppress("DEPRECATION", "MissingPermission")
internal class RootStaPolicy(private val wifi: WifiManager) : RootStaIsolation.Policy {
    override fun read(): RootStaIsolation.Snapshot {
        val enabled = wifi.isWifiEnabled
        if (!enabled) return RootStaIsolation.Snapshot(false, -1, active = false, idle = false)
        val info = checkNotNull(wifi.connectionInfo) { "Current STA connection state unavailable" }
        val state = checkNotNull(info.supplicantState) { "Current STA supplicant state unavailable" }
        val active = when (state) {
            SupplicantState.AUTHENTICATING, SupplicantState.ASSOCIATING,
            SupplicantState.ASSOCIATED, SupplicantState.FOUR_WAY_HANDSHAKE,
            SupplicantState.GROUP_HANDSHAKE, SupplicantState.COMPLETED -> true
            else -> false
        }
        val idle = state == SupplicantState.DISCONNECTED || state == SupplicantState.INACTIVE
        check(info.networkId < 0 || (state != SupplicantState.INVALID && state != SupplicantState.UNINITIALIZED)) {
            "Current STA supplicant state unknown"
        }
        return RootStaIsolation.Snapshot(enabled, info.networkId, active, idle)
    }

    override fun disconnect(): Boolean = wifi.disconnect()
    override fun reconnect(): Boolean = wifi.reconnect()
}
