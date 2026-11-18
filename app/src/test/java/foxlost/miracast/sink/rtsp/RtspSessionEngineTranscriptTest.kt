package foxlost.miracast.sink.rtsp

import foxlost.miracast.sink.CompatibilityProfile
import foxlost.miracast.sink.RtspEntry
import foxlost.miracast.sink.media.WfdAvcFormats
import foxlost.miracast.sink.uibc.UibcEndpoint
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RtspSessionEngineTranscriptTest {
    @Test fun standardsM1ThroughM7UsesSourceUrlAndBoundClientPorts() {
        RtspTranscript().use { t ->
            t.handshake()
            val m3 = t.request("GET_PARAMETER", "wfd_video_formats\r\nwfd_audio_codecs\r\nwfd_client_rtp_ports\r\n")
            assertTrue(RtspCodec.bodyText(m3).contains("15550 0 mode=play"))
            t.select("rtsp://192.168.49.15:7236/wfd1.0/streamid=0 none")
            val setup = t.trigger("SETUP")
            assertEquals("SETUP rtsp://192.168.49.15:7236/wfd1.0/streamid=0 RTSP/1.0", setup.startLine)
            assertEquals("RTP/AVP/UDP;unicast;client_port=15550-15551", setup.header("transport"))
            t.respond(setup, headers = t.setupHeaders)
            val play = t.read()
            assertEquals("PLAY", play.startLine.substringBefore(' '))
            assertEquals("source-session", play.sessionId())
            t.respond(play)
            assertTrue(t.played.await(2, TimeUnit.SECONDS))
            assertEquals(52532, t.transports.single().remoteRtpPort)
            // A source-owned M16 is answered, never replaced with sink polling.
            assertEquals("RTSP/1.0 200 OK", t.request("GET_PARAMETER", headers = mapOf("Session" to "source-session")).startLine)
        }
    }

    @Test fun m3CannotAdvanceBeforeMatchingM2ResponseAndSinkDoesNotSendFirst() {
        RtspTranscript().use { t ->
            assertEquals(0, t.source.getInputStream().available())
            t.send("OPTIONS")
            assertEquals("RTSP/1.0 200 OK", t.read().startLine)
            val m2 = t.read()
            assertEquals("OPTIONS * RTSP/1.0", m2.startLine)
            assertEquals("RTSP/1.0 455 Method Not Valid in This State", t.request("GET_PARAMETER", "wfd_video_formats\r\n").startLine)
            t.respond(m2, headers = emptyMap())
            assertEquals("RTSP/1.0 200 OK", t.request("GET_PARAMETER", "wfd_video_formats\r\n").startLine)
        }
    }

    @Test fun sourceControlsAreAcknowledgedBeforeSessionRequestsAndTeardownCloses() {
        RtspTranscript().use { t ->
            t.establish()
            val pause = t.trigger("PAUSE")
            assertEquals("PAUSE", pause.startLine.substringBefore(' '))
            assertEquals("source-session", pause.sessionId())
            t.respond(pause)
            val play = t.trigger("PLAY")
            assertEquals("PLAY", play.startLine.substringBefore(' '))
            t.respond(play)
            val teardown = t.trigger("TEARDOWN")
            assertEquals("TEARDOWN", teardown.startLine.substringBefore(' '))
            t.respond(teardown)
            assertTrue(t.ended.await(2, TimeUnit.SECONDS))
            assertEquals(-1, t.source.getInputStream().read())
        }
    }

    @Test fun directSourceSetupIsNotMistakenForSinkRoleEvenOnAcceptedSocket() {
        RtspTranscript(entry = RtspEntry.Inbound).use { t ->
            t.handshake()
            assertEquals("RTSP/1.0 405 Method Not Allowed", t.request("SETUP").startLine)
            assertTrue(t.transports.isEmpty())
            t.select()
            val setup = t.trigger("SETUP")
            assertEquals("SETUP", setup.startLine.substringBefore(' '))
        }
    }

    @Test fun unsupportedSelectionsAreRejectedWithoutPoisoningValidNegotiation() {
        RtspTranscript().use { t ->
            t.handshake()
            for (body in listOf(
                "wfd_content_protection: HDCP2.1 port=1024\r\n",
                "wfd_audio_codecs: AAC 00000001 00\r\n",
                "wfd_video_formats: 00\r\n",
                "wfd_video_formats: 00 00 01 01 00000002 00000000 00000000 00 0000 0000 00 none none\r\n",
                "wfd_video_formats: 00 00 02 10 00000008 00000000 00000000 00 0000 0000 00 none none\r\n",
                "wfd_video_formats: 00 00 01 10 00000040 00000000 00000000 00 0000 0000 00 none none\r\n",
                "wfd_video_formats: 00 00 01 10 00000080 00000000 00000000 00 0000 0000 00 none none\r\n",
                "wfd_client_rtp_ports: RTP/AVP/TCP;unicast 15550 0 mode=play\r\n",
                "wfd_presentation_URL: http://source/stream none\r\n",
                "wfd_trigger_method: RECORD\r\n",
            )) {
                assertEquals("RTSP/1.0 451 Parameter Not Understood", t.request("SET_PARAMETER", body).startLine)
            }
            t.select()
            assertEquals("SETUP", t.trigger("SETUP").startLine.substringBefore(' '))
        }
    }

    @Test fun setupRequiresSourcePresentationSelectionNotImplicitFallback() {
        RtspTranscript().use { t ->
            t.handshake()
            assertEquals("RTSP/1.0 455 Method Not Valid in This State", t.request("SET_PARAMETER", "wfd_trigger_method: SETUP\r\n").startLine)
            t.select()
            assertEquals("RTSP/1.0 200 OK", t.request("GET_PARAMETER", "wfd_video_formats\r\n").startLine)
            assertEquals("SETUP", t.trigger("SETUP").startLine.substringBefore(' '))
        }
    }

    @Test fun mismatchedResponseCseqCannotAdvanceOrConsumePendingRequest() {
        RtspTranscript().use { t ->
            t.send("OPTIONS")
            t.read()
            val options = t.read()
            t.source.getOutputStream().write(RtspCodec.encode("RTSP/1.0 200 OK", mapOf("CSeq" to "${options.cseq()!! + 100}")))
            assertEquals("RTSP/1.0 455 Method Not Valid in This State", t.request("GET_PARAMETER", "wfd_video_formats\r\n").startLine)
            t.respond(options, headers = emptyMap())
            assertEquals("RTSP/1.0 200 OK", t.request("GET_PARAMETER", "wfd_video_formats\r\n").startLine)
            assertEquals(1L, t.ended.count)
            assertTrue(t.transports.isEmpty())
        }
    }

    @Test fun suppliedWrongOrEmptyPlayResponseSessionCannotStartPlayback() {
        for (headers in listOf(mapOf("Session" to "other-session"), mapOf("Session" to ""))) {
            RtspTranscript().use { t ->
                t.handshake()
                t.select()
                val setup = t.trigger("SETUP")
                t.respond(setup, headers = t.setupHeaders)
                val play = t.read()
                t.respond(play, headers = headers)
                assertTrue(t.ended.await(2, TimeUnit.SECONDS))
                assertEquals(1L, t.played.count)
            }
        }
    }

    @Test fun omittedResponseSessionRequiresMatchingCseqAndPreservesSessionForControls() {
        RtspTranscript().use { t ->
            t.handshake()
            t.select()
            t.respond(t.trigger("SETUP"), headers = t.setupHeaders)
            val play = t.read()
            t.source.getOutputStream().write(RtspCodec.encode("RTSP/1.0 200 OK",
                mapOf("CSeq" to "${play.cseq()!! + 100}")))
            // The following source request is a barrier after the unmatched reply.
            assertEquals("RTSP/1.0 200 OK", t.request("GET_PARAMETER").startLine)
            assertEquals(1L, t.played.count)
            t.respond(play, headers = emptyMap())
            assertTrue(t.played.await(2, TimeUnit.SECONDS))

            val pause = t.trigger("PAUSE")
            assertEquals("source-session", pause.sessionId())
            t.respond(pause, headers = emptyMap())
            val resume = t.trigger("PLAY")
            assertEquals("source-session", resume.sessionId())
            t.respond(resume, headers = emptyMap())
            val teardown = t.trigger("TEARDOWN")
            assertEquals("source-session", teardown.sessionId())
            t.respond(teardown, headers = emptyMap())
            assertTrue(t.ended.await(2, TimeUnit.SECONDS))
            assertEquals(-1, t.source.getInputStream().read())
            assertEquals(1, t.endCount)
        }
    }

    @Test fun playResponseSessionParametersDoNotReplaceSetupSession() {
        RtspTranscript().use { t ->
            t.handshake()
            t.select()
            t.respond(t.trigger("SETUP"), headers = t.setupHeaders)
            t.respond(t.read(), headers = mapOf("Session" to "source-session;timeout=30"))
            assertTrue(t.played.await(2, TimeUnit.SECONDS))
            val pause = t.trigger("PAUSE")
            assertEquals("source-session", pause.header("session"))
            t.respond(pause)
        }
    }

    @Test fun setupMustReturnSessionAndMatchingUdpTransport() {
        val invalid = listOf(
            mapOf("Transport" to "RTP/AVP/UDP;unicast;client_port=15550-15551;server_port=52532-52533"),
            mapOf("Session" to "source-session"),
            mapOf("Session" to "source-session", "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"),
            mapOf("Session" to "source-session", "Transport" to "RTP/AVP/UDP;unicast;client_port=15552-15553;server_port=52532-52533"),
            mapOf("Session" to "source-session", "Transport" to "RTP/AVP/UDP;multicast;client_port=15550-15551;server_port=52532-52533"),
        )
        for (headers in invalid) {
            RtspTranscript().use { t ->
                t.handshake()
                t.select()
                t.respond(t.trigger("SETUP"), headers = headers)
                assertTrue(t.ended.await(2, TimeUnit.SECONDS))
                assertTrue(t.transports.isEmpty())
                assertEquals(1L, t.played.count)
            }
        }
    }

    @Test fun suppliedWrongOrEmptySourceSessionRejectedButSessionSurvives() {
        RtspTranscript().use { t ->
            t.establish()
            for (headers in listOf(mapOf("Session" to "other"), mapOf("Session" to ""))) {
                assertEquals("RTSP/1.0 454 Session Not Found", t.request("GET_PARAMETER", headers = headers).startLine)
                assertEquals("RTSP/1.0 454 Session Not Found",
                    t.request("SET_PARAMETER", "wfd_trigger_method: TEARDOWN\r\n", headers).startLine)
            }
            assertEquals("RTSP/1.0 200 OK", t.request("GET_PARAMETER", headers = mapOf("Session" to "source-session")).startLine)
            assertEquals(1L, t.ended.count)
        }
    }

    @Test fun optInSecondPlay406IsNonfatalAndFirstPlayAlreadyStartsMedia() {
        RtspTranscript(secondPlay = true).use { t ->
            t.establish()
            val duplicate = t.read()
            assertEquals("PLAY", duplicate.startLine.substringBefore(' '))
            t.respond(duplicate, status = "406 Not Acceptable")
            assertEquals("RTSP/1.0 200 OK", t.request("GET_PARAMETER", headers = mapOf("Session" to "source-session")).startLine)
            assertEquals(1L, t.ended.count)
        }
    }

    @Test fun acceptedUibcUpdateRequiresExplicitEnableAndAllowsLaterDisable() {
        RtspTranscript().use { t ->
            t.handshake()
            val capability = "wfd_uibc_capability: input_category_list=GENERIC; generic_cap_list=SingleTouch; hidc_cap_list=none; port=50000\r\n"
            assertEquals("RTSP/1.0 200 OK", t.request("SET_PARAMETER", capability).startLine)
            assertTrue(t.endpoints.isEmpty())
            assertEquals("RTSP/1.0 200 OK", t.request("SET_PARAMETER", "wfd_uibc_setting: enable\r\n").startLine)
            // A following request is a deterministic barrier for post-ack commit.
            t.request("GET_PARAMETER", "wfd_video_formats\r\n")
            assertEquals(50000, t.endpoints.single().port)
            t.request("SET_PARAMETER", "wfd_uibc_setting: disable\r\n")
            t.request("GET_PARAMETER", "wfd_video_formats\r\n")
            assertTrue(t.disabled.isNotEmpty())
        }
    }

    @Test fun mediaPreparationErrorIsContainedAndCleanupOccursOnce() {
        RtspTranscript(mediaFailure = true).use { t ->
            t.handshake(queryCapabilities = false)
            t.send("GET_PARAMETER", "wfd_video_formats\r\nwfd_client_rtp_ports\r\n")
            assertTrue(t.ended.await(2, TimeUnit.SECONDS))
            assertTrue(t.endReason.contains("media preparation callback failed"))
            assertEquals(1, t.endCount)
        }
    }

    @Test fun rtpOnlyOfferDoesNotSynthesizeRtcp() {
        RtspTranscript(localRtcpPort = null).use { t ->
            t.handshake()
            t.select()
            val setup = t.trigger("SETUP")
            assertEquals("RTP/AVP/UDP;unicast;client_port=15550", setup.header("transport"))
            t.respond(setup, headers = t.setupHeaders)
            t.respond(t.read())
            assertTrue(t.played.await(2, TimeUnit.SECONDS))
            assertNull(t.transports.single().local.rtcpPort)
            assertNull(t.transports.single().remoteRtcpPort)
        }
    }

    @Test fun mandatoryRequestTimeoutClosesAndNotifiesOnce() {
        val clock = java.util.concurrent.atomic.AtomicLong()
        RtspTranscript(nanoTime = clock::get).use { t ->
            t.send("OPTIONS")
            t.read()
            t.read() // Leave M2 unanswered.
            clock.set(5_000_000_001L)
            assertTrue(t.ended.await(2, TimeUnit.SECONDS))
            assertTrue(t.endReason.contains("OPTIONS response timeout"))
            assertEquals(-1, t.source.getInputStream().read())
            t.engine.close("duplicate close")
            assertEquals(1, t.endCount)
        }
    }

    @Test fun sourceSilenceUsesNegotiatedSessionTimeout() {
        val clock = java.util.concurrent.atomic.AtomicLong()
        RtspTranscript(nanoTime = clock::get).use { t ->
            t.handshake()
            t.select()
            t.respond(t.trigger("SETUP"), headers = t.setupHeaders + ("Session" to "source-session;timeout=10"))
            t.respond(t.read())
            assertTrue(t.played.await(2, TimeUnit.SECONDS))
            clock.set(5_000_000_000L)
            assertEquals("RTSP/1.0 200 OK", t.request("SET_PARAMETER", "vendor_optional_hint: ignored\r\n").startLine)
            clock.set(10_000_000_001L)
            assertTrue(t.ended.await(2, TimeUnit.SECONDS))
            assertEquals("source control timeout", t.endReason)
        }
    }

    @Test fun firstSourceMessageHasSixSecondDeadline() {
        val clock = java.util.concurrent.atomic.AtomicLong()
        RtspTranscript(nanoTime = clock::get).use { t ->
            clock.set(6_000_000_001L)
            assertTrue(t.ended.await(2, TimeUnit.SECONDS))
            assertEquals("source control timeout", t.endReason)
        }
    }

    @Test fun splitM4CannotTriggerSetupUntilVideoAndPresentationAreAccepted() {
        RtspTranscript().use { t ->
            t.handshake(queryCapabilities = false)
            assertEquals("RTSP/1.0 455 Method Not Valid in This State",
                t.request("SET_PARAMETER", "wfd_presentation_URL: rtsp://127.0.0.1/stream none\r\n").startLine)
            assertEquals("RTSP/1.0 200 OK", t.request("GET_PARAMETER", "wfd_video_formats\r\nwfd_client_rtp_ports\r\n").startLine)
            assertEquals("RTSP/1.0 200 OK",
                t.request("SET_PARAMETER", "wfd_presentation_URL: rtsp://127.0.0.1/stream none\r\n").startLine)
            assertEquals("RTSP/1.0 455 Method Not Valid in This State",
                t.request("SET_PARAMETER", "wfd_trigger_method: SETUP\r\n").startLine)
            assertEquals("RTSP/1.0 200 OK", t.request("SET_PARAMETER",
                "wfd_video_formats: 00 00 01 01 00000020 00000000 00000000 00 0000 0000 00 none none\r\n").startLine)
            assertEquals("SETUP rtsp://127.0.0.1/stream RTSP/1.0", t.trigger("SETUP").startLine)
        }
    }

    @Test fun validMidSessionUpdatesAndUnknownExtensionsRemainLegal() {
        RtspTranscript().use { t ->
            t.establish()
            assertEquals("RTSP/1.0 200 OK", t.request("SET_PARAMETER",
                "wfd_video_formats: 00 00 01 01 00000020 00000000 00000000 00 0000 0000 00 none none\r\n" +
                    "vendor_optional_hint: ignored\r\n").startLine)
            assertEquals("RTSP/1.0 451 Parameter Not Understood", t.request("SET_PARAMETER", "wfd_audio_codecs: AAC 00000001 00\r\n").startLine)
            assertEquals("RTSP/1.0 200 OK", t.request("GET_PARAMETER", headers = mapOf("Session" to "source-session")).startLine)
            assertEquals(1L, t.ended.count)
            assertEquals(1, t.transports.size)
        }
    }

    @Test fun sourceCannotOverrideLocallyDisabledUibc() {
        RtspTranscript(uibcEnabled = false).use { t ->
            t.handshake()
            assertEquals("RTSP/1.0 451 Parameter Not Understood", t.request("SET_PARAMETER",
                "wfd_uibc_capability: input_category_list=GENERIC; generic_cap_list=SingleTouch; hidc_cap_list=none; port=50000\r\nwfd_uibc_setting: enable\r\n").startLine)
            assertTrue(t.endpoints.isEmpty())
            assertEquals("RTSP/1.0 200 OK", t.request("GET_PARAMETER", "wfd_video_formats\r\n").startLine)
        }
    }

    @Test fun m16WithOrWithoutSessionRefreshesDeadlineWithoutSinkPolling() {
        for (headers in listOf(emptyMap(), mapOf("Session" to "source-session"))) {
            val clock = java.util.concurrent.atomic.AtomicLong()
            RtspTranscript(nanoTime = clock::get).use { t ->
                t.handshake()
                t.select()
                t.respond(t.trigger("SETUP"), headers = t.setupHeaders + ("Session" to "source-session;timeout=10"))
                t.respond(t.read())
                assertTrue(t.played.await(2, TimeUnit.SECONDS))
                clock.set(9_000_000_000L)
                assertEquals("RTSP/1.0 200 OK", t.request("GET_PARAMETER", headers = headers).startLine)
                clock.set(18_000_000_000L)
                assertEquals("RTSP/1.0 200 OK", t.request("SET_PARAMETER", "vendor_optional_hint: ignored\r\n").startLine)
                assertEquals(1L, t.ended.count)
                clock.set(19_000_000_001L)
                assertTrue(t.ended.await(2, TimeUnit.SECONDS))
                assertEquals("source control timeout", t.endReason)
            }
        }
    }

    @Test fun audioOnlyR1SelectionCanEstablishTransport() {
        RtspTranscript().use { t ->
            t.handshake()
            assertEquals("RTSP/1.0 200 OK", t.request("SET_PARAMETER",
                "wfd_presentation_URL: rtsp://127.0.0.1/audio none\r\n" +
                    "wfd_video_formats: none\r\nwfd_audio_codecs: LPCM 00000002 00\r\n").startLine)
            val setup = t.trigger("SETUP")
            assertEquals("SETUP rtsp://127.0.0.1/audio RTSP/1.0", setup.startLine)
            t.respond(setup, headers = t.setupHeaders)
            t.respond(t.read())
            assertTrue(t.played.await(2, TimeUnit.SECONDS))
        }
    }
}

