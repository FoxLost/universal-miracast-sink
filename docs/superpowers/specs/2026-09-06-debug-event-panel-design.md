# In-App Debug Event Panel Design

**Date:** 2026-09-06  
**Status:** Approved design and implementation contract

## Goal and scope

Expose the most recent diagnostics for the active Miracast sink session in a fixed bottom Compose panel owned by `MainActivity`. The panel displays at most 12 events, does not scroll, and uses the categories `SINK`, `P2P`, `WFD`, `DHCP`, `RTSP`, `RTP`, `MEDIA`, and `ERROR`.

This feature is intentionally app-local and in-memory. It is not a general event bus, is not persisted, and does not replace Android `Log` output. `MainActivity.kt` and its resources remain UI-owned and are consumed through the stable contract below.

## Event contract

`foxlost.miracast.sink.DebugEvent` is an immutable value:

```kotlin
data class DebugEvent(
    val timestampMs: Long,
    val category: DebugEventCategory,
    val message: String,
)
```

`DebugEventCategory` is the closed enum containing exactly the eight panel categories. `DebugEventLog.events` is a read-only `StateFlow<List<DebugEvent>>`. The list is ordered oldest-to-newest, contains no more than `MAX_EVENTS` (12), and is replaced atomically on each accepted event. UI may collect this flow with Compose and render the list in order. `DebugEventLog.clear()` empties the list and resets deduplication/rate-limit state.

The sink provides two explicit noise controls: `recordOnce(category, key, message, timestampMs)` accepts only the first event for a key until clear, and `recordRateLimited(category, key, message, intervalMs, timestampMs)` accepts no more than one event per interval. Both support injected timestamps for deterministic tests.

## Data flow and instrumentation

Protocol and lifecycle components call the sink directly at meaningful seams; there is no dispatcher or subscriber registry:

- `AdaptiveSessionController`: session start, P2P group formation/reset, state transitions, WFD profile selection, RTSP control transitions, transport readiness, and stopping.
- `MiracastService`: service/session lifecycle, RTSP listener and connection outcomes, DHCP/ARP address resolution, media preparation, and P2P terminal callbacks.
- `P2pManager`: sink start/stop, WFD framework/fallback outcomes, attempt failures, group candidates/readiness, and discovery state.
- `RtspSessionEngine`: control start, parameter requests (rate-limited), SETUP/PLAY and transport negotiation, media preparation, profile detection, and session finish.
- `RtpReceiver`: receiver bind/stop, first valid RTP packet, rate-limited received/lost counters, and rate-limited processing errors. Individual packets are never logged.
- `MediaDecoderPipeline`: hardware/software decoder setup, SPS reconfiguration, first decoded frame, audio output setup, decoder errors (rate-limited), and release.

Events are diagnostic only and do not alter protocol decisions, socket ownership, retries, media timing, or lifecycle transitions. Existing Android logs remain available for detailed troubleshooting.

## Lifecycle and error handling

The sink is safe to call from worker threads. Event publication is synchronized and uses immutable list replacement. A new diagnostic session may call `clear()` before recording its first event. Failure events use `ERROR`; ordinary teardown and rejected/EOF control paths remain visible as `RTSP`, `P2P`, or `SINK` events with the reason in the message.

No event is emitted for every RTP datagram, MPEG-TS packet, PAT, or decoder input. Repeated discovery, WFD parameter keepalives, and decoder failures are rate-limited so the bounded tail remains useful.

## Verification

Deterministic JVM tests cover the 12-event cap and ordering, `recordOnce` deduplication/reset behavior, and rate-limit suppression plus interval-boundary acceptance. Relevant app unit tests and compilation provide the implementation proof; UI verification is performed separately by driving the actual MainActivity surface.

## Self-review

- No placeholders or unresolved design decisions remain.
- The consumer contract matches the eight required categories and fixes list ordering/cap semantics.
- The design explicitly excludes MainActivity/resource edits from the instrumentation ownership boundary.
- The implementation scope is one focused in-memory sink plus seam instrumentation; no persistence, general bus, or packet-level logging is included.
