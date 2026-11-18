# RTSP package

`RtspConnection` adapts a socket to the single `RtspSessionEngine` state machine;
`RtspProtocol` handles byte-counted RTSP messages and UDP Transport parsing.
TCP entry direction does not determine Wi-Fi Direct GO ownership or reverse
the WFD source/sink roles. The sink initiates TCP to the source's advertised
control port. The unused inbound server, legacy responder, and callback-only
constructors have been removed.

## Session flow

Following Wi-Fi Display Technical Specification v2.1 sections 4.5.4 and 6.4:

1. Source sends M1 OPTIONS; sink acknowledges before sending M2 OPTIONS.
2. Matching successful M2 completes method negotiation; no sink-first OPTIONS.
3. Source M3 GET_PARAMETER queries receive the sink's capability snapshot.
   The RTP receiver is bound before a capability response is constructed.
4. Source M4 SET_PARAMETER selections are validated against that same snapshot.
   Unsupported codecs, protected media, TCP RTP, malformed selections, and
   invalid presentation URLs are rejected rather than falsely acknowledged.
   Unknown extension names are not treated as implemented media features.
   Selection before M3 receives 455. Split selections accumulate only after
   acceptance; SETUP needs a primary presentation URL and selected video or audio.
   Media diagnostics report actual M3 response fields only after the response
   write succeeds, and M4 media selections only after validation, a successful
   200 write and commit. Rejections are distinct. Selected CEA FPS is nominal,
   not measured presentation FPS; bounded media-only values exclude URLs,
   device identity and raw payloads.
5. Source M5 SETUP trigger is acknowledged before sink M6 SETUP. The selected
   primary presentation URL (including its port/path) is used on the existing
   control connection; the optional trailing `none` is not part of the URL.
6. M6 must return a Session and unicast UDP Transport matching the offered
   client ports. Only then is transport published and M7 PLAY sent.
7. First successful PLAY starts media. An explicitly enabled second-PLAY quirk
   is optional: its rejection, including the spec's already-playing 406, does
   not invalidate the first successful PLAY.

Later source SET_PARAMETER triggers PLAY, PAUSE, and TEARDOWN are acknowledged
before the corresponding sink-to-source request. TEARDOWN closes after its
response, or on failure/deadline. Direct source SETUP/PLAY/PAUSE/TEARDOWN methods
receive 405: the source is the RTSP server even if the sink is the P2P GO.
Supported parameter updates remain legal after initial negotiation.

CSeq responses must match an outstanding request and indicate success before
advancing it. SETUP must create the Session. Later responses may omit its echo;
the established ID is retained for subsequent sink requests. A supplied empty
or different ID is rejected, not adopted. This applies to PLAY, PAUSE,
TEARDOWN and optional recovery on the same control connection, independent
of P2P role or source identity.

Source GET_PARAMETER and SET_PARAMETER may also omit Session, including the
empty M16 keepalive. A supplied empty or different ID receives 454. This follows
WFD v2.1 Appendix E.1 (page 186), whose M7 response omits Session and whose
note explicitly makes it optional for server-originated requests.
Empty GET_PARAMETER is answered without initiating sink keepalive polling.
Mandatory transactions have a 5-second response deadline; first M1 and
pre-session negotiation progress have a 6-second deadline.
After establishment, only source M16 refreshes the negotiated keepalive timeout
(60 seconds by default, minimum 10 seconds), not arbitrary parameter requests.
EOF, errors, timeout, and teardown close the socket, clear pending transactions,
disable published UIBC, and notify session end once.

## Optional UIBC and recovery

The source owns the UIBC TCP listener and supplies its port in M4/M14.
Capability/setting changes are committed only after the successful response has
been written. A source-selected endpoint is not published until an explicit
accepted `enable`/`enabled` setting; Android identity does not imply enablement.
The local opt-in must also have been advertised in M3; a source cannot override
a locally disabled UIBC offer.
A later disable or endpoint replacement retires the optional input connection.
UIBC connection/callback failures remain nonfatal to media.

M3 advertises `wfd_idr_request_capability: 1` because the sink implements M13.
Requests use the negotiated Session, permit one in-flight optional request,
and are rate limited. Rejected optional recovery does not end playback; a
successful RTSP response is not proof that the source emitted an IDR picture.

## Reference-capture cross-check

The workspace's official Miralink reference recordings corroborate the two
successful M7 variants, rather than requiring an Android-specific profile:

- `capture-android/android.pcap`: SETUP response frame 11027 establishes
  `0000000a`; PLAY request 11028 uses it, and matching 200 response 11036 omits
  Session. A subsequent PLAY receives 406, not a Session mismatch.
- `capture-win/android.pcap`: SETUP response 24158 and PLAY response 24161
  both carry `2001155254`.
- `capture-newdevice/android.pcap`: SETUP response 10098 and PLAY response
  10102 include `1804289383;timeout=30`; parameters are not part of the ID.

These are separate reference runs, not a packet capture of the reported
Redmi6 failure. They contain no established-session M16 exchange; headerless
keepalive acceptance is grounded in Appendix E.1 and the socket/deadline
regressions, not a claim of hardware-observed M16 behavior. Inspection skipped
embedded text fragments in memory without modifying the original recordings.

## References

- [Wi-Fi Alliance Wi-Fi Display Technical Specification v2.1](https://raw.githubusercontent.com/wiki/albfan/miraclecast/files/Wi-Fi_Display_Technical_Specification_v2.1_0.pdf), sections 4.5.4, 4.11.2, 6.4, 6.5.1 and Appendix E.1 (page 186: M7 Session omission and optional Session in source requests).
- [AOSP WifiDisplaySource.cpp](https://android.googlesource.com/platform/frameworks/av/+/android-8.1.0_r1/media/libstagefright/wifi-display/source/WifiDisplaySource.cpp): `sendM4`, `sendTrigger`, `sendM16`, `onSetupRequest`.
- [Historical AOSP WifiDisplaySink.cpp](https://android.googlesource.com/platform/frameworks/av/+/android-4.2.2_r1.2/media/libstagefright/wifi-display/sink/WifiDisplaySink.cpp): sink TCP client, M1/M2, Session/Transport parsing. This early experimental sink contains placeholder capabilities/URLs and sends SETUP before its trigger acknowledgement; those details are not normative templates.

Consumer socket transcript tests cover the exchange and error paths rather
than source-text/wiring assertions. These controls do not imply compatibility
with every firmware, HDCP requirement, codec, or source variant.
