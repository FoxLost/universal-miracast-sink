package foxlost.miracast.sink.rtsp

import foxlost.miracast.sink.CompatibilityProfile
import foxlost.miracast.sink.RtspEntry
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspSessionEngineTranscriptTest {
    @Test
    fun windowsSourceGoTranscriptUsesRestrictedPublicAndNegotiatedSession() {
        val server = ServerSocket(0)
        val source = Socket("127.0.0.1", server.localPort)
        val sink = server.accept()
        server.close()
        val transport = mutableListOf<NegotiatedTransport>()
        val played = mutableListOf<Boolean>()
        val playedLatch = CountDownLatch(1)
        val engine = RtspSessionEngine(
            socket = sink,
            generation = 11L,
            sourceIp = "127.0.0.1",
            entry = RtspEntry.Outbound,
            initialProfile = CompatibilityProfile.windows(),
            callbacks = callbacks(transport, played, playedLatch),
        )
        engine.start()
        val input = source.getInputStream()
        val output = source.getOutputStream()

        send(output, "OPTIONS * RTSP/1.0", 1)
        val m1 = read(input)
        assertEquals("200 OK", m1.startLine.substringAfter("RTSP/1.0 "))
        assertEquals("org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER", m1.header("public"))
        val m2 = read(input)
        assertEquals("OPTIONS * RTSP/1.0", m2.startLine)
        sendResponse(output, m2.cseq()!!, "")

        val query = "wfd_video_formats\r\nwfd_audio_codecs\r\n" +
            "intel_friendly_name\r\nmicrosoft_video_formats\r\n"
        send(output, "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", 2, query)
        val m3 = read(input)
        assertTrue(RtspCodec.bodyText(m3).contains("wfd_video_formats:"))
        assertTrue(RtspCodec.bodyText(m3).contains("microsoft_video_formats:"))

        val m4Body = "wfd_presentation_URL: rtsp://127.0.0.1/wfd1.0/streamid=0\r\n" +
            "wfd_video_formats: 00\r\nwfd_audio_codecs: LPCM 00000002 00\r\n" +
            "microsoft_video_formats: none\r\n"
        send(output, "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", 3, m4Body)
        assertEquals("200 OK", read(input).startLine.substringAfter("RTSP/1.0 "))
        send(output, "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", 4, "wfd_trigger_method: SETUP\r\n")
        assertEquals("200 OK", read(input).startLine.substringAfter("RTSP/1.0 "))
        val setup = read(input)
        assertEquals("SETUP", setup.startLine.substringBefore(' '))
        assertTrue(setup.header("transport")!!.contains("client_port=15550-15551"))
        sendResponse(
            output,
            setup.cseq()!!,
            "Session: windows-session\r\nTransport: RTP/AVP/UDP;unicast;server_port=52532-52535;ssrc=0xAABBCCDD;rtcp-fb-ssrc=123\r\n",
        )
        val play = read(input)
        assertEquals("PLAY", play.startLine.substringBefore(' '))
        assertEquals("windows-session", play.sessionId())
        sendResponse(output, play.cseq()!!, "Session: windows-session\r\n")
        val secondPlay = read(input)
        assertEquals("PLAY", secondPlay.startLine.substringBefore(' '))
        assertEquals("windows-session", secondPlay.sessionId())
        sendResponse(output, secondPlay.cseq()!!, "Session: windows-session\r\n")
        assertTrue(playedLatch.await(2, TimeUnit.SECONDS))
        assertTrue(played.singleOrNull() == true)

        assertEquals(1, transport.size)
        assertEquals("windows-session", transport.single().sessionId)
        assertEquals(52532, transport.single().remoteRtpPort)
        assertEquals(0xAABBCCDDL, transport.single().ssrc)
        engine.close("test complete")
        source.close()
    }

    @Test
    fun androidSinkGoTranscriptAcceptsInboundSetupAndUsesLocalServerPorts() {
        val server = ServerSocket(0)
        val source = Socket("127.0.0.1", server.localPort)
        val sink = server.accept()
        server.close()
        val transport = mutableListOf<NegotiatedTransport>()
        val played = mutableListOf<Boolean>()
        val playedLatch = CountDownLatch(1)
        val engine = RtspSessionEngine(
            socket = sink,
            generation = 12L,
            sourceIp = "127.0.0.1",
            entry = RtspEntry.Inbound,
            initialProfile = CompatibilityProfile.android(secondPlay = false),
            callbacks = callbacks(transport, played, playedLatch),
        )
        engine.start()
        val input = source.getInputStream()
        val output = source.getOutputStream()

        send(output, "OPTIONS * RTSP/1.0", 1)
        val options = read(input)
        assertEquals("200 OK", options.startLine.substringAfter("RTSP/1.0 "))
        val sinkOptions = read(input)
        sendResponse(output, sinkOptions.cseq()!!, "")
        send(output, "GET_PARAMETER rtsp://sink/wfd1.0 RTSP/1.0", 2, "wfd_video_formats\r\nwfd_audio_codecs\r\n")
        assertEquals("200 OK", read(input).startLine.substringAfter("RTSP/1.0 "))

        send(
            output,
            "SETUP rtsp://sink/wfd1.0/streamid=0 RTSP/1.0",
            3,
            "",
            "Transport: RTP/AVP/UDP;unicast;client_port=5000-5001\r\n",
        )
        val setup = read(input)
        assertEquals("200 OK", setup.startLine.substringAfter("RTSP/1.0 "))
        assertTrue(setup.header("transport")!!.contains("server_port=15550-15551"))
        val session = setup.sessionId()
        assertNotNull(session)
        send(output, "PLAY rtsp://sink/wfd1.0/streamid=0 RTSP/1.0", 4, "", "Session: $session\r\n")
        assertEquals("200 OK", read(input).startLine.substringAfter("RTSP/1.0 "))

        assertEquals(1, transport.size)
        assertTrue(playedLatch.await(2, TimeUnit.SECONDS))
        assertTrue(played.singleOrNull() == true)
        assertEquals(15550, transport.single().local.rtpPort)
        engine.close("test complete")
        source.close()
    }

    @Test
    fun androidSourceAospPresentationUrlReachesSetupDespiteSuffixAndPeerHost() {
        val server = ServerSocket(0)
        val source = Socket("127.0.0.1", server.localPort)
        val sink = server.accept()
        server.close()
        val transport = mutableListOf<NegotiatedTransport>()
        val engine = RtspSessionEngine(
            socket = sink,
            generation = 14L,
            sourceIp = "127.0.0.1",
            entry = RtspEntry.Outbound,
            initialProfile = CompatibilityProfile.android(secondPlay = false),
            callbacks = object : RtspSessionCallbacks {
                override fun onMediaStarting(generation: Long) = Unit
                override fun prepareMedia(generation: Long) = LocalRtpEndpoint(15550, 15551)
                override fun onTransportReady(generation: Long, value: NegotiatedTransport) {
                    transport += value
                }
                override fun onPlay(generation: Long) = Unit
                override fun onProfile(generation: Long, profile: CompatibilityProfile) = Unit
                override fun onSessionEnded(generation: Long, reason: String) = Unit
            },
        )
        engine.start()
        val input = source.getInputStream()
        val output = source.getOutputStream()

        send(output, "OPTIONS * RTSP/1.0", 1)
        assertEquals("200 OK", read(input).startLine.substringAfter("RTSP/1.0 "))
        val sinkOptions = read(input)
        sendResponse(output, sinkOptions.cseq()!!, "")
        send(output, "GET_PARAMETER rtsp://source/wfd1.0 RTSP/1.0", 2, "wfd_video_formats\r\nwfd_audio_codecs\r\n")
        assertEquals("200 OK", read(input).startLine.substringAfter("RTSP/1.0 "))

        send(
            output,
            "SET_PARAMETER rtsp://source/wfd1.0 RTSP/1.0",
            3,
            "wfd_presentation_URL: rtsp://192.168.49.15:7236/wfd1.0/streamid=0 none\r\n" +
                "wfd_video_formats: 00\r\n" +
                "wfd_audio_codecs: LPCM 00000002 00\r\n",
        )
        assertEquals("200 OK", read(input).startLine.substringAfter("RTSP/1.0 "))
        send(
            output,
            "SET_PARAMETER rtsp://source/wfd1.0 RTSP/1.0",
            4,
            "wfd_trigger_method: SETUP\r\n",
        )
        assertEquals("200 OK", read(input).startLine.substringAfter("RTSP/1.0 "))
        val setup = read(input)
        assertEquals("SETUP", setup.startLine.substringBefore(' '))
        assertTrue(setup.startLine.contains("192.168.49.15:7236/wfd1.0/streamid=0"))
        sendResponse(
            output,
            setup.cseq()!!,
            "Session: android-session\r\nTransport: RTP/AVP/UDP;unicast;server_port=5000-5001\r\n",
        )
        val play = read(input)
        assertEquals("PLAY", play.startLine.substringBefore(' '))
        assertEquals("android-session", play.sessionId())
        assertEquals(1, transport.size)
        assertEquals(15550, transport.single().local.rtpPort)

        engine.close("test complete")
        source.close()
    }

    @Test
    fun outboundSetupWithoutSessionHeaderFailsClosed() {
        val server = ServerSocket(0)
        val source = Socket("127.0.0.1", server.localPort)
        val sink = server.accept()
        server.close()
        val ended = mutableListOf<String>()
        val endedLatch = CountDownLatch(1)
        val transport = mutableListOf<NegotiatedTransport>()
        val engine = RtspSessionEngine(
            sink,
            13L,
            "127.0.0.1",
            RtspEntry.Outbound,
            CompatibilityProfile.unknown(RtspEntry.Outbound),
            object : RtspSessionCallbacks {
                override fun onMediaStarting(generation: Long) = Unit
                override fun prepareMedia(generation: Long) = LocalRtpEndpoint(15550, 15551)
                override fun onTransportReady(generation: Long, value: NegotiatedTransport) { transport += value }
                override fun onPlay(generation: Long) = Unit
                override fun onProfile(generation: Long, profile: CompatibilityProfile) = Unit
                override fun onSessionEnded(generation: Long, reason: String) {
                    ended += reason
                    endedLatch.countDown()
                }
            },
        )
        engine.start()
        val input = source.getInputStream()
        val output = source.getOutputStream()
        send(output, "OPTIONS * RTSP/1.0", 1)
        read(input)
        val sinkOptions = read(input)
        sendResponse(output, sinkOptions.cseq()!!, "")
        send(
            output,
            "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0",
            2,
            "wfd_presentation_URL: rtsp://127.0.0.1/wfd1.0/streamid=0\r\n" +
                "wfd_video_formats: 00\r\nwfd_trigger_method: SETUP\r\n",
        )
        read(input)
        val setup = read(input)
        sendResponse(
            output,
            setup.cseq()!!,
            "Transport: RTP/AVP/UDP;unicast;server_port=52532-52533\r\n",
        )
        assertTrue(endedLatch.await(2, TimeUnit.SECONDS))
        assertTrue(ended.single().contains("missing Session"))
        assertTrue(transport.isEmpty())
        engine.close("test complete")
        source.close()
    }

    @Test
    fun prepareMediaErrorIsContainedAndEndsSession() {
        val server = ServerSocket(0)
        val source = Socket("127.0.0.1", server.localPort)
        val sink = server.accept()
        server.close()
        val ended = mutableListOf<String>()
        val endedLatch = CountDownLatch(1)
        val engine = RtspSessionEngine(
            socket = sink,
            generation = 15L,
            sourceIp = "127.0.0.1",
            entry = RtspEntry.Outbound,
            initialProfile = CompatibilityProfile.android(secondPlay = false),
            callbacks = object : RtspSessionCallbacks {
                override fun onMediaStarting(generation: Long) = Unit
                override fun prepareMedia(generation: Long): LocalRtpEndpoint? {
                    throw AssertionError("receiver startup failed")
                }
                override fun onTransportReady(generation: Long, transport: NegotiatedTransport) = Unit
                override fun onPlay(generation: Long) = Unit
                override fun onProfile(generation: Long, profile: CompatibilityProfile) = Unit
                override fun onSessionEnded(generation: Long, reason: String) {
                    ended += reason
                    endedLatch.countDown()
                }
            },
        )
        engine.start()
        val input = source.getInputStream()
        val output = source.getOutputStream()
        send(output, "OPTIONS * RTSP/1.0", 1)
        read(input)
        val sinkOptions = read(input)
        sendResponse(output, sinkOptions.cseq()!!, "")
        send(
            output,
            "SET_PARAMETER rtsp://127.0.0.1/wfd1.0 RTSP/1.0",
            2,
            "wfd_trigger_method: SETUP\r\n",
        )
        assertEquals("200 OK", read(input).startLine.substringAfter("RTSP/1.0 "))
        assertTrue(endedLatch.await(2, TimeUnit.SECONDS))
        assertEquals(1, ended.size)
        assertTrue(ended.single().contains("media preparation callback failed"))
        source.close()
    }

    private fun callbacks(
        transport: MutableList<NegotiatedTransport>,
        played: MutableList<Boolean>,
        playedLatch: CountDownLatch,
    ) = object : RtspSessionCallbacks {
        override fun onMediaStarting(generation: Long) = Unit
        override fun prepareMedia(generation: Long) = LocalRtpEndpoint(15550, 15551)
        override fun onTransportReady(generation: Long, value: NegotiatedTransport) { transport += value }
        override fun onPlay(generation: Long) {
            played += true
            playedLatch.countDown()
        }
        override fun onProfile(generation: Long, profile: CompatibilityProfile) = Unit
        override fun onSessionEnded(generation: Long, reason: String) = Unit
    }

    private fun send(
        output: java.io.OutputStream,
        startLine: String,
        cseq: Int,
        body: String = "",
        extraHeaders: String = "",
    ) {
        val headers = linkedMapOf("CSeq" to cseq.toString())
        if (extraHeaders.isNotEmpty()) {
            for (line in extraHeaders.split("\r\n")) {
                val separator = line.indexOf(':')
                if (separator > 0) headers[line.substring(0, separator)] = line.substring(separator + 1).trim()
            }
        }
        output.write(RtspCodec.encode(startLine, headers, body.toByteArray(StandardCharsets.UTF_8)))
        output.flush()
    }

    private fun sendResponse(output: java.io.OutputStream, cseq: Int, headers: String) {
        val parsed = RtspCodec.parseHeaderBlock(headers)
        parsed["CSeq"] = cseq.toString()
        output.write(RtspCodec.encode("RTSP/1.0 200 OK", parsed))
        output.flush()
    }

    private fun read(input: java.io.InputStream): RtspMessage =
        RtspCodec.readMessage(input) ?: error("missing RTSP message")
}
