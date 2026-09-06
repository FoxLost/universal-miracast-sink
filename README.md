# Universal Miracast Sink

A system-level Miracast (Wi-Fi Display) receiver for Android 10+, deployed as a privileged system app via Magisk. Turns any Android device into a wireless display sink.

**Current version**: v1.4 (versionCode 5)

## Little story about this project:
I have Xiaomi Pad 6 that has Miracast Sink mode built in to devices (com.xiaomi.miralink) that works on newer android 14 (HyperOS 2) and i want to implement that so my Second devices can become like viewfinder when using camera and another,
so i try to get many data like decompile apk from xiaomi miralink, decompile firmware from my android box (x96 mini) that has firmware android 7 and android 9 build that support Miracast Sink, and capture the wifi direct communication with
my usb wifi that support monitoring mode, after that i spend roughly 1 week vibe coding the app with all the data that i can find, try using root methode send command to android hidden api, using xposed to hook system_server, and
try using magisk module to put the apk on system so the app can get android hidden api directly, and that finally the app working and my device can be datacted as miracast sink display, so here the project i publish

## Screenshots

| Dashboard | Streaming |
|-----------|-----------|
| ![Dashboard](docs/images/screenshot.jpg) | ![Streaming](docs/images/device-photo.jpg) |

## Architecture

```
MainActivity (Jetpack Compose Dashboard)
    |
    v
MiracastService (Foreground Service)
    |-- P2pManager ----- P2P discovery, WFD IE injection, group management
    |   |-- P2pReceiver ---- BroadcastReceiver for P2P state changes
    |   `-- SupplicantWriter - Root-level wpa_supplicant DGRAM injection
    |
    |-- RtspServer ------ Inbound RTSP server on port 7236
    |   `-- RtspConnection -- Per-connection RTSP handling
    |
    |-- startRtspHandshake() -- Sink-to-Source RTSP client (WFD M1-M7)
    |
    `-- PlayerActivity -- Media playback (immersive fullscreen)
        |-- RtpReceiver ---- UDP RTP packet reception
        |-- TsDemuxer ------ MPEG-TS demux (H.264 video + LPCM audio)
        `-- MediaDecoderPipeline -- HW H.264 decoder + AudioTrack
```

## Protocol Flow

The sink uses the **reverse RTSP model** (the standard WFD sink behaviour, matching
the Xiaomi Pad 6): the sink connects **to** the source's RTSP server on the
peer-advertised control port (default 7236) and pulls the stream. The source is
usually the P2P Group Owner.

```
Source Device (GO)                        Sink (This App, P2P client)
------------------                        ---------------------------
1. P2P Discovery -> WFD IE in beacons
2. GO Negotiation (sink intent 0 -> 8) -> Source becomes GO
                                          3. TCP connect to source:(control port)
   M1: OPTIONS * RTSP/1.0 ---------->    4. Respond 200 OK + Public: GET_PARAMETER,SET_PARAMETER
                                          5. Send OPTIONS (sink M2)
   <----------------------- 200 OK        6. (Public: SETUP,PLAY,TEARDOWN,PAUSE,GET_PARAMETER,SET_PARAMETER)
   M3: GET_PARAMETER (28 params) ---->   7. Respond 200 OK + full capability set
   M5: SET_PARAMETER (caps+trigger) ->    8. Respond 200 OK
                                          9. SETUP rtsp://source/wfd1.0/streamid=0
   <--------------- 200 OK Transport      10. (client_port, server_port, ssrc, Session)
                                          11. PLAY (+ second PLAY, Android convention)
   <------------------- 200 OK            12. RTP streaming begins
   ...                                    ...
   SET_PARAMETER (TEARDOWN trigger) ->    13. Stop playback, clean up
```

**Windows compatibility:** Windows Miracast sources only run their RTSP server when
they are the Group Owner. The sink therefore defaults to GO intent `0` (letting the
source win GO negotiation) and escalates to a configurable fallback intent on retry.
The legacy "sink as GO" mode remains available in Settings for sources that need it.

## Adaptive session lifecycle

Each sink start creates a generation-scoped session. `P2pManager` serializes framework LISTEN, extended LISTEN, discovery, and the optional supplicant monitor. A group-started indication is only a candidate: readiness polling refreshes group info followed by connection info and waits for a formed group, peer, UP `p2p0` interface, and non-loopback IPv4 address.

