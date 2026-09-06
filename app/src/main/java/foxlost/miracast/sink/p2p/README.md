# P2P Package (`p2p`)

Wi-Fi Direct and Wi-Fi Display setup — P2P discovery, WFD Information Element injection, and wpa_supplicant communication.

## Files

### `P2pManager.kt` — P2P/WFD Lifecycle Manager

Manages the Wi-Fi Direct sink lifecycle: starting discovery, injecting WFD capabilities into beacons, and reporting connection state changes.

**Key flows**:

1. **startSink(generation)**: Disables framework `WifiDisplayController` (`wifi_display_on=0`), assigns a generation-scoped P2P attempt token, and calls `chainWfdInfo()` to set WFD capabilities.
2. **chainWfdInfo()**: Tries two methods to set WFD info:
   - **Method A**: Reflection-based `setWfdInfo()` on `WifiP2pManager` (works with platform-signed system app)
   - **Method B**: Root fallback — `app_process` execution of `SupplicantWriter` to inject `WFD_SUBELEM_SET` directly into wpa_supplicant
3. **Force GO role**: Calls `createGroup()` on the P2P channel. `startDiscoveryLoop()` is gated inside the createGroup callbacks (onSuccess/onFailure) with a 5s timeout fallback — this ensures the sink acts as GO rather than joining a foreign group as a client
4. **LISTEN/monitor serialization**: Framework listen, extended listen, discovery, and the optional supplicant monitor have one owner. A group-started event stops all pre-group work before readiness polling; duplicate events are ignored. If framework `startListening()` reports asynchronous failure, the manager tears down listen/monitor state and falls back to discovery.
5. **Group/network readiness**: `P2P-GROUP-STARTED` is only a candidate. Each readiness poll refreshes group info and connection info in the Android-required order, then waits up to 6s for a formed group, a peer, an UP P2P interface, and a non-loopback IPv4 address before notifying the service.
6. **Bounded retry**: A failed formation, readiness timeout, group loss, invitation fallback failure/stall, or pre-RTSP control-connect failure cleans up the old token, waits up to 2s for `requestGroupInfo()` to report no group, and retries after 500ms, at most three total attempts. Delayed callbacks from an old generation or attempt are ignored.
7. **requestConnectionInfo()**: Queries the framework in its required order and notifies `onP2pGroupConnected(group, info, attemptId)` only after readiness.

**WFD Information Element:**
`00111C440032` is the canonical default payload: `0011` device info,
`1C44` session availability/throughput fields, and `0032` max throughput.
At runtime `P2pManager` generates this from the framework `setWfdInfo()` API
and the configured RTSP control port; it is not a hardcoded Xiaomi payload.
The optional supplicant fallback is used when the framework call throws or its asynchronous `ActionListener` reports failure; setup is guarded so only one framework-or-fallback path runs.

**Interface**:
```kotlin
interface P2pListener {
    fun onP2pGroupConnected(group: WifiP2pGroup, info: WifiP2pInfo, attemptId: Long)
    fun onP2pGroupDisconnected(attemptId: Long)
    fun onP2pAttemptFailed(attemptId: Long, reason: String) {}
    fun onP2pAttemptExhausted(attemptId: Long, reason: String) {}
    fun onDiscoveryStateChanged(active: Boolean)
}
```

`WifiP2pManager` is asynchronous: group and connection details are requested only
after a group indication, and RTSP is not started from the indication alone. This
matches the Android API contract: [WifiP2pManager](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager)
documents `requestGroupInfo()` followed by `requestConnectionInfo()`. If a future
implementation adds `ConnectivityManager.NetworkCallback`, it must register each
callback at most once and unregister it during cleanup, as required by
[NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback).

### `P2pReceiver.kt` — P2P Connection Broadcast Receiver

Listens for `WIFI_P2P_CONNECTION_CHANGED_ACTION` broadcasts. When the network info shows connected, triggers `P2pManager.requestConnectionInfo()`. When disconnected, also triggers request to handle the disconnected state.

### `SupplicantWriter.kt` — Root wpa_supplicant DGRAM Writer

A standalone `app_process` executable that communicates with wpa_supplicant via Unix domain socket (DGRAM).

**Usage**: `app_process / foxlost.miracast.sink.p2p.SupplicantWriter <socket> <cmd1> [cmd2...]`

**How it works**:
1. Creates a SOCK_DGRAM `LocalSocketImpl` via reflection
2. Connects to the wpa_supplicant control socket (`/data/vendor/wifi/wpa/sockets/p2p0`)
3. Sends wpa_supplicant commands (one per `\n`-terminated line)
4. Reads response and prints to stdout

This bypasses the Android Wi-Fi HAL entirely, injecting WFD settings directly at the wpa_supplicant level.

## Port Assignments

| Port | Purpose |
|---|---|
| 7236 | RTSP control (TCP) — advertised in WFD IE |
| 15550 | RTP video (UDP) |
| 15551 | RTP audio (UDP) |
| 1902 | Source RTP server port (from SETUP response) |

## iptables Rules (applied by MiracastService)

```
iptables -I INPUT -i p2p0 -p tcp --dport 7236 -j ACCEPT
iptables -I INPUT -i p2p0 -j ACCEPT
iptables -I FORWARD -i p2p0 -j ACCEPT
iptables -I FORWARD -o p2p0 -j ACCEPT
```

These open the p2p0 interface for RTSP control and RTP data flow.
