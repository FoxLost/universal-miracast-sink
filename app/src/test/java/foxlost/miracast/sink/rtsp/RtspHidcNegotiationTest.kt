package foxlost.miracast.sink.rtsp

import foxlost.miracast.sink.CompatibilityProfile
import foxlost.miracast.sink.RtspEntry
import foxlost.miracast.sink.uibc.UibcCapability
import foxlost.miracast.sink.uibc.UibcCategory
import foxlost.miracast.sink.uibc.UibcEndpoint
import foxlost.miracast.sink.uibc.UibcInputPath
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RtspHidcNegotiationTest {
    @Test
    fun windowsHidcSelectionAndCombinedDisableReplaceOnlyOptionalInput() {
        ServerSocket(0).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { source ->
                source.soTimeout = 3_000
                val endpoints = LinkedBlockingQueue<UibcEndpoint>()
                val disabled = LinkedBlockingQueue<String>()
                val engine = RtspSessionEngine(
                    listener.accept(), 81L, "127.0.0.1", RtspEntry.Outbound,
                    CompatibilityProfile.windows(),
                    object : RtspSessionCallbacks by RtspSessionCallbacks.NONE {
                        override fun prepareMedia(generation: Long) = LocalRtpEndpoint(15550, null)
                        override fun onUibcEndpoint(generation: Long, endpoint: UibcEndpoint) { endpoints.add(endpoint) }
                        override fun onUibcDisabled(generation: Long, reason: String) { disabled.add(reason) }
                    },
                    capabilitySnapshot = { WfdCapabilities.fullResponse(it, uibcEnabled = true) },
                )
                engine.start()
                val output = source.getOutputStream()
                val input = source.getInputStream()
                fun parameter(cseq: Int, body: String) {
                    output.write(RtspCodec.encode("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", mapOf("CSeq" to cseq.toString()), body.toByteArray()))
                    assertEquals("RTSP/1.0 200 OK", RtspCodec.readMessage(input)!!.startLine)
                }
                try {
                    output.write(RtspCodec.encode("OPTIONS * RTSP/1.0", mapOf("CSeq" to "0")))
                    assertEquals("RTSP/1.0 200 OK", RtspCodec.readMessage(input)!!.startLine)
                    val options = RtspCodec.readMessage(input)!!
                    output.write(RtspCodec.encode("RTSP/1.0 200 OK", mapOf("CSeq" to options.cseq().toString())))
                    output.write(RtspCodec.encode("GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0",
                        mapOf("CSeq" to "0"), "wfd_video_formats\r\nwfd_client_rtp_ports\r\nwfd_uibc_capability\r\n".toByteArray()))
                    assertEquals("RTSP/1.0 200 OK", RtspCodec.readMessage(input)!!.startLine)
                    parameter(1, "wfd_uibc_capability: input_category_list=HIDC; generic_cap_list=none; hidc_cap_list=Keyboard/USB, Mouse/USB, SingleTouch/USB, MultiTouch/USB; port=50000\r\nwfd_uibc_setting: enable\r\n")
                    val hidc = endpoints.poll(2, TimeUnit.SECONDS)!!
                    assertEquals(UibcCategory.HIDC, hidc.category)
                    assertEquals(UibcInputPath.USB, hidc.path)
                    assertEquals(UibcCapability.MultiTouch, hidc.hidType)
                    assertEquals(50000, hidc.port)
                    parameter(2, "wfd_uibc_capability: input_category_list=GENERIC; generic_cap_list=SingleTouch; hidc_cap_list=none; port=50001\r\nwfd_uibc_setting: disable\r\n")
                    assertNotNull(disabled.poll(2, TimeUnit.SECONDS))
                    assertNull(endpoints.poll(100, TimeUnit.MILLISECONDS))
                    parameter(3, "wfd_uibc_setting: enable\r\n")
                    val generic = endpoints.poll(2, TimeUnit.SECONDS)!!
                    assertEquals(UibcCategory.GENERIC, generic.category)
                    assertEquals(50001, generic.port)
                    parameter(4, "wfd_uibc_capability: none\r\n")
                    assertNotNull(disabled.poll(2, TimeUnit.SECONDS))
                    output.write(RtspCodec.encode("GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0", mapOf("CSeq" to "5")))
                    assertEquals("RTSP/1.0 200 OK", RtspCodec.readMessage(input)!!.startLine)
                } finally {
                    engine.close("test complete")
                }
            }
        }
    }
}
