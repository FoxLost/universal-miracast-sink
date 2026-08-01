# P2P Package (`p2p`)

Wi-Fi Direct and Wi-Fi Display setup — P2P discovery, WFD Information Element injection, and wpa_supplicant communication.

## Files

### `P2pManager.kt` — P2P/WFD Lifecycle Manager

Manages the Wi-Fi Direct sink lifecycle: starting discovery, injecting WFD capabilities into beacons, and reporting connection state changes.

**Key flows**:

1. **startSink()**: Disables framework `WifiDisplayController` (`wifi_display_on=0`), calls `chainWfdInfo()` to set WFD capabilities
2. **chainWfdInfo()**: Tries two methods to set WFD info:
   - **Method A**: Reflection-based `setWfdInfo()` on `WifiP2pManager` (works with platform-signed system app)
   - **Method B**: Root fallback — `app_process` execution of `SupplicantWriter` to inject `WFD_SUBELEM_SET` directly into wpa_supplicant
3. **Force GO role**: Calls `createGroup()` on the P2P channel. `startDiscoveryLoop()` is gated inside the createGroup callbacks (onSuccess/onFailure) with a 5s timeout fallback — this ensures the sink acts as GO rather than joining a foreign group as a client
4. **startDiscoveryLoop()**: Schedules `discoverPeers()` every 10 seconds via Handler. On success, notifies `onDiscoveryStateChanged(true)`
5. **requestConnectionInfo()**: Queries P2P group and connection info. When group is formed, stops discovery and notifies `onP2pGroupConnected(group, info)`

**WFD Information Element**:
```
WFD IE hex: 0151 001C 4432
  Byte 0-1: 0151 — Device type (primary sink, session available, content protection support, P2P connectivity)
  Byte 2-3: 001C — RTSP control port (7236 in WFD session context; IE byte interpretation is spec-version dependent)
  Byte 4:   44   — Session availability
  Byte 5:   32   — Max throughput = 50 Mbps (0x32 = 50 decimal)
```

Full `WFD_SUBELEM_SET` command: `00060151001C4432`

**Interface**:
```kotlin
interface P2pListener {
    fun onP2pGroupConnected(group: WifiP2pGroup, info: WifiP2pInfo)
    fun onP2pGroupDisconnected()
    fun onDiscoveryStateChanged(active: Boolean)
}
```

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
