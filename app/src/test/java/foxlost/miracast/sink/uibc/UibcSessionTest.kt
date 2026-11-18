package foxlost.miracast.sink.uibc

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UibcSessionTest {
    @Test fun oneGenerationSessionSerializesFramesAndClosesIdempotently() {
        val server = ServerSocket(0)
        val received = mutableListOf<ByteArray>()
        val done = CountDownLatch(1)
        val acceptor = Thread {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                repeat(3) {
                    val header = readFully(input, 4)
                    val length = ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
                    received += header + readFully(input, length - 4)
                }
                done.countDown()
            }
        }
        acceptor.start()
        val generation = 42L
        val session = TcpUibcSession(generation, socketFactory = { host, port -> Socket(host, port) })
        val endpoint = UibcEndpoint("127.0.0.1", server.localPort, setOf(UibcCapability.SingleTouch), generation)
        session.start(endpoint)
        session.submit(TouchEvent(TouchAction.DOWN, listOf(TouchPoint(1, 1, 2))))
        session.submit(TouchEvent(TouchAction.MOVE, listOf(TouchPoint(1, 3, 4))))
        session.submit(TouchEvent(TouchAction.UP, listOf(TouchPoint(1, 5, 6))))
        assertTrue(done.await(3, TimeUnit.SECONDS))
        session.close("test")
        session.close("idempotent")
        server.close()
        assertEquals(3, received.size)
        assertEquals(0, received[0][4].toInt())
        assertEquals(2, received[1][4].toInt())
        assertEquals(1, received[2][4].toInt())
    }

    @Test fun singleTouchEndpointLimitsPointerCount() {
        val server = ServerSocket(0)
        var frame = ByteArray(0)
        val done = CountDownLatch(1)
        val acceptor = Thread {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val header = readFully(input, 4)
                val length = ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
                frame = header + readFully(input, length - 4)
                done.countDown()
            }
        }
        acceptor.start()
        val session = TcpUibcSession(43L, socketFactory = { host, port -> Socket(host, port) })
        session.start(UibcEndpoint("127.0.0.1", server.localPort, setOf(UibcCapability.SingleTouch), 43L))
        session.submit(
            TouchEvent(
                TouchAction.DOWN,
                listOf(TouchPoint(1, 100, 200), TouchPoint(2, 300, 400)),
            ),
        )
        assertTrue(done.await(3, TimeUnit.SECONDS))
        session.close("test")
        server.close()
        assertEquals(1, frame[7].toInt())
    }

    @Test fun staleGenerationEndpointIsRejected() {
        val session = TcpUibcSession(5L)
        try {
            session.start(UibcEndpoint("127.0.0.1", 1, setOf(UibcCapability.SingleTouch), 6L))
        } catch (_: IllegalArgumentException) {
            return
        } finally {
            session.close("test")
        }
        throw AssertionError("stale endpoint was accepted")
    }

    private fun readFully(input: java.io.InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(bytes, offset, size - offset)
            check(count >= 0) { "unexpected EOF" }
            offset += count
        }
        return bytes
    }
}
