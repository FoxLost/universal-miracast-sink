# Adaptive Miracast Session Controller Design

**Date:** 2026-09-05
**Status:** Approved design; P2P stability implementation and validation status are recorded in §15.

## 1. Goal and scope

Make the Android application use one adaptive Wi-Fi Display (WFD) session implementation that selects the correct behavior from the P2P role, the source/request direction, and the source's RTSP evidence. The implementation must preserve the proven Windows 11 source-GO path while supporting the Android-source path in which the Android sink becomes Group Owner (GO) and accepts an inbound RTSP connection.

The design replaces the current split between `MiracastService.startRtspHandshake()` and `RtspServer`/`RtspConnection` with one session controller and one RTSP state machine behind two transport-entry adapters. The controller owns ordering, cancellation, negotiated transport, media readiness, and cleanup. `MiracastService` remains a foreground-service and notification adapter. `PlayerActivity` owns only the rendering surface and UI lifecycle.

The supported Android compatibility scope is API 32 and later. API 28–31 are not acceptance targets for this design. API 33/34 permission and receiver behavior remains guarded so the API 32 path is valid. The design does not add MICE, HDCP, WFD R2 codecs, or UIBC input delivery; those capabilities remain unsupported until their complete protocol paths exist.

This is a design-only change. No source implementation or migration shim is included; deterministic transcript fixtures remain implementation deliverables.

## 2. Evidence and compatibility principles

The design preserves behavior documented by the current repository and the prior successful Windows trace:

- Windows sources use the sink-side reverse RTSP model and run their RTSP server when they are P2P Group Owner.
- The Windows-facing sink `Public` header is restricted to `org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER`; advertising sink `SETUP`/`PLAY` caused a source disconnect in the observed path.
- The Windows-compatible P2P default is a client-oriented GO intent (`0`), allowing the source to become GO. A bounded fallback GO intent (`8`) is retained for sources that require the sink to become GO.
- The WFD identity used by the observed sink is derived from the primary-sink device information, configured RTSP control port, and max throughput; the canonical default is `00111C440032` for port 7236 and throughput 50.
- Local RTP/RTCP sockets must be bound before the sink sends `SETUP`; the source may send the first datagram immediately after `SETUP`.
- CSeq values correlate transactions; responses must not be matched merely by arrival order. `Content-Length` is an encoded-byte count.
- The source's returned `Session`, `server_port`, SSRC, RTCP feedback SSRC, and optional transport parameters are negotiated values, not literals.
- H.264 hardware decoding, SPS-aware reconfiguration, and LPCM descriptor removal plus big-endian-to-little-endian conversion are retained.

The repository contains the design references and source comments for the trace, but the packet-capture files named by the earlier interoperability design are not tracked in the current `HEAD`. Implementation acceptance therefore requires checked-in deterministic transcript fixtures or an attached verification report; this design does not treat an unobserved capture as a test result.

Observed vendor behavior is a compatibility constraint, not a license to claim unsupported behavior. Every capability advertised in WFD IE or RTSP must have a corresponding implementation and verification case.

## 3. Design overview

### 3.1 External seam: `WfdSessionController`

Introduce one deep session module with a small caller-facing interface:

- `start(request)`: begins discovery or joins the requested WFD session.
- `stop(reason)`: idempotently cancels the session and releases every owned resource.
- `state`: exposes the typed lifecycle state.
- `events`: emits typed session events for notification, surface, diagnostics, and first-frame state.

The controller interface does not expose raw sockets, Android broadcast intents, `WifiP2pManager` callbacks, RTSP strings, or Activity instances. It accepts a session request containing user settings and optional role preference, then produces typed events such as:

- `PeerFound`
- `GroupFormed`
- `ControlConnected`
- `Negotiated`
- `TransportReady`
- `FirstFrame`
- `SessionEnded`
- `Failure`

The controller is the only module allowed to decide whether a session uses an outbound or inbound RTSP adapter. It also owns the session generation ID so stale callbacks from a previous attempt cannot mutate a new session.

### 3.2 Lifecycle states

The controller reports a state machine with explicit transition ownership:

```text
Idle
  -> P2pStarting
  -> Discovering
  -> GroupForming
  -> GroupFormed
  -> ControlConnecting       (outbound adapter)
  -> ControlAccepted         (inbound adapter)
  -> Negotiating
  -> TransportReady
  -> MediaStarting
  -> Streaming
  -> Stopping
  -> Idle

Any active state -> Failed -> Stopping -> Idle
```

