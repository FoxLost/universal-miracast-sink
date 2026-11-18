# Core Package (`foxlost.miracast.sink`)

The application package contains the dashboard, separate settings page, foreground service, adaptive session controller, and fullscreen player.

## Files

### `MainActivity.kt` — Dashboard and diagnostics

Jetpack Compose dashboard for starting/stopping the sink, returning to a live player, opening the separate Settings Activity through a gear icon, and viewing a taller live diagnostic panel. Runtime permissions are requested for location and, on Android 13+, nearby Wi-Fi devices. Active state is collected from the service rather than an optimistic button-local flag. Return appears only after both RTSP PLAY and the current player owner's UDP binding are confirmed; listening or a prepared endpoint is insufficient. Stop uses the acknowledged cleanup path.

The **Activity** panel retains the newest 12 in-memory events and exposes all of them through an independently scrollable, multiline list. Rows show only `category / message`; timestamps are intentionally not displayed. Starting a new sink session clears the previous tail. This is diagnostic telemetry, not durable logging or an event bus.
The log flow is collected inside that panel, so diagnostic events do not recompose the entire dashboard.

### `SessionNavigation.kt` — Active-player ownership

The service authorizes a generation/attempt/port snapshot and a unique owner lease. A retained player is reordered to the foreground instead of creating a second receiver. Stale bind/destroy callbacks cannot retire a replacement in the same generation. Destroying the current streaming player hides Return until media-only recovery rebinds the same authorized endpoints; it does not create a new P2P group or RTSP connection. Stop, failure and session replacement invalidate both the snapshot and lease.


### `SettingsActivity.kt` and `NavigationReset.kt` — Draft settings and acknowledged navigation

Settings uses saveable local drafts, separate Resolution/FPS dropdowns, and an Auto/2.4 GHz/5 GHz band dropdown. Devices reporting no 5 GHz hardware support offer only Auto/2.4 GHz; a previously stored unsupported choice must be changed before Save or session startup. Save validates and persists the draft; Close/Back discard it. The system-name option clears the override rather than freezing the current system name.

Every Main/Settings transition requests cleanup through a retained ViewModel and generation-scoped service acknowledgement. Entry also runs cleanup when Settings is recreated directly. Navigation waits for player/media retirement, actual root-owner restoration and P2P group absence; failure or a 65-second deadline exposes Retry instead of navigating. Pending actions survive rotation, completed actions are delivered only while resumed, and returning to Main does not restart the sink.

### `MiracastService.kt` — Foreground service

The service owns the session generation, outbound RTSP client, P2P manager, media hand-off, notification, and cleanup. Start/stop commands use the foreground-service path; generation-checked recovery commands route optional M13 IDR requests without recreating playback. P2P work and outbound RTSP startup belong only to the current generation; the advertised local RTSP port is not a local listener. A wake lock is held while the sink is active.

On a ready P2P group, the service resolves the source address from the peer MAC and ARP/procfs tables and connects to the source's advertised RTSP control port. Privileged ARP reads use the fixed session-owned root operation. The bridge launches only the fixed non-exported player Activity with validated generation/port extras, preserving background playback startup without overlay permission. Pre-RTSP connect failures are reported to P2P so the bounded attempt policy can retry. RTP receiver binding is awaited for up to 5 seconds before SETUP continues.
For a source client, peer identity is resolved through a readonly root P2P query before matching complete ARP entries on the selected group interface. Lookup and outbound TCP share the 90-second startup budget; generation/attempt changes and expiry reject late results. Missing peers and temporarily unavailable optional lookup routes remain retryable, but root-owner EOF, malformed replies and permanent root errors fail promptly. The service owns the resolver thread and interrupts it on group/control/session cleanup. Privileged ARP reads avoid repeated ordinary-UID procfs denials.

Cleanup invalidates the session generation before closing RTSP/UIBC/media resources, retires the matching `PlayerActivity` and its decoder/touch state, releases the wake lock, and stops P2P. Navigation completion waits for the player acknowledgement, owned CONNECT settlement, all retired root owners, and a fresh absent-group observation before stopping the service. Duplicate stop requests share the completion result; the `stopping` guard prevents late callbacks or new starts from reopening media.
When Generic or HIDC USB touchscreen UIBC is negotiated and the opt-in setting is
enabled, the service owns one generation-scoped source TCP session. The player
uses a pure gesture processor and the current registry entry; category, path,
and HID type travel with the negotiated endpoint. Failures, EOF, and saturation
unregister only UIBC and leave RTSP/RTP alive. Teardown, P2P loss, service stop,
and generation replacement close the session. Activity pause/destruction,
surface loss, rotation, and video geometry changes release contacts without
closing the service-owned connection. Session-end broadcasts are package- and
generation-scoped so an old attempt cannot finish a replacement player.

