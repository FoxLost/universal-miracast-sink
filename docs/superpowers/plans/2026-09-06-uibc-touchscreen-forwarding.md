# UIBC Touchscreen Forwarding Implementation Plan

**Date:** 2026-09-06  
**Design:** `docs/superpowers/specs/2026-09-06-uibc-touchscreen-forwarding-design.md`  
**Target branch:** `feature/uibc-touchscreen-forwarding`  
**Scope:** Historical original Generic-only implementation plan. The REA-driven implementation revision below supersedes its original HIDC/media exclusions.

**Implementation revision:** Generic and HIDC USB touch, gesture/queue lifecycle
fixes, RTP reordering/IDR recovery, LPCM PTS, and shared playback timing are now
implemented. JVM regression coverage and actual TCP/UDP loopback smoke scenarios
exercise the software paths. The original phases/checklist below remain a record
of the first plan; physical Windows/Android input, codec/audio presentation, and
vendor-platform acceptance must not be marked verified without device evidence.

## Implementation invariants

- UIBC remains disabled by default and is an optional feature of an otherwise healthy WFD session.
- The source owns the UIBC listening TCP endpoint. The sink connects only to a validated source-advertised port after capability/setting negotiation.
- Exactly one UIBC TCP connection belongs to one `AdaptiveSessionController` generation.
- All UIBC work is behind typed seams: no raw socket or RTSP strings reach `PlayerActivity`.
- Generic WFD touch only: `SingleTouch` and `MultiTouch`, `DOWN`, `UP`, and `MOVE`; no HIDC, keyboard, mouse, timestamps, reconnect, or JNI.
- Source video dimensions, not sink display dimensions, are the coordinate domain.
- UIBC connect/write/parse failures never tear down valid RTSP/RTP playback.
- Existing untracked capture/debug artifacts must remain untouched.

## Phase 0 — establish the implementation contract and fixtures

### Files and symbols

- `app/src/main/java/foxlost/miracast/sink/uibc/UibcSession.kt`
  - Define immutable `UibcEndpoint`, `UibcCapability`, `TouchAction`, `TouchPoint`, and `TouchEvent` models.
  - Define the small `UibcSession` interface: `start(endpoint)`, `submit(event)`, `close(reason)`.
  - Keep models independent of Android `MotionEvent` and RTSP message types.
- `app/src/main/java/foxlost/miracast/sink/uibc/UibcCapabilityParser.kt` (new)
  - Define the source capability parser and normalized result types.
  - Treat parameter names and capability tokens case-insensitively; trim ordinary WFD whitespace.
  - Reject malformed ports, unsupported category-only HIDC claims, duplicate contradictory values, and unsupported settings.
- `app/src/test/java/foxlost/miracast/sink/uibc/UibcCapabilityParserTest.kt` (new)
  - Add fixtures for Windows M4/M14 forms, Android delayed/omitted forms, `SingleTouch`, `MultiTouch`, combined capability, `none`, valid ports, invalid ports, whitespace, casing, and `wfd_uibc_setting` enable/disable values.

### Procedure

1. Model endpoint address, port, supported Generic capabilities, source enablement, and generation separately. Do not make `SettingsManager.uibcEnabled` imply source support.
2. Make parser output deterministic and side-effect free so RTSP and unit tests use the same implementation.
3. Define explicit parser errors/results that allow the RTSP engine to ignore an optional malformed UIBC field without failing video negotiation.

### Proof command

```bash
./gradlew :app:testDebugUnitTest --tests 'foxlost.miracast.sink.uibc.UibcCapabilityParserTest'
```

## Phase 1 — implement and verify exact UIBC Generic framing

### Files and symbols