`TransportReady` means the RTSP `SETUP` transaction succeeded, the selected local RTP/RTCP endpoints are bound, and the returned remote transport values have been parsed. `Streaming` is not entered merely because a `PLAY` response arrived; it requires functioning media flow and a first decodable video frame (or an explicit video-only readiness result for a source that does not provide audio).

The controller serializes transitions. A duplicate P2P group callback, duplicate SETUP trigger, duplicate PLAY response, surface recreation, socket EOF, or user stop cannot create a second transport or advance the same state twice.

## 4. Unified session context

### 4.1 Immutable identity and role

Create one session context at group formation. It is the authoritative source for all later protocol and media decisions; identity and role fields are immutable, while negotiation fields are versioned by the controller:

```text
SessionContext
  generation
  peer: address, device name, WFD device info, manufacturer/model if known
  sourceHint: Windows, Android, Unknown, or Undetermined
  p2pRole: SinkClientSourceGo | SinkGoSourceClient
  group interface and operating channel
  groupOwnerAddress
  localGroupAddress
  peerControlPort
  localControlPort
  source fingerprint evidence
  compatibility profile
```

`p2pRole` is derived from the framework's actual `WifiP2pInfo.isGroupOwner`, never from a configured assumption. `groupOwnerAddress` and the local group address come from the formed group. Fixed addresses such as `192.168.49.1` or `192.168.137.1` are not session inputs; an ARP lookup is permitted only as a sink-GO fallback to resolve the peer's actual address by MAC.

Identity and role fields are immutable after group formation. `sourceHint`, fingerprint evidence, compatibility profile, selected URL, selected codec, bound endpoints, remote endpoints, RTSP `Session`, SSRC, and readiness counters are versioned negotiation fields that the controller may update as RTSP evidence arrives. Each update is controller-owned and emitted as a typed event.

### 4.2 Negotiation and media fields

The session record contains:

- the requested WFD parameter names and the request order;
- the truthful capability snapshot used for WFD IE and M3;
- the selected video/audio formats after M4 validation;
- the normalized presentation URL and source endpoint;
- the local RTP and RTCP sockets and actual bound ports;
- the negotiated remote RTP/RTCP ports, RTSP Session, SSRC, RTCP feedback SSRC, timeout, blocksize, and recognized transport parameters;
- sequence/loss/RTCP counters;
- media readiness (`SurfaceReady`, `ReceiverBound`, `FirstRtp`, `DecoderConfigured`, `FirstFrame`);
- cancellation state and a typed terminal reason.

WFD IE serialization, `WfdCapabilities`, M4 validation, `SETUP`, `RtpReceiver`, and RTCP validation all consume this record. No layer may read a second hardcoded RTP port or session ID from settings after negotiation.

## 5. P2P role-driven adapters

### 5.1 P2P adapter interface

Place Android framework and vendor-specific behavior behind a P2P adapter. Its interface reports:

- discovery/listening state;
- eligible WFD peers;
- group formation and actual role;
- group-owner and local addresses;
- peer WFD control port and identity metadata;
- disconnection and formation failure;
- capability/runtime failures for reflection or root supplicant fallback.

`P2pManager`, `P2pReceiver`, `SupplicantEventMonitor`, and `SupplicantWriter` become implementation details of this adapter. The controller must not consume Android broadcast intents directly.

The framework path remains primary. Root/supplicant commands are fallback adapters for builds where framework APIs cannot perform a required privileged operation. Reflection failure is reported as a typed capability failure, not silently treated as a successful WFD advertisement.

### 5.2 Discoverability and Extended Listen stop/resume

The controller explicitly owns the listen lifecycle:

1. On `start`, disable the framework `WifiDisplayController` takeover, enter framework WFD sink mode (`MIRACAST_SINK = 2`), publish the canonical WFD identity, and enter listen/discovery.
2. While waiting for a source, keep framework listening alive and retain the successful STA/channel policy. Peer-list broadcasts are telemetry and eligibility input; they are not an implicit `connect()` request.
3. When a real GO negotiation, persistent invitation, or group transition begins, stop discovery/listen retries and mark the transition in flight. Do not keep an extended-listen keepalive or discovery loop racing the formation handshake.
4. After the framework reports a formed group, stop Extended Listen and all pre-group timers. The group interface and RTSP adapter now own the session.
5. On group removal, failed formation, cancellation, or terminal teardown, stop the active group adapter, restore the STA/network state, and resume listen only if the controller remains active and no new session generation has started.

