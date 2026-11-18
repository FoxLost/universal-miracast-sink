package foxlost.miracast.sink.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RootCleanupBarrierTest {
    @Test
    fun failedRestorationBlocksConsumersEvenAfterUnusedSuccessorCloses() {
        val barrier = RootCleanupBarrier()
        val first = barrier.reserve()
        first.awaitPrevious()
        first.complete(false)
        val cancelled = barrier.reserve()
        assertThrows(RootCleanupException::class.java) { cancelled.awaitPrevious() }
        cancelled.complete(true)

        var driverChanges = 0
        val next = barrier.reserve()
        assertThrows(RootCleanupException::class.java) {
            next.awaitPrevious()
            driverChanges++
        }
        assertEquals(0, driverChanges)
        next.complete(true)
    }

    @Test
    fun finishingAnOlderOwnerAgainCannotReleaseANewerFailedOwner() {
        val barrier = RootCleanupBarrier()
        val first = barrier.reserve()
        first.awaitPrevious()
        first.complete(true)
        val second = barrier.reserve()
        second.awaitPrevious()
        second.complete(false)
        first.complete(true)
        val next = barrier.reserve()
        assertThrows(RootCleanupException::class.java) { next.awaitPrevious() }
        next.complete(true)
    }
    @Test
    fun pendingOwnerMustRestoreBeforeNextConsumerChangesState() {
        val barrier = RootCleanupBarrier()
        val driverOwner = java.util.concurrent.atomic.AtomicInteger(1)
        val first = barrier.reserve()
        first.awaitPrevious()
        val second = barrier.reserve()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val entered = java.util.concurrent.CountDownLatch(1)
            val consumer = executor.submit {
                entered.countDown()
                second.awaitPrevious()
                assertEquals(0, driverOwner.getAndSet(2))
            }
            org.junit.Assert.assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
            org.junit.Assert.assertFalse(consumer.isDone)
            assertEquals(1, driverOwner.getAndSet(0))
            first.complete(true)
            consumer.get(1, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(2, driverOwner.get())
        } finally {
            first.complete(true)
            second.complete(true)
            executor.shutdownNow()
        }
    }

    @Test fun navigationCleanupIncludesAnOlderOwnerEvenIfNewestOwnerHasRetired() {
        val barrier = RootCleanupBarrier()
        val older = barrier.reserve()
        val newest = barrier.reserve()
        newest.complete(true)
        org.junit.Assert.assertFalse(barrier.awaitClean(1))
        older.complete(true)
        org.junit.Assert.assertTrue(barrier.awaitClean(100))
    }
}
