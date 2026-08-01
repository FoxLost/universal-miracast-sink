# Media Package (`media`)

RTP packet reception, MPEG-TS demultiplexing, H.264 video decoding, and LPCM audio playback.

## Files

### `RtpReceiver.kt` — UDP RTP Packet Receiver

Binds a `DatagramSocket` on the specified port (default 15550) with `reuseAddress=true` and a 2MB receive buffer. Strips the 12-byte RTP header and passes payload data to `TsDemuxer`.

**Details**:
- Listens on `0.0.0.0:15550` (all interfaces)
- Buffer: 65535 bytes per packet (jumbo frame capable)
- Skips minimum RTP header (12 bytes)
- Thread: `RtpReceiverThread` runs receive loop
- Graceful shutdown via `isRunning` volatile flag

### `TsDemuxer.kt` — MPEG-TS Demultiplexer

Parses MPEG Transport Stream (188-byte packets with 0x47 sync byte) carried in RTP payloads. Auto-detects video and audio PIDs from PES stream headers.

**PID Auto-Detection**:
| Stream Type | Stream ID Range | Detection |
|---|---|---|
| H.264 Video | 0xE0–0xEF | First non-PAT/PMT PES with video stream ID |
| Audio (LPCM/AAC) | 0xC0–0xDF or 0xBD | Subsequent PES with audio stream ID |

**Video Processing**:
1. Accumulates PES payload across multiple TS packets
2. On each `payload_start_indicator`, flushes accumulated data
3. Extracts Annex-B NAL units (0x00000001 or 0x000001 delimiters)
4. Outputs individual NAL units with PTS timestamps

**Audio Processing**:
1. Accumulates PES payload across multiple TS packets
2. On each `payload_start_indicator`, flushes accumulated data
3. **Skips 4-byte LPCM audio descriptor** (emphasis, mute, frame_count, quant_word_length) from PES payload header
4. **Swaps endianness**: MPEG-TS LPCM is big-endian, Android `AudioTrack` expects little-endian 16-bit PCM
5. Passes to `onAudioFrameExtracted(pcmData, 48000, 2)`

**Architecture Issue**: Early versions had audio PID detection gated behind `videoPid == -1`, meaning audio would never be detected after video PID was found. Fixed by separating the detection blocks.

### `MediaDecoderPipeline.kt` — H.264 Decoder + AudioTrack

Manages the hardware H.264 video decoder and audio playback.

**Video Decoder**:
1. Creates `MediaFormat` for `video/avc` at 1920x1080
2. Tries hardware decoder first: `MediaCodec.createDecoderByType("video/avc")`
3. Falls back to software: `c2.android.avc.decoder` → `OMX.google.h264.decoder`
4. Configures with Surface (for direct rendering, no buffer copying)
5. Reports video dimensions via `onVideoSizeChanged` callback (from `outputFormat.width/height`)
6. Debug output: `Hardware H.264 MediaCodec started successfully (OMX.qcom.video.decoder.avc)`

**Audio Playback**:
1. Initializes `AudioTrack` with STREAM_MUSIC, 48kHz, stereo, PCM_16BIT
2. Writes PCM data via `audioTrack.write()` (streaming mode)
3. Buffer size: 2× minimum buffer

## RTP → Decoded Output Pipeline

```
UDP Packet (RTP)
    │  strip 12-byte RTP header
    ▼
MPEG-TS payload (188-byte packets with 0x47 sync)
    │  PES reassembly across TS packets
    ▼
┌─────────────────────────────────────────┐
│ H.264 NAL Units (video)                 │
│   → MediaCodec (OMX.qcom H.264 HW)     │
│   → Surface (direct render)             │
├─────────────────────────────────────────┤
│ LPCM Audio Frames (48kHz, 2ch, 16-bit)  │
│   → Skip 4-byte descriptor              │
│   → BE→LE byte swap                     │
│   → AudioTrack (STREAM_MUSIC)           │
└─────────────────────────────────────────┘
```

## Known Issues & Resolutions

| Issue | Resolution |
|---|---|
| `KEY_LOW_LATENCY=1` crashed QCOM OMX decoder | Removed from MediaFormat — not supported on Snapdragon 625 |
| Audio PID never detected | Moved audio PID detection outside `videoPid == -1` guard |
| Static noise on audio | Added BE→LE endian swap (MPEG-TS LPCM is big-endian) |
| Distorted audio with endian swap | Added 4-byte LPCM descriptor skip in PES payload |
| Audio descriptor skip caused regression | Confirmed: needs both skip AND swap |
