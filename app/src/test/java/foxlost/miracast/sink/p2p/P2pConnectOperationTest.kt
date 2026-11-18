package foxlost.miracast.sink.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class P2pConnectOperationTest {
    private val address = "02:00:00:00:00:01"

    @Test fun appDispatchWithLostCallbackRequiresCancellationBeforeReplacement() {
        val operation = P2pConnectOperation(9, address)
        var appCancels = 0
        var helperInspections = 0
        assertFalse(operation.canReplace())
        assertTrue(operation.settle({ peer ->
            assertEquals(address, peer)
            helperInspections++
            RootPendingConnect.State.PENDING
        }, { appCancels++ }))
        assertEquals(1, appCancels)
        assertEquals(1, helperInspections)
        assertTrue(operation.canReplace())
    }

    @Test fun lateExplicitConnectRejectionDuringInspectionAvoidsCancellingAnotherOperation() {
        val operation = P2pConnectOperation(9, address)
        var cancelled = false
        assertTrue(operation.settle({
            operation.rejected()
            assertFalse(operation.canReplace())
            RootPendingConnect.State.PENDING
        }, { cancelled = true }))
        assertFalse(cancelled)
        assertTrue(operation.canReplace())
    }

    @Test fun formedOrAlreadyEndedAppConnectNeedsNoCancellation() {
        val operation = P2pConnectOperation(9, address)
        var cancelled = false
        assertTrue(operation.settle({ RootPendingConnect.State.COMPLETED }, { cancelled = true }))
        assertFalse(cancelled)
        assertTrue(operation.canReplace())
    }

    @Test fun explicitConnectRejectionCanBeReplacedWithoutLaunchingInspection() {
        val operation = P2pConnectOperation(9, address)
        operation.rejected()
        assertTrue(operation.canReplace())
        assertTrue(operation.settle({ error("Must not inspect rejected CONNECT") }, {
            error("Must not cancel rejected CONNECT")
        }))
    }

    @Test fun cancellationBusyOrMissingCallbackCannotReleaseAppOrGlobalCleanupProof() {
        val operation = P2pConnectOperation(9, address)
        assertFalse(operation.settle({ RootPendingConnect.State.PENDING }, {
            error("cancelConnect reason=2 or callback timeout")
        }))
        assertFalse(operation.canReplace())
        assertFalse(operation.settle({ RootPendingConnect.State.COMPLETED }, {
            error("Failed cleanup must not become a silent retry")
        }))
    }

    @Test fun nullGroupWithUnknownNegotiationStateCannotCancelOrAdmitAnotherConnect() {
        val operation = P2pConnectOperation(9, address)
        assertFalse(operation.settle({ RootPendingConnect.State.UNKNOWN }, {
            error("Must not cancel from null group alone")
        }))
        assertFalse(operation.canReplace())
    }

    @Test fun anotherPeerNegotiationIsNeverCancelled() {
        val operation = P2pConnectOperation(9, address)
        assertFalse(operation.settle({ RootPendingConnect.State.UNRELATED }, {
            error("Must not cancel another peer")
        }))
        assertFalse(operation.canReplace())
    }

    @Test fun overlappingStopAndAttemptCleanupShareOneRealCancellationAcknowledgement() {
        val operation = P2pConnectOperation(9, address)
        val entered = CountDownLatch(1)
        val acknowledge = CountDownLatch(1)
        val cancellations = java.util.concurrent.atomic.AtomicInteger()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val stop = executor.submit<Boolean> {
                operation.settle({ RootPendingConnect.State.PENDING }, {
                    cancellations.incrementAndGet()
                    entered.countDown()
                    check(acknowledge.await(2, TimeUnit.SECONDS))
                })
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val failure = executor.submit<Boolean> {
                operation.settle({ error("Second cleanup must reuse original result") }, {
                    error("Must not cancel twice")
                })
            }
            assertFalse(operation.canReplace())
            acknowledge.countDown()
            assertTrue(stop.get(2, TimeUnit.SECONDS))
            assertTrue(failure.get(2, TimeUnit.SECONDS))
            assertEquals(1, cancellations.get())
            assertTrue(operation.canReplace())
        } finally {
            acknowledge.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun lateOldRejectionCannotReleaseSuccessorOperation() {
        val old = P2pConnectOperation(9, address)
        old.rejected()
        val successor = P2pConnectOperation(10, address)
        old.rejected()
        assertTrue(old.canReplace())
        assertFalse(successor.canReplace())
        assertTrue(old.settle({ error("Old rejection requires no inspection") }, {
            error("Old cleanup cannot cancel successor")
        }))
        assertFalse(successor.canReplace())
    }
}
