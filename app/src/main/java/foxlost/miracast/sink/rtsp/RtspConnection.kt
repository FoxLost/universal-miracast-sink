package foxlost.miracast.sink.rtsp

import foxlost.miracast.sink.CompatibilityProfile
import foxlost.miracast.sink.RtspEntry
import foxlost.miracast.sink.SettingsManager
import java.net.Socket

/** Connection-entry adapter for the unified RTSP session engine. */
class RtspConnection(
    private val socket: Socket,
    private val generation: Long,
    private val entry: RtspEntry,
    private val profile: CompatibilityProfile,
    private val callbacks: RtspSessionCallbacks,
) {
    private val engine = RtspSessionEngine(
        socket = socket,
        generation = generation,
        sourceIp = socket.inetAddress.hostAddress ?: "127.0.0.1",
        entry = entry,
        initialProfile = profile,
        callbacks = callbacks,
    )

    /** Legacy constructor retained for the existing server adapter. */
    constructor(
        socket: Socket,
        onPlayTriggered: () -> Unit,
        onSetupTriggered: () -> Unit = {},
    ) : this(
        socket = socket,
        generation = 0L,
        entry = RtspEntry.Inbound,
        profile = CompatibilityProfile.unknown(RtspEntry.Inbound),
        callbacks = object : RtspSessionCallbacks {
            override fun onMediaStarting(generation: Long) = Unit

            override fun prepareMedia(generation: Long): LocalRtpEndpoint {
                onSetupTriggered()
                val rtp = try {
                    SettingsManager.rtpVideoPort.takeIf { it in 1..65534 }
                } catch (_: Exception) {
                    null
                } ?: SettingsManager.DEFAULT_RTP_VIDEO_PORT
                return LocalRtpEndpoint(rtp, (rtp + 1).takeIf { it <= 65535 })
            }

            override fun onTransportReady(generation: Long, transport: NegotiatedTransport) = Unit
            override fun onPlay(generation: Long) = onPlayTriggered()
            override fun onProfile(generation: Long, profile: CompatibilityProfile) = Unit
            override fun onSessionEnded(generation: Long, reason: String) = Unit
        },
    )

    fun start() = engine.start()

    fun close(reason: String = "closed") = engine.close(reason)
}
