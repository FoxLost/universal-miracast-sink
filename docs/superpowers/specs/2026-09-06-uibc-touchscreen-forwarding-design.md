# UIBC Touchscreen Forwarding Design

**Date:** 2026-09-06  
**Status:** Historical original Generic-only design. The REA-driven implementation revision below supersedes its HIDC exclusions.

**Implementation revision:** The application now supports source-selected Generic
and HIDC USB touchscreen descriptors/reports, gesture-safe bounded queues and
neutral release, plus RTP loss/IDR recovery and audio-master presentation timing.
The current implemented contract and hardware limitations are documented in the
root README and package READMEs. The sections below retain the original design
for historical context; they are not claims that physical interoperability has
been verified.

## 1. Goal and scope

Add an opt-in Wi-Fi Display User Input Back Channel (UIBC) path that forwards touch input from the sink's fullscreen Android display to Windows and Android Miracast sources. The first implementation uses the general/easy approach: native Kotlin protocol code at the existing RTSP/session seam, one source-owned UIBC TCP session, and no JNI or platform input injection.

The supported input contract is WFD Generic touch with `SingleTouch` and `MultiTouch` capability, and `DOWN`, `UP`, and `MOVE` actions. The sink forwards touch events; it does not inject input into Android or Windows through `InputManager`, Accessibility, HIDC, or an OEM-specific API.

The feature remains disabled by default. Existing P2P, reverse-RTSP, RTP, playback, Windows capability, Android-source compatibility, and teardown behavior must remain unchanged when UIBC is disabled or unavailable.

## 2. Existing architecture and evidence

The implementation will use the repository's current deep session seam rather than introduce a second networking path:

- `rtsp/RtspSessionEngine.kt` owns byte-oriented RTSP parsing, request/response sequencing, WFD triggers, transport setup, and generation callbacks.
- `rtsp/RtspSessionCallbacks` is the typed seam from RTSP negotiation into the service.
- `rtsp/WfdCapabilities.kt` builds request-filtered M4 capability responses.
- `MiracastService.kt` owns the generation-scoped session, source address, RTSP connection, media hand-off, and idempotent cleanup.
- `PlayerActivity.kt` owns the fullscreen `TextureView`, decoded video dimensions, and Android lifecycle.
- `uibc/UibcSender.kt` is currently experimental and is not in the active streaming flow. Its current packet shape is not a valid implementation of the required Generic touch Describe structure, and it has no RTSP capability or M14/M15 lifecycle.

The design is based on the WFD UIBC v2.1 contract and the repository's Windows/Android observations:

- UIBC is optional and is negotiated separately from RTP.
- A source advertises its UIBC listening TCP port in `wfd_uibc_capability` in M4 and/or a later M14 `SET_PARAMETER`; the source must be listening before advertising the port.
- M15 `wfd_uibc_setting` enables or disables the negotiated path.
- One UIBC TCP connection is used for a session.
- Generic touch contains a message type and Describe length followed by a pointer description: pointer count, pointer ID, and 16-bit X/Y coordinates.
- The UIBC header carries version/T/reserved fields, category, and a 16-bit length; an optional RTP timestamp is present only when the T bit is set. The length covers the complete UIBC payload, including header/body/padding. Generic frames use even-byte padding.
- Touch actions are `DOWN=0`, `UP=1`, and `MOVE=2`; coordinates use a top-left origin and the negotiated video coordinate space, not raw sink pixels.
- Android's public APIs expose P2P but not arbitrary RTSP/UIBC or `InputManager` injection. Accessibility gesture dispatch is user-enabled and gesture-level, so it is not a transport or interoperability solution.

Normative/reference material: Wi-Fi Display Technical Specification v2.1 §4.11, §6.1.15–16, and §6.4.14–15; Intel WDS UIBC capability grammar; and the `miracle-uibcctl` packet implementation used as a packet-level corroboration.

## 3. Architecture

### 3.1 UIBC session module

Introduce a deep module in `app/src/main/java/foxlost/miracast/sink/uibc/UibcSession.kt` with a small interface:

```kotlin
interface UibcSession {
    fun start(endpoint: UibcEndpoint)
    fun submit(event: TouchEvent)
    fun close(reason: String = "closed")
}
```

