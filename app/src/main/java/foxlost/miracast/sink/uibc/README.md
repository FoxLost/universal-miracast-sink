# UIBC Package (`uibc`)

UIBC (User Input Back Channel) — enables sending touch/mouse/keyboard input from the sink back to the Miracast source. This allows the receiving device's screen to act as a remote control for the source.

## Status: Experimental

The UIBC implementation is experimental and not currently active in the main streaming flow. It is designed for future use when touch input passthrough is needed.

## `UibcSender.kt` — Touch Event Sender

Connects to the source's UIBC TCP port and sends Wi-Fi Display UIBC Generic touch input packets.

### Protocol

UIBC packets follow the Wi-Fi Display UIBC specification:
- **Version + T field**: 1 byte (0x00)
- **Category**: 1 byte (0x00 = Generic)
- **Length**: 2 bytes (total packet length - 4)
- **Control data**: Generic touch event IE

### Touch Event Encoding

```
Byte 0:     UIBC Action (0=DOWN, 1=UP, 2=MOVE)
Byte 1:     Pointer ID (1)
Byte 2-3:   Normalized X coordinate (0-65535)
Byte 4-5:   Normalized Y coordinate (0-65535)
```

### Normalization

Coordinates are normalized to the range 0-65535 (fixed-point 16.16):
```
normX = (x / screenWidth)  * 65535
normY = (y / screenHeight) * 65535
```

Values are clamped to `[0, 65535]`.

### Connection

- TCP connection to source IP on the port specified in the `wfd_uibc_capability` parameter
- Currently disabled (`port=none` in our capabilities response)
- When enabled, would connect in a background thread and keep connection alive for the session duration

### Future Work

- Enable UIBC in sink capabilities response
- Handle mouse/keyboard HIDC events
- Surface touch events from PlayerActivity
- Maintain UIBC session across orientation changes
