package foxlost.miracast.sink.p2p

import android.net.wifi.p2p.WifiP2pDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pManagerPeerPolicyTest {
    @Test
    fun discoveryOnlyArmingRequiresWfdAndAvailableOrInvitedStatus() {
        assertTrue(
            P2pManager.isWfdPeerEligible(
                WifiP2pDevice.AVAILABLE,
                wfdEnabled = true,
                preferredAddressProvided = false,
                preferredAddressMatches = false,
            )
        )
        assertTrue(
            P2pManager.isWfdPeerEligible(
                WifiP2pDevice.INVITED,
                wfdEnabled = true,
                preferredAddressProvided = false,
                preferredAddressMatches = false,
            )
        )
        assertFalse(
            P2pManager.isWfdPeerEligible(
                WifiP2pDevice.AVAILABLE,
                wfdEnabled = false,
                preferredAddressProvided = false,
                preferredAddressMatches = false,
            )
        )
    }

    @Test
    fun explicitRequestNeverFallsBackToAnUnmatchedPeer() {
        assertTrue(
            P2pManager.isWfdPeerEligible(
                WifiP2pDevice.UNAVAILABLE,
                wfdEnabled = true,
                preferredAddressProvided = true,
                preferredAddressMatches = true,
            )
        )
        assertFalse(
            P2pManager.isWfdPeerEligible(
                WifiP2pDevice.AVAILABLE,
                wfdEnabled = true,
                preferredAddressProvided = true,
                preferredAddressMatches = false,
            )
        )
    }

    @Test
    fun headlessConnectUsesTemporaryNetworkId() {
        assertTrue(P2pManager.NETWORK_ID_TEMPORARY == -1)
    }

    @Test
    fun invitationFallbackRequiresAnAcceptedInvitation() {
        assertTrue(
            P2pManager.invitationFallbackPeer(
                peerAddress = "f4:7b:09:f3:b8:9a",
                invitationAccepted = true,
                fallbackScheduled = false,
                activeGroup = false,
            ) == "f4:7b:09:f3:b8:9a",
        )
        assertNull(
            P2pManager.invitationFallbackPeer(
                peerAddress = "f4:7b:09:f3:b8:9a",
                invitationAccepted = false,
                fallbackScheduled = false,
                activeGroup = false,
            )
        )
    }

    @Test
    fun invitationFallbackIsBlockedForDuplicateOrActiveGroup() {
        assertNull(
            P2pManager.invitationFallbackPeer(
                peerAddress = "f4:7b:09:f3:b8:9a",
                invitationAccepted = true,
                fallbackScheduled = true,
                activeGroup = false,
            )
        )
        assertNull(
            P2pManager.invitationFallbackPeer(
                peerAddress = "f4:7b:09:f3:b8:9a",
                invitationAccepted = true,
                fallbackScheduled = false,
                activeGroup = true,
            )
        )
    }

    @Test
    fun repeatedInvitationMetadataCanScheduleAfterStateReset() {
        val peer = "f4:7b:09:f3:b8:9a"
        // The previous attempt has already scheduled its one fallback.
        assertNull(
            P2pManager.invitationFallbackPeer(
                peerAddress = peer,
                invitationAccepted = true,
                fallbackScheduled = true,
                activeGroup = false,
            )
        )
        // A new received invitation with the same metadata resets the
        // attempt before its acceptance event arrives.
        assertNull(
            P2pManager.invitationFallbackPeer(
                peerAddress = peer,
                invitationAccepted = false,
                fallbackScheduled = false,
                activeGroup = false,
            )
        )
        // Once that new attempt is explicitly accepted, it can schedule one
        // fresh fallback rather than inheriting the old guard.
        assertTrue(
            P2pManager.invitationFallbackPeer(
                peerAddress = peer,
                invitationAccepted = true,
                fallbackScheduled = false,
                activeGroup = false,
            ) == peer
        )
    }
    @Test
    fun interfaceDiscoveryUsesProcWhenSysfsIsDenied() {
        val procNetDev = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes
              wlan0: 123 1 0 0 0 0 0 0 456 2 0 0 0 0 0 0
              p2p1: 789 3 0 0 0 0 0 0 987 4 0 0 0 0 0 0
        """.trimIndent()

        assertEquals(
            listOf("p2p1", "p2p0"),
            P2pManager.p2pInterfaceCandidates(
                sysfsNames = null,
                procNetDev = procNetDev,
            ),
        )
    }

    @Test
    fun interfaceDiscoveryFallsBackToFrameworkInterfaceAndDeduplicates() {
        assertEquals(
            listOf("p2p0"),
            P2pManager.p2pInterfaceCandidates(
                sysfsNames = null,
                procNetDev = null,
            ),
        )
        assertEquals(
            listOf("p2p0", "p2p-dev-wlan0"),
            P2pManager.p2pInterfaceCandidates(
                sysfsNames = listOf("p2p0", "p2p0", "wlan0"),
                procNetDev = "p2p-dev-wlan0: 0 0 0 0 0 0 0 0",
            ),
        )
    }

    @Test
    fun interfaceDiscoveryRejectsUnsafeOrNonP2pNames() {
        assertEquals(
            listOf("p2p0"),
            P2pManager.p2pInterfaceCandidates(
                sysfsNames = listOf("wlan0", "p2p", "p2p0/../wlan0", "../p2p1"),
                procNetDev = null,
            ),
        )
    }

}