The concrete implementation owns the one TCP socket, one serialized writer, a bounded event queue, framing, partial writes, and idempotent close. It accepts events only for the active session generation. It may coalesce pending `MOVE` events for the same pointer, but it must never reorder or discard `DOWN` or `UP`. It must not create one thread per touch event.

`UibcEndpoint` contains the connected source address, validated source UIBC port, and the negotiated Generic capability (`SingleTouch`, `MultiTouch`, or both). `TouchEvent` contains the action and one or more pointer records in video coordinates. The public session interface does not expose a socket, Android `MotionEvent`, or RTSP message strings.

`UibcSender.kt` should be replaced by, or reduced to, an adapter over this module. The current malformed encoder must not remain as a second implementation.

### 3.2 Pure protocol codec

Add `app/src/main/java/foxlost/miracast/sink/uibc/UibcCodec.kt`. It encodes only the supported Generic touch subset:

1. Build the Generic touch message using the WFD-defined touch message type.
2. Encode the Describe length and pointer count/records exactly as specified.
3. Encode pointer IDs and X/Y as unsigned big-endian 16-bit values.
4. Build the UIBC header with version `000`, timestamp bit clear (`T=0` in the initial scope), reserved bits zero, Generic category, and the correct complete-payload length.
5. Add required even-byte padding.
6. Return one immutable byte array for the writer to send with a write-all loop.

The codec must not reuse the current `UibcSender` layout, which treats the message as a key event, omits the required touch Describe fields, and uses an incorrect simplified length model. HIDC, keyboard, mouse, zoom, scroll, rotate, timestamps, and vendor extensions are not encoded.

### 3.3 RTSP negotiation seam

Extend `RtspSessionEngine.kt` and `RtspSessionCallbacks` with typed UIBC negotiation callbacks, for example:

```kotlin
fun onUibcEndpoint(generation: Long, endpoint: UibcEndpoint)
fun onUibcDisabled(generation: Long, reason: String)
```

The engine must parse UIBC parameters wherever a source sends them:

- M4 response to a sink-issued capability query;
- M14 `SET_PARAMETER` containing selected UIBC information; and
- M15 `SET_PARAMETER` containing `wfd_uibc_setting`.

Parsing must be case-insensitive for parameter names, tolerate normal WFD whitespace, reject malformed ports and unsupported categories, and preserve the distinction between capability advertisement and enablement. A capability port is not sufficient to start sending until the source has enabled UIBC (when the source uses M15) and the sink setting is enabled.

`WfdCapabilities.kt` remains request-filtered. It must report only truthful sink capabilities. Because this feature is sink-to-source forwarding, the sink must not claim to host an inbound UIBC listener merely because it can send to a source. Unless the opposite direction is separately implemented, the sink-side capability remains `port=none`/the grammar's equivalent. The source's advertised endpoint is stored in the per-session negotiation model and is the endpoint used by `UibcSession`.

The existing Windows-facing RTSP `Public` header remains exactly the restricted value already required by the repository. UIBC is additive and must not change SETUP/PLAY timing, CSeq matching, presentation URL validation, or RTP transport negotiation.

### 3.4 Service ownership and Activity input

`MiracastService.kt` creates the UIBC session only after a valid source endpoint is negotiated. It supplies the current source IP and generation, owns the session until teardown, and closes it from all existing cleanup paths: source TEARDOWN/EOF, P2P group loss, service stop, failed attempt, and generation replacement.

`PlayerActivity.kt` attaches a touch listener to the displayed video surface. It converts Android `MotionEvent` data through `TouchMapper` and submits typed events to the current generation's UIBC session. High-rate MOVE traffic should use a process-local generation registry/callback analogous to `RtpEndpointRegistry`, not broadcasts. The Activity must not own the RTSP socket or decide the source port.

`SettingsManager.kt` keeps the existing opt-in setting, but its meaning becomes “allow UIBC when a source advertises a supported capability,” not “claim UIBC unconditionally.” `MainActivity.kt` may retain the existing setting label and should explain that source support and M15 enablement are also required.

## 4. Capability and source compatibility contract

### Windows sources

Windows may query the full WFD, Intel, Microsoft, and WFD2 parameter families. Existing request-filtered responses remain complete and syntactically valid. Windows UIBC selection may arrive with M4 or M14 and may advertise a source TCP port such as 50000. The sink validates and stores that port, but only opens UIBC after the source is advertising/listening and the feature is enabled on both sides.

