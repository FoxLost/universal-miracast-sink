package foxlost.miracast.sink.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootBridgeProtocolTest {
    @Test
    fun onlyActualConsumerCommandsAreAllowed() {
        listOf(
            "WFD_SUBELEM_SET 0 000600111c440032",
            "WFD_SUBELEM_SET 0 000600011c440032",
            "WFD_SUBELEM_SET 7 00020001",
            "WFD_SUBELEM_SET 7 ",
            "WFD_SUBELEM_SET 0 ",
            "P2P_EXT_LISTEN 0 0",
            "P2P_EXT_LISTEN 10 10",
            "P2P_EXT_LISTEN 65535 65535",
            "P2P_CONNECT aa:bb:cc:DD:EE:FF pbc go_intent=0",
        ).forEach { assertTrue(it, RootBridgeProtocol.validSupplicant(it)) }
    }

    @Test
    fun arbitraryRpcAndInjectedCommandsAreRejected() {
        listOf(
            "STATUS", "ATTACH", "REMOVE_NETWORK all", "SET country US",
            "IFNAME=p2p0 P2P_EXT_LISTEN 10 10",
            "P2P_EXT_LISTEN 10 10\nREMOVE_NETWORK all",
            "P2P_EXT_LISTEN 10 10;reboot",
            "P2P_CONNECT aa:bb:cc:dd:ee:ff pbc go_intent=15",
            "P2P_CONNECT aa:bb:cc:dd:ee:fg pbc go_intent=0",
            "WFD_SUBELEM_SET 8 00020001", "WFD_SUBELEM_SET 0 000600111c44",
            "WFD_SUBELEM_SET 7 00020003", "../p2p0", "cat /proc/net/arp",
        ).forEach { assertFalse(it, RootBridgeProtocol.validSupplicant(it)) }
    }

    @Test
    fun invalidExtendedListenWindowsNeverReachRootSocket() {
        listOf("0 10", "10 0", "11 10", "65536 65536", "-1 -1", "1 999999999999", "10 10 ")
            .forEach { assertFalse(it, RootBridgeProtocol.validSupplicant("P2P_EXT_LISTEN $it")) }
    }

    @Test
    fun sentOrEmbeddedOkNeverMeansReadiness() {
        listOf("SENT: WFD_SUBELEM_SET", "RECV: OK", "NOT_OK", "ERR denied OK", "OK\nFAIL", "", "EOF")
            .forEach { reply ->
                var rejected = false
                try { RootBridgeProtocol.requireOk(reply) } catch (_: IllegalStateException) { rejected = true }
                assertTrue(reply, rejected)
            }
        RootBridgeProtocol.requireOk("OK")
    }

    @Test
    fun apkPathsAreOneQuotedShellWord() {
        assertEquals("'/data/app/a b/base.apk'", RootBridgeProtocol.shellQuote("/data/app/a b/base.apk"))
        assertEquals("'a'\\'';reboot'", RootBridgeProtocol.shellQuote("a';reboot"))
    }

    @Test
    fun playerLaunchRejectsSentinelGenerationsAndInvalidEndpointPairs() {
        listOf(
            Triple(-1L, 15550, 15551), Triple(1L, 0, 1), Triple(1L, 65535, 1),
            Triple(1L, 15550, -1), Triple(1L, 15550, 65536), Triple(1L, 15550, 15550),
        ).forEach { (generation, rtp, rtcp) ->
            var rejected = false
            try { RootBridgeProtocol.validatePlayer(generation, rtp, rtcp) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("$generation/$rtp/$rtcp", rejected)
        }
        RootBridgeProtocol.validatePlayer(0L, 1, 65535)
        RootBridgeProtocol.validatePlayer(Long.MAX_VALUE, 65534, 65535)
        RootBridgeProtocol.validatePlayer(1L, 15550, 0)
    }

    @Test
    fun peerRequestsRejectInjectedAddressesAndInvalidIntentRanges() {
        listOf(
            Triple(-1L, "aa:bb:cc:dd:ee:ff", 0),
            Triple(1L, "aa:bb:cc:dd:ee:ff\nPLAYER 1 1 2", 0),
            Triple(1L, "aa:bb:cc:dd:ee:ff;reboot", 0),
            Triple(1L, "aa:bb:cc:dd:ee:ff", -1),
            Triple(1L, "aa:bb:cc:dd:ee:ff", 16),
        ).forEach { (generation, address, intent) ->
            var rejected = false
            try { RootBridgeProtocol.validatePeer(generation, address, intent) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        }
        RootBridgeProtocol.validatePeer(0, "aa:bb:cc:DD:EE:FF", 0)
        RootBridgeProtocol.validatePeer(Long.MAX_VALUE, "aa:bb:cc:dd:ee:ff", 15)
    }
}
