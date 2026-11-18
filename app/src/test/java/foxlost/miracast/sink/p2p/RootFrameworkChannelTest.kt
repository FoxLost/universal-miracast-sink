package foxlost.miracast.sink.p2p

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootFrameworkChannelTest {
    @Test
    fun startupCanExceedAnIdleIntervalWhenRealStepsKeepCompleting() {
        ChannelFixture(idleTimeoutMs = 2_000).use { fixture ->
            val entered = CountDownLatch(1)
            val reply = fixture.submit {
                entered.countDown()
                fixture.channel.readReply(startup = true)
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val pacing = CountDownLatch(1)
            val started = System.nanoTime()
            for (completed in 1..16) {
                fixture.send("PROGRESS START $completed 16")
                assertFalse(pacing.await(250, TimeUnit.MILLISECONDS))
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) > 2_000)
            fixture.send("OK")
            assertEquals("OK", reply.get(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun startupWithoutProgressTimesOutWhileThePipeRemainsOpen() {
        ChannelFixture(idleTimeoutMs = 1_500).use { fixture ->
            val entered = CountDownLatch(1)
            val timedOut = fixture.submit {
                entered.countDown()
                assertThrows(SocketTimeoutException::class.java) {
                    fixture.channel.readReply(startup = true)
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            timedOut.get(8, TimeUnit.SECONDS)
            assertFalse("A command timeout is not cleanup proof", fixture.channel.cleanup.isDone)
        }
    }

    @Test
    fun progressCannotKeepANormalCommandAlive() {
        ChannelFixture(idleTimeoutMs = 1_500).use { fixture ->
            val entered = CountDownLatch(1)
            val stopProgress = CountDownLatch(1)
            val progressSupplied = CountDownLatch(1)
            val timedOut = fixture.submit {
                entered.countDown()
                assertThrows(SocketTimeoutException::class.java) {
                    fixture.channel.readReply()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val producer = fixture.submit {
                var completed = 0
                while (!stopProgress.await(100, TimeUnit.MILLISECONDS)) {
                    fixture.send("PROGRESS START ${++completed} 1000")
                    progressSupplied.countDown()
                }
            }
            try {
                assertTrue(progressSupplied.await(5, TimeUnit.SECONDS))
                timedOut.get(8, TimeUnit.SECONDS)
                assertFalse("The producer must still be supplying progress", producer.isDone)
                assertFalse(fixture.channel.cleanup.isDone)
            } finally {
                stopProgress.countDown()
            }
            producer.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun repeatedUnchangedProgressCannotHideAStalledStartup() {
        ChannelFixture(idleTimeoutMs = 1_500).use { fixture ->
            val stopProgress = CountDownLatch(1)
            val supplied = CountDownLatch(1)
            val timedOut = fixture.submit {
                assertThrows(SocketTimeoutException::class.java) {
                    fixture.channel.readReply(startup = true)
                }
            }
            val producer = fixture.submit {
                while (!stopProgress.await(100, TimeUnit.MILLISECONDS)) {
                    fixture.send("PROGRESS START 0 215")
                    supplied.countDown()
                }
            }
            try {
                assertTrue(supplied.await(5, TimeUnit.SECONDS))
                timedOut.get(8, TimeUnit.SECONDS)
                assertFalse("Repeated announcements must not count as completed steps", producer.isDone)
                assertFalse(fixture.channel.cleanup.isDone)
            } finally {
                stopProgress.countDown()
            }
            producer.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun ordinaryOkAndCompletedPreparationDoNotReleaseCleanupConsumers() {
        ChannelFixture().use { fixture ->
            fixture.send("OK")
            assertEquals("OK", fixture.channel.readReply())
            assertFalse("An ordinary acknowledgment is not restoration proof", fixture.channel.cleanup.isDone)

            fixture.send("PROGRESS START 1 2")
            fixture.send("PROGRESS START 2 2")
            fixture.send("OK")
            // Reading this reply fences consumption of both preceding preparation records.
            assertEquals("OK", fixture.channel.readReply())
            assertFalse("Even completed progress is not terminal cleanup proof", fixture.channel.cleanup.isDone)

            val waiting = CountDownLatch(1)
            val nextOwner = fixture.submit {
                waiting.countDown()
                fixture.channel.cleanup.get(10, TimeUnit.SECONDS)
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            assertFalse(nextOwner.isDone)
            fixture.send("CLEANUP OK")
            assertTrue(nextOwner.get(5, TimeUnit.SECONDS))
            assertTrue(fixture.channel.cleanup.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun explicitCleanupFailureRejectsTheNextOwnerBeforeEof() {
        ChannelFixture().use { fixture ->
            fixture.send("PROGRESS START 1 1")
            fixture.send("CLEANUP ERR")
            assertFalse(fixture.channel.cleanup.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun eofWithoutCleanupMarkerRejectsTheNextOwnerDespiteOkAndProgress() {
        ChannelFixture().use { fixture ->
            fixture.send("PROGRESS START 1 1")
            fixture.send("OK")
            assertEquals("OK", fixture.channel.readReply())
            assertFalse(fixture.channel.cleanup.isDone)
            fixture.writer.close()
            assertFalse(fixture.channel.cleanup.get(5, TimeUnit.SECONDS))
        }
    }

    private class ChannelFixture(idleTimeoutMs: Long = 5_000) : AutoCloseable {
        private val input = PipedInputStream(16 * 1024)
        val writer = PipedOutputStream(input)
        val channel = RootFrameworkChannel(input, idleTimeoutMs)
        private val executor = Executors.newCachedThreadPool()
        private val futures = mutableListOf<Future<*>>()

        fun send(line: String) {
            writer.write("$line\n".toByteArray(Charsets.UTF_8))
            writer.flush()
        }

        fun <T> submit(action: () -> T): Future<T> =
            executor.submit(Callable { action() }).also { futures.add(it) }

        override fun close() {
            try {
                writer.close()
                channel.cleanup.get(5, TimeUnit.SECONDS)
            } finally {
                input.close()
                futures.forEach { it.cancel(true) }
                executor.shutdownNow()
                assertTrue("Channel test workers must stop", executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }
}