The Extended Listen timing policy is explicit rather than scattered between `P2pManager` and Xposed hooks. The successful Windows trace's 10/10 ms extended-listen optimization is retained as a device/profile capability, not forced universally. A known-good device profile may enable the Xposed `p2pExtListen`/`configureExtListen` override during pre-group Windows-compatible listening and must disable it when listening stops. Devices whose drivers thrash on aggressive 10/10 or 100/100 timing use the framework-native timing; the controller must not periodically re-issue competing socket commands. In both cases the invariant is the same: listen timing is active only in `Discovering`/`GroupForming`, and is stopped before session teardown completes.

The STA optimization from the successful path is retained: when configured for sink mode, disconnect the STA and disable saved-network auto-reconnect so the single radio remains available to P2P. The adapter records every disabled network and restores it exactly once on stop or failure.

### 5.3 Role policy and connection attempts

The app remains a Primary Sink:

- use framework sink mode, never source mode;
- default GO intent is `0` for Windows compatibility;
- retain a bounded fallback attempt with configurable intent `8` when group formation or source compatibility requires sink-GO;
- use `WifiP2pConfig.NETWORK_ID_TEMPORARY` for headless fresh connects so stale persistent groups are not accidentally reinvoked;
- prefer a received persistent invitation and serialize group probes/fallbacks;
- only accept an explicitly matched peer for a vendor connection-request path.

The role is selected from the actual formed group, not from `forceAutonomousGo` alone. A configured autonomous GO request is a role preference; it does not bypass the controller's source/profile detection or create a second session path.

## 6. Automatic source and profile detection

### 6.1 Evidence collected before RTSP

Before opening RTSP, the controller records weak hints:

- actual P2P role;
- peer WFD enabled state, control port, device name, and advertised device info;
- persistent invitation metadata;
- group address/subnet and whether the peer is the GO;
- whether a configured role preference forced sink-GO.

These hints choose the initial adapter and timeout policy but do not claim a source vendor. A Windows source is expected to be the GO; an Android-source-compatible session may require the sink to be GO.

### 6.2 RTSP evidence and profile lock

The RTSP engine emits normalized evidence to a pure profile selector:

- direction of the first control connection and first request;
- `OPTIONS` `Server`, `User-Agent`, and `Require` headers;
- the requested parameter family and count;
- presence of `intel_*`, `microsoft_*`, or `wfd2_*` names;
- selected video/audio format and presentation URL shape;
- transport syntax, source/client port conventions, and whether the source expects sink-initiated `SETUP`/`PLAY`;
- source keepalive, second-PLAY, PAUSE, and teardown ordering.

Profile selection is monotonic. It begins with `Unknown` and locks to `Windows` when the source-GO role plus Microsoft/Intel evidence or the observed 26-parameter query is present. It locks to `Android` when the sink-GO inbound path and Android-compatible request/order/transport evidence are present. A contradictory or incomplete source remains `Unknown` and uses the conservative baseline; it is never classified solely from a device name.

The profile selector returns a policy value, not a new protocol implementation:

```text
CompatibilityProfile
  role policy
  control entry mode
  Public header policy
  capability response policy
  trigger/setup direction
  URL normalization policy
  PLAY pacing and second-PLAY policy
  keepalive/PAUSE policy
  listen timing preference
  failure/retry policy
```

After profile lock, all later behavior must consume this policy. A profile must not change the P2P role or reopen a second RTSP connection after media negotiation has started.

## 7. Unified RTSP engine and adapters

### 7.1 Common RTSP state machine

`RtspProtocol` remains the byte-oriented codec. Build one session engine around it with:

- case-insensitive header lookup;
- CRLF framing and byte-accurate `Content-Length`;
- bounded line/body sizes;
- CSeq transaction table keyed by integer CSeq and method;
- explicit request/response direction;
- typed parse/validation errors;
- one serialized write path per connection;
- method/trigger de-duplication;
- keepalive, PAUSE, method TEARDOWN, trigger TEARDOWN, EOF, timeout, and non-2xx handling.

`RtspServer` and the outbound client adapter only provide connection establishment and I/O ownership. They do not contain separate WFD behavior.

