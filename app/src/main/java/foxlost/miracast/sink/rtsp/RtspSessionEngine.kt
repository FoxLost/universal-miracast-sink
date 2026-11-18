package foxlost.miracast.sink.rtsp

import android.util.Log
import foxlost.miracast.sink.DebugEventCategory
import foxlost.miracast.sink.DebugEventLog
import foxlost.miracast.sink.AdaptiveProfileSelector
import foxlost.miracast.sink.CompatibilityProfile
import foxlost.miracast.sink.RtspEntry
import foxlost.miracast.sink.SourceEvidence
import foxlost.miracast.sink.SourceKind
import foxlost.miracast.sink.uibc.UibcCapabilityParser
import foxlost.miracast.sink.uibc.UibcCapabilityParseResult
import foxlost.miracast.sink.uibc.UibcEndpoint
import foxlost.miracast.sink.uibc.UibcSetting
import foxlost.miracast.sink.uibc.UibcSettingParseResult
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private const val UIBC_LOG_TAG = "MiracastUIBC"

private fun rtspUibcLogDebug(message: String) {
    try { Log.d(UIBC_LOG_TAG, message) } catch (_: RuntimeException) {}
}

private fun rtspUibcLogInfo(message: String) {
    try { Log.i(UIBC_LOG_TAG, message) } catch (_: RuntimeException) {}
}

private fun rtspUibcLogWarn(message: String) {
    try { Log.w(UIBC_LOG_TAG, message) } catch (_: RuntimeException) {}
}

private fun rtspMediaLog(message: String) {
    // Android's logging stub throws in host transcript tests, as with the UIBC logs.
    try { Log.i("MiracastRTSP", message) } catch (_: RuntimeException) {}
    DebugEventLog.record(DebugEventCategory.WFD, message)
}

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
    fun onUibcEndpoint(generation: Long, endpoint: foxlost.miracast.sink.uibc.UibcEndpoint) = Unit
    fun onUibcDisabled(generation: Long, reason: String) = Unit

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

