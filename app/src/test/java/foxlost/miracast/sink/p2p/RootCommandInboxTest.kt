package foxlost.miracast.sink.p2p

import java.io.FilterInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCommandInboxTest {
    @Test
    fun liveOwnerCanRunCommandsInOrderAndCheckItsOwnership() {
        InboxFixture().use { fixture ->
            fixture.send("START")
            val first = fixture.submit { fixture.inbox.next() }
            assertEquals("START", first.get(5, TimeUnit.SECONDS))
            fixture.inbox.checkActive()

            fixture.send("NEXT")
            val second = fixture.submit { fixture.inbox.next() }
            assertEquals("NEXT", second.get(5, TimeUnit.SECONDS))
            fixture.inbox.checkActive()
        }
    }

    @Test
    fun eofWakesAConsumerWaitingForItsNextCommand() {
        InboxFixture().use { fixture ->
            val entered = CountDownLatch(1)
            val next = fixture.submit {
                entered.countDown()
                fixture.inbox.next()
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            fixture.writer.close()
            assertNull(next.get(5, TimeUnit.SECONDS))
            assertThrows(CancellationException::class.java) { fixture.inbox.checkActive() }
        }
    }

    @Test
    fun eofCancelsActivePreparationAndDiscardsAnAlreadyQueuedCommand() {
        val active = CountDownLatch(1)
        val stopWorker = CountDownLatch(1)
        val executedQueuedCommands = AtomicInteger()
        InboxFixture(queuedBytes = "START\nNEXT\n".toByteArray(Charsets.UTF_8).size).use { fixture ->
            val worker = fixture.submit {
                assertEquals("START", fixture.inbox.next())
                fixture.inbox.checkActive()
                active.countDown()
                assertThrows(CancellationException::class.java) {
                    // Model synchronous preparation with explicit cancellation checkpoints.
                    while (!stopWorker.await(20, TimeUnit.MILLISECONDS)) {
                        fixture.inbox.checkActive()
                    }
                    throw AssertionError("Active preparation ended without observing owner EOF")
                }
                val queued = fixture.inbox.next()
                if (queued != null) executedQueuedCommands.incrementAndGet()
                assertNull("Queued work must not survive the owner's EOF", queued)
            }
            try {
                fixture.send("START")
                assertTrue(active.await(5, TimeUnit.SECONDS))
                fixture.send("NEXT")
                assertTrue("The input reader must drain queued commands while preparation is active",
                    fixture.commandsDrained.await(5, TimeUnit.SECONDS))
                fixture.inbox.checkActive()
                fixture.writer.close()
                worker.get(5, TimeUnit.SECONDS)
                assertEquals(0, executedQueuedCommands.get())
                assertThrows(CancellationException::class.java) { fixture.inbox.checkActive() }
                assertNull(fixture.inbox.next())
            } finally {
                stopWorker.countDown()
            }
        }
    }

    private class InboxFixture(queuedBytes: Int = Int.MAX_VALUE) : AutoCloseable {
        private val input = PipedInputStream(16 * 1024)
        val writer = PipedOutputStream(input)
        val commandsDrained = CountDownLatch(1)
        private val observedInput = object : FilterInputStream(input) {
            private var consumed = 0

            private fun beforeRead() {
                // A new read after all payload bytes means prior lines have been handled.
                if (consumed >= queuedBytes) commandsDrained.countDown()
            }

            override fun read(): Int {
                beforeRead()
                return input.read().also { if (it >= 0) consumed++ }
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                beforeRead()
                return input.read(bytes, offset, length).also { if (it > 0) consumed += it }
            }
        }
        val inbox = RootCommandInbox(observedInput)
        private val executor = Executors.newSingleThreadExecutor()
        private val futures = mutableListOf<Future<*>>()

        fun send(command: String) {
            writer.write("$command\n".toByteArray(Charsets.UTF_8))
            writer.flush()
        }

        fun <T> submit(action: () -> T): Future<T> =
            executor.submit(Callable { action() }).also { futures.add(it) }

        override fun close() {
            try {
                writer.close()
            } finally {
                observedInput.close()
                input.close()
                futures.forEach { it.cancel(true) }
                executor.shutdownNow()
                assertTrue("Inbox test workers must stop", executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }
}