/** Real-socket consumer fixture; no access to engine internals. */
internal class RtspTranscript(
    secondPlay: Boolean = false,
    entry: RtspEntry = RtspEntry.Outbound,
    mediaFailure: Boolean = false,
    localRtcpPort: Int? = 15551,
    nanoTime: () -> Long = System::nanoTime,
    uibcEnabled: Boolean = true,
) : Closeable {
    val source: Socket
    val engine: RtspSessionEngine
    val played = CountDownLatch(1)
    val ended = CountDownLatch(1)
    val transports = java.util.concurrent.CopyOnWriteArrayList<NegotiatedTransport>()
    val endpoints = java.util.concurrent.CopyOnWriteArrayList<UibcEndpoint>()
    val disabled = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile var endReason = ""
    @Volatile var endCount = 0
    private var nextCseq = 1
    val setupHeaders = mapOf("Session" to "source-session;timeout=60", "Transport" to
        if (localRtcpPort == null) "RTP/AVP/UDP;unicast;client_port=15550;server_port=52532"
        else "RTP/AVP/UDP;unicast;client_port=15550-15551;server_port=52532-52533;ssrc=0xAABBCCDD")

    init {
        val listener = ServerSocket(0)
        source = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 3000 }
        val sink = listener.accept()
        listener.close()
        engine = RtspSessionEngine(sink, 11L, "127.0.0.1", entry,
            CompatibilityProfile.android(secondPlay).copy(setupDelayMs = 0, firstPlayDelayMs = 0, secondPlayDelayMs = 0),
            object : RtspSessionCallbacks by RtspSessionCallbacks.NONE {
                override fun prepareMedia(generation: Long): LocalRtpEndpoint {
                    if (mediaFailure) throw AssertionError("receiver startup failed")
                    return LocalRtpEndpoint(15550, localRtcpPort)
                }
                override fun onTransportReady(generation: Long, transport: NegotiatedTransport) { transports += transport }
                override fun onPlay(generation: Long) { played.countDown() }
                override fun onSessionEnded(generation: Long, reason: String) {
                    endReason = reason
                    endCount++
                    ended.countDown()
                }
                override fun onUibcEndpoint(generation: Long, endpoint: UibcEndpoint) { endpoints += endpoint }
                override fun onUibcDisabled(generation: Long, reason: String) { disabled += reason }
            }, nanoTime, { port ->
                WfdSelection.parameters(WfdCapabilities.fullResponse(port, uibcEnabled)).toMutableMap().apply {
                    this["wfd_video_formats"] = WfdAvcFormats.build(720, 30, listOf(
                        WfdAvcFormats.Decoder(1, 16) { _, _, _ -> true },
                        WfdAvcFormats.Decoder(2, 16) { _, _, _ -> true },
                    ))
                }.entries.joinToString("") { (name, value) -> "$name: $value\r\n" }
            })
        engine.start()
    }

    fun send(method: String, body: String = "", headers: Map<String, String> = emptyMap()) {
        val url = if (method == "OPTIONS") "*" else "rtsp://localhost/wfd1.0"
        val all = linkedMapOf("CSeq" to "${nextCseq++}").apply { putAll(headers) }
        source.getOutputStream().write(RtspCodec.encode("$method $url RTSP/1.0", all, body.toByteArray()))
    }
    fun read(): RtspMessage = RtspCodec.readMessage(source.getInputStream()) ?: error("missing RTSP message")
    fun request(method: String, body: String = "", headers: Map<String, String> = emptyMap()): RtspMessage {
        send(method, body, headers)
        return read()
    }
    fun respond(request: RtspMessage, status: String = "200 OK", headers: Map<String, String> = mapOf("Session" to "source-session")) {
        source.getOutputStream().write(RtspCodec.encode("RTSP/1.0 $status", linkedMapOf("CSeq" to "${request.cseq()}").apply { putAll(headers) }))
    }
    fun handshake(queryCapabilities: Boolean = true) {
        assertEquals("RTSP/1.0 200 OK", request("OPTIONS").startLine)
        val options = read()
        assertEquals("OPTIONS * RTSP/1.0", options.startLine)
        respond(options, headers = emptyMap())
        if (queryCapabilities) {
            assertEquals("RTSP/1.0 200 OK", request("GET_PARAMETER",
                "wfd_video_formats\r\nwfd_audio_codecs\r\nwfd_client_rtp_ports\r\nwfd_uibc_capability\r\n").startLine)
        }
    }
    fun select(url: String = "rtsp://127.0.0.1/wfd1.0/streamid=0 none") {
        assertEquals("RTSP/1.0 200 OK", request("SET_PARAMETER", "wfd_presentation_URL: $url\r\n" +
            "wfd_video_formats: 00 00 01 01 00000020 00000000 00000000 00 0000 0000 00 none none\r\n").startLine)
    }
    fun trigger(method: String): RtspMessage {
        assertEquals("RTSP/1.0 200 OK", request("SET_PARAMETER", "wfd_trigger_method: $method\r\n").startLine)
        return read()
    }
    fun establish() {
        handshake()
        select()
        respond(trigger("SETUP"), headers = setupHeaders)
        val play = read()
        assertEquals("PLAY", play.startLine.substringBefore(' '))
        respond(play)
        assertTrue(played.await(2, TimeUnit.SECONDS))
    }
    override fun close() {
        engine.close("test complete")
        source.close()
    }
}