The engine retains the restricted sink `Public` header for the Windows profile. The internal state machine may still send sink-initiated `SETUP` and `PLAY`; the header is not a declaration that those transactions are initiated by the remote source.

### 7.2 Outbound adapter: Windows source-GO flow

This is selected when the formed group says the sink is a P2P client and the source is the GO, or when a source-compatible profile confirms a source RTSP server.

```text
Source (Windows, GO)                 Sink (P2P client)
---------------------                 ----------------
M1 OPTIONS ------------------------>
                         <---------- 200 + restricted Public
                         ----------> M2 OPTIONS
                         <---------- 200 + source Public/Server
M3 GET_PARAMETER ------------------>
                         <---------- request-filtered M3
M4 SET_PARAMETER ------------------>
                         <---------- 200 after validation
M5 SETUP trigger ------------------>
                         <---------- 200
                         ----------> M6 SETUP + bound client ports
                         <---------- 200 + Session/Transport
                         ----------> M7 PLAY + parsed Session
                         <---------- 200
RTP/RTCP -------------------------->
```

The adapter connects to the peer-advertised control port and uses the actual group-owner address. It binds local RTP/RTCP before acknowledging the SETUP trigger or sending M6. It normalizes a presentation URL containing `255.255.255.255` to the known source GO address, rejects unrelated/malformed URLs, validates selected formats and ports, parses the returned Session and all recognized Transport fields, and treats the first functioning media path—not merely a PLAY response—as streaming readiness.

The Windows profile preserves the observed 80 ms setup delay and approximately 150/200 ms PLAY pacing where device testing proves they are needed. The optional second PLAY is a policy switch and is never required for the Windows success contract.

### 7.3 Inbound adapter: Android source with sink GO

This is selected when the formed group says the sink is GO and the peer is a source client, or when the source opens the sink's advertised control port first.

```text
Sink (GO, server)                   Source (Android, client)
------------------                   ----------------------
accept TCP
<------------------ OPTIONS/M1
200 + profile Public -------------->
<------------------ GET_PARAMETER
request-filtered M3 -------------->
<------------------ SET_PARAMETER selected values
200 after validation -------------->
<------------------ SETUP or SETUP trigger
200 + one Session/Transport ------->
<------------------ PLAY
200 + Session ---------------------->
RTP/RTCP -------------------------->
```

The inbound adapter accepts Android request ordering and negotiated client/server port conventions without weakening validation. If the source's protocol variant uses a sink-initiated SETUP after a trigger, the same engine transitions to exactly one outbound SETUP transaction on the already accepted control connection. It must not run the inbound SETUP path and the reverse SETUP path concurrently. The selected direction is recorded in the session context before the first transport is created.

The sink-GO path must use the peer's actual address and advertised control port. An ARP lookup by peer MAC is a fallback only when the framework does not provide an address. It must not start an unconditional outbound probe while an inbound request is pending.

### 7.4 Capability and selected-parameter policy

`WfdCapabilities` becomes a serializer over the session capability snapshot and request set:

- answer every requested parameter that the active profile requires;
- use encoded-byte `Content-Length` and CRLF termination;
- return only truthful capabilities supported by the running decoder, audio output, transport, and RTCP implementation;
- return syntactically valid `none` for unsupported optional fields where permitted;
- omit WFD2 or other vendor fields only according to the selected compatibility profile and observed request/response contract;
- never let a settings toggle claim UIBC, M13/IDR, RTCP, HDCP, dynamic format change, or other unsupported behavior.

M4 acknowledgement occurs only after selected URL, video/audio formats, transport ports, optional fields, and profile constraints have been parsed. A malformed or unsupported selection returns a defined RTSP error and enters the profile's failure policy; it is not acknowledged with `200 OK` followed by a media failure.

## 8. Transport, SSRC, and RTCP

Introduce a session-owned RTP/RTCP transport adapter. It binds the requested local ports before SETUP and returns actual bound endpoints. The adapter:

- restores the full datagram length before every receive;
- parses RTP version, padding, extension, CSRC count, marker, payload type, sequence, timestamp, and SSRC;
- rejects malformed packets without killing the session;
- tracks sequence gaps and exposes loss metrics;
- filters or validates the negotiated source SSRC;
- parses RTCP independently, at minimum sender reports and session/SSRC identity;
- accepts odd and nonconsecutive remote ports and does not infer `rtcp = rtp + 1` from a response;
- stops RTP and RTCP idempotently and reports socket errors to the controller.

