package foxlost.miracast.sink.p2p

import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

class SupplicantControlSessionTest {
    private class Daemon(val global: Boolean = false) {
        val replies = ArrayDeque<String>()
        var radioChanged = false
        var loseMutationReply = false
        var wrongIdentity = false
        var validPong = true
        val events = mutableListOf<String>()
        fun send(wire: String) {
            val routed = wire.startsWith("IFNAME=")
            val command = if (routed) wire.substringAfter(' ') else wire
            check(global || !routed) { "Per-interface endpoint rejects global routing" }
            if (global && !routed && command.startsWith("WFD_")) {
                replies.add("UNKNOWN COMMAND")
                return
            }
            if (routed && command == "ATTACH") {
                replies.add("UNKNOWN COMMAND")
                return
            }
            when (command) {
                "PING" -> replies.add(if (validPong) "PONG" else "FAIL")
                "IFNAME" -> replies.add(if (wrongIdentity) "other0" else if (global && !routed) "UNKNOWN COMMAND" else "p2p0")
                "INTERFACES" -> replies.add(if (global) "wlan0\np2p0\n" else "UNKNOWN COMMAND")
                "ATTACH" -> { replies.add("<3>P2P-DEVICE-FOUND peer"); replies.add("OK") }
                "WFD_SUBELEM_SET 7 00020001" -> {
                    radioChanged = true
                    if (!loseMutationReply) replies.add("OK")
                }
                else -> error("Unexpected daemon command")
            }
        }
        fun session() = SupplicantControlSession(::send, { replies.pollFirst() ?: error("Reply lost") }, events::add)
    }

    @Test fun inheritedGlobalSocketCanVerifyItsRealInterfaceBeforeMutation() {
        val daemon = Daemon(global = true)
        val session = daemon.session()
        session.verify()
        assertTrue(session.mutate("WFD_SUBELEM_SET 7 00020001"))
        assertTrue(daemon.radioChanged)
    }

    @Test fun perInterfaceSocketDoesNotReceiveGuessedGlobalRouting() {
        val daemon = Daemon()
        val session = daemon.session()
        session.verify()
        assertTrue(session.mutate("WFD_SUBELEM_SET 7 00020001"))
        assertTrue(daemon.radioChanged)
    }

    @Test fun rejectedPongCannotAuthorizeDriverMutation() {
        val daemon = Daemon().apply { validPong = false }
        val session = daemon.session()
        assertThrows(IllegalStateException::class.java) { session.verify() }
        assertThrows(IllegalStateException::class.java) { session.mutate("WFD_SUBELEM_SET 7 00020001") }
        assertFalse(daemon.radioChanged)
    }

    @Test fun attachEventBeforeOkDoesNotLoseTheEventOrRejectTheMonitor() {
        for (global in listOf(false, true)) {
            val daemon = Daemon(global = global)
            val session = daemon.session()
            session.verify()
            session.attach()
            assertEquals(listOf("<3>P2P-DEVICE-FOUND peer"), daemon.events)
        }
    }

    @Test fun rejectedTransportCannotAttachAMonitor() {
        val daemon = Daemon(global = true).apply { validPong = false }
        val session = daemon.session()
        assertThrows(IllegalStateException::class.java) { session.verify() }
        assertThrows(IllegalStateException::class.java) { session.attach() }
        assertTrue(daemon.events.isEmpty())
    }

    @Test fun appliedMutationWithLostReplyRemainsUncertainNotRejected() {
        val daemon = Daemon().apply { loseMutationReply = true }
        val session = daemon.session()
        session.verify()
        assertThrows(SupplicantOutcomeUnknown::class.java) { session.mutate("WFD_SUBELEM_SET 7 00020001") }
        assertTrue(daemon.radioChanged)
    }

    @Test fun peerReplyMustBindTheIntendedAddressToTheSelectedDevice() {
        val peer = "96:08:53:3b:71:33"
        assertEquals("a6:08:53:3b:71:33", RootBridgeProtocol.peerInterfaceAddress(peer,
            "$peer\nintended_addr=a6:08:53:3b:71:33\n"))
        assertNull(RootBridgeProtocol.peerInterfaceAddress(peer,
            "02:11:22:33:44:55\nintended_addr=a6:08:53:3b:71:33\n"))
        assertNull(RootBridgeProtocol.peerInterfaceAddress(peer,
            "$peer\nintended_addr=a6:08:53:3b:71:33\nintended_addr=02:11:22:33:44:55\n"))
        assertNull(RootBridgeProtocol.peerInterfaceAddress(peer, "$peer\nintended_addr=00:00:00:00:00:00\n"))
    }

    @Test fun persistentInvitationUsesObservedInterfaceWithoutGoNegotiation() {
        val peer = "96:08:53:3b:71:33"
        assertEquals("a6:08:53:3b:71:33", RootBridgeProtocol.peerInterfaceAddress(peer,
            "$peer\ninterface_addr=a6:08:53:3b:71:33\nintended_addr=00:00:00:00:00:00\n"))
        assertEquals("a6:08:53:3b:71:33", RootBridgeProtocol.peerInterfaceAddress(peer,
            "$peer\ninterface_addr=a6:08:53:3b:71:33\nintended_addr=02:11:22:33:44:55\n"))
        assertNull(RootBridgeProtocol.peerInterfaceAddress(peer,
            "$peer\ninterface_addr=a6:08:53:3b:71:33\ninterface_addr=02:11:22:33:44:55\n"))
    }
}
