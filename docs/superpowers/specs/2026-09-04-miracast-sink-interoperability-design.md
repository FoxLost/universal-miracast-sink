# Miracast Sink Interoperability Design

**Date:** 2026-09-04
**Status:** Validated design; implementation is not included in this document.

## 1. Goal and scope

Make the Android application a predictable Wi-Fi Display (WFD) **Primary Sink** for Windows 11 sources while preserving interoperability with the captured Android-source path. The design covers Wi-Fi Direct connection setup, WFD RTSP negotiation, negotiated RTP/RTCP transport, MPEG-TS demultiplexing, H.264/LPCM rendering, and deterministic teardown.

The supported Android compatibility scope is **API 32 and later** (Android 12 through the current compile/target API 34). API 28–31 are out of scope for this design. API 33/34-only permissions and receiver behavior must remain guarded so the API 32 path is valid. The current repository declares `minSdk = 29`; changing that declaration is an implementation task and is not part of this design-only change.

The design does not add Miracast-over-Infrastructure (MICE), HDCP, WFD R2 codecs, or UIBC input delivery. Those capabilities remain explicitly unsupported unless their complete protocol paths are implemented and verified.

## 2. Evidence and compatibility principles

The design is grounded in the local captures and references:

- `findings/miracast-connection-flow-analysis.md` documents a successful Windows 11 GO to Xiaomi sink session, including M1–M7, MPEG2-TS/RTP, RTCP, and UIBC.
- `findings/android-vs-windows-source-comparison.md` contrasts the Windows and Android source wire behavior, including Windows' 26-parameter query, selected format, transport metadata, and variable source ports.
- `paper/miracast_spec_v2.3.pdf` and `paper/wifi_display_spec_v2.1.pdf` define WFD roles, Wi-Fi Direct, RTSP control, codecs, transport, and teardown.
- `paper/[MS-WFDPE].pdf` defines the Microsoft capability extensions and their meanings.
- `findings/decompiled/framework_wifi/.../WifiP2pManager.java` defines `MIRACAST_SOURCE = 1` and `MIRACAST_SINK = 2`.
- `reversed-devices-firware/Pad6_Miracast_Core/decompiled/WfdService/.../WFDSession.java` provides a reference for explicit session, transport, playback, standby, resolution, and UIBC state.

Observed-vendor behavior is a compatibility constraint, not permission to claim unsupported behavior. In particular, the sink's Windows-facing `Public` header remains the restricted header observed in the capture; adding methods merely because they appear in a generic RTSP summary is prohibited.

## 3. Architecture

### 3.1 External seam: WFD session controller

Introduce one deep WFD session module with a small interface:

- `start(peer, localNetwork)`: starts or joins one WFD session.
- `stop(reason)`: idempotently terminates the session.
- `state`: reports `Idle`, `P2pConnecting`, `ControlConnecting`, `Negotiating`, `Streaming`, `Stopping`, or `Failed`.
- typed events: `PeerFound`, `GroupFormed`, `Negotiated`, `FirstFrame`, `SessionEnded`, and `Failure`.

The controller owns ordering and lifecycle. It does not expose raw sockets, ad-hoc strings, Android activities, or implementation-specific P2P callbacks to callers.

### 3.2 Internal adapters

The controller contains internal seams for:

1. **P2P adapter** — Android `WifiP2pManager` first; a root/supplicant adapter only where the framework cannot perform the required privileged operation. It emits peer, group, role, gateway, local-address, and disconnect events.
2. **Capability model** — one immutable per-session model used to serialize the WFD Information Element, M3 response, M4 validation, and transport binding.
3. **RTSP codec/session** — byte-oriented message framing plus a transaction table keyed by CSeq. Header names are case-insensitive; `Content-Length` counts encoded bytes; requests and responses are not matched by arrival position.
4. **RTP/RTCP transport** — binds the negotiated local ports before PLAY, parses RTP headers, tracks SSRC/sequence/timestamps, and handles a separate RTCP endpoint.
5. **MPEG-TS demuxer** — consumes validated TS packets and emits timestamped elementary frames.
6. **Media renderer** — accepts timestamped H.264 and LPCM frames, controls decoder configuration, and reports first decodable output.
7. **Optional UIBC adapter** — absent from the default capability model. It may be added only with negotiated-port, descriptor, event, lifecycle, and teardown support.

`MiracastService` orchestrates the service and notification. `PlayerActivity` owns the display surface only; it must not own RTSP state or decide network ports.

## 4. Exact Windows M1–M7 wire contract