- `app/src/main/java/foxlost/miracast/sink/uibc/UibcCodec.kt` (new)
  - Implement `encodeTouch(event: TouchEvent): ByteArray`.
  - Encode the WFD-defined Generic touch message type, Describe length, pointer count, pointer IDs, and 16-bit X/Y fields.
  - Encode version `000`, `T=0`, zero reserved fields, Generic category, full-payload length, and required even padding.
  - Use unsigned big-endian values and reject coordinates/pointer sets that violate the model.
- `app/src/main/java/foxlost/miracast/sink/uibc/UibcSender.kt`
  - Remove the existing ad-hoc encoder and either delete the class or make it a narrow compatibility adapter over `UibcSession`/`UibcCodec`.
  - Do not preserve the current key-event type, simplified body, or per-event thread behavior.
- `app/src/test/java/foxlost/miracast/sink/uibc/UibcCodecTest.kt` (new)
  - Add golden byte vectors for SingleTouch and MultiTouch `DOWN`, `MOVE`, and `UP`.
  - Assert category/version/T bit, Generic message type, Describe length, pointer count and IDs, big-endian coordinates, complete-payload length, padding, and invalid input handling.
  - Include one-pointer and multiple-pointer records and boundary coordinates `0` and `65535`.

### Procedure

1. Build the inner Generic Describe into a byte buffer, then calculate the outer UIBC length from final bytes rather than hardcoding offsets.
2. Keep timestamp support absent from the public model so the initial encoder always emits `T=0`.
3. Return an immutable frame and leave socket ownership to `UibcSession`.
4. Use normative WFD vectors/reference packet captures to validate the byte layout; do not infer framing from the current `UibcSender.kt` comments.

### Proof command

```bash
./gradlew :app:testDebugUnitTest --tests 'foxlost.miracast.sink.uibc.UibcCodecTest'
```

## Phase 2 — add one serialized, generation-owned TCP session

### Files and symbols

- `app/src/main/java/foxlost/miracast/sink/uibc/UibcSession.kt`
  - Implement the concrete session with one socket, one writer loop, bounded queue, `writeFully`, and idempotent `close`.
  - Serialize events in order; coalesce only queued `MOVE` events for the same pointer set.
  - Reject stale-generation submissions and stop accepting events after close.
  - Emit bounded diagnostics on connect/write/EOF failure without throwing from the worker.
- `app/src/main/java/foxlost/miracast/sink/uibc/UibcSessionRegistry.kt` (new)
  - Provide a process-local generation-keyed registration/lookup seam for `PlayerActivity` to submit events without broadcasts.
  - `register(generation, session)`, `session(generation)`, and `unregister(generation)` must be thread-safe and generation-safe.
  - Registry must not own lifecycle; `MiracastService` remains the owner and clears it during cleanup.
- `app/src/main/java/foxlost/miracast/sink/DebugEventLog.kt`
  - Add `DebugEventCategory.UIBC` for bounded negotiation/session diagnostics. Use `ERROR` for failures only when they are actionable errors; use `UIBC` for normal capability, connection, enable/disable, and close events. Update `DebugEventLogTest.kt` and package notes for the additional category.
- `app/src/test/java/foxlost/miracast/sink/uibc/UibcSessionTest.kt` (new)
  - Use a loopback `ServerSocket` fake source.
  - Verify one connection per generation, serialized frames, partial writes, MOVE coalescing without DOWN/UP loss, EOF handling, worker termination, stale submissions, and idempotent close.
  - Assert a UIBC failure does not invoke an RTSP/session-fatal callback.

### Procedure

1. Connect asynchronously only after `start(endpoint)` receives a validated endpoint.
2. Keep the queue bounded; define a deterministic behavior when full (coalesce MOVE first, never silently lose a required edge action).
3. Close the output/socket and interrupt/join the writer during `close`; make repeated close safe.
4. Register only after service ownership is established and unregister before generation invalidation completes.

### Proof command

```bash
./gradlew :app:testDebugUnitTest --tests 'foxlost.miracast.sink.uibc.UibcSessionTest'
```

## Phase 3 — integrate capability negotiation at the RTSP seam

