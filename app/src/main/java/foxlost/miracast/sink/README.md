# Core Package (`foxlost.miracast.sink`)

The application package contains the dashboard, foreground service, adaptive session controller, and fullscreen player.

## Files

### `MainActivity.kt` — Dashboard and diagnostics

Jetpack Compose dashboard for starting/stopping the sink, editing persisted settings, and viewing a compact live diagnostic panel. Runtime permissions are requested for location and, on Android 13+, nearby Wi-Fi devices.

The **Activity** panel retains the newest 12 in-memory events and renders at most four rows. Rows show only `category / message`; timestamps are intentionally not displayed. Starting a new sink session clears the previous tail. This is diagnostic telemetry, not durable logging or an event bus.

### `MiracastService.kt` — Foreground service

The service owns the session generation, RTSP server, P2P manager, media hand-off, notification, and cleanup. It calls `startForeground()` for every command path, starts the configured RTSP listener (default TCP 7236), and starts P2P work only for the current generation. A wake lock is held while the sink is active.

On a ready P2P group, the service resolves the source address from the peer MAC and ARP/procfs tables, applies the p2p0 firewall rules, and connects to the source's advertised RTSP control port. Pre-RTSP connect failures are reported to P2P so the bounded attempt policy can retry. RTP receiver binding is awaited for up to 5 seconds before SETUP continues.

Cleanup invalidates the session generation before closing RTSP/media resources, broadcasts `foxlost.miracast.SESSION_END` to finish `PlayerActivity`, releases the wake lock, stops P2P, and stops the service. The `stopping` guard makes cleanup idempotent and prevents late callbacks from reopening media.

### `AdaptiveSession.kt` — Generation/state controller

Each start creates a monotonically increasing generation. The controller accepts only callbacks belonging to the current generation and attempt, and ignores stale callbacks after reset/stop.

States are:

`Idle → P2pStarting → Discovering → GroupForming → GroupFormed → ControlConnecting/ControlAccepted → Negotiating → TransportReady → MediaStarting → Streaming → Stopping`.

The source owns the RTSP server in both P2P role arrangements, so the sink uses an outbound RTSP connection. Profiles are selected from observed source evidence (Windows, Android, or unknown) and control timing/capability details such as the optional second PLAY.

### `PlayerActivity.kt` — Fullscreen media surface

Receives the negotiated RTP/RTCP ports and session generation, prepares a `TextureView`, decodes H.264 with the media pipeline, and plays LPCM audio when enabled. It applies immersive fullscreen behavior and finishes when the service broadcasts `SESSION_END`.

## Session lifecycle and retry behavior

`P2pManager` serializes framework LISTEN, extended LISTEN, discovery, and the optional supplicant monitor. It prefers framework `startListening()` and falls back to peer discovery if reflection or the asynchronous action fails. A group-started indication is only a candidate: readiness polling refreshes group info followed by connection info and waits for a formed group, peer, UP P2P interface, and non-loopback IPv4 address.

Readiness is polled every 250 ms for up to 6 seconds. Failed formation, readiness timeout, group loss, invitation fallback/stall, and pre-RTSP control-connect failures invalidate the attempt, wait up to 2 seconds for group cleanup, then retry after 500 ms. There are at most three attempts per session generation. When all attempts fail, the service reports exhaustion and finishes the session. Delayed callbacks from prior generations/attempts are ignored.

## Telemetry categories

`DebugEventLog` is thread-safe and process-local. It retains a maximum of 12 events, evicts oldest entries, clears deduplication/rate-limit state on session start, and does not persist or export data.

| Category | Typical events |
|---|---|
| `SINK` | service/session start and stop |
| `P2P` | discovery, group candidates, group formation/loss, attempt reset |
| `WFD` | framework or supplicant WFD setup, profile selection, parameters |
| `DHCP` | source address resolution and interface readiness |
| `RTSP` | connection, requests/responses, negotiation, transport, teardown |
| `RTP` | receiver bind, first packet, packet counters, stop |
| `MEDIA` | decoder/audio start, SPS changes, first decoded frame |
| `ERROR` | bind, readiness, address, protocol, and decoder failures |

One-shot events are deduplicated per key. Noisy events use a 5-second default rate limit (or an explicit interval), so packet/error paths remain bounded.

## WFD and RTSP handshake

The sink advertises the runtime-generated WFD payload `00111C440032` (device information, session availability, and 50 Mbps maximum throughput), using the framework API or the root supplicant fallback. The configured RTSP control port is advertised rather than a fixed vendor payload.

The source initiates the reverse-RTSP exchange. The sink responds to M1 OPTIONS, sends its M2 OPTIONS, answers capability GET/SET_PARAMETER requests, sends SETUP with its local RTP ports, waits for media receiver readiness, parses the returned Session, and sends PLAY (including the configured Android-compatible second PLAY). TEARDOWN or group loss closes the session.

## Build, sign, and package

Requirements: Android SDK/build-tools 34.0.0, Gradle wrapper, and an AOSP platform key pair. `scripts/build.sh` expects `../platform_build-main/target/product/security/platform.pk8` and `platform.x509.pem` by default; set `PLATFORM_KEY_DIR` to override. It builds release, signs with the platform key, verifies the signature, and emits `app/build/outputs/apk/release/MiracastSink-v<VERSION>.apk` plus `MiracastSink.apk`.

```bash
./scripts/build.sh
./scripts/pack-magisk.sh
# output: magisk-miracast-sink-v<VERSION>-platform.zip
```

The packer extracts package/version metadata from the signed APK, updates `magisk-miracast-sink/module.prop`, copies the APK into `system/priv-app/MiracastSink/`, and creates the flashable zip. Install it from Magisk Manager or with:

```bash
adb push magisk-miracast-sink-v1.4-platform.zip /sdcard/
adb shell su -c 'magisk --install-module /sdcard/magisk-miracast-sink-v1.4-platform.zip'
```

`customize.sh` rejects a module without the signed APK and clears stale PackageManager, ART, and launcher icon caches during installation. Reboot after installing. Do not deploy this documentation workflow directly to a device without root/Magisk and platform-compatible signing.

## Verification notes

The repository contains JVM tests for the adaptive session controller, retry/readiness predicates, telemetry bounds, service launch/cleanup helpers, protocol parsing, RTP, and MPEG-TS behavior. Run the targeted tests and a release build in an environment with the Android SDK and platform keys. Device-level Miracast interoperability remains hardware/vendor dependent; this package has no emulator substitute for Wi-Fi P2P, supplicant, firewall, RTSP, and decoder behavior. Diagnostic captures are read-only helpers and do not themselves start a connection.