A session token ties the transport to one controller generation. A process-global `(rtpPort, rtcpPort)` rendezvous without a generation is not sufficient. The RTSP engine publishes the actual local endpoint and negotiated remote endpoint through the session record; `PlayerActivity` does not select ports or consume raw sockets.

RTCP is not advertised as supported until the parser, session/SSRC validation, and lifecycle are implemented and covered by tests. The default profile may accept an optional RTCP packet without using it for clock correction, but it must not claim a feature the implementation does not provide.

## 9. Media readiness and rendering

### 9.1 Renderer boundary

The controller creates or coordinates a media pipeline that accepts:

- complete Annex-B H.264 NAL/access units with PTS/DTS;
- negotiated LPCM frames with sample rate, channel count, format, and timestamps;
- a rendering-surface attachment/detachment event.

`TsDemuxer` receives RTP payload slices only. It owns TS synchronization, PAT/PMT assembly, continuity handling, PES reassembly, timestamp extraction, PID mapping, and LPCM framing. Unsupported stream types are ignored or reported as typed media errors without relabeling H.265 as H.264.

`MediaDecoderPipeline` gates output until SPS/PPS and a decodable IDR are available, handles only advertised SPS/format changes, retains timestamps, and applies bounded backpressure rather than silently treating a full input queue as success. Audio scheduling is tied to the media clock; immediate un-timestamped `AudioTrack.write()` is not the synchronization contract.

### 9.2 Surface lifecycle

`PlayerActivity` receives a typed surface event from the controller. It:

- creates the fullscreen surface;
- attaches/detaches it from the renderer;
- reports configuration changes;
- finishes on a typed session-ended event.

A surface becoming unavailable pauses or detaches rendering but does not bypass controller cleanup. Surface recreation must reattach to the same session generation without rebinding a second RTP receiver. The controller may delay `MediaStarting` until a surface exists, but the transport remains bound before RTSP SETUP so early RTP cannot race socket creation.

`FirstFrame` is emitted only after a decoder has accepted the required parameter sets and rendered a valid frame. Audio is optional when the source negotiates video-only or when truthful capability policy disables audio.

## 10. Cancellation, cleanup, and resource ownership

Every session has a cancellation token and generation. `stop(reason)` is idempotent and executes in a fixed order:

1. mark the generation stopping and reject new RTSP transactions;
2. send a valid RTSP teardown response/request when the control socket is usable and policy permits;
3. cancel connection attempts, delayed setup/play tasks, discovery timers, keepalive timers, and profile probes;
4. close the RTSP connection and all accepted connection handles;
5. stop RTP, RTCP, demux, decoder, audio, and renderer callbacks;
6. notify `PlayerActivity` through the typed session event and finish/reset its surface;
7. stop the formed P2P group according to lifecycle policy;
8. stop Extended Listen and supplicant monitors;
9. restore every temporarily disabled STA network exactly once;
10. release wake locks, foreground notification actions, and references to the session generation;
11. publish a terminal reason and return to `Idle`.

Socket EOF, source timeout, malformed framing, invalid URL, unsupported selected format, rejected transport, RTP/RTCP error, decoder failure, P2P disconnect, and user stop have distinct diagnostic reasons. A generic catch may log an exception, but it must still route through the same cleanup state machine and cannot leave the service in `Streaming`.

The controller must not broadcast a process-global `SESSION_END` as the only cleanup mechanism. Broadcasts may remain as a UI compatibility adapter, but resource release is controller-owned and occurs before the terminal event is emitted.

## 11. Compatibility and failure policy

### 11.1 Windows profile

The Windows profile is selected conservatively from source-GO role plus RTSP evidence. It preserves:

- restricted sink `Public` header;
- reverse sink-to-source control connection;
- client GO intent `0` and bounded fallback `8`;
- persistent invitation handling and temporary network IDs;
- no peer-list implicit connect;
- STA disconnect/saved-network suppression during sink mode;
- actual GO address and source-advertised control port;
- canonical WFD IE derived from the capability snapshot;
- requested-parameter M3 including Windows Intel/Microsoft families;
- parsed Session, variable Transport ports, SSRC, RTCP-FB-SSRC, timeout, and blocksize;
- pre-SETUP RTP/RTCP binding;
- optional second PLAY and observed setup/play pacing;
- H.264 hardware-first decoding without QCOM `KEY_LOW_LATENCY` and LPCM endian/descriptor handling.

