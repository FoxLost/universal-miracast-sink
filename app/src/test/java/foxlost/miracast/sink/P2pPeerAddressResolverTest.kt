package foxlost.miracast.sink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class P2pPeerAddressResolverTest {
    private val device = "96:08:53:3b:71:33"
    private val iface = "a6:08:53:3b:71:33"

    private class Lookup {
        var millis = 0L
        var current = true
        fun resolve(peer: String, table: () -> String?, identity: () -> String? = { null }): String? =
            P2pPeerAddressResolver.resolveFromArp(peer, "p2p0", "192.168.28.29", 0L,
                identity, table, { current }, { millis * 1_000_000 }, { millis += it })
    }

    @Test fun cereusInterfaceIdentityCanArriveAfterTheOldTwoSecondCutoff() {
        val lookup = Lookup()
        val ip = lookup.resolve(device,
            table = { "192.168.28.30 0x1 0x2 $iface * p2p0\n" },
            identity = { if (lookup.millis >= 3_000) iface else null })
        assertEquals("192.168.28.30", ip)
        assertEquals(87_000, remainingControlConnectMs(0, lookup.millis * 1_000_000))
    }

    @Test fun exactLegacyPeerIdentityStillResolvesWithoutAnAlias() {
        assertEquals("192.168.28.30", Lookup().resolve(device.uppercase(),
            { "192.168.28.30 0x1 0x2 ${device.uppercase()} * p2p0\n" }))
    }

    @Test fun authoritativeInterfaceIdentityWinsOverAStaleDeviceAddress() {
        val table = "192.168.28.99 0x1 0x2 $device * p2p0\n" +
            "192.168.28.30 0x1 0x2 $iface * p2p0\n"
        assertEquals("192.168.28.30", Lookup().resolve(device, { table }, { iface }))
    }

    @Test fun unknownPeerNeverUsesTheFirstOrOnlyOtherNeighbor() {
        val lookup = Lookup()
        assertNull(lookup.resolve(device, { "192.168.28.30 0x1 0x2 $iface * p2p0\n" }))
        assertEquals(90_000L, lookup.millis)
    }

    @Test fun wrongInterfaceIncompleteSelfAndInvalidIpv4AreNotReady() {
        val rejected = listOf(
            "192.168.28.30 0x1 0x2 $iface * wlan0",
            "192.168.28.30 0x1 0x0 $iface * p2p0",
            "192.168.28.29 0x1 0x2 $iface * p2p0",
            "999.168.28.30 0x1 0x2 $iface * p2p0",
            "127.0.0.1 0x1 0x2 $iface * p2p0",
            "224.0.0.1 0x1 0x2 $iface * p2p0",
        )
        for (row in rejected) assertNull(row, Lookup().resolve(device, { row }, { iface }))
    }

    @Test fun multiplePeerIpv4AddressesAreNotGuessed() {
        val table = "192.168.28.30 0x1 0x2 $iface * p2p0\n" +
            "192.168.28.31 0x1 0x2 $iface * p2p0\n"
        assertNull(Lookup().resolve(device, { table }, { iface }))
    }

    @Test fun cancelledAttemptCannotPublishAnAddressReturnedByAnInflightRead() {
        val lookup = Lookup()
        assertNull(lookup.resolve(device, {
            lookup.current = false
            "192.168.28.30 0x1 0x2 $iface * p2p0\n"
        }, { iface }))
    }

    @Test fun expiredSharedWindowCannotPublishALateAddress() {
        val lookup = Lookup()
        assertNull(lookup.resolve(device, {
            lookup.millis = 90_000
            "192.168.28.30 0x1 0x2 $iface * p2p0\n"
        }, { iface }))
    }

    @Test fun failedRootOwnerIsNotRetriedAsPeerReadiness() {
        val lookup = Lookup()
        val failure = IllegalStateException("Root socket owner EOF")
        var reads = 0
        val thrown = assertThrows(IllegalStateException::class.java) {
            lookup.resolve(device, {
                reads++
                throw failure
            }, { iface })
        }
        assertSame(failure, thrown)
        assertEquals(1, reads)
        assertEquals(0L, lookup.millis)
    }

    @Test fun failedInterfaceQueryDoesNotFallBackToAStaleDeviceRow() {
        val failure = IllegalStateException("Invalid peer interface reply")
        val thrown = assertThrows(IllegalStateException::class.java) {
            Lookup().resolve(device,
                { throw AssertionError("ARP must not be queried after an IPC failure") },
                { throw failure })
        }
        assertSame(failure, thrown)
    }

    @Test fun cancelledInterfaceReadDoesNotStartAnotherRootQuery() {
        val lookup = Lookup()
        assertNull(lookup.resolve(device,
            { throw AssertionError("Cancelled resolver must not query ARP") },
            { lookup.current = false; iface }))
    }

    @Test fun expiredInterfaceReadDoesNotStartAnotherRootQuery() {
        val lookup = Lookup()
        assertNull(lookup.resolve(device,
            { throw AssertionError("Expired resolver must not query ARP") },
            { lookup.millis = 90_000; iface }))
    }

    @Test fun interruptedResolverDoesNotStartRootQueries() {
        Thread.currentThread().interrupt()
        try {
            assertNull(Lookup().resolve(device,
                { throw AssertionError("Interrupted resolver must not query ARP") },
                { throw AssertionError("Interrupted resolver must not query peer identity") }))
        } finally {
            Thread.interrupted()
        }
    }
}