### Files and symbols

- `app/src/main/java/foxlost/miracast/sink/rtsp/RtspSessionEngine.kt`
  - Extend `RtspSessionCallbacks` with the concrete methods `onUibcEndpoint(generation: Long, endpoint: UibcEndpoint)` and `onUibcDisabled(generation: Long, reason: String)`.
  - Parse UIBC capability and setting bodies from M4 responses, M14 `SET_PARAMETER`, and M15 `SET_PARAMETER`.
  - Add private helpers such as `learnUibc(body)`, `applyUibcSetting(body)`, and `maybePublishUibcEndpoint()` that preserve capability-versus-enable state.
  - Inspect response bodies without disturbing existing CSeq/pending handling; keep SETUP/PLAY/TEARDOWN behavior unchanged.
  - Treat malformed/unsupported optional UIBC as a UIBC-only outcome; do not return a fatal RTSP error unless the existing mandatory parameter policy requires it.
- `app/src/main/java/foxlost/miracast/sink/rtsp/RtspConnection.kt`
  - Implement no-op UIBC callbacks in the legacy constructor adapter so existing inbound/test construction remains source-compatible.
- `app/src/main/java/foxlost/miracast/sink/rtsp/WfdCapabilities.kt`
  - Preserve request filtering and `wfd_uibc_capability: none` for the sink's unimplemented inbound listener direction.
  - Ensure the UIBC settings toggle cannot cause an unsupported capability claim.
  - Add tests for requested UIBC capability response and default/disabled behavior.
- `app/src/main/java/foxlost/miracast/sink/AdaptiveSession.kt`
  - Add immutable per-generation `UibcNegotiation` state with `endpoint`, `capabilities`, `sourceEnabled`, and `published` fields.
  - Add guarded controller methods to apply endpoint/setting callbacks and ignore stale generations.
- `app/src/main/java/foxlost/miracast/sink/MiracastService.kt`
  - Extend `createConnection()` callbacks to create/register the UIBC session only when `SettingsManager.uibcEnabled` and source negotiation both allow it.
  - Pass the connected source IP and validated source port; never use a UI/configured port.
  - Close/unregister UIBC from `finishSession`, `stopSinkAndCleanup`, `onP2pGroupDisconnected`, `onP2pAttemptFailed`, and new-session replacement.
  - Keep RTSP/RTP success independent of UIBC connect failure.
- `app/src/test/java/foxlost/miracast/sink/rtsp/RtspSessionEngineTranscriptTest.kt`
  - Add Windows M4/M14/M15 transcripts, Android omission/delayed-order transcripts, duplicate enable/disable cases, stale-generation callbacks, invalid port/category cases, and assertion that UIBC failure leaves PLAY/RTP path alive.
- `app/src/test/java/foxlost/miracast/sink/rtsp/WfdCapabilitiesTest.kt` (new)
  - Assert disabled/default responses do not claim an inbound UIBC listener and remain request-filtered.

### Procedure

1. Parse source-advertised UIBC capability before attempting a UIBC socket.
2. Require M15 enablement for Windows profiles. For Android profiles, treat a valid selected M14 capability as enabled when M15 is absent, but honor an explicit M15 disable; this is the only source-profile difference.
3. Publish one endpoint callback per valid negotiated endpoint and make duplicate capability messages idempotent.
4. Preserve the existing restricted `Public` header, Microsoft/Intel response completeness, reverse-RTSP sequencing, and Android second-PLAY profile.

### Proof commands

```bash
./gradlew :app:testDebugUnitTest --tests 'foxlost.miracast.sink.rtsp.RtspSessionEngineTranscriptTest'
./gradlew :app:testDebugUnitTest --tests 'foxlost.miracast.sink.rtsp.WfdCapabilitiesTest'
```

## Phase 4 — implement video-space coordinate mapping

### Files and symbols