The 10/10 Extended Listen optimization is selected only on a known-good device/profile. The controller must stop it at group formation and resume it only after a later listen cycle.

### 11.2 Android profile

The Android profile supports sink-GO/source-client sessions and source-specific RTSP ordering. It accepts inbound control, uses actual negotiated ports, and allows the source's selected transport convention when valid. It may use the Android double-PLAY convention, but it does not force that behavior on Windows. It never creates both an inbound and outbound media transport for one session.

### 11.3 Unknown and unsupported behavior

Unknown sources use the conservative baseline:

- primary sink WFD identity;
- H.264 formats known to decode;
- LPCM only when available;
- UIBC, content protection, M13/IDR, WFD2, Microsoft dynamic features, and unsupported RTCP features as `none` or omitted according to grammar;
- no fixed subnet/address assumptions;
- strict URL, codec, port, and session validation;
- bounded retry only for P2P/control connection failures.

A source that requires HDCP, an unsupported codec, malformed transport, or an unimplemented control feature is rejected with a typed compatibility failure. The implementation must not silently downgrade a protected or unsupported stream into a false successful session.

## 12. Affected files and implementation seams

Primary files to migrate behind the controller:

- `app/src/main/java/foxlost/miracast/sink/MiracastService.kt`
- `app/src/main/java/foxlost/miracast/sink/p2p/P2pManager.kt`
- `app/src/main/java/foxlost/miracast/sink/p2p/P2pReceiver.kt`
- `app/src/main/java/foxlost/miracast/sink/p2p/SupplicantEventMonitor.kt`
- `app/src/main/java/foxlost/miracast/sink/p2p/SupplicantWriter.kt`
- `app/src/main/java/foxlost/miracast/sink/rtsp/RtspServer.kt`
- `app/src/main/java/foxlost/miracast/sink/rtsp/RtspConnection.kt`
- `app/src/main/java/foxlost/miracast/sink/rtsp/RtspProtocol.kt`
- `app/src/main/java/foxlost/miracast/sink/rtsp/WfdCapabilities.kt`
- `app/src/main/java/foxlost/miracast/sink/media/RtpReceiver.kt`
- `app/src/main/java/foxlost/miracast/sink/media/TsDemuxer.kt`
- `app/src/main/java/foxlost/miracast/sink/media/MediaDecoderPipeline.kt`
- `app/src/main/java/foxlost/miracast/sink/PlayerActivity.kt`

Secondary policy/configuration files:

- `app/src/main/java/foxlost/miracast/sink/SettingsManager.kt`
- `app/src/main/java/foxlost/miracast/sink/xposed/XposedHook.kt`
- `app/src/main/java/foxlost/miracast/sink/MainActivity.kt` (only for exposing settings whose semantics change)
- package READMEs and the service notification adapter

The clean cutover removes the Service-local RTSP handshake and the `RtspConnection`-local duplicate setup state. It does not retain parallel legacy paths after all callers migrate.

## 13. Verification and acceptance criteria

### 13.1 Pure policy and state tests

Tests must prove:

- role/profile selection for source-GO Windows, sink-GO Android, and unknown sources;
- profile lock is monotonic and contradictory evidence selects conservative behavior;
- duplicate group events, duplicate SETUP triggers, duplicate PLAY responses, surface recreation, and stale callbacks cannot create duplicate transports;
- Extended Listen starts only in pre-group states, stops at group formation/cancellation, and resumes after failure/teardown only when the controller remains active;
- cancellation transitions every active state through one cleanup path and returns to `Idle` with a terminal reason.

### 13.2 Windows RTSP transcript tests

A deterministic transcript fixture must prove:

- M1 source OPTIONS and the exact restricted sink `Public` header;
- M2 sink OPTIONS with CSeq correlation independent of literal values;
- the requested Windows parameter set and byte-accurate M3 response;
- validated M4 selected format, URL normalization, LPCM selection, client ports, and optional-field policy;
- one M5 SETUP trigger, pre-bound local endpoints, M6 client ports, parsed Session, odd/nonconsecutive remote ports, SSRC, RTCP-FB-SSRC, timeout, and blocksize;
- M7 PLAY with the parsed Session and optional second PLAY policy;
- keepalive, PAUSE, method/trigger TEARDOWN, EOF, timeout, malformed message, and non-2xx cleanup;
- first RTP/RTCP packets accepted before or around PLAY without truncation or startup races.

