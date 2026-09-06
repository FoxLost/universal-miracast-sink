package foxlost.miracast.sink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class P2pPeerAddressResolverTest {
    @Test
    fun parsesKernelArpRowsAndNormalizesMac() {
        val table = """
            IP address       HW type     Flags       HW address            Mask     Device
            192.168.49.2     0x1         0x2         3E:3A:2C:35:09:2D     *        p2p0
            0.0.0.0          0x0         0x0         00:00:00:00:00:00     *        p2p0
        """.trimIndent()

        assertEquals(
            mapOf("3e:3a:2c:35:09:2d" to "192.168.49.2"),
            P2pPeerAddressResolver.parseArpTable(table),
        )
    }
    @Test
    fun fallsBackToRootReaderWhenDirectProcfsReadFails() {
        var rootReads = 0
        val table = P2pPeerAddressResolver.readTableWithFallback(
            directReader = { throw SecurityException("EACCES") },
            rootReader = {
                rootReads++
                "192.168.49.2 0x1 0x2 3e:3a:2c:35:09:2d * p2p0"
            },
        )

        assertEquals(1, rootReads)
        assertEquals("192.168.49.2 0x1 0x2 3e:3a:2c:35:09:2d * p2p0", table)
    }

    @Test
    fun doesNotInvokeRootReaderWhenDirectProcfsReadSucceeds() {
        var rootReads = 0
        val table = P2pPeerAddressResolver.readTableWithFallback(
            directReader = { "direct arp table" },
            rootReader = {
                rootReads++
                "root arp table"
            },
        )

        assertEquals(0, rootReads)
        assertEquals("direct arp table", table)
    }

    @Test
    fun retriesUntilPeerAddressAppears() {
        val tables = listOf(
            "IP address HW type Flags HW address Mask Device\n",
            "IP address HW type Flags HW address Mask Device\n" +
                "192.168.49.2 0x1 0x2 3e:3a:2c:35:09:2d * p2p0\n",
        )
        var reads = 0
        val sleeps = mutableListOf<Long>()

        val address = P2pPeerAddressResolver.resolveFromArp(
            peerMac = "3E:3A:2C:35:09:2D",
            readTable = { tables[reads++].also { } },
            attempts = 2,
            retryDelayMs = 25L,
            sleeper = { sleeps += it },
        )

        assertEquals("192.168.49.2", address)
        assertEquals(listOf(25L), sleeps)
    }

    @Test
    fun boundedLookupReturnsNullWhenPeerIsAbsent() {
        var reads = 0
        val address = P2pPeerAddressResolver.resolveFromArp(
            peerMac = "3e:3a:2c:35:09:2d",
            readTable = { reads++; "" },
            attempts = 3,
            retryDelayMs = 0L,
        )

        assertNull(address)
        assertEquals(3, reads)
    }
}
