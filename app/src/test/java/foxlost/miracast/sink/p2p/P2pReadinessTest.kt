package foxlost.miracast.sink.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class P2pReadinessTest {
    private val gate = GroupReadinessGate(timeoutMs = 6_000L)

    @Test
    fun groupStartedBeforeIpv4RemainsWaitingThenTimesOut() {
        assertEquals(
            GroupReadinessGate.Result.Waiting,
            gate.evaluate(5_999L, groupFormed = true, peerPresent = true, interfaceUp = true, ipv4Present = false),
        )
        assertEquals(
            GroupReadinessGate.Result.TimedOut,
            gate.evaluate(6_000L, groupFormed = true, peerPresent = true, interfaceUp = true, ipv4Present = false),
        )
    }

    @Test
    fun readinessRequiresEveryNetworkPredicate() {
        assertEquals(
            GroupReadinessGate.Result.Waiting,
            gate.evaluate(1_000L, groupFormed = true, peerPresent = true, interfaceUp = true, ipv4Present = false),
        )
        assertEquals(
            GroupReadinessGate.Result.Ready,
            gate.evaluate(1_000L, groupFormed = true, peerPresent = true, interfaceUp = true, ipv4Present = true),
        )
    }
}