### `AdaptiveSession.kt` — Generation/state controller

Each start creates a monotonically increasing generation. The controller accepts only callbacks belonging to the current generation and attempt, and ignores stale callbacks after reset/stop.

States are:

`Idle → P2pStarting → Discovering → GroupForming → GroupFormed → ControlConnecting → Negotiating → TransportReady → MediaStarting → Streaming → Stopping`.

The source owns the RTSP server in both P2P role arrangements, so the sink uses an outbound RTSP connection. Profiles are selected from observed source evidence (Windows, Android, or unknown) and control timing/capability details such as the optional second PLAY.

### `PlayerActivity.kt` — Fullscreen media surface

Receives the negotiated RTP/RTCP ports and session generation, prepares a `TextureView`, decodes H.264 with the media pipeline, and plays LPCM audio when enabled. It applies immersive fullscreen behavior and finishes when the service broadcasts `SESSION_END`.

The lifecycle-aware Back callback first shows a Toast. A second completed Back within two seconds reorders the dashboard without finishing the player or tearing down the session. Leaving/resuming the player, a completed ordinary touch, or a non-Back key disarms the window. An edge Back can deliver app-level DOWN/CANCEL before the system callback, so those events must not clear the first warning. Return preserves the authorized epoch and requests video-reference recovery for a recreated surface; Main/Settings transitions still stop and clean up the session. No display geometry or UIBC encoding changes are involved.

Host smoke invokes the production `PlayerActivity.dispatchTouchEvent` and `onKeyDown` using platform adapters: edge DOWN/CANCEL preserves the second-Back window, while ordinary UP/non-Back input disarms it. The edge case failed before the correction and passed afterward. Activity animations, physical gestures and actual dashboard rendering still require a device.

The receiver separates UDP arrival from demux/decoder work, reorders a bounded
window, suppresses duplicates, and reports unrecoverable gaps. Demux passes
complete video PES access units and LPCM PTS to bounded playback workers.
Video presentation follows a shared audio-master/monotonic clock and inclusive
decoder crop metadata. Loss requests M13 through the service; requests are
rate-limited and unsupported recovery responses are nonfatal.

PCM packet drops preserve a healthy `AudioTrack`; positive PTS gaps rebase its
mapping rather than recreate the device. The clock cannot use pre-gap samples,
released/underrun tracks or timestamps from an older generation as audio master.
Partial nonblocking writes use source-timeline scheduling and a 200 ms device backlog, increased only when the actual start threshold is larger. The device is primed to its reported streaming threshold before play; API 31+ requests up to 100 ms. A delayed audio head cannot gate its own PCM producer.
Actual write failures and format/timeline changes still release the device.
Reset/drop reasons are rate-limited in `MediaDecoderPipeline` logcat.

`RtpReceiver` also emits five-second logcat summaries during idle input, with
packet-loss/duplicate/local-drop/SSRC-rejection counters, ready depth and maximum
receive-to-consume queue wait. This is local backlog, not measured radio jitter.
Both UDP transports are closed on worker exit and partial startup failure.

## Session lifecycle and retry behavior

`P2pManager` serializes framework LISTEN, extended LISTEN, discovery, and the optional supplicant monitor. Startup confirms no previous native group remains before root setup/advertising. Framework listening falls back to discovery only when unsupported; authorization and driver failures remain failures. A group-started indication is only a candidate: readiness polls paired fresh group/connection snapshots, validates active identity/role, and waits for a remote WFD source plus current group IPv4. Local GO ownership alone is not a remote peer; the GO interface must own its framework-reported IPv4.

Readiness is polled every 250 ms for up to 6 seconds using monotonic time; peer association or WFD metadata may become ready during that window. Late snapshots cannot bypass expiry, and missing Binder callbacks have a separate bounded probe timeout. Failed formation, readiness timeout, group loss, invitation fallback/stall, and pre-RTSP control-connect failures invalidate the attempt, settle the original app/helper CONNECT owner and retire its helper, wait up to 2 seconds for group cleanup, then retry after 500 ms. Null group information alone is not negotiation-idle proof. There are at most three attempts per session generation. Cleanup callbacks/timers require both token and runnable ownership, including after reentrant failure notification.
A missing previously framework-observed group is a loss even while the group-started hint remains set. A start hint without any framework-observed group still permits propagation grace. Delayed probes and queued replays carry the originating attempt token so an old callback cannot invalidate a successor.
Attempt IDs remain unique across starts of the same controller. Service failure reports carry generation and attempt, and startup/discovery/auto-accept callbacks cannot adopt a successor token. Duplicate group admission and stale retry expiry are ignored; exhaustion is tied to the actual failed attempt, including group loss. Stopping invalidates controller work before cleanup, and repeated attempt reset cannot publish another retry transition.
The SDK 33+ public app-channel path and legacy helper path retain independent
operation ownership. Pending cancellation must be acknowledged on the original
channel; formed/already-ended proof uses fresh framework evidence. Unknown or
unrelated state cannot pass cleanup. Stop/failure workers capture their original
operation, and unsettled app operations remain part of the global cleanup gate.


