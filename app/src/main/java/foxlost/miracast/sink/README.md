# Core Package (`foxlost.miracast.sink`)

The main application package containing the service, activities, and WFD handshake logic.

## Files

### `MainActivity.kt` — Dashboard UI

Jetpack Compose-based dashboard showing:
- Sink device name (read from wpa_supplicant via `dumpsys wifi`)
- Service status ("Inactive" vs "Ready to accept")
- Connect-to guide text
- START/STOP Miracast Sink button (toggles foreground service)
- Footer credit: "Made with Free Time and Free Will by FoxLost"

### `MiracastService.kt` — Foreground Service

The central orchestrator. Runs as a foreground service with a persistent notification.

**Responsibilities**:
- **Startup**: Calls `startForeground()` unconditionally at top of `onStartCommand` (satisfies Android 14+ FGS contract for all action types — START, STOP, and DISCONNECT), creates notification channel, starts RTSP server on port 7236, runs root setup script (hidden API bypass, iptables flush), initializes P2P discovery
- **P2P lifecycle**: Receives `onP2pGroupConnected` with group info, finds source IP from ARP table (`/proc/net/arp`), opens iptables rules for p2p0
- **WFD handshake** (`startRtspHandshake`): Connects TCP to source:7236, drives M1-M7 RTSP negotiation, starts PlayerActivity after SETUP 200 OK, sends PLAY with parsed Session ID
- **Streaming**: Keeps RTSP socket alive (`soTimeout=0`), handles GET_PARAMETER keepalive, TEARDOWN trigger
- **Cleanup**: Uses `stopping` flag to make `stopSinkAndCleanup()` idempotent (prevents duplicate teardown on double-delivered DISCONNECT actions), releases wake lock, stops P2P, sends `SESSION_END` broadcast to finish PlayerActivity
- **Notification**: Updates with "Connected to: [device name]" + Disconnect button during streaming
- **Wake lock**: `SCREEN_BRIGHT_WAKE_LOCK` to keep screen on

**Constants exposed**:
| Constant | Value | Purpose |
|---|---|---|
| `ACTION_START` | "ACTION_START" | Start Miracast sink service |
| `ACTION_STOP` | "ACTION_STOP" | Stop Miracast sink service |
| `ACTION_DISCONNECT` | "ACTION_DISCONNECT" | Disconnect from notification button |
| `CHANNEL_ID` | "MiracastSinkChannel" | Notification channel |
| `isActive` | Boolean | Static flag for service state (read by dashboard) |

**Key flow**: `onStartCommand` → start RTSP → root setup → P2P start → `onP2pGroupConnected` → restart RTSP → ARP lookup → `startRtspHandshake` → M1-M7 → PLAY → streaming → TEARDOWN → cleanup

### `PlayerActivity.kt` — Video Playback Surface

Displays the decoded Miracast video stream in immersive fullscreen.

**Features**:
- Uses `TextureView` with `SurfaceTextureListener` for proper view hierarchy integration
- **Immersive fullscreen**: Hides both status bar and gesture navigation bar via `WindowInsetsController` (API 30+) or `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` (older APIs); reapplied in `onResume`
- **Edge-to-edge rendering**: Theme uses translucent navigation/status bars so video draws behind system bars when they transiently appear on swipe
- Resizes viewport dynamically: `maxOf` (crop to fill) in portrait, `minOf` (fit) in landscape
- `onConfigurationChanged` detects rotation and adjusts view size
- Receives `SESSION_END` broadcast to auto-finish on disconnect
- Video dimensions detected from MediaCodec output format and applied to `SurfaceTexture.setDefaultBufferSize()`

## WFD Handshake Details

The handshake (`startRtspHandshake` in MiracastService) is a carefully ordered RTSP exchange matching real Android Wi-Fi Display behavior:

1. **TCP connect** to source:7236 (sink acts as RTSP client)
2. **Wait** for source's M1 OPTIONS (source initiates)
3. **Respond** 200 OK with `Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER`
4. **Send** sink's OPTIONS (M2) with `Require: org.wfa.wfd1.0`
5. **Respond** to M3 GET_PARAMETER with sink capabilities
6. **Respond** to M5a SET_PARAMETER (source caps + presentation URL)
7. **Respond** to M5b SET_PARAMETER (trigger_method: SETUP)
8. **Send** SETUP with `Transport: RTP/AVP/UDP;unicast;client_port=15550-15551`
9. **Parse** Session from SETUP response
10. **Start** PlayerActivity (RTP receiver prepares)
11. **Send** PLAY with parsed Session
12. **Set** `soTimeout=0` (infinite, keep socket alive)
13. **Handle** TEARDOWN trigger → break loop → broadcast `SESSION_END`

## Capabilities Advertised

```
wfd_video_formats: 00 00 01 10 0001bde1 00300000 000003c0
wfd_audio_codecs: LPCM 00000002 00
wfd_client_rtp_ports: RTP/AVP/UDP;unicast 15550 0 mode=play
wfd_uibc_capability: none
```

- Resolutions: up to 1080p60 (CEP profile 3.1)
- Audio: LPCM 48kHz 2ch 16-bit
- RTP: Video on port 15550, Audio on port 15551

## Notification States

| State | Text | Disconnect Button | Tap Target |
|---|---|---|---|
| Idle | "Miracast Sink is active" | No | MainActivity |
| Connected | "Connected to: [Device]" | Yes | MainActivity |
| Streaming | "Streaming from: [Device]" | Yes | PlayerActivity |
