package foxlost.miracast.sink.rtsp

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RtspProtocolTest {
    @Test
    fun parsesHeadersCaseInsensitivelyAndReadsUtf8BodyByBytes() {
        val body = "é\r\nwfd_trigger_method: SETUP\r\n".toByteArray(StandardCharsets.UTF_8)
        val wire = RtspCodec.encode(
            "RTSP/1.0 200 OK",
            linkedMapOf("cSeQ" to "7", "sEsSiOn" to "1234;timeout=30", "Content-Length" to body.size.toString()),
            body,
        )

        val message = RtspCodec.readMessage(ByteArrayInputStream(wire))
        assertNotNull(message)
        assertEquals(7, message!!.cseq())
        assertEquals("1234", message.sessionId())
        assertEquals("é\r\nwfd_trigger_method: SETUP\r\n", RtspCodec.bodyText(message))
    }

    @Test
    fun parsesOddTransportPortsAndOptionalFields() {
        val transport = RtspTransport.parse(
            "RTP/AVP/UDP;unicast;server_port=52532-52533;" +
                "ssrc=0xAABBCCDD;rtcp-fb-ssrc=123;blocksize=1400"
        )

        assertNotNull(transport)
        assertEquals(52532, transport!!.serverRtpPort)
        assertEquals(52533, transport.serverRtcpPort)
        assertEquals(0xAABBCCDDL, transport.ssrc)
        assertEquals(123L, transport.rtcpFeedbackSsrc)
        assertEquals("1400", transport.parameters["blocksize"])
    }

    @Test
    fun rejectsMalformedTransportPortValuesWithoutRejectingTransport() {
        val transport = RtspTransport.parse("RTP/AVP/UDP;unicast;server_port=0-70000")
        assertNotNull(transport)
        assertNull(transport!!.serverRtpPort)
        assertNull(transport.serverRtcpPort)
    }
}