## Telemetry categories

`DebugEventLog` is thread-safe and process-local. It retains a maximum of 12 events, evicts oldest entries, clears deduplication/rate-limit state on session start, and does not persist or export its panel data. RTP summaries and bounded media timing/reset diagnostics are separately available in logcat.
Applied preferences and startup snapshots use `MiracastSettings`/`MiracastApp`;
sink-local WFD ACK/readback uses helper diagnostics, while M3/M4 media evidence
uses `MiracastRTSP`. Nominal CEA FPS is not measured FPS, and local maximum
throughput is not an encoder-rate command. WFD readback is asynchronous and
diagnostic-only. Save still uses the existing preference application/navigation.


| `SINK` | service/session start and stop |
| `P2P` | discovery, group candidates, formation/loss, attempt reset |
| `WFD` | framework or supplicant WFD setup, profile selection, parameters |
| `DHCP` | source address resolution and interface readiness |
| `RTSP` | connection, requests/responses, negotiation, transport, teardown |
| `UIBC` | optional capability, enablement, connection, disable, and close diagnostics |
| `RTP` | receiver bind, first packet, packet counters, stop |
| `MEDIA` | decoder/audio start, SPS changes, first decoded frame |
| `ERROR` | bind, readiness, address, protocol, and decoder failures |

One-shot events are deduplicated per key. Noisy events use a 5-second default rate limit (or an explicit interval), so packet/error paths remain bounded.

## WFD and RTSP handshake

The sink advertises runtime-generated primary-sink WFD information through the framework API or root supplicant fallback. Session availability is cleared while a group/session owns the sink. The configured local control-port field is advertised, but outbound RTSP uses the source's own advertised port or 7236.

The sink initiates TCP; the source initiates M1 OPTIONS on that connection. After acknowledging M1, the sink sends M2 and waits for its matching success. Before M3, it binds the actual RTP endpoint and snapshots decoder-derived capabilities. M4 selection is validated before commit. The source's M5 trigger is acknowledged before sink M6 SETUP; Session/UDP Transport are validated before M7 PLAY. The first successful PLAY starts media; duplicate PLAY is opt-in only. Valid source M16 keepalives own session liveness. TEARDOWN completion/deadline or group loss closes the session.

UIBC requires the advertised local opt-in, an accepted source-selected endpoint, and explicit enablement. Unsupported input leaves video usable. Early video is bounded across surface creation; the decoder is initialized from SPS rather than guessed dimensions.

## Build, sign, and package

Requirements: Android SDK/build-tools 34.0.0, side-by-side NDK 29.0.14206865, Gradle wrapper, and JDK 17. `scripts/build.sh` signs with a persistent ordinary application key outside the repository, not an AOSP/ROM platform key. The native identity helper is packaged inside the APK; the module remains an ordinary-app installer with no boot binary. See the root README for signing environment variables and backup requirements.

```bash
./scripts/build.sh
./scripts/pack-magisk.sh
# output: magisk-miracast-sink-v<VERSION>-root.zip
```

Install the APK normally with `adb install -r`. It has its own UID and requests root only when a sink session begins. It is not an Xposed module. The optional `miracast-sink-root` bundle installs that same APK immediately through PackageManager during installation in a running Android system. It mounts nothing and has no boot service, SELinux policy, or automatic application removal.

Remove the old `miracast-sink-system` overlay and reboot before migration. Existing shared-UID/signature conflicts are reported without deleting the old application or its data; back up and resolve them explicitly. The bundle does not bypass Android's package identity checks.

## Verification notes

The repository contains JVM tests for the adaptive session controller, retry/readiness predicates, telemetry bounds, service launch/cleanup helpers, root command restrictions, protocol parsing, RTP, and MPEG-TS behavior. Run tests and a release build with the Android SDK. Host smoke checks can exercise installer streaming/failure handling and shell quoting, but cannot verify root Binder identity or SELinux on a physical ROM. Device-level Miracast interoperability remains hardware/vendor dependent. Diagnostic captures do not themselves start a connection.
