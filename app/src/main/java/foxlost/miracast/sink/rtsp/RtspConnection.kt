package foxlost.miracast.sink.rtsp

import foxlost.miracast.sink.CompatibilityProfile
import foxlost.miracast.sink.RtspEntry
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


    fun start() = engine.start()

    fun requestIdr(): Boolean = engine.requestIdr()

    fun close(reason: String = "closed") = engine.close(reason)
}