/** WFD sink control state machine. TCP direction never changes WFD RTSP roles. */
class RtspSessionEngine(
    private val socket: Socket,
    private val generation: Long,
    private val sourceIp: String,
    private val entry: RtspEntry,
    initialProfile: CompatibilityProfile,
    private val callbacks: RtspSessionCallbacks,
    private val nanoTime: () -> Long = System::nanoTime,
    private val capabilitySnapshot: (Int) -> String = { WfdCapabilities.fullResponse(it) },
) {
    private val cseq = AtomicInteger(1)
    private data class PendingRequest(
        val method: String,
        val optional: Boolean = false,
        val sentAtNanos: Long,
    )
    private val pending = ConcurrentHashMap<Int, PendingRequest>()
    private val finishOnce = AtomicBoolean(false)
    private val setupOnce = AtomicBoolean(false)
    private var optionsComplete = false
    private var selectedPresentation = false
    private var selectedVideo = false
    private var selectedAudio = false
    private var advertisedCapabilities: String? = null
    @Volatile private var lastSourceRequestNanos = nanoTime()
    @Volatile private var lastKeepaliveNanos = nanoTime()
    @Volatile private var sourceTimeoutNanos = 60_000_000_000L
    @Volatile private var inboundRequestNanos = Long.MIN_VALUE
    private val sinkOptionsOnce = AtomicBoolean(false)
    private var output: OutputStream? = null
    private var profile = initialProfile
    private var uibcCapabilities: Set<foxlost.miracast.sink.uibc.UibcCapability> = emptySet()
    private var uibcPort: Int? = null
    private var uibcSetting: UibcSetting? = null
    private var uibcEndpointPublished = false
    private var uibcCategory = foxlost.miracast.sink.uibc.UibcCategory.GENERIC
    private var uibcPath: foxlost.miracast.sink.uibc.UibcInputPath? = null
    private var uibcHidType: foxlost.miracast.sink.uibc.UibcCapability? = null
    private var presentationUrl = "rtsp://$sourceIp/wfd1.0/streamid=0"
    @Volatile private var sessionId: String? = null
    private var localEndpoint: LocalRtpEndpoint? = null
    private var secondPlaySent = false
    @Volatile private var running = false
    @Volatile private var playing = false
    private var lastIdrRequestNanos = Long.MIN_VALUE

    private data class PendingUibcUpdate(
        val capability: UibcCapabilityParseResult? = null,
        val setting: UibcSettingParseResult? = null,
    ) {
        val present: Boolean get() = capability != null || setting != null
    }

    fun start() {
        if (running || finishOnce.get()) return
        try {
            DebugEventLog.record(
                DebugEventCategory.RTSP,
                "RTSP ${entry.name.lowercase()} session started peer=$sourceIp",
            )
            output = socket.getOutputStream()
            lastSourceRequestNanos = nanoTime()
            running = true
            thread(name = "RTSP-Session-$generation-$sourceIp") { readLoop() }
            thread(name = "RTSP-Deadline-$generation", isDaemon = true) {
                while (running) {
                    Thread.sleep(250)
                    val now = nanoTime()
                    val expired = pending.entries.firstOrNull {
                        now - it.value.sentAtNanos >= 5_000_000_000L
                    }
                    if (expired != null && pending.remove(expired.key, expired.value) && !expired.value.optional) {
                        finish("${expired.value.method} response timeout")
                    }
                    val inboundStarted = inboundRequestNanos
                    if (running && inboundStarted != Long.MIN_VALUE && now - inboundStarted >= 5_000_000_000L) {
                        finish("sink response timeout")
                    }
                    val sourceDeadline = if (sessionId == null) 6_000_000_000L else sourceTimeoutNanos
                    val lastSourceMessage = if (sessionId == null) lastSourceRequestNanos else lastKeepaliveNanos
                    if (running && now - lastSourceMessage >= sourceDeadline) {
                        finish("source control timeout")
                    }
                }
            }
        } catch (e: Exception) {
            finish("control start failed: ${e.message ?: "unknown"}")
        }
    }

    fun close(reason: String = "closed") {
        finish(reason)
    }

    /** Optional recovery: a rejected M13 must never end otherwise valid playback. */
    fun requestIdr(): Boolean {
        synchronized(this) {
            if (!running || !playing || finishOnce.get()) return false
            val now = nanoTime()
            pending.entries.removeAll { it.value.optional && now - it.value.sentAtNanos >= 5_000_000_000L }
            if (pending.values.any { it.optional } ||
                (lastIdrRequestNanos != Long.MIN_VALUE && now - lastIdrRequestNanos < 1_000_000_000L)
            ) return false
            lastIdrRequestNanos = now
            thread(name = "RTSP-IDR-$generation") {
                synchronized(this) {
                    if (!running || !playing) return@thread
                    sendRequest(
                        "SET_PARAMETER", presentationUrl,
                        mapOf("Session" to sessionHeader()),
                        "wfd_idr_request\r\n".toByteArray(StandardCharsets.UTF_8),
                        optional = true,
                    )
                }
            }
            return true
        }
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
        inboundRequestNanos = nanoTime()
        val method = message.startLine.substringBefore(' ').trim().uppercase()
        val cseqValue = message.cseq()?.toString() ?: run {
            sendResponse("0", "400 Bad Request")
            return
        }
        if (!message.startLine.endsWith(" RTSP/1.0")) {
            sendResponse(cseqValue, "400 Bad Request")
            return
        }
        // WFD v2.1 Appendix E.1 permits source requests, including M16,
        // without Session. A supplied ID must still match the SETUP session.
        if (sessionId != null &&
            (method == "GET_PARAMETER" || method == "SET_PARAMETER") && message.header("session") != null &&
            !sessionMatches(message)
        ) {
            sendResponse(cseqValue, "454 Session Not Found")
            return
        }
        lastSourceRequestNanos = nanoTime()
        val body = RtspCodec.bodyText(message)
        // M4/M14/M15 are source -> sink SET_PARAMETER transactions. Parse
        // before replying, but commit only after the successful 200 response.
        val pendingUibc = if (method == "SET_PARAMETER") stageUibc(body) else null
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
                if (!optionsComplete) {
                    sendResponse(cseqValue, "455 Method Not Valid in This State")
                    return
                }
                if (body.isBlank()) {
                    if (sessionId != null) lastKeepaliveNanos = nanoTime()
                    val headers = sessionId?.let { mapOf("Session" to it) } ?: emptyMap()
                    sendResponse(cseqValue, "200 OK", headers)
                    return
                }
                val local = ensureMedia() ?: return
                val capabilities = advertisedCapabilities
                    ?: capabilitySnapshot(local.rtpPort).also { advertisedCapabilities = it }
                learnProfile(body, message)
                val response = WfdCapabilities.buildResponse(body, localEndpoint?.rtpPort, advertisedCapabilities = capabilities)
                    .toByteArray(StandardCharsets.UTF_8)
                if (!sendResponse(cseqValue, "200 OK", body = response)) return
                WfdSelection.mediaDiagnostics(response.decodeToString()).takeIf { it.isNotEmpty() }?.let {
                    rtspMediaLog("M3 response written generation=$generation $it")
                }
                if (body.lineSequence().any { it.trim().equals("wfd_uibc_capability", true) }) {
                    rtspUibcLogInfo("UIBC M3 capability response generation=$generation enabled=${response.decodeToString().contains("wfd_uibc_capability: input_category_list", true)}")
                }
            }
            "SET_PARAMETER" -> {
                if (!optionsComplete || advertisedCapabilities == null) {
                    rtspMediaLog("M4/SET_PARAMETER rejected generation=$generation reason=capabilities_not_advertised")
                    sendResponse(cseqValue, "455 Method Not Valid in This State")
                    return
                }
                if (pendingUibc?.capability?.valid == false || pendingUibc?.setting?.valid == false) {
                    rtspMediaLog("M4/SET_PARAMETER rejected generation=$generation reason=invalid_uibc")
                    sendResponse(cseqValue, "451 Parameter Not Understood")
                    return
                }
                val uibcSelected = pendingUibc?.capability?.capabilities?.isNotEmpty() == true ||
                    pendingUibc?.setting?.setting == UibcSetting.ENABLED
                if (uibcSelected && parameter(advertisedCapabilities!!, "wfd_uibc_capability").let {
                        it == null || it.equals("none", true)
                    }
                ) {
                    rtspMediaLog("M4/SET_PARAMETER rejected generation=$generation reason=uibc_not_advertised")
                    sendResponse(cseqValue, "451 Parameter Not Understood")
                    return
                }
                // Profile selection is independent of optional UIBC state.
                // Defer endpoint publication until this request is acknowledged.
                learnProfile(body, message, publishUibc = false)
                val validationError = validateSelected(body)
                if (validationError != null) {
                    rtspMediaLog("M4/SET_PARAMETER rejected generation=$generation reason=$validationError")
                    sendResponse(cseqValue, "451 Parameter Not Understood")
                    return
                }
                val trigger = parameter(body, "wfd_trigger_method")?.uppercase()
                if (trigger != null && trigger !in setOf("SETUP", "PLAY", "PAUSE", "TEARDOWN")) {
                    rtspMediaLog("M4/SET_PARAMETER rejected generation=$generation reason=unsupported_trigger")
                    sendResponse(cseqValue, "451 Parameter Not Understood")
                    return
                }
                val selectedVideoUpdate = parameter(body, "wfd_video_formats")?.let { !it.equals("none", true) }
                val selectedAudioUpdate = parameter(body, "wfd_audio_codecs")?.let { !it.equals("none", true) }
                if ((trigger == "SETUP" && ((!selectedPresentation && presentationUrlToken(body) == null) ||
                            (!(selectedVideoUpdate ?: selectedVideo) && !(selectedAudioUpdate ?: selectedAudio)) ||
                            setupOnce.get())) ||
                    (trigger in setOf("PLAY", "PAUSE", "TEARDOWN") &&
                        (sessionId == null || pending.values.any { !it.optional }))
                ) {
                    rtspMediaLog("M4/SET_PARAMETER rejected generation=$generation reason=invalid_trigger_state")
                    sendResponse(cseqValue, "455 Method Not Valid in This State")
                    return
                }
                if (!sendResponse(cseqValue, "200 OK")) return
                if (pendingUibc != null) {
                    rtspUibcLogDebug("UIBC source SET_PARAMETER acknowledged generation=$generation")
                    commitUibc(pendingUibc)
                }
                maybePublishUibcEndpoint()
                updatePresentationUrl(body)
                selectedVideoUpdate?.let { selectedVideo = it }
                selectedAudioUpdate?.let { selectedAudio = it }
                WfdSelection.mediaDiagnostics(body).takeIf { it.isNotEmpty() }?.let {
                    val timing = WfdSelection.selectedCeaTiming(body)?.let { mode -> " selectedCEA=$mode" }.orEmpty()
                    rtspMediaLog("M4 selection committed generation=$generation $it$timing")
                }
                when (trigger) {
                    "SETUP" -> initiateSetup()
                    "PLAY", "PAUSE", "TEARDOWN" ->
                        sendRequest(trigger, presentationUrl, mapOf("Session" to sessionHeader()))
                }
            }
            // The source is the RTSP server, even when the sink is the P2P GO.
            "SETUP", "PLAY", "PAUSE", "TEARDOWN" ->
                sendResponse(cseqValue, "405 Method Not Allowed", mapOf("Allow" to "OPTIONS, GET_PARAMETER, SET_PARAMETER"))
            else -> sendResponse(cseqValue, "501 Not Implemented")
        }
    }

    private fun processResponse(message: RtspMessage) {
        val number = message.cseq() ?: run {
            finish("response missing valid CSeq")
            return
        }
        // Unsolicited or late optional replies cannot advance another transaction.
        val request = pending.remove(number) ?: return
        val method = request.method
        lastSourceRequestNanos = nanoTime()
        val statusCode = message.startLine.split(' ', limit = 3).getOrNull(1)?.toIntOrNull() ?: 0
        val success = statusCode in 200..299
        DebugEventLog.record(
            if (success) DebugEventCategory.RTSP else DebugEventCategory.ERROR,
            "RTSP response $method status=$statusCode",
        )
        if (!success) {
            if (request.optional) {
                DebugEventLog.recordRateLimited(DebugEventCategory.MEDIA, "optional-rejected-$generation", "Source rejected optional ${request.method} status=$statusCode")
                return
            }
            finish("$method rejected: ${message.startLine}")
            return
        }
        // Appendix E.1 also shows M7 success without a Session echo. CSeq
        // already binds this response to a request on this control connection;
        // only SETUP creates the session, and a supplied ID cannot replace it.
        val responseSession = message.sessionId()
        if (sessionId != null && method != "OPTIONS" &&
            message.header("session") != null && responseSession != sessionId
        ) {
            if (request.optional) return
            val detail = if (responseSession == null) "empty Session header" else "different Session ID"
            finish("$method response Session mismatch (CSeq=$number, $detail)")
            return
        }
        when (method) {
            "OPTIONS" -> optionsComplete = true
            "SETUP" -> {
                val negotiatedSession = responseSession
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
                if (transport == null || !transport.profile.equals("RTP/AVP/UDP", true) ||
                    !transport.unicast || transport.parameters.containsKey("multicast") ||
                    transport.parameters.containsKey("interleaved") ||
                    transport.clientRtpPort != local.rtpPort ||
                    transport.clientRtcpPort != local.rtcpPort ||
                    transport.serverRtpPort == null ||
                    local.rtcpPort != null && transport.serverRtcpPort == null
                ) {
                    finish("SETUP response Transport mismatch or unsupported transport")
                    return
                }
                message.header("session")?.split(';')?.drop(1)?.forEach { token ->
                    if (token.trim().startsWith("timeout=", true)) {
                        token.substringAfter('=').trim().toLongOrNull()?.takeIf { it in 1..86400 }
                            ?.let { sourceTimeoutNanos = it.coerceAtLeast(10) * 1_000_000_000L }
                    }
                }
                lastSourceRequestNanos = nanoTime()
                lastKeepaliveNanos = lastSourceRequestNanos
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
                if (!request.optional) {
                    playing = true
                    if (!invokeCallback("play") { callbacks.onPlay(generation) }) return
                }
                if (profile.secondPlay && !secondPlaySent) {
                    secondPlaySent = true
                    delay(profile.secondPlayDelayMs)
                    sendRequest("PLAY", presentationUrl, mapOf("Session" to sessionHeader()), optional = true)
                }
            }
            "PAUSE" -> playing = false
            "TEARDOWN" -> finish("source teardown completed")
        }
    }


    private fun initiateSetup() {
        if (!setupOnce.compareAndSet(false, true)) return
        val local = ensureMedia() ?: return
        DebugEventLog.record(DebugEventCategory.RTSP, "RTSP sending SETUP localRTP=${local.rtpPort}")
        delay(profile.setupDelayMs)
        val ports = if (local.rtcpPort == null) "${local.rtpPort}" else "${local.rtpPort}-${local.rtcpPort}"
        sendRequest(
            "SETUP",
            presentationUrl,
            mapOf("Transport" to "RTP/AVP/UDP;unicast;client_port=$ports"),
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

    private fun stageUibc(body: String): PendingUibcUpdate? {
        if (body.isBlank()) return null
        val capability = UibcCapabilityParser.parseBody(body).takeIf { it.advertised }
        val setting = UibcCapabilityParser.parseSettingBody(body).takeIf { it.present }
        return PendingUibcUpdate(capability, setting).takeIf { it.present }
    }

    /**
     * Apply source UIBC capability/port/setting state after its SET_PARAMETER
     * transaction has received a successful response. This keeps endpoint
     * publication and disable notifications out of the pre-200 path.
     */
    private fun commitUibc(update: PendingUibcUpdate) {
        update.capability?.let { capability ->
            if (!capability.valid) {
                clearUibc(capability.error ?: "unsupported capability")
            } else if (capability.capabilities.isEmpty()) {
                clearUibc("source advertised no supported UIBC touch capability")
            } else {
                val changed = uibcCapabilities != capability.capabilities || uibcPort != capability.port ||
                    uibcCategory != capability.category || uibcPath != capability.path || uibcHidType != capability.hidType
                if (changed) {
                    val wasPublished = uibcEndpointPublished
                    uibcCapabilities = capability.capabilities
                    uibcPort = capability.port
                    uibcCategory = capability.category
                    uibcPath = capability.path
                    uibcHidType = capability.hidType
                    uibcEndpointPublished = false
                    if (wasPublished) {
                        notifyUibcDisabled(if (capability.port == null) "source UIBC port withdrawn" else "source UIBC endpoint changed")
                    }
                }
                DebugEventLog.record(DebugEventCategory.UIBC, "UIBC capability committed port=${capability.port}")
                rtspUibcLogInfo("UIBC capability committed generation=$generation category=${capability.category} path=${capability.path} type=${capability.hidType} port=${capability.port} capabilities=${capability.capabilities}")
            }
        }
        update.setting?.let { setting ->
            if (!setting.valid) {
                uibcSetting = UibcSetting.DISABLED
                rtspUibcLogWarn("UIBC setting rejected generation=$generation: ${setting.error}")
                disableUibc(setting.error ?: "unsupported setting")
            } else {
                uibcSetting = setting.setting
                rtspUibcLogInfo("UIBC setting committed generation=$generation value=${setting.setting}")
                if (setting.setting == UibcSetting.DISABLED) {
                    disableUibc("source disabled UIBC")
                }
            }
        }
    }

    private fun clearUibc(reason: String) {
        val hadState = uibcCapabilities.isNotEmpty() || uibcPort != null || uibcEndpointPublished
        uibcCapabilities = emptySet()
        uibcPort = null
        uibcEndpointPublished = false
        rtspUibcLogWarn("UIBC capability cleared generation=$generation: $reason")
        if (hadState) notifyUibcDisabled(reason)
    }

    private fun disableUibc(reason: String) {
        val wasPublished = uibcEndpointPublished
        uibcEndpointPublished = false
        rtspUibcLogInfo("UIBC disabled generation=$generation: $reason")
        if (wasPublished) notifyUibcDisabled(reason)
    }

    private fun notifyUibcDisabled(reason: String) {
        try {
            callbacks.onUibcDisabled(generation, reason)
        } catch (t: Throwable) {
            // UIBC is optional. A failing UIBC callback must not tear down RTP.
            rtspUibcLogWarn("UIBC disable callback failed generation=$generation: ${failureMessage(t)}")
        }
    }

    private fun notifyUibcEndpoint(endpoint: UibcEndpoint): Boolean {
        return try {
            callbacks.onUibcEndpoint(generation, endpoint)
            true
        } catch (t: Throwable) {
            // UIBC is optional. A failing endpoint callback must not tear down RTSP/RTP.
            rtspUibcLogWarn("UIBC endpoint callback failed generation=$generation: ${failureMessage(t)}")
            notifyUibcDisabled("endpoint callback failed")
            false
        }
    }

    private fun maybePublishUibcEndpoint() {
        val port = uibcPort ?: run {
            if (uibcSetting == UibcSetting.ENABLED) {
                rtspUibcLogDebug("UIBC waiting for source port generation=$generation")
            }
            return
        }
        if (uibcCapabilities.isEmpty() || uibcEndpointPublished) return
        val enabled = uibcSetting == UibcSetting.ENABLED
        if (!enabled) {
            rtspUibcLogDebug("UIBC capability held disabled generation=$generation setting=$uibcSetting profile=${profile.sourceKind}")
            return
        }
        val endpoint = try {
            UibcEndpoint(sourceIp, port, uibcCapabilities, generation, uibcCategory, uibcPath, uibcHidType)
        } catch (e: IllegalArgumentException) {
            notifyUibcDisabled(e.message ?: "invalid endpoint")
            return
        }
        if (notifyUibcEndpoint(endpoint)) {
            uibcEndpointPublished = true
            DebugEventLog.record(DebugEventCategory.UIBC, "UIBC endpoint published port=$port")
            rtspUibcLogInfo("UIBC endpoint published generation=$generation source=$sourceIp port=$port")
        }
    }

    private fun learnProfile(body: String, message: RtspMessage, publishUibc: Boolean = true) {
        val names = body.lineSequence()
            .map { it.substringBefore(':').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val evidence = SourceEvidence(
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
            profile = selected.copy(secondPlay = profile.secondPlay)
            DebugEventLog.record(DebugEventCategory.WFD, "WFD source profile detected ${profile.sourceKind}")
            rtspUibcLogInfo("WFD source profile detected generation=$generation kind=${profile.sourceKind}")
            invokeCallback("profile") { callbacks.onProfile(generation, profile) }
        }
        if (publishUibc) maybePublishUibcEndpoint()
    }

    private fun validateSelected(body: String): String? {
        val selectedUrl = presentationUrlToken(body)
        if (parameter(body, "wfd_presentation_URL") != null) {
            if (selectedUrl.isNullOrBlank() || selectedUrl.equals("none", true)) return "missing presentation URL"
            val urls = parameter(body, "wfd_presentation_URL")!!.split(Regex("\\s+"))
            if (urls.size > 2) return "invalid presentation URL list"
            for (url in urls.filterNot { it.equals("none", true) }) {
                val uri = try { java.net.URI(url) } catch (_: Exception) { null }
                    ?: return "invalid presentation URL"
                if (!uri.scheme.equals("rtsp", true) || uri.host.isNullOrBlank() ||
                    uri.userInfo != null || uri.fragment != null || uri.port !in -1..65535 ||
                    uri.port == 0
                ) return "invalid presentation URL"
            }
        }
        val capabilities = advertisedCapabilities ?: return "capabilities have not been advertised"
        return WfdCapabilities.validateSelection(body, capabilities)
    }

    /** Primary and optional secondary URL tokens are distinct from the URI. */
    private fun presentationUrlToken(body: String): String? =
        parameter(body, "wfd_presentation_URL")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.takeIf { it.isNotEmpty() }

    private fun updatePresentationUrl(body: String) {
        val selected = presentationUrlToken(body)
            ?.takeUnless { it.equals("none", true) || it.isBlank() }
            ?: return
        presentationUrl = if (profile.normalizeBroadcastUrl) selected.replace("255.255.255.255", sourceIp) else selected
        selectedPresentation = true
    }

    private fun parameter(body: String, name: String): String? = body.lineSequence()
        .firstOrNull { it.substringBefore(':').trim().equals(name, true) }
        ?.substringAfter(':', "")
        ?.trim()


    private fun sessionMatches(message: RtspMessage): Boolean =
        sessionId != null && message.sessionId() == sessionId

    private fun sessionHeader(): String = checkNotNull(sessionId) { "RTSP Session not negotiated" }

    private fun publicHeader(): String = "org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER"

    private fun sendResponse(cseqValue: String, status: String, headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0)): Boolean {
        val all = LinkedHashMap<String, String>()
        all["CSeq"] = cseqValue
        all.putAll(headers)
        if (body.isNotEmpty()) all["Content-Type"] = "text/parameters"
        val sent = write(RtspCodec.encode("RTSP/1.0 $status", all, body))
        inboundRequestNanos = Long.MIN_VALUE
        return sent
    }

    private fun sendRequest(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
        optional: Boolean = false,
    ): Int = synchronized(this) {
        val number = cseq.getAndIncrement()
        pending[number] = PendingRequest(method, optional, nanoTime())
        val all = LinkedHashMap<String, String>()
        all["CSeq"] = number.toString()
        all["User-Agent"] = "MiracastSink"
        if (body.isNotEmpty()) all["Content-Type"] = "text/parameters"
        all.putAll(headers)
        if (!write(RtspCodec.encode("$method $url RTSP/1.0", all, body))) pending.remove(number)
        number
    }

    private fun write(bytes: ByteArray): Boolean {
        return try {
            synchronized(this) {
                val stream = output ?: throw IllegalStateException("RTSP output is unavailable")
                stream.write(bytes)
                stream.flush()
            }
            true
        } catch (e: Exception) {
            if (running) finish("control write failed: ${e.message ?: "unknown"}")
            false
        }
    }

    private fun delay(milliseconds: Long) {
        if (milliseconds <= 0) return
        try { Thread.sleep(milliseconds) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun finish(reason: String) {
        if (!finishOnce.compareAndSet(false, true)) return
        running = false
        playing = false
        DebugEventLog.record(DebugEventCategory.RTSP, "RTSP session finishing: $reason")
        pending.clear()
        if (uibcEndpointPublished) disableUibc("RTSP session ended")
        try { socket.close() } catch (_: Exception) {}
        try {
            callbacks.onSessionEnded(generation, reason)
        } catch (_: Throwable) {
            // Cleanup callbacks are best effort. A faulty callback must not
            // turn an RTSP disconnect into another uncaught process crash.
        }
    }
}