- `app/src/main/java/foxlost/miracast/sink/uibc/TouchMapper.kt` (new)
  - Implement pure `map(MotionPoint, VideoLayout, videoWidth, videoHeight, pointerId): TouchPoint` functions without depending on Activity or socket state.
  - Invert the actual centered fit/crop transform used by `PlayerActivity.adjustViewSize()`.
  - Account for displayed rectangle offsets, fullscreen insets, orientation changes, and decoded video dimensions.
  - Clamp to video bounds and normalize to unsigned `[0, 65535]`.
  - Define deterministic pointer-ID and out-of-rectangle behavior.
- `app/src/test/java/foxlost/miracast/sink/uibc/TouchMapperTest.kt` (new)
  - Cover corners, center, letterbox, portrait crop, centered offsets, fullscreen insets, orientation/size changes, clamping, and normalized boundary values.
  - Verify stable pointer IDs and SingleTouch/MultiTouch pointer selection.

### Procedure

1. Derive the displayed video rectangle from the actual `TextureView` bounds and `videoWidth/videoHeight`, not screen size.
2. Convert Activity/window touch coordinates to video coordinates by subtracting the displayed rectangle origin and dividing by the display scale.
3. For cropped layouts, map only the visible video region and clamp to its nearest video edge; ignore a DOWN that begins in a letterbox bar, and clamp an active gesture that moves into a bar. Cover both decisions in tests.
4. Convert Android pointer actions to the three UIBC actions while preserving active pointer records.

### Proof command

```bash
./gradlew :app:testDebugUnitTest --tests 'foxlost.miracast.sink.uibc.TouchMapperTest'
```

## Phase 5 — wire Activity touch events to the current session

### Files and symbols

- `app/src/main/java/foxlost/miracast/sink/PlayerActivity.kt`
  - Attach one touch listener to the displayed surface/container.
  - Translate `ACTION_DOWN`, `ACTION_POINTER_DOWN`, `ACTION_MOVE`, `ACTION_POINTER_UP`, and `ACTION_UP` into typed Generic touch events through `TouchMapper`.
  - Look up the session by `sessionGeneration()` in `UibcSessionRegistry`; drop events if no compatible session is present.
  - Clear/unregister the Activity-side association on `onDestroy`; do not close the service-owned session directly.
  - Keep existing decoder/surface lifecycle, orientation handling, and session-end receiver behavior unchanged.
- `app/src/main/java/foxlost/miracast/sink/SettingsManager.kt`
  - Retain `uibcEnabled` default `false`; document that source capability plus the applicable Windows M15 or Android profile policy is required.
- `app/src/main/java/foxlost/miracast/sink/MainActivity.kt`
  - Update the existing UIBC setting helper text to describe opt-in/source support; do not add a second toggle or change session start behavior.

### Procedure

1. Keep a stable pointer map for the gesture and emit all active pointers for MultiTouch.
2. For a SingleTouch-only endpoint, forward the designated active pointer and handle additional pointers deterministically without corrupting the first pointer's DOWN/UP lifecycle.
3. Ensure no touch callback can submit into a previous generation after service teardown or orientation recreation.

### Proof command

```bash
./gradlew :app:testDebugUnitTest --tests 'foxlost.miracast.sink.uibc.TouchMapperTest' --tests 'foxlost.miracast.sink.uibc.UibcSessionTest'
```

## Phase 6 — update package notes and targeted diagnostics

### Files and symbols

- `app/src/main/java/foxlost/miracast/sink/uibc/README.md`
  - Replace experimental/malformed packet notes with the implemented Generic SingleTouch/MultiTouch contract, capability/setting prerequisites, coordinate domain, and lifecycle limits.
  - Explicitly state that HIDC, timestamps, reconnect, and Android input injection remain unsupported.
- `app/src/main/java/foxlost/miracast/sink/README.md`
  - Add UIBC to the architecture and lifecycle sections, including one generation-owned TCP session and optional-failure semantics.
