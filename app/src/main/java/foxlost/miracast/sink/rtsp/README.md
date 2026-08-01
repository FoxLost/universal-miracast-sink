# RTSP Package (`rtsp`)

RTSP (Real-Time Streaming Protocol) server and connection handler for WFD capability negotiation (M1-M7 messages per Wi-Fi Display specification).

## Files

### `RtspServer.kt` — TCP Server on Port 7236

Binds a `ServerSocket` on the specified port with `reuseAddress=true`. Accepts incoming TCP connections and spawns `RtspConnection` handlers.

**Note**: While this server handles inbound connections, the primary WFD handshake is driven by `MiracastService.startRtspHandshake()` as a TCP **client** connecting to the source's RTSP port. The inbound server handles additional connections (e.g., from the source, self-tests, or secondary WFD sessions).

### `RtspConnection.kt` — RTSP Session Handler

Handles a single RTSP TCP session. Parses RTSP request/response lines, extracts headers (including `Content-Length` for body reading), and dispatches to appropriate handlers.

**Request handling**:
| Method | Response |
|---|---|
| `OPTIONS` | 200 OK + `Public: org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER` |
| `GET_PARAMETER` | 200 OK + sink video/audio/RTP/UIBC capabilities |
| `SET_PARAMETER` | 200 OK; if `wfd_trigger_method: SETUP`, triggers SETUP→PLAY→onPlayTriggered |
| `TEARDOWN` | 200 OK |

**Protocol details**:
- CSeq tracking via `AtomicInteger`
- RTSP request/response format with proper `\r\n` line endings
- Body reading using `Content-Length` header
- UTF-8 encoding throughout

### `RtspHandler.kt` — Generic RTSP Request Handler (Legacy)

A simpler RTSP handler that responds to standard WFD RTSP methods. Kept for reference and potential use in scenarios where the Inbound RTSP server needs to handle more complex exchanges.

## WFD RTSP Methods

| Method | Direction | Purpose |
|---|---|---|
| `OPTIONS` | Bidirectional | Capability discovery |
| `GET_PARAMETER` | Source → Sink | Query sink capabilities (video/audio/RTP) |
| `SET_PARAMETER` | Source → Sink | Set WFD parameters, trigger SETUP/TEARDOWN |
| `SETUP` | Sink → Source | Establish RTP transport |
| `PLAY` | Sink → Source | Start media streaming |
| `TEARDOWN` | Bidirectional | End session |

## WFD Public Header (OPTIONS Response)

The Public header in OPTIONS responses advertises supported methods:

- **Sink → Source**: `org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER`
  (Sink handles parameter queries and triggers)
- **Source → Sink**: `org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER`
  (Source handles transport setup and stream control)

This matches real Android Wi-Fi Display source/sink behavior — verified against packet capture of a working Miralink receiver.

## Sink Capabilities Response

```
wfd_video_formats: 00 00 01 10 0001bde1 00300000 000003c0
wfd_audio_codecs: LPCM 00000002 00
wfd_client_rtp_ports: RTP/AVP/UDP;unicast 15550 0 mode=play
wfd_uibc_capability: input_category_list=GENERIC,HIDC; generic_cap_list=Keyboard,Mouse,SingleTouch,MultiTouch; hidc_cap_list=Keyboard/USB,Mouse/USB,SingleTouch/USB,MultiTouch/USB; port=none
```

## Trigger Method Flow

The source sends `wfd_trigger_method: SETUP` and `wfd_trigger_method: TEARDOWN` as `SET_PARAMETER` body values to control the streaming lifecycle. The sink responds 200 OK to acknowledge, then performs the triggered action:
- **SETUP trigger**: Sink sends `SETUP` → waits for 200 OK → sends `PLAY`
- **TEARDOWN trigger**: Sink breaks the RTSP loop, broadcasts `SESSION_END`, returns to dashboard

## Key Implementation Detail: `Public:` Header

A critical bug was discovered during development: listing `SETUP, PLAY` in the sink's Public header (as the spec suggests) caused the source to disconnect P2P immediately. The real Android WFD stack only lists `GET_PARAMETER, SET_PARAMETER` in the sink's Public response — confirmed by packet capture of a working Miralink Miracast receiver.