### 13.3 Android-source transcript tests

Fixtures must cover:

- inbound TCP acceptance after sink-GO formation;
- Android OPTIONS/GET_PARAMETER/SET_PARAMETER/SETUP/PLAY ordering;
- the source-selected client/server port convention and actual bound sink port;
- the variant where a SETUP trigger requires one sink-initiated SETUP on the accepted connection;
- no duplicate inbound plus reverse setup;
- keepalive, PAUSE, both teardown forms, EOF, and source timeout;
- alternate negotiated subnets and peer addresses without fixed-IP assumptions.

### 13.4 Capability, transport, and media tests

Tests must prove:

- WFD IE, M3, M4 validation, and M6 transport derive from one session context;
- unsupported UIBC, WFD2, IDR, HDCP, dynamic format, and RTCP features are not falsely advertised;
- RTP parser handles CSRC, extension, padding, sequence gaps, variable datagram sizes, malformed packets, and unexpected SSRC;
- RTCP parser validates sender reports/session identity and stops independently;
- PAT/PMT/PES fragmentation, continuity recovery, H.264 SPS/PPS/IDR gating, audio-free streams, LPCM framing, timestamp propagation, and bounded malformed-input handling;
- first-frame readiness requires a decodable frame and not merely a PLAY response;
- surface detach/reattach does not create a second network receiver.

### 13.5 Device acceptance

On at least one API 32 and one API 34 device:

- the app advertises the canonical runtime-derived WFD identity without requiring an Xposed value override;
- Windows 11 discovers the sink, forms a source-GO group, completes M1–M7, renders the first IDR-backed frame, and sustains the captured session duration without RTP truncation or RTSP disconnect;
- Windows streaming succeeds with second PLAY enabled and disabled;
- the Android-source path succeeds with the sink as GO and reaches first frame using the source's negotiated transport;
- Extended Listen stops after group formation and resumes after teardown without stale timers or a second group transition;
- Windows source teardown and Android source teardown release RTSP, RTP, RTCP, media, P2P, STA, wake-lock, notification, and Activity resources;
- verification logs include source/profile evidence, P2P role and addresses, RTSP CSeq/Session, RTP/RTCP ports and SSRC, first-frame evidence, sustained counters, and terminal reason.

Passing a build alone is not acceptance. The implementation requires protocol transcripts plus device evidence for both role-driven flows.

## 14. Self-review

- The design has one session controller and one RTSP state machine; inbound and outbound adapters are connection-entry seams, not duplicate protocol implementations.
- P2P role is taken from actual group state, while source/profile detection is refined from RTSP evidence; neither is inferred from a fixed subnet or device name alone.
- Windows source-GO and Android sink-GO flows are both explicit, and the Android reverse-SETUP variant is constrained to one transport plan.
- The restricted Windows `Public` header is preserved without preventing internal sink-initiated SETUP/PLAY.
- Extended Listen has an explicit start/stop/resume lifecycle, and the 10/10 optimization is retained as a device/profile capability rather than contradicted by a universal override.
- Transport, Session, ports, SSRC, RTCP, and media readiness are session-owned; `PlayerActivity` has no network authority.
- Cancellation, EOF, timeout, malformed input, and teardown converge on one idempotent cleanup path.
- No unresolved TODO, TBD, placeholder implementation, or unapproved feature claim remains. API 32+ scope and unsupported protocol features are explicit.

## 15. Implementation status and self-review (2026-09-06)

The approved P2P stability slice is implemented behind the existing
`P2pManager`, `MiracastService`, and `AdaptiveSessionController` seams. It uses
three total P2P attempts, a 500 ms inter-attempt delay, a bounded 6 s
group/network readiness gate, serialized pre-group cleanup, and generation plus
attempt guards. `P2P-GROUP-STARTED` alone never starts RTSP. Windows source-GO
uses the validated group-owner address; Android sink-GO/source-client retains
the ARP-based peer resolution path.

Deterministic tests cover retry exhaustion and cleanup ordering, stale attempt
and generation callbacks, readiness timeout/predicates, and both role paths.
The full JVM unit-test task and release assembly were run. `git diff --check`
was clean. Lint was run but remains blocked by existing project findings
(API-30 WFD access under minSdk 29, Android 13 notification permission,
manifest permission/receiver issues, and warnings); those findings are not
silently suppressed by this implementation.

Official API references used for the readiness/serialization decisions:

- https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pManager
- https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback
