package foxlost.miracast.sink.p2p

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RootStaIsolationTest {
    private val connected = RootStaIsolation.Snapshot(true, 7, active = true, idle = false)
    private val idle = RootStaIsolation.Snapshot(true, -1, active = false, idle = true)

    private class Wifi(var state: RootStaIsolation.Snapshot) : RootStaIsolation.Policy {
        private val original = state
        var disconnectAccepted = true
        var disconnectFailure = false
        var reconnectAccepted = true
        var reconnects = 0
        override fun read() = state
        override fun disconnect(): Boolean {
            state = state.copy(networkId = -1, active = false, idle = true)
            if (disconnectFailure) error("Lost disconnect reply")
            return disconnectAccepted
        }
        override fun reconnect(): Boolean {
            check(state.wifiEnabled && !state.active && state.networkId < 0)
            reconnects++
            if (reconnectAccepted) state = original
            return reconnectAccepted
        }
    }

    @Test fun ownedDisconnectionRestoresItsConnectionRequestOnlyOnce() {
        val wifi = Wifi(connected)
        val owner = RootStaIsolation(wifi)
        owner.acquire {}
        assertEquals(idle, wifi.state)
        owner.close()
        assertEquals(connected, wifi.state)
        owner.close()
        assertEquals(1, wifi.reconnects)
    }

    @Test fun originallyDisconnectedOrDisabledRadioIsNotConnectedByCleanup() {
        for (original in listOf(idle, idle.copy(wifiEnabled = false))) {
            val wifi = Wifi(original)
            val owner = RootStaIsolation(wifi)
            owner.acquire {}
            owner.close()
            assertEquals(original, wifi.state)
            assertEquals(0, wifi.reconnects)
        }
    }

    @Test fun cancellationBeforeMutationLeavesOriginalConnectionUntouched() {
        for (cancelAt in 1..2) {
            val wifi = Wifi(connected)
            val owner = RootStaIsolation(wifi)
            var checkpoint = 0
            assertThrows(CancellationException::class.java) {
                owner.acquire { if (++checkpoint == cancelAt) throw CancellationException() }
            }
            owner.close()
            assertEquals(connected, wifi.state)
            assertEquals(0, wifi.reconnects)
        }
    }

    @Test fun cancellationAfterDispatchStillRestoresTheConnectionRequest() {
        val wifi = Wifi(connected)
        val owner = RootStaIsolation(wifi)
        var checkpoint = 0
        assertThrows(CancellationException::class.java) {
            owner.acquire { if (++checkpoint == 3) throw CancellationException() }
        }
        assertEquals(idle, wifi.state)
        owner.close()
        assertEquals(connected, wifi.state)
    }

    @Test fun rejectedOrLostDisconnectReplyDoesNotLoseRollbackOwnership() {
        for (lost in listOf(false, true)) {
            val wifi = Wifi(connected).apply { disconnectAccepted = false; disconnectFailure = lost }
            val owner = RootStaIsolation(wifi)
            assertThrows(IllegalStateException::class.java) { owner.acquire {} }
            owner.close()
            assertEquals(connected, wifi.state)
        }
    }

    @Test fun userConnectionAndRadioOffAreNeverOverwritten() {
        for (replacement in listOf(connected.copy(networkId = 99),
            connected.copy(networkId = -1), idle.copy(wifiEnabled = false))) {
            val wifi = Wifi(connected)
            val owner = RootStaIsolation(wifi)
            owner.acquire {}
            wifi.state = replacement
            owner.close()
            assertEquals(replacement, wifi.state)
            assertEquals(0, wifi.reconnects)
        }
    }

    @Test fun rejectedReconnectAndUnknownStateCannotClaimSuccessfulRestoration() {
        val wifi = Wifi(connected)
        val owner = RootStaIsolation(wifi)
        owner.acquire {}
        wifi.state = idle.copy(idle = false)
        assertThrows(IllegalStateException::class.java) { owner.close() }
        assertEquals(0, wifi.reconnects)
        wifi.state = idle
        wifi.reconnectAccepted = false
        assertThrows(IllegalStateException::class.java) { owner.close() }
        assertEquals(idle, wifi.state)
        wifi.reconnectAccepted = true
        owner.close()
        assertEquals(connected, wifi.state)
    }
}