The following is the required happy-path contract. CSeq values below are those observed in the Windows capture; implementations must correlate by the received CSeq rather than require these literal numbers.

### M1: source OPTIONS

Windows source to sink:

```text
OPTIONS * RTSP/1.0

CSeq: 1

Require: org.wfa.wfd1.0


```

Sink response:

```text
RTSP/1.0 200 OK

CSeq: 1

Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER


```

The restricted sink `Public` value is intentional. The local RTSP documentation records that advertising `SETUP, PLAY` from the sink caused a source disconnect, and the Windows capture confirms the restricted value.

### M2: sink OPTIONS

After acknowledging M1, sink to source:

```text
OPTIONS * RTSP/1.0

CSeq: <sink transaction>

Require: org.wfa.wfd1.0


```

The source response normally includes `SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER` and a Microsoft `Server` header. The sink accepts the response without assuming a particular product version or CSeq.

### M3: source capability query

Windows sends `GET_PARAMETER` for the 26 parameters recorded in `findings/android-vs-windows-source-comparison.md:86-115`:

- `wfd_video_formats`
- `wfd_audio_codecs`
- `wfd_client_rtp_ports`
- `wfd_display_edid`
- `wfd_connector_type`
- `wfd_uibc_capability`
- `wfd2_rotation_capability`
- `wfd2_video_formats`
- `wfd2_audio_codecs`
- `wfd2_video_stream_control`
- `wfd_content_protection`
- `wfd_idr_request_capability`
- `intel_friendly_name`
- `intel_sink_manufacturer_name`
- `intel_sink_model_name`
- `intel_sink_version`
- `intel_sink_device_URL`
- `microsoft_latency_management_capability`
- `microsoft_format_change_capability`
- `microsoft_diagnostics_capability`
- `microsoft_cursor`
- `microsoft_rtcp_capability`
- `microsoft_video_formats`
- `microsoft_max_bitrate`
- `microsoft_multiscreen_projection`
- `microsoft_audio_mute`
- `microsoft_color_space_conversion`

The response must:

- return `200 OK` with the request CSeq;
- include one valid CRLF-terminated response line for every requested parameter;
- calculate `Content-Length` from the encoded response bytes;
- return only capabilities actually supported by the running device/session;
- use syntactically valid `none` values for unsupported optional extensions where the WFD/Microsoft grammar permits them.

The captured sink advertises H.264 Constrained Baseline up to 1080p60, LPCM 48 kHz/16-bit/stereo, and an RTP client port beginning at 15550. The implementation must serialize the actual configured/bound port rather than a second hardcoded value.

### M4: source-selected parameters

Windows sends `SET_PARAMETER` containing the selected H.264 format (captured as 1920x1080p60 CBP), LPCM, `wfd_presentation_URL`, client RTP-port information, UIBC information, and `intel_overscan_comp: x=0, y=0`.

The sink must acknowledge the request only after parsing and validating the body. It must retain the selected stream URL, codec, and transport values for subsequent SETUP. A presentation URL containing `255.255.255.255` may be normalized to the already-known source GO address; an unrelated or malformed URL is rejected with a defined RTSP error.

The source's selected UIBC port (50000 in the Windows capture) is not an instruction to silently enable an unimplemented UIBC path. If UIBC is unsupported, the sink advertises `none` and rejects or ignores the optional selected UIBC field according to the WFD error policy without failing video setup.

### M5: SETUP trigger

Windows sends:

```text
SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0

CSeq: 4

Content-Type: text/parameters

Content-Length: ...


wfd_trigger_method: SETUP

```

The sink returns `200 OK` with the same CSeq, then initiates M6. The trigger must be processed once; duplicate triggers cannot create multiple sockets, players, or SETUP transactions.

### M6: sink SETUP

The sink sends SETUP to the selected source URL:

```text
SETUP rtsp://<source-go>/wfd1.0/streamid=0 RTSP/1.0

CSeq: <sink transaction>

Transport: RTP/AVP/UDP;unicast;client_port=<local-rtp>-<local-rtcp>


```

The local RTP/RTCP sockets must already be bound before this request is sent. The source may return odd and nonconsecutive server ports; the sink must not assume an even RTP port or `rtcp = rtp + 1`. It records `Session`, `server_port`, `ssrc`, `rtcp-fb-ssrc`, `blocksize`, timeout, and other recognized Transport parameters without requiring all optional fields.

### M7: sink PLAY

After a successful SETUP response, the sink sends:

```text
PLAY rtsp://<source-go>/wfd1.0/streamid=0 RTSP/1.0

CSeq: <sink transaction>

Session: <parsed-session-id>


```