Readiness is polled every 250 ms for up to 6 seconds. Formation, readiness, group-loss, invitation fallback/stall, and pre-RTSP control-connect failures invalidate the attempt, wait up to 2 seconds for cleanup, and retry after 500 ms. There are at most three attempts per generation. Old generation/attempt callbacks are ignored; exhausted attempts finish the session rather than looping indefinitely.

## In-app debug Activity

The dashboard Activity panel is a bounded, process-local diagnostic view. `DebugEventLog` retains at most 12 events in memory and the UI renders the newest four rows. Rows display category and message only (no timestamp). Starting a new sink session clears the event tail and deduplication/rate-limit state; telemetry is not persisted or exported.

Categories are `SINK`, `P2P`, `WFD`, `DHCP`, `RTSP`, `RTP`, `MEDIA`, and `ERROR`. One-shot events are deduplicated, and noisy packet/error paths are rate-limited (5 seconds by default), keeping the panel bounded while exposing lifecycle, address resolution, negotiation, transport, and decoder failures.

The package-level implementation notes are in [`app/src/main/java/foxlost/miracast/sink/README.md`](app/src/main/java/foxlost/miracast/sink/README.md).

## Verification caveats

JVM tests cover the adaptive controller, retry/readiness predicates, telemetry bounds, service cleanup helpers, protocol parsing, RTP, and MPEG-TS behavior. A release build still requires Android SDK/build-tools 34.0.0 and the AOSP platform keys. Device-level P2P, supplicant, firewall, RTSP, and decoder interoperability remains hardware/vendor dependent; no emulator verification substitutes for that path.

The read-only `capture-debug.sh` helper collects logs and state but does not launch the app, enable the sink, alter Wi-Fi, or initiate a Miracast connection.

## Release workflow

The repository has no automated release workflow. The established release pattern is a GitHub release on `main` with a single Magisk asset named `magisk-miracast-sink-v<VERSION>.zip` (the v1.3 release is the reference). The local packer emits `magisk-miracast-sink-v<VERSION>-platform.zip`; copy/rename that exact output to the release asset name only when publishing.

The v1.4 release target is tag `v1.4` on `origin` with asset `magisk-miracast-sink-v1.4.zip`. Do not upload an unsigned APK or deploy to a device as part of the release process.

## Build, sign, and package

```bash
# Build and sign with the sibling AOSP platform key repository
./scripts/build.sh

# Pack the signed APK into a flashable Magisk module
./scripts/pack-magisk.sh
# output: magisk-miracast-sink-v1.4-platform.zip
```

`scripts/build.sh` expects `../platform_build-main/target/product/security/platform.pk8` and `platform.x509.pem` by default; set `PLATFORM_KEY_DIR` to override. It emits a verified `MiracastSink-v<VERSION>.apk` and `MiracastSink.apk`. The packer extracts APK metadata, updates `magisk-miracast-sink/module.prop`, copies the APK to `system/priv-app/MiracastSink/`, and creates the zip.

Install from Magisk Manager or with:

```bash
adb push magisk-miracast-sink-v1.4-platform.zip /sdcard/
adb shell su -c 'magisk --install-module /sdcard/magisk-miracast-sink-v1.4-platform.zip'
```

`customize.sh` rejects a module without the signed APK and clears stale PackageManager, ART, and launcher icon caches during installation. Reboot after installing.

## Technical Details

| Property | Value |
|---|---|
| Package | `foxlost.miracast.sink` |
| UID | 1000 (system), via `sharedUserId="android.uid.system"` |
| Signing | AOSP platform key (system certificate) |
| Deployment | Magisk module at `/system/priv-app/MiracastSink/` |
| Device name | Global settings `device_name` (overridable in Settings) |
| WFD IE | `00111C440032` (primary sink, session available, max throughput 50 Mbps) |
| GO intent | 0 (client, Windows default) → 8 (fallback), configurable |
| RTSP port | Configured TCP control port (default 7236) |
| RTP ports | 15550 (video), 15551 (RTCP) — UDP (configurable) |
| Video | H.264 hardware decoder (SPS-aware reconfigure) |
| Audio | LPCM 48kHz 2ch 16-bit, big-endian to little-endian conversion |

## Settings

The dashboard's **SETTINGS** panel persists every tunable via `SettingsManager`
(`SharedPreferences`). Changes take effect on the next session start.