A Windows UIBC failure is optional-feature failure: it must be logged and exposed diagnostically without aborting RTSP/RTP video setup. A malformed mandatory RTSP body still follows the existing RTSP validation/error policy.

### Android sources

Android sources may omit UIBC, send UIBC parameters later, use M14/M15 ordering different from Windows, or ignore optional UIBC fields. Absence or rejection of UIBC must not prevent normal Android negotiation, including the existing optional second PLAY behavior. No Android hidden API or Accessibility permission is assumed.

### Capability grammar

The parser accepts only capability tokens that map to the implemented Generic `SingleTouch`/`MultiTouch` subset. It must not infer support from the settings toggle, a nonzero-looking string, or an unrelated HIDC token. A source port must be an integer in `1..65535`; the endpoint is associated with the already-connected source address unless a source-specific profile explicitly permits another address.

## 5. Coordinate mapping

Add `app/src/main/java/foxlost/miracast/sink/uibc/TouchMapper.kt` as a pure module. Its input is a touch point in the Activity/window coordinate space, the actual displayed video rectangle, the decoded video width/height, orientation, and pointer ID. Its output is a `TouchEvent` in the negotiated video coordinate space.

The mapper must:

- invert `PlayerActivity`'s actual centered fit/crop transform rather than divide by the sink display width/height;
- account for letterboxing, portrait crop, fullscreen insets, and orientation changes;
- use the decoded/negotiated video width and height as the coordinate domain;
- use top-left origin;
- clamp edge values to the valid video rectangle and normalize to unsigned 16-bit values `[0, 65535]`;
- preserve stable Android pointer IDs across a gesture; and
- encode all active pointers for `MultiTouch`, while using one pointer for `SingleTouch` and handling extra pointers deterministically (ignore/reject with a diagnostic event).

The initial UI scope supports `ACTION_DOWN`, `ACTION_POINTER_DOWN`, `ACTION_MOVE`, `ACTION_POINTER_UP`, and `ACTION_UP` by translating them to the WFD Generic `DOWN`, `MOVE`, and `UP` actions with the active pointer description required by the protocol. It does not synthesize events after Activity destruction or orientation changes.

## 6. Lifecycle and error handling

The UIBC session follows the existing generation-scoped lifecycle:

1. RTSP parses a source capability and validates the port/categories.
2. M15 or the source profile indicates UIBC enabled; otherwise no UIBC socket is opened.
3. The service starts one UIBC TCP connection using the source IP and advertised port.
4. Touch events are queued only while that generation and socket are active.
5. Connect failure, write failure, EOF, malformed negotiation, source disable, RTSP teardown, P2P loss, Activity destruction, or service stop closes the session idempotently.
6. Late callbacks/events from an old generation are ignored.

UIBC connect/write failure must never crash its worker and must not tear down healthy RTP playback. It emits bounded `UIBC`/`ERROR` diagnostics and makes the session unavailable until a new WFD generation. Socket and worker cleanup must complete before the generation is considered stopped. A duplicate capability, enable, disable, or close operation is harmless.

## 7. Verification plan

### Deterministic JVM tests

Add focused tests without requiring Android hardware:

- `app/src/test/java/foxlost/miracast/sink/uibc/UibcCodecTest.kt`: exact byte vectors for SingleTouch and MultiTouch DOWN/UP/MOVE; pointer counts/IDs; big-endian coordinates; header/category/version; full-payload length; even padding; and invalid input rejection.
- `TouchMapperTest.kt`: corners, center, letterbox, portrait crop, fullscreen offsets, orientation/size changes, clamping, single-pointer lifecycle, and multi-pointer records.
- `UibcCapabilityParserTest.kt`: case/whitespace variations, M4/M14 capability forms, valid/invalid ports, Generic versus HIDC, SingleTouch versus MultiTouch, and `wfd_uibc_setting` enable/disable values.
- Extend `rtsp/RtspSessionEngineTranscriptTest.kt`: M4/M14/M15 ordering, callback generation, source endpoint validation, no endpoint/no socket, duplicate triggers, and UIBC errors that leave RTSP alive.
- Add a loopback UIBC session test with a fake TCP source: one connection per generation, serialized writes, partial-write handling, MOVE coalescing without DOWN/UP loss, EOF handling, and idempotent close.
- Add capability-response coverage for `WfdCapabilities.kt` proving disabled/default behavior does not claim unsupported inbound UIBC.