The session ID must be the parsed SETUP value, not a default literal. The optional second PLAY remains a compatibility setting and is never required for Windows success. The sink considers streaming active only after the first PLAY response and a functioning media path; it may expose an earlier `TransportReady` state for diagnostics.

### Ongoing control

During streaming, the sink responds to source `GET_PARAMETER` keepalives and non-trigger `SET_PARAMETER` updates without resetting the session. It handles `PAUSE`, method `TEARDOWN`, trigger `TEARDOWN`, socket EOF, timeout, and non-2xx responses through the same cleanup state machine.

## 5. Truthful capability policy

The capability model is authoritative. Every advertised capability has a corresponding implementation and verification case.

Default policy:

- **Video:** advertise only decoder-tested H.264 formats. Do not advertise HEVC or a profile/level that the selected API 32 device cannot decode.
- **Audio:** advertise LPCM 48 kHz, 16-bit, 2-channel only when audio output is available and the demuxer/renderer accepts it. Treat a video-only stream as valid.
- **RTP:** advertise the actual session RTP endpoint; M3 and M6 values must be consistent with the bound sockets.
- **Content protection:** `none`; no protected stream is accepted as if it were clear content.
- **IDR request:** advertise `0` until RTSP M13 generation, response handling, and source behavior are implemented. [MS-WFDPE] defines `1` as the ability to send M13; first-frame quality must instead be ensured by source IDR behavior and decoder keyframe gating.
- **RTCP:** advertise support only after an RTCP receiver and session/SSRC handling exist. Otherwise use `none`.
- **UIBC:** advertise `none` by default. The existing `UibcSender.kt` is experimental and is not part of the active flow; the settings toggle must not cause a capability claim without the full selected-port and input-event path.
- **Microsoft/Intel/WFD2 extensions:** answer requested fields with truthful supported values or syntactically valid `none`; do not claim dynamic format change, latency modes, diagnostics, cursor, multiscreen, or WFD2 streaming unless implemented.

The response is request-filtered. `fullResponse()` is a diagnostic representation, not a license to emit unsolicited or unsupported fields.

## 6. P2P role and WFD Information Element

### Role policy

The app is a Primary Sink. It must invoke the framework sink mode (`MIRACAST_SINK = 2`), never the source mode (`1`). The default GO intent is client-oriented so a Windows source can remain GO; the standard permits either endpoint to be GO for a single-sink session, so a controlled fallback may support sink-as-GO for Android sources.

The P2P adapter supports both:

- first-time discovery, provision discovery, GO negotiation, WPS, DHCP, and group formation; and
- persistent-group invitations and fast reconnection, which is the path observed in the Windows capture.

Peer discovery and connection state are driven by real framework callbacks/broadcasts. Vendor-specific supplicant monitoring is a fallback adapter, not the primary application contract. The adapter reports the actual group-owner address and local interface address; fixed `192.168.137.1`/`192.168.49.1` probes may be diagnostic fallbacks only.

### WFD IE policy

The canonical supplicant WFD device-info payload for the observed sink is:

```text
00111C440032
```

This represents device-info `0x0011`, control port `0x1C44` (7236), and maximum throughput `0x0032` (50). The implementation must construct this value from the canonical capability/role model and must not have a separate hardcoded IE in an Xposed hook. Content-protection and WFD service-discovery bits are not set unless their corresponding protocol paths are enabled.

The same WFD identity must appear consistently in beacon/probe/action-frame advertisement as provided by the Android framework/supplicant adapter. A hook may repair a vendor framework seam, but it must not silently change the advertised contract.

## 7. RTP, TS, and media boundaries

### RTP/RTCP adapter

The adapter owns UDP sockets and exposes `RtpPacket`/`RtcpPacket` values. It must:

- parse the RTP version, CSRC count, extension, padding, payload type, marker, sequence, timestamp, and SSRC;
- restore the full receive buffer length before every datagram receive;
- reject or account for malformed packets without killing the session;
- detect sequence gaps and expose loss metrics;
- accept the negotiated source SSRC and tolerate optional RTCP feedback SSRC metadata;
- receive RTCP independently, at minimum parsing sender reports and session identity; and
- stop both endpoints idempotently.

No Activity or TS parser reads raw UDP sockets.

### MPEG-TS demuxer

The demuxer accepts complete RTP payload slices and emits timestamped elementary frames. It owns:

- TS sync and resynchronization;
- adaptation-field bounds checks;
- continuity-counter validation and discontinuity recovery;
- PAT/PMT section assembly, including sections crossing TS packets;
- selected H.264 and audio elementary PID mapping;
- PES reassembly and PTS/DTS extraction with wrap-aware timestamps; and
- LPCM framing based on the negotiated audio format.

It does not infer H.264 from arbitrary stream IDs when PMT data is available, and it does not label H.265 as H.264. Unsupported stream types cause a typed media error or are ignored without corrupting the selected video stream.

### Decoder and renderer

The decoder boundary accepts complete Annex-B H.264 access units/NAL units with timestamps. It gates output until a decodable IDR with required SPS/PPS has arrived, handles only advertised format changes, and applies bounded backpressure rather than silently dropping input because an input buffer is temporarily unavailable. Audio frames retain timestamps and are scheduled against the video clock; immediate un-timestamped `AudioTrack.write()` is not the synchronization contract.

`PlayerActivity` receives a prepared rendering surface and lifecycle events. It does not select ports, parse RTSP, or instantiate the network state machine. Surface destruction stops media consumption but does not bypass controller cleanup.

## 8. API 32 implementation scope

The implementation target is API 32+:

- use API 32-compatible Wi-Fi/P2P and foreground-service paths;
- guard API 33 Nearby Devices permission requests and exported/not-exported receiver flags through compatibility APIs;
- retain API 30+ window-insets behavior behind version checks;
- keep hidden framework calls isolated in the P2P adapter and treat reflection failure as a typed capability/runtime failure;
- do not require API 33-only constants or methods on the API 32 path; and
- verify at least one API 32 device/emulator and one API 34 device.

API 28–31 support is not an acceptance criterion. Reintroducing a lower minimum later requires a separate compatibility design and test matrix.

## 9. Error handling and cleanup

All failures converge on one idempotent `stop(reason)` path. It must:

1. stop accepting new RTSP transactions;
2. respond to a valid teardown trigger/method before closing when the socket is usable;
3. close RTSP, RTP, RTCP, and any optional UIBC sockets;
4. stop the receiver, demuxer, decoder, audio output, and rendering surface callbacks;
5. finish or reset `PlayerActivity` through a typed session event;
6. remove the P2P group only according to the configured lifecycle policy and restore any temporarily disabled STA/network state;
7. release wake locks and foreground notification actions; and
8. return to `Idle` with a diagnostic reason and no leaked thread, socket, process, or callback.

Malformed RTSP framing, unsupported methods, invalid Content-Length, bad URLs, unsupported selected formats, source timeouts, RTP loss, decoder failure, and P2P disconnects are distinct diagnostic reasons. They must not be hidden by a generic catch that leaves the service in `Streaming`.

## 10. Verification and acceptance criteria

### Protocol-vector verification

Deterministic tests using the local Windows capture/transcript must prove:

- exact M1–M7 direction/order and legal restricted sink `Public` header;
- case-insensitive header parsing and CSeq-based transaction matching with reordered responses;
- byte-accurate Content-Length and complete 26-parameter M3 response;
- M4 selected format, URL, client-port, and optional-field validation;
- parsed SETUP Session and Transport values, including odd/nonconsecutive server ports, SSRC, RTCP-FB-SSRC, and blocksize;
- first RTP/RTCP packets received after sockets bind and before/around PLAY without a startup race;
- keepalive, PAUSE, method/trigger TEARDOWN, EOF, timeout, and non-2xx cleanup; and
- truthful capability serialization: no IDR/UIBC/RTCP claim without its implementation.

### Media-vector verification

Fixtures must prove:

- variable-size RTP datagrams do not become truncated after a short packet;
- RTP headers with CSRC, extension, padding, sequence gaps, and unexpected SSRC are handled safely;
- periodic PAT/PMT and PES data crossing TS packets are demultiplexed;
- H.264 SPS/PPS/IDR yields the expected dimensions and first decodable frame;
- a source with no audio remains valid; LPCM audio is rendered only when negotiated and synchronized; and
- malformed TS/PES data produces a bounded media error rather than a process crash.

### Device and end-to-end verification

On API 32 and API 34 devices:

- the app enters framework WFD sink mode and advertises canonical `00111C440032` without requiring an Xposed value override;
- Windows 11 discovers/connects, completes M1–M7, renders the first IDR-backed frame, and sustains at least the captured session duration without RTP truncation or RTSP disconnect;
- the optional second PLAY can be enabled or disabled without preventing Windows streaming;
- Windows source teardown returns the app to `Idle` with all media/control/P2P resources released; and
- an Android source using the alternate GO/subnet and transport conventions still reaches the negotiated streaming state.

