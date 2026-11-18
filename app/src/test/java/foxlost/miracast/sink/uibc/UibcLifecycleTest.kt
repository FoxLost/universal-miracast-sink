package foxlost.miracast.sink.uibc

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class UibcLifecycleTest {
    private fun event(action: TouchAction, x: Int, id: Int = 1) = TouchEvent(action, listOf(TouchPoint(id, x, 0)))
    private fun endpoint(generation: Long = 1, hid: Boolean = false) = UibcEndpoint("127.0.0.1", 50000,
        setOf(UibcCapability.SingleTouch), generation,
        if (hid) UibcCategory.HIDC else UibcCategory.GENERIC,
        if (hid) UibcInputPath.USB else null, if (hid) UibcCapability.SingleTouch else null)

    @Test fun queueNeverCoalescesAcrossGestureEdges() {
        val queue = TouchEventQueue(8)
        val input = listOf(event(TouchAction.DOWN, 1), event(TouchAction.MOVE, 10), event(TouchAction.UP, 10), event(TouchAction.DOWN, 20), event(TouchAction.MOVE, 30))
        input.forEach { assertTrue(queue.offer(it)) }
        assertEquals(input, List(5) { queue.removeFirst() })
        assertTrue(queue.offer(event(TouchAction.MOVE, 1)))
        assertTrue(queue.offer(event(TouchAction.MOVE, 2)))
        assertEquals(event(TouchAction.MOVE, 2), queue.removeFirst())
        assertTrue(queue.isEmpty)
    }

    @Test fun queueSignalsSaturationWithoutReplacingAnEdgeOrUnrelatedContact() {
        val queue = TouchEventQueue(2)
        assertTrue(queue.offer(event(TouchAction.DOWN, 1)))
        assertTrue(queue.offer(event(TouchAction.UP, 2)))
        assertFalse(queue.offer(event(TouchAction.DOWN, 3)))
        assertFalse(queue.offer(event(TouchAction.MOVE, 4, 2)))
        assertEquals(TouchAction.DOWN, queue.removeFirst().action)
        assertEquals(TouchAction.UP, queue.removeFirst().action)
    }

    @Test fun saturationClosesAndUnregistersInsteadOfLeavingInputLive() {
        val factoryEntered = CountDownLatch(1)
        val allowFactory = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val socket = MemorySocket()
        val session = TcpUibcSession(1, queueCapacity = 2, socketFactory = { _, _ ->
            factoryEntered.countDown(); allowFactory.await(); socket
        }, onClosed = { closed.countDown() })
        UibcSessionRegistry.register(1, session)
        session.start(endpoint())
        assertTrue(factoryEntered.await(2, TimeUnit.SECONDS))
        session.submit(event(TouchAction.DOWN, 1))
        session.submit(event(TouchAction.UP, 2))
        session.submit(event(TouchAction.DOWN, 3))
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertNull(UibcSessionRegistry.session(1))
        allowFactory.countDown()
        assertTrue(socket.closed.await(2, TimeUnit.SECONDS))
        assertEquals(0, socket.bytes.size())
    }

    @Test fun closeDuringPendingFactoryRejectsLateSocketAndCallbackRunsOnce() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val socket = MemorySocket()
        val calls = AtomicInteger()
        val session = TcpUibcSession(1, socketFactory = { _, _ ->
            entered.countDown()
            // Deliberately emulate a connector which ignores interruption.
            while (true) { try { proceed.await(); break } catch (_: InterruptedException) { } }
            socket
        }, onClosed = { calls.incrementAndGet() })
        session.start(endpoint())
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        session.close("rotation")
        proceed.countDown()
        assertTrue(socket.closed.await(2, TimeUnit.SECONDS))
        session.close("again")
        assertEquals(1, calls.get())
        assertEquals(0, socket.bytes.size())
    }

    @Test fun connectFailureAndPeerEofInvalidateRegistry() {
        val failed = CountDownLatch(1)
        val broken = TcpUibcSession(1, socketFactory = { _, _ -> throw java.io.IOException("refused") }, onClosed = { failed.countDown() })
        UibcSessionRegistry.register(1, broken)
        broken.start(endpoint())
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertNull(UibcSessionRegistry.session(1))
        val ended = CountDownLatch(1)
        val socket = MemorySocket()
        val session = TcpUibcSession(2, socketFactory = { _, _ -> socket }, onClosed = { ended.countDown() })
        UibcSessionRegistry.register(2, session)
        session.start(endpoint(2))
        socket.peerEof.countDown()
        assertTrue(ended.await(2, TimeUnit.SECONDS))
        assertNull(UibcSessionRegistry.session(2))
    }

    @Test fun hidInitializationPrecedesInputAndCloseReleasesWrittenContact() {
        val socket = MemorySocket()
        val initialized = CountDownLatch(1)
        val written = CountDownLatch(1)
        socket.afterWrite = {
            val sent = frames(socket.bytes.toByteArray())
            if (sent.any { it[6].toInt() == 0 && it[9].toInt() == 0x12 }) initialized.countDown()
            if (sent.any { it[6].toInt() == 0 && it[9].toInt() == 1 }) written.countDown()
        }
        val session = TcpUibcSession(1, socketFactory = { _, _ -> socket })
        try {
            session.start(endpoint(hid = true))
            assertTrue(initialized.await(2, TimeUnit.SECONDS))
            val startup = frames(socket.bytes.toByteArray())
            assertEquals(1, startup[0][6].toInt())
            val maximum = startup[1]
            assertEquals(2, maximum[5].toInt()) // Negotiated USB SingleTouch, not MultiTouch.
            assertEquals(0, maximum[6].toInt())
            assertEquals(3, maximum[8].toInt())
            assertEquals(0x12, maximum[9].toInt())
            assertEquals(1, maximum[10].toInt()) // Advertised maximum is one for SingleTouch.
            assertEquals(0, maximum[11].toInt())
            session.submit(event(TouchAction.DOWN, 0x1234))
            assertTrue(written.await(2, TimeUnit.SECONDS))
            session.reset()
            session.close("surface lost")
            val sent = frames(socket.bytes.toByteArray())
            assertEquals(1, sent[2][9].toInt())
            assertEquals(1, sent[2][10].toInt())
            assertEquals(3, sent[2][13].toInt()) // Tip + Confidence.
            assertEquals(1, sent.last()[10].toInt()) // Lift remains a valid record.
            assertEquals(2, sent.last()[13].toInt()) // Tip cleared, Confidence retained.
            assertTrue(socket.noDelay)
        } finally { session.close("test done") }
    }

    @Test fun writeFailureClosesSocketAndRemovesRegistry() {
        val socket = MemorySocket()
        socket.failWrite = true
        val done = CountDownLatch(1)
        val session = TcpUibcSession(1, socketFactory = { _, _ -> socket }, onClosed = { done.countDown() })
        UibcSessionRegistry.register(1, session)
        session.start(endpoint())
        session.submit(event(TouchAction.DOWN, 1))
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertNull(UibcSessionRegistry.session(1))
        assertTrue(socket.closed.await(2, TimeUnit.SECONDS))
    }

    private fun frames(bytes: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val length = ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
            result.add(bytes.copyOfRange(offset, offset + length))
            offset += length
        }
        return result
    }

    private class MemorySocket : Socket() {
        val bytes = ByteArrayOutputStream()
        val closed = CountDownLatch(1)
        val peerEof = CountDownLatch(1)
        var noDelay = false
        var failWrite = false
        var afterWrite: () -> Unit = {}
        override fun isConnected() = true
        override fun setTcpNoDelay(on: Boolean) { noDelay = on }
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
            override fun write(value: ByteArray, offset: Int, length: Int) {
                if (failWrite) throw java.io.IOException("write failed")
                bytes.write(value, offset, length)
                afterWrite()
            }
        }
        override fun getInputStream(): InputStream = object : InputStream() {
            override fun read(): Int { peerEof.await(); return -1 }
        }
        override fun close() { closed.countDown(); peerEof.countDown() }
    }
}
