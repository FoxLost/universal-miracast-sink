package foxlost.miracast.sink.rtsp

import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RtspRecoveryTest {
    @Test fun advertisedIdrRecoveryIsRateLimitedAndRejectionPreservesPlayback() {
        RtspTranscript().use { t ->
            assertFalse(t.engine.requestIdr())
            t.handshake()
            val capabilities = t.request("GET_PARAMETER", "wfd_idr_request_capability\r\n")
            assertEquals("1", WfdSelection.parameters(RtspCodec.bodyText(capabilities))["wfd_idr_request_capability"])
            t.select()
            t.respond(t.trigger("SETUP"), headers = t.setupHeaders)
            t.respond(t.read())
            assertTrue(t.played.await(2, TimeUnit.SECONDS))
            assertTrue(t.engine.requestIdr())
            val recovery = t.read()
            assertTrue(recovery.startLine.startsWith("SET_PARAMETER "))
            assertEquals("source-session", recovery.sessionId())
            assertEquals("text/parameters", recovery.header("content-type"))
            assertEquals("wfd_idr_request\r\n", RtspCodec.bodyText(recovery))
            assertFalse(t.engine.requestIdr())
            t.respond(recovery, status = "451 Parameter Not Understood")
            assertEquals("RTSP/1.0 200 OK", t.request("GET_PARAMETER", headers = mapOf("Session" to "source-session")).startLine)
            assertEquals(1L, t.ended.count)
        }
    }
}