A verification report must include packet captures/logs for M1–M7, negotiated ports and Session, first-frame evidence, sustained media counters, and teardown reason. Passing a build alone is not sufficient acceptance.

## 11. Host capture preflight and Android visibility

The non-destructive host preflight performed for this design found:

- `phy0` exposes only `wlan0` (ifindex 3, MAC `c6:4e:3a:47:8c:03`), currently `managed`, with no active association. `iw dev wlan0 link` reported `Not connected`; NetworkManager reported `wlan0:wifi:disconnected`.
- `iw list` confirms monitor mode is supported, along with 2.4 GHz channels 1–14 and 5 GHz channels 36–165. It also reports that interface combinations are not supported.
- The host has `tcpdump` and `tshark`. No separate `wlan1`, `wlx*`, or existing monitor interface was present.
- The wired `eth0` connection is unrelated to Wi-Fi Direct and cannot capture over-the-air P2P management/action frames.

No interface type, power, channel, association, or network configuration was changed. The existing `capture_full_sink_idle/run_full_capture.sh` is not runnable unchanged on this host: its documented default expects a second TL-WDN5200H/RTL8821AU adapter (normally `wlan1`/`wlx*`/`mrn0`), and its setup path changes an interface to monitor mode at lines 118–123. Running that path against the only `wlan0` would conflict with a managed Wi-Fi connection and violate the no-device-state-change constraint.

### Capture limitations and safe procedure

An implementation verification run requires a second Wi-Fi adapter that supports monitor mode, or an explicitly scheduled maintenance window in which changing `wlan0` to monitor mode is acceptable. Because this host reports no interface combinations, one adapter cannot safely provide simultaneous managed/P2P operation and independent monitor capture. A monitor adapter also sees one channel at a time:

1. During discovery, hop the 2.4 GHz social channels 1, 6, and 11 slowly enough to capture probe responses, provision discovery, and GO-negotiation frames.
2. Once the group is formed, lock the monitor adapter to the actual P2P operating channel. Do not assume 2.4 GHz: the local captures include both Windows 2.4 GHz sessions and Android 5 GHz sessions.
3. Capture the Android-side `p2p0`/group interface and the Windows-side Wi-Fi interface at the same time when possible. An OTA monitor capture alone cannot decrypt WPA2 data or replace endpoint captures for RTSP/RTP payload validation.

### Verifying that Android appears as a Miracast display

With the Android sink in its listening state and a Windows 11 source in range:

1. On the monitor adapter, observe an Android P2P probe response/beacon/action-frame advertisement containing a WFD Information Element. Verify the primary-sink/session-available device information and the canonical device-info payload `00111C440032`; correlate the advertised friendly name with the Android sink.
2. On the endpoint capture, verify Wi-Fi Direct group formation and DHCP address assignment. The source may use a Windows `192.168.137.0/24` group or another negotiated subnet; the sink must use the actual gateway/address rather than a fixed subnet assumption.
3. Verify Windows sends WFA service discovery traffic as observed locally (`SSDP M-SEARCH` for `urn:schemas-wifialliance-org:device:WFADevice:1`) and then the TCP 7236 WFD control exchange.
4. In Windows, press **Win+K** and confirm the Android friendly name is listed as a wireless display. Selection is the user-visible discovery criterion; packet evidence must then show the M1–M7 contract, negotiated transport, and first RTP/IDR frame.
5. Preserve the monitor and endpoint captures together with the negotiated P2P channel, sink/source IPs, RTSP CSeq/Session, RTP/RTCP ports, and teardown reason. A managed `wlan0` capture while disconnected is only a tool smoke check, not proof of Miracast visibility.

This host preflight is therefore sufficient to establish driver capability and capture-tool availability, but not to claim that an Android sink is currently visible: no P2P interface or monitor capture was active during the check.

## 12. Self-review

- No unresolved placeholders, TODOs, or undecided alternatives remain.
- API scope is explicit: API 32+; API 28–31 are intentionally excluded rather than implicitly promised by the current `minSdk 29`.
- The design distinguishes the standards-permitted P2P GO role choice from the Windows interoperability default (source as GO).
- The Windows restricted `Public` header is intentionally preserved and does not contradict the internal RTSP state machine, which still supports sink-initiated SETUP/PLAY.
- Capability claims are tied to implementation and verification; optional UIBC, M13/IDR, RTCP, HDCP, WFD2, and MICE are not falsely advertised.
- The canonical WFD IE, M3 ports, M4 selections, M6 transport, and media socket bindings all derive from one session model rather than independent constants.
- Cleanup covers both normal protocol teardown and failures before/after media startup.