### Hardware interoperability

On a Windows Miracast source and a compatible Android source, verify that video/audio streaming succeeds with UIBC disabled, with no UIBC capability, and with UIBC capability enabled. For enabled sessions verify source receipt of SingleTouch and MultiTouch DOWN/MOVE/UP at known video coordinates. Exercise source disable, RTSP TEARDOWN, P2P loss, Activity destruction, service stop, and reconnect/new-generation behavior. Device-level P2P, firewall, source UIBC listener, RTSP, and decoder behavior remains hardware/vendor dependent; an emulator is not sufficient evidence.

## 8. Phased implementation scope

1. **Protocol foundation:** introduce immutable endpoint/event models, robust capability parser, exact Generic touch codec, and codec/parser tests. Keep the feature disabled by default.
2. **RTSP/session integration:** add M4/M14/M15 parsing and typed callbacks; create the generation-owned `UibcSession`; add loopback and RTSP transcript tests.
3. **Touch path:** add `TouchMapper`, Activity forwarding, SingleTouch/MultiTouch pointer lifecycle, and mapping tests.
4. **Interoperability hardening:** run Windows and Android hardware sessions, fix only observed source/profile differences, and verify bounded diagnostics and cleanup.

## 9. Explicitly out of scope

- HIDC keyboard, mouse, USB, or vendor-specific input.
- Android `InputManager` injection, Accessibility-based injection, or source-side input emulation.
- UIBC timestamps (`T=1`), retransmission, automatic reconnect, or multiple concurrent UIBC TCP sessions.
- JNI/native `libwds` integration.
- P2P/GO negotiation redesign, RTSP `Public` header changes, RTP transport redesign, HDCP, WFD2 codecs, MICE, or general Miracast source support beyond Windows and Android.
- Persisting touch events, exporting input telemetry, or exposing raw sockets to UI code.

## 10. Assumptions and acceptance criteria

### Assumptions

- The source UIBC listener is reachable at the source IP used by the existing RTSP session and follows the advertised-port contract.
- The source's UIBC capability and setting can be observed in M4/M14/M15 on the RTSP connection; sources that do not advertise it are treated as unsupported.
- Decoded video dimensions represent the coordinate space expected by the source, including any source-selected format changes already handled by the media pipeline.
- The current privileged/system deployment and `INTERNET` permission remain available; no new platform or Accessibility privilege is required.
- Initial acceptance includes Android API 29+ repository compatibility, with Windows and Android source behavior validated on physical devices.

### Acceptance criteria

- UIBC is off by default and disabled sessions open no UIBC socket or worker.
- With a valid Windows or Android source capability plus enablement, exactly one generation-scoped TCP UIBC session is used.
- The codec emits spec-valid Generic SingleTouch and MultiTouch DOWN/UP/MOVE frames with correct length, category, pointer records, coordinate endianness, and padding.
- Touch coordinates map to the negotiated video dimensions across fit, crop, fullscreen, and orientation cases; pointer IDs and action ordering are preserved.
- Missing, delayed, malformed, unsupported, or disabled UIBC never prevents otherwise valid RTSP/RTP playback.
- All teardown, failure, P2P loss, Activity destruction, and generation replacement paths close the UIBC socket and ignore stale events without uncaught worker exceptions.
- JVM tests cover codec bytes, parser variants, coordinate transforms, pointer lifecycle, RTSP negotiation, and TCP session ordering; physical Windows and Android sessions demonstrate the enabled path.
- No implementation claims HIDC, input injection, timestamps, reconnect, or other explicitly out-of-scope behavior.

## 11. Self-review

- No unfinished sections or unresolved protocol decisions remain; unsupported decisions are explicit scope boundaries.
- The design distinguishes the source's advertised UIBC endpoint from the sink's own capability response and does not let the settings toggle make a false capability claim.
- The design preserves the existing restricted RTSP `Public` header, reverse-RTSP flow, Windows parameter completeness, Android optional behavior, and generation-scoped cleanup.
- SingleTouch and MultiTouch are represented consistently in capability parsing, event mapping, codec framing, tests, and acceptance criteria.
- Concrete files and symbols match the current repository architecture, while new modules are limited to the UIBC seam, pure mapping/codec logic, and their tests.
- The requested change is design-only: this document contains no implementation, build, deployment, or feature-branch instructions beyond the implementation contract itself.