| Setting | Default | Notes |
|---|---|---|
| Device name | system `device_name` | Advertised over P2P / WFD / `intel_friendly_name`. Resettable to system value |
| Manufacturer | `Build.MANUFACTURER` | Reported as `intel_sink_manufacturer_name` |
| RTSP port | 7236 | WFD control port |
| RTP video port | 15550 | UDP video (+1 for RTCP) |
| GO intent (client) | 0 | Source becomes GO (Windows path) |
| GO intent (fallback) | 8 | Sink becomes GO if the peer insists |
| Force sink as GO | off | Legacy Android-source path |
| Max video resolution | 1080p60 | Also 1080p30 / 720p60 presets |
| Audio (LPCM) | on | Advertise + play LPCM audio |
| UIBC | off | Touch back-channel (experimental) |
| Send second PLAY | on | Android double-PLAY convention |
| Max throughput | 50 Mbps | WFD IE max-throughput field |

The M4 `GET_PARAMETER` response answers every parameter the source requests
(including the `intel_*` and `microsoft_*` families Windows 11 queries) and omits
the `wfd2_*` family, mirroring the Xiaomi Pad 6 capability exchange byte-for-byte.


## Requirements

| Component | Purpose |
|---|---|
| Root + Magisk | System app deployment, iptables, root scripts |
| LSPosed (optional) | Alternate permission bypass path |
| Platform signing | `CONFIGURE_WIFI_DISPLAY` permission |
| Android 10+ (API 29+) | `minSdk 29` |

## Packages

| Package | Purpose |
|---|---|
| `foxlost.miracast.sink` | Core: Service, Activities, RTSP handshake |
| `p2p` | P2P/WFD: discovery, WFD IE, supplicant injection |
| `rtsp` | RTSP: server, connection handler, protocol |
| `media` | Media: RTP, MPEG-TS demux, H.264 decode, LPCM audio |
| `uibc` | UIBC: touch input backchannel (experimental) |
| `xposed` | LSPosed: framework permission bypass hooks |

## Logging

```bash
# Recommended — app + WiFi P2P framework logs, colour-coded:
./scripts/logcat.sh

# App only (no framework noise):
./scripts/logcat.sh app

# Clear buffer first, then tail:
./scripts/logcat.sh -c

# Snapshot current buffer:
./scripts/logcat.sh -s

# One-liner for your own scripts:
adb logcat -s $(./scripts/logcat.sh raw)
```

### Complete host debug capture

For a timestamped, read-only device capture that includes root `dmesg`,
all logcat buffers, app/framework filtering, and final state snapshots:

```bash
# Uses the only adb device, or set ANDROID_SERIAL when several are connected.
./scripts/capture-debug.sh

# Explicit device selection:
./scripts/capture-debug.sh -s <serial>

# Press Ctrl-C to stop. The script then collects final snapshots, creates
# capture-debug-<timestamp>.tar.gz, and writes its SHA-256 checksum.
```

The helper verifies adb and root access, saves pre-clear `dmesg`, logcat, and
read-only state, clears only volatile diagnostic buffers, then starts full
`dmesg` and logcat capture before any user action. If the device's `dmesg`
does not support `-wT`, it falls back to timestamped polling. Filtered,
line-buffered `dmesg` and logcat lines are printed to the same terminal and
saved beside the raw streams. It does not launch the app, enable the sink,
alter Wi-Fi, change interfaces, or initiate a Miracast connection.

Set `CAPTURE_OUT` to choose the output directory:

```bash
CAPTURE_OUT="$PWD/captures/retest-1" ./scripts/capture-debug.sh
```

The capture includes the existing app/media/Wi-Fi P2P/WFD tag inventory plus
kernel, `nl80211`, RTSP, RTP, and media-related keywords.

### Tag inventory

| Group | Tags |
|---|---|
| App | `MiracastApp`, `MiracastP2P`, `MiracastRTSP`, `MiracastUIBC`, `MiracastRoot`, `MiracastSettings` |
| Media | `TsDemuxer`, `RtpReceiver`, `MediaDecoderPipeline` |
| WiFi P2P (framework) | `WifiP2pService`, `WifiP2pManager`, `WifiP2pNative`, `SupplicantP2pIfaceHal`, `wpa_supplicant`, `HalDevMgr` |
| WFD (framework) | `WifiDisplaySink`, `WifiDisplaySource`, `wifi_display`, `WifiDisplayController`, `WfdSession` |
| UIBC (framework) | `UIBC`, `RemoteDisplay` |
