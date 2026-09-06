package foxlost.miracast.sink.rtsp

import foxlost.miracast.sink.DebugEventCategory
import foxlost.miracast.sink.DebugEventLog
import foxlost.miracast.sink.AdaptiveProfileSelector
import foxlost.miracast.sink.CompatibilityProfile
import foxlost.miracast.sink.P2pRole
import foxlost.miracast.sink.RtspEntry
import foxlost.miracast.sink.SourceEvidence
import foxlost.miracast.sink.SourceKind
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** The local RTP/RTCP endpoint that is ready before RTSP SETUP. */
data class LocalRtpEndpoint(val rtpPort: Int, val rtcpPort: Int?)

data class NegotiatedTransport(
    val sessionId: String?,
    val local: LocalRtpEndpoint,
    val remoteRtpPort: Int?,
    val remoteRtcpPort: Int?,
    val ssrc: Long?,
    val rtcpFeedbackSsrc: Long?,
    val parameters: Map<String, String?>,
)

interface RtspSessionCallbacks {
    fun onMediaStarting(generation: Long)
    fun prepareMedia(generation: Long): LocalRtpEndpoint?
    fun onTransportReady(generation: Long, transport: NegotiatedTransport)
    fun onPlay(generation: Long)
    fun onProfile(generation: Long, profile: CompatibilityProfile)
    fun onSessionEnded(generation: Long, reason: String)

    companion object {
        val NONE = object : RtspSessionCallbacks {
            override fun onMediaStarting(generation: Long) = Unit
            override fun prepareMedia(generation: Long): LocalRtpEndpoint? = null
            override fun onTransportReady(generation: Long, transport: NegotiatedTransport) = Unit
            override fun onPlay(generation: Long) = Unit
            override fun onProfile(generation: Long, profile: CompatibilityProfile) = Unit
            override fun onSessionEnded(generation: Long, reason: String) = Unit
        }
    }
}

/**
 * One WFD RTSP state machine used by both accepted source connections and the
 * sink's reverse connection to a source GO. Entry direction changes the first
 * transport transaction, not the message parser or cleanup semantics.
 */