- `README.md`
  - Update the settings/technical details and verification caveat only after hardware behavior is demonstrated; do not claim universal source support.
- `scripts/logcat.sh`
  - Keep `MiracastUIBC` included in the application tag set; this tag already exists, so no filter behavior change is expected.
- `app/src/test/java/foxlost/miracast/sink/DebugEventLogTest.kt`
  - Update category expectations for the concrete `DebugEventCategory.UIBC` addition.

### Procedure

1. Use existing bounded `DebugEventLog` semantics; do not create a UIBC event bus.
2. Rate-limit connect/write failures and avoid logging every MOVE or packet.
3. Keep diagnostic messages free of raw touch payloads or private source data.

### Proof command

```bash
./gradlew :app:testDebugUnitTest --tests 'foxlost.miracast.sink.DebugEventLogTest'
```

## Phase 7 — full scoped verification

### JVM test command

Run the complete existing unit-test suite after all phases land:

```bash
./gradlew :app:testDebugUnitTest
```

The suite must cover existing adaptive-session, RTSP protocol/session, media, P2P, service, and debug tests in addition to the new UIBC tests. Do not replace the full suite with only UIBC tests.

### Optional build proof

When implementation is complete and the Android SDK is available, run the repository's normal debug/release verification separately from the design/plan work:

```bash
./gradlew :app:assembleDebug
./scripts/build.sh
```

Do not package, install, sign, or deploy as part of this plan's documentation-only change.

### Physical Windows verification

1. Enable the sink's UIBC setting; start a Windows Miracast projection.
2. Confirm RTSP M3/M4/M14/M15 logs show a valid Generic capability, source port, and enablement before the UIBC TCP connect.
3. Confirm video/audio reaches PLAY even when UIBC connect is blocked or the source omits UIBC.
4. With UIBC enabled, perform SingleTouch DOWN/MOVE/UP and MultiTouch pointer-down/move/pointer-up sequences.
5. Confirm source-visible coordinates match known video corners/center and that event ordering is preserved.
6. Trigger source disable, RTSP TEARDOWN, P2P loss, Activity destruction, service stop, and a new session. Confirm the old socket/worker is closed and no stale events are sent.

Use existing read-only diagnostics without changing network state:

```bash
./scripts/logcat.sh app
./scripts/capture-debug.sh
```

### Physical Android-source verification

1. Run the same baseline with UIBC disabled and with an Android source that omits UIBC.
2. Confirm normal Android RTSP negotiation and optional second PLAY are unchanged.
3. If the Android source advertises Generic UIBC, verify delayed M14/M15 ordering, one TCP session, SingleTouch/MultiTouch events, coordinate mapping, and teardown.
4. Confirm omission or rejection of UIBC never prevents RTP playback.

## Acceptance checklist

- [ ] Default settings open no UIBC socket or worker.
- [ ] Windows and Android capability forms parse case-insensitively with strict port/category validation.
- [ ] M4/M14 capability and M15 setting ordering is handled idempotently.
- [ ] Exactly one generation-scoped UIBC TCP session is created.
- [ ] Generic touch frames have correct version/category/length/Describe/pointer/endian/padding fields.
- [ ] SingleTouch and MultiTouch DOWN/UP/MOVE pointer lifecycles preserve ordering and IDs.
- [ ] Coordinates use negotiated video dimensions and pass fit/crop/orientation tests.
- [ ] Missing, delayed, malformed, unsupported, or failed UIBC leaves RTSP/RTP playback healthy.
- [ ] Teardown, P2P loss, Activity destruction, service stop, and generation replacement close sockets and reject stale events.
- [ ] Existing unit tests and all new UIBC tests pass.
- [ ] Physical Windows and Android checks demonstrate the enabled and graceful-absence paths.
- [ ] Out-of-scope HIDC, input injection, timestamps, reconnect, JNI, and unrelated protocol changes are absent.
