# UIBC package

This package implements optional WFD Generic and USB HIDC `SingleTouch` and
`MultiTouch` sink-to-source forwarding over one generation-owned TCP connection.
HIDC sends the digitizer descriptor followed by Miralink's Contact Count Maximum
initialization before touch reports. Reports support at most ten contacts; no
keyboard, mouse, or non-USB HID is claimed.

UIBC is disabled by default. When the opt-in setting is enabled, the sink
advertises Generic and USB HIDC touch with `port=none`; it never hosts a UIBC
listener. The source must select supported touch capabilities and a real TCP
port. Selection prefers USB HIDC MultiTouch, USB HIDC SingleTouch, then Generic.
The source must explicitly enable the accepted path with `wfd_uibc_setting:
enable`; a capability alone never enables input, including on Android. Source
requests cannot override an M3 snapshot that advertised `none` because local
opt-in was off. The source must listen before advertising its endpoint; Windows
may additionally require the user to allow input from the receiver. The sink
connects to the source address already used by RTSP, never a UI-configured port.
Missing, rejected, disabled, or failed UIBC is optional and leaves RTP playback
usable; the sink does not send touch data without accepted enablement.

Generic coordinates are scaled to the negotiated video pixel rectangle
(`0..width-1`, `0..height-1`). This interprets WFD 2.1 Tables 15–17's
“normalized with respect to the negotiated resolution” as video pixels; those
tables do not establish a full uint16 normalization scale. This interpretation
still needs real-source interoperability confirmation. HIDC uses `0..32767`,
matching the captured Miralink descriptor and its coordinate scale.
HID items and report values are little endian; WFD lengths and Generic coordinates
are big endian. Initial letterbox touches are rejected; active gestures clamp.
`TouchGestureProcessor` owns stable contacts, SingleTouch primary semantics and
explicit cancellation releases. Coalescing only replaces adjacent same-contact
MOVE events. Saturation fails the input session closed, with best-effort neutral
reports for written contacts and a bounded 150ms close deadline.

The MultiTouch profile matches the 214-byte Miralink descriptor in the supplied
`wireshark-windows/19.12.05.pcapng` (frame 286). Input Report ID 1 contains an
8-bit Contact Count, a 16-bit little-endian Scan Time, and two six-byte contact
slots: Tip Switch + Confidence, Contact ID, X and Y. A report is 16 bytes;
the padded WFD HIDC message is 26 bytes. Axes use logical `0..32767` and a nominal
virtual 160 x 90 mm plane (SI centimetres, exponent -2), not measured screen DPI.
SingleTouch is a coherent derived one-slot profile with maximum one contact;
it is not claimed to be a captured Miralink SingleTouch descriptor.

Contact Count Maximum (`0x55`) is Feature Report ID `0x12`, a 16-bit little-endian
value. Miralink actually sends `12 0a 00` through HIDC Usage=0 immediately after
its descriptor (frame 287), including on re-enable (frame 2563). This observed
initialization is implemented, superseding the earlier assumption that the
descriptor alone was sufficient on the sink side. WFD Table 22 names only
input/descriptor usages: this capture-backed behavior is not a claim that WFD
defines a general reverse USB GET_FEATURE transaction. The source's internal
feature retrieval and Windows input injection remain outside packet evidence.
SingleTouch sends `12 01 00` with its matching descriptor.

Each connection sends descriptor then maximum immediately, without waiting for
Activity creation, surface layout or DPI. Source disable/re-enable creates a
fresh connection and repeats both messages. Miralink's keyboard descriptor is
not copied: keyboard/mouse support is neither implemented nor advertised.

More than two contacts use
[Windows hybrid reporting](https://learn.microsoft.com/en-us/windows-hardware/design/component-guidelines/selecting-packet-reporting-modes-in-multitouch-devices):
the first report counts all valid records in the logical frame, subsequent
reports carry count zero, and all fragments share one Scan Time. Ten contacts
therefore use five reports; an unused tail slot is zero. The supplied Miralink
captures only exercised one contact, so larger hybrid frames are verified
against the documented contract rather than claimed as captured behavior.

Scan Time is relative to the first DOWN of each gesture, starts at zero, and
wraps at 65536 in 100-microsecond units. IDs remain stable in `0..9`. Contact
Count includes lifts; a lift retains Confidence (flags 2 versus pressed flags 3)
and the last transmitted pressed coordinates. Contacts arriving beyond ten are
ignored for their remaining lifetime, not promoted when another contact leaves.

The one-time `UIBC first touch captured` marker identifies Activity capture;
`UIBC first input report sent` identifies successful TCP write/flush. Neither
asserts source-side HID parsing or Windows input injection. No raw coordinates
or per-MOVE payloads are logged.

There are no common-header RTP timestamps, reconnect, JNI, or Android input injection. Peer EOF,
connect/write failure and saturation close and unregister the session and invoke
`onClosed`; callers must identity-guard that callback. `reset()` queues explicit
releases without closing. `close()` also releases actual written contacts before
closing when the peer remains healthy; blocked IO is terminated after bounded
grace. Activity lifecycle changes should reset the gesture processor and session.