class RtspSessionEngine(
    private val socket: Socket,
    private val generation: Long,
    private val sourceIp: String,
    private val entry: RtspEntry,
    initialProfile: CompatibilityProfile,
    private val callbacks: RtspSessionCallbacks,
) {
    private val cseq = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, String>()
    private val finishOnce = AtomicBoolean(false)
    private val setupOnce = AtomicBoolean(false)
    private val inboundSetupOnce = AtomicBoolean(false)
    private val sinkOptionsOnce = AtomicBoolean(false)
    private var output: OutputStream? = null
    private var profile = initialProfile
    private var presentationUrl = "rtsp://$sourceIp/wfd1.0/streamid=0"
    private var sessionId: String? = null
    private var localEndpoint: LocalRtpEndpoint? = null
    private var secondPlaySent = false
    @Volatile private var running = false

    fun start() {
        if (running) return
        try {
            DebugEventLog.record(
                DebugEventCategory.RTSP,
                "RTSP ${entry.name.lowercase()} session started peer=$sourceIp",
            )
            output = socket.getOutputStream()
            running = true
            thread(name = "RTSP-Session-$generation-$sourceIp") { readLoop() }
        } catch (e: Exception) {
            finish("control start failed: ${e.message ?: "unknown"}")
        }
    }

    fun close(reason: String = "closed") {
        finish(reason)
    }

    private fun readLoop() {
        try {
            val input = socket.getInputStream()
            while (running) {
                val message = RtspCodec.readMessage(input) ?: break
                if (message.startLine.startsWith("RTSP/", ignoreCase = true)) {
                    processResponse(message)
                } else {
                    processRequest(message)
                }
            }
            if (running) finish("control EOF")
        } catch (t: Throwable) {
            // RTSP callbacks execute on this worker and may cross into Android
            // lifecycle/media APIs. Never let an unchecked failure or Error
            // escape: Android treats an uncaught worker failure as fatal.
            if (running) finish("control read failed: ${failureMessage(t)}")
        }
    }

    private fun processRequest(message: RtspMessage) {
        val method = message.startLine.substringBefore(' ').trim().uppercase()
        val cseqValue = message.cseq()?.toString() ?: "0"
        val body = RtspCodec.bodyText(message)
        if (method in setOf("OPTIONS", "SETUP", "PLAY", "PAUSE", "TEARDOWN")) {
            DebugEventLog.record(DebugEventCategory.RTSP, "RTSP request $method")
        } else {
            DebugEventLog.recordRateLimited(DebugEventCategory.WFD, "wfd-request-$generation", "WFD parameter request $method", 1_000L)
        }
        when (method) {
            "OPTIONS" -> {
                sendResponse(cseqValue, "200 OK", mapOf("Public" to publicHeader()))
                if (sinkOptionsOnce.compareAndSet(false, true)) {
                    delay(profile.setupDelayMs)
                    sendRequest("OPTIONS", "*", mapOf("Require" to "org.wfa.wfd1.0"))
                }
            }
            "GET_PARAMETER" -> {
                learnProfile(body, message)
                val response = WfdCapabilities.buildResponse(body, localEndpoint?.rtpPort)
                    .toByteArray(StandardCharsets.UTF_8)
                sendResponse(cseqValue, "200 OK", body = response)
            }
            "SET_PARAMETER" -> {
                learnProfile(body, message)
                val validationError = validateSelected(body)
                if (validationError != null) {
                    sendResponse(cseqValue, "451 Parameter Not Understood")
                    finish(validationError)
                    return
                }
                sendResponse(cseqValue, "200 OK")
                if (hasTrigger(body, "TEARDOWN")) {
                    finish("source teardown trigger")
                    return
                }
                updatePresentationUrl(body)
                if (hasTrigger(body, "SETUP")) initiateSetup()
            }
            "SETUP" -> handleInboundSetup(cseqValue, message)
            "PLAY" -> {
                if (!sessionMatches(message)) {
                    sendResponse(cseqValue, "454 Session Not Found")
                } else {
                    sendResponse(cseqValue, "200 OK", mapOf("Session" to sessionHeader()))
                    invokeCallback("play") { callbacks.onPlay(generation) }
                }
            }
            "PAUSE" -> sendResponse(cseqValue, "200 OK", mapOf("Session" to sessionHeader()))
            "TEARDOWN" -> {
                sendResponse(cseqValue, "200 OK", mapOf("Session" to sessionHeader()))
                finish("source teardown method")
            }
            else -> sendResponse(cseqValue, "501 Not Implemented")
        }
    }

    private fun processResponse(message: RtspMessage) {
        val number = message.cseq() ?: return
        val method = pending.remove(number) ?: return
        val statusCode = message.startLine.split(' ', limit = 3).getOrNull(1)?.toIntOrNull() ?: 0
        val success = statusCode in 200..299
        DebugEventLog.record(
            if (success) DebugEventCategory.RTSP else DebugEventCategory.ERROR,
            "RTSP response $method status=$statusCode",
        )
        if (!success) {
            finish("$method rejected: ${message.startLine}")
            return
        }
        when (method) {
            "SETUP" -> {
                val negotiatedSession = message.sessionId()
                    ?: run {
                        finish("SETUP response missing Session header")
                        return
                    }
                sessionId = negotiatedSession
                val transport = message.header("transport")?.let(RtspTransport::parse)
                val local = localEndpoint ?: run {
                    finish("SETUP response arrived before local transport")
                    return
                }
                val negotiated = NegotiatedTransport(
                    sessionId = sessionId,
                    local = local,
                    remoteRtpPort = transport?.serverRtpPort,
                    remoteRtcpPort = transport?.serverRtcpPort,
                    ssrc = transport?.ssrc,
                    rtcpFeedbackSsrc = transport?.rtcpFeedbackSsrc,
                    parameters = transport?.parameters ?: emptyMap(),
                )
                if (!invokeCallback("transport ready") {
                        callbacks.onTransportReady(generation, negotiated)
                    }) return
                delay(profile.firstPlayDelayMs)
                sendRequest("PLAY", presentationUrl, mapOf("Session" to sessionHeader()))
            }
            "PLAY" -> {
                if (profile.secondPlay && !secondPlaySent) {
                    secondPlaySent = true
                    delay(profile.secondPlayDelayMs)
                    sendRequest("PLAY", presentationUrl, mapOf("Session" to sessionHeader()))
                } else {
                    invokeCallback("play") { callbacks.onPlay(generation) }
                }
            }
        }
    }

    private fun handleInboundSetup(cseqValue: String, message: RtspMessage) {
        if (!inboundSetupOnce.compareAndSet(false, true)) {
            sendResponse(cseqValue, "455 Method Not Valid in This State", mapOf("Session" to sessionHeader()))
            return
        }
        DebugEventLog.record(DebugEventCategory.RTSP, "RTSP inbound SETUP accepted")
        val local = ensureMedia() ?: return
        sessionId = sessionId ?: generatedSession()
        val requestTransport = message.header("transport")?.let(RtspTransport::parse)
        val remoteRtp = requestTransport?.clientRtpPort
        val remoteRtcp = requestTransport?.clientRtcpPort
        val negotiated = NegotiatedTransport(
            sessionId = sessionId,
            local = local,
            remoteRtpPort = remoteRtp,
            remoteRtcpPort = remoteRtcp,
            ssrc = requestTransport?.ssrc,
            rtcpFeedbackSsrc = requestTransport?.rtcpFeedbackSsrc,
            parameters = requestTransport?.parameters ?: emptyMap(),
        )
        if (!invokeCallback("transport ready") {
                callbacks.onTransportReady(generation, negotiated)
            }) return
        DebugEventLog.record(
            DebugEventCategory.RTSP,
            "RTSP transport negotiated RTP=${local.rtpPort} remoteRTP=${remoteRtp ?: "unknown"}",
        )
        val clientPorts = if (remoteRtp != null && remoteRtcp != null) ";client_port=$remoteRtp-$remoteRtcp" else ""
        sendResponse(
            cseqValue,
            "200 OK",
            mapOf(
                "Session" to sessionHeader(),
                "Transport" to "RTP/AVP/UDP;unicast$clientPorts;server_port=${local.rtpPort}-${local.rtcpPort ?: local.rtpPort}",
            ),
        )
    }

    private fun initiateSetup() {
        if (!setupOnce.compareAndSet(false, true)) return
        val local = ensureMedia() ?: return
        DebugEventLog.record(DebugEventCategory.RTSP, "RTSP sending SETUP localRTP=${local.rtpPort}")
        delay(profile.setupDelayMs)
        sendRequest(
            "SETUP",
            presentationUrl,
            mapOf("Transport" to "RTP/AVP/UDP;unicast;client_port=${local.rtpPort}-${local.rtcpPort ?: local.rtpPort}"),
        )
    }

    private fun ensureMedia(): LocalRtpEndpoint? {
        localEndpoint?.let { return it }
        DebugEventLog.record(DebugEventCategory.MEDIA, "RTSP requested media receiver")
        try {
            callbacks.onMediaStarting(generation)
        } catch (t: Throwable) {
            finish("media starting callback failed: ${failureMessage(t)}")
            return null
        }
        val endpoint = try {
            callbacks.prepareMedia(generation)
        } catch (t: Throwable) {
            finish("media preparation callback failed: ${failureMessage(t)}")
            return null
        }
        if (endpoint == null) {
            finish("RTP/RTCP receiver did not bind")
            return null
        }
        localEndpoint = endpoint
        DebugEventLog.record(DebugEventCategory.MEDIA, "Media receiver prepared RTP=${endpoint.rtpPort}")
        return endpoint
    }

    private fun invokeCallback(name: String, callback: () -> Unit): Boolean {
        return try {
            callback()
            true
        } catch (t: Throwable) {
            finish("$name callback failed: ${failureMessage(t)}")
            false
        }
    }

    private fun failureMessage(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName

    private fun learnProfile(body: String, message: RtspMessage) {
        val names = body.lineSequence()
            .map { it.substringBefore(':').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val evidence = SourceEvidence(
            role = if (entry == RtspEntry.Outbound) P2pRole.SinkClientSourceGo else P2pRole.SinkGoSourceClient,
            entry = entry,
            firstMethod = message.startLine.substringBefore(' ').uppercase(),
            server = message.header("server"),
            userAgent = message.header("user-agent"),
            requestedParameters = names,
            hasMicrosoftParameters = names.any { it.startsWith("microsoft_", true) },
            hasIntelParameters = names.any { it.startsWith("intel_", true) },
            hasWfd2Parameters = names.any { it.startsWith("wfd2_", true) },
        )
        val selected = AdaptiveProfileSelector.select(evidence)
        if (selected.sourceKind != SourceKind.Unknown &&
            (selected.sourceKind != profile.sourceKind || selected.entry != profile.entry)
        ) {
            profile = selected
            DebugEventLog.record(DebugEventCategory.WFD, "WFD source profile detected ${profile.sourceKind}")
            invokeCallback("profile") { callbacks.onProfile(generation, profile) }
        }
    }

    private fun validateSelected(body: String): String? {
        val selectedUrl = presentationUrlToken(body)
        if (!selectedUrl.isNullOrBlank() && !selectedUrl.equals("none", true)) {
            val uri = try { java.net.URI(selectedUrl) } catch (_: Exception) { null }
                ?: return "invalid presentation URL"
            val host = uri.host ?: return "presentation URL has no host"
            if (!uri.scheme.equals("rtsp", true)) return "presentation URL must use rtsp"
            // The RTSP connection is already established to sourceIp. Android
            // sources commonly append "none" to this parameter and may report
            // a group/interface address that differs from the connected peer.
            // Keep syntax validation, but do not redirect or reject Android's
            // already-connected socket based on the URL host.
            if (profile.sourceKind != SourceKind.Android &&
                host != sourceIp && host != "255.255.255.255"
            ) return "presentation URL host mismatch"
        }
        val video = parameter(body, "wfd_video_formats")
        if (!video.isNullOrBlank() && !video.equals("none", true)) {
            val first = video.trim().split(Regex("\\s+")).firstOrNull()
            if (first.isNullOrBlank() || !first.matches(Regex("[0-9A-Fa-f]+"))) return "unsupported video format"
        }
        val audio = parameter(body, "wfd_audio_codecs")
        if (!audio.isNullOrBlank() && !audio.equals("none", true) && !audio.startsWith("LPCM", true)) {
            return "unsupported audio codec"
        }
        val ports = Regex("(?i)(?:client_port|server_port)\\s*=\\s*(\\d+)(?:\\s*-\\s*|\\s+)(\\d+)")
        for (match in ports.findAll(body)) {
            val first = match.groupValues[1].toIntOrNull()
            val second = match.groupValues[2].toIntOrNull()
            if (first == null || second == null || first !in 1..65535 || second !in 1..65535) return "invalid RTP/RTCP ports"
        }
        return null
    }

    /** Android's WFD grammar emits the URL followed by a `none` capability token. */
    private fun presentationUrlToken(body: String): String? =
        parameter(body, "wfd_presentation_URL")
            ?.trim()
            ?.let { raw ->
                val parts = raw.split(Regex("\\s+"), limit = 2)
                if (parts.size == 2 && !parts[1].equals("none", true)) raw else parts[0]
            }
            ?.takeIf { it.isNotEmpty() }

    private fun updatePresentationUrl(body: String) {
        val selected = presentationUrlToken(body)
            ?.takeUnless { it.equals("none", true) || it.isBlank() }
            ?: return
        presentationUrl = if (profile.normalizeBroadcastUrl) selected.replace("255.255.255.255", sourceIp) else selected
    }

    private fun parameter(body: String, name: String): String? = body.lineSequence()
        .firstOrNull { it.substringBefore(':').trim().equals(name, true) }
        ?.substringAfter(':', "")
        ?.trim()

    private fun hasTrigger(body: String, trigger: String): Boolean =
        body.lineSequence().any { it.trim().equals("wfd_trigger_method: $trigger", true) }

    private fun sessionMatches(message: RtspMessage): Boolean =
        message.sessionId()?.let { sessionId == null || it == sessionId } != false

    private fun generatedSession(): String = "${generation.toString(16)}-${System.currentTimeMillis().toString(16)}"

    private fun sessionHeader(): String = sessionId ?: generatedSession().also { sessionId = it }

    private fun publicHeader(): String = "org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER"

    private fun sendResponse(cseqValue: String, status: String, headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0)) {
        val all = LinkedHashMap<String, String>()
        all["CSeq"] = cseqValue
        all.putAll(headers)
        if (body.isNotEmpty()) all["Content-Type"] = "text/parameters"
        write(RtspCodec.encode("RTSP/1.0 $status", all, body))
    }

    private fun sendRequest(method: String, url: String, headers: Map<String, String> = emptyMap()): Int {
        val number = cseq.getAndIncrement()
        pending[number] = method
        val all = LinkedHashMap<String, String>()
        all["CSeq"] = number.toString()
        all["User-Agent"] = "MiracastSink"
        all.putAll(headers)
        write(RtspCodec.encode("$method $url RTSP/1.0", all))
        return number
    }

    private fun write(bytes: ByteArray) {
        try {
            synchronized(this) {
                output?.write(bytes)
                output?.flush()
            }
        } catch (e: Exception) {
            if (running) finish("control write failed: ${e.message ?: "unknown"}")
        }
    }

    private fun delay(milliseconds: Long) {
        if (milliseconds <= 0) return
        try { Thread.sleep(milliseconds) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun finish(reason: String) {
        if (!finishOnce.compareAndSet(false, true)) return
        running = false
        DebugEventLog.record(DebugEventCategory.RTSP, "RTSP session finishing: $reason")
        pending.clear()
        try { socket.close() } catch (_: Exception) {}
        try {
            callbacks.onSessionEnded(generation, reason)
        } catch (_: Throwable) {
            // Cleanup callbacks are best effort. A faulty callback must not
            // turn an RTSP disconnect into another uncaught process crash.
        }
    }
}
