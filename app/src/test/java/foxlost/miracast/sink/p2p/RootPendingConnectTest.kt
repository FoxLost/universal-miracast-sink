package foxlost.miracast.sink.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RootPendingConnectTest {
    private val address = "02:00:00:00:00:01"

    private class Framework {
        val owner = RootPendingConnect()
        var state = RootPendingConnect.State.PENDING
        var inspections = 0
        var cancellations = 0
        var failCancel = false
        var formDuringCancel = false

        fun close(generation: Long = 4) = owner.close(generation, {
            inspections++
            state
        }, {
            cancellations++
            if (formDuringCancel) state = RootPendingConnect.State.COMPLETED
            if (failCancel) error("cancelConnect failed reason=2")
            state = RootPendingConnect.State.COMPLETED
        })
    }

    @Test fun pendingConnectMustCancelBeforeOwnershipCanBeReused() {
        val framework = Framework()
        framework.owner.dispatched(4, address)
        assertThrows(IllegalStateException::class.java) { framework.owner.dispatched(5, address) }
        framework.close()
        assertEquals(1, framework.cancellations)
        framework.owner.dispatched(5, address)
    }

    @Test fun dispatchedConnectWithLostBinderReplyStillRequiresCancellation() {
        val framework = Framework()
        assertThrows(IllegalStateException::class.java) {
            framework.owner.dispatched(4, address)
            error("Binder reply lost after dispatch")
        }
        framework.close()
        assertEquals(1, framework.cancellations)
    }

    @Test fun rejectedConnectCreatesNoCancellationObligation() {
        val framework = Framework()
        framework.owner.dispatched(4, address)
        framework.owner.rejected(4, address)
        framework.close()
        assertEquals(0, framework.inspections)
        assertEquals(0, framework.cancellations)
    }

    @Test fun unusedHelperNeverInspectsOrCancelsAnotherPeersOperation() {
        val framework = Framework()
        framework.state = RootPendingConnect.State.UNRELATED
        framework.close()
        assertEquals(0, framework.inspections)
        assertEquals(0, framework.cancellations)
    }

    @Test fun formedOrAlreadyEndedConnectDoesNotCancelInAnInapplicableState() {
        val framework = Framework()
        framework.owner.dispatched(4, address)
        framework.state = RootPendingConnect.State.COMPLETED
        framework.close()
        framework.close()
        assertEquals(1, framework.inspections)
        assertEquals(0, framework.cancellations)
    }

    @Test fun cancelBusyIsNotCleanupSuccessAndRetainsOwnership() {
        val framework = Framework()
        framework.owner.dispatched(4, address)
        framework.failCancel = true
        assertThrows(IllegalStateException::class.java) { framework.close() }
        assertThrows(IllegalStateException::class.java) { framework.owner.dispatched(5, address) }
        framework.failCancel = false
        framework.close()
        assertEquals(2, framework.cancellations)
    }

    @Test fun callbackTimeoutIsNotCleanupSuccessAndRetainsOwnership() {
        val owner = RootPendingConnect()
        owner.dispatched(4, address)
        assertThrows(java.util.concurrent.TimeoutException::class.java) {
            owner.close(4, { RootPendingConnect.State.PENDING }, {
                throw java.util.concurrent.TimeoutException("cancelConnect callback timeout")
            })
        }
        assertThrows(IllegalStateException::class.java) { owner.dispatched(5, address) }
    }

    @Test fun nullGroupWithoutCompletionProofCannotReleaseOrCancelOwnership() {
        val framework = Framework()
        framework.owner.dispatched(4, address)
        framework.state = RootPendingConnect.State.UNKNOWN
        assertThrows(IllegalStateException::class.java) { framework.close() }
        assertEquals(0, framework.cancellations)
        assertThrows(IllegalStateException::class.java) { framework.owner.dispatched(5, address) }
    }

    @Test fun unrelatedPeerIsNeverCancelledAndCannotAdmitSuccessor() {
        val framework = Framework()
        framework.owner.dispatched(4, address)
        framework.state = RootPendingConnect.State.UNRELATED
        assertThrows(IllegalStateException::class.java) { framework.close() }
        assertEquals(0, framework.cancellations)
        assertThrows(IllegalStateException::class.java) { framework.owner.dispatched(5, address) }
    }

    @Test fun formationRacingCancelFailureRequiresFreshCompletionProof() {
        val framework = Framework()
        framework.owner.dispatched(4, address)
        framework.failCancel = true
        framework.formDuringCancel = true
        framework.close()
        assertEquals(2, framework.inspections)
        assertEquals(1, framework.cancellations)
        framework.owner.dispatched(5, address)
    }

    @Test fun staleCleanupAndLateRejectionCannotReleaseSuccessor() {
        val framework = Framework()
        framework.owner.dispatched(4, address)
        framework.close()
        framework.owner.dispatched(5, address)
        assertThrows(IllegalStateException::class.java) { framework.close(4) }
        assertThrows(IllegalStateException::class.java) { framework.owner.rejected(4, address) }
        framework.state = RootPendingConnect.State.PENDING
        framework.close(5)
        assertEquals(2, framework.cancellations)
    }
}
