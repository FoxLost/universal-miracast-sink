package foxlost.miracast.sink.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootSocketSessionTest {
    private class Driver {
        var wfd = false
        var uibc = false
        var period = 0
        var reject = false
        var loseReply = false
        var failListenCleanup = false

        var excluded = ""

        fun eligible(frequency: Int): Boolean = excluded.split(',').filter { it.isNotEmpty() }.none {
            val bounds = it.split('-').map(String::toInt)
            frequency in bounds[0]..bounds[1]
        }
        fun execute(command: String): Boolean {
            if (reject) return false
            when {
                command == "P2P_EXT_LISTEN 0 0" -> {
                    if (failListenCleanup) return false
                    period = 0
                }
                command.startsWith("P2P_EXT_LISTEN ") -> period = command.split(' ')[1].toInt()
                command.startsWith("WFD_SUBELEM_SET 0 ") -> wfd = command != "WFD_SUBELEM_SET 0 "
                command.startsWith("WFD_SUBELEM_SET 7 ") -> uibc = command != "WFD_SUBELEM_SET 7 "
                command.startsWith("P2P_SET disallow_freq ") -> excluded = command.removePrefix("P2P_SET disallow_freq ")
                else -> error("Unexpected driver operation")
            }
            if (loseReply) {
                loseReply = false
                throw SupplicantOutcomeUnknown()
            }
            return true
        }
    }

    @Test
    fun ownerEofRestoresItsChangesAndRepeatedCloseCannotAffectNextOwner() {
        val driver = Driver()
        val session = RootSocketSession(driver::execute, { "" }, { _, _ -> error("Unexpected peer query") })
        assertEquals("OK", session.request("COMMAND WFD_SUBELEM_SET 0 000600111c440032"))
        session.request("COMMAND WFD_SUBELEM_SET 7 00020001")
        session.request("COMMAND P2P_EXT_LISTEN 10 10")
        assertTrue(driver.wfd && driver.uibc && driver.period == 10)
        session.close()
        assertFalse(driver.wfd)
        assertFalse(driver.uibc)
        assertEquals(0, driver.period)
        driver.period = 99
        session.close()
        assertEquals(99, driver.period)
    }

    @Test
    fun lostAcknowledgmentStillRestoresAppliedDriverState() {
        val driver = Driver().apply { loseReply = true }
        val session = RootSocketSession(driver::execute, { "" }, { _, _ -> error("Unexpected peer query") })
        assertEquals("UNCERTAIN", session.request("COMMAND P2P_EXT_LISTEN 10 10"))
        assertEquals(10, driver.period)
        session.close()
        assertEquals(0, driver.period)
    }

    @Test
    fun rejectedOperationDoesNotClearAnotherOwnersState() {
        val driver = Driver().apply { wfd = true; reject = true }
        val session = RootSocketSession(driver::execute, { "" }, { _, _ -> error("Unexpected peer query") })
        assertTrue(session.request("COMMAND WFD_SUBELEM_SET 0 000600111c440032").startsWith("ERR "))
        driver.reject = false
        session.close()
        assertTrue(driver.wfd)
    }

    @Test
    fun cleanupFailureIsReportedAfterOtherIndependentStateIsRestored() {
        val driver = Driver()
        val session = RootSocketSession(driver::execute, { "" }, { _, _ -> error("Unexpected peer query") })
        session.request("COMMAND WFD_SUBELEM_SET 0 000600111c440032")
        session.request("COMMAND WFD_SUBELEM_SET 7 00020001")
        session.request("COMMAND P2P_EXT_LISTEN 10 10")
        driver.failListenCleanup = true
        assertThrows(IllegalStateException::class.java) { session.close() }
        assertEquals(10, driver.period)
        assertFalse(driver.wfd)
        assertFalse(driver.uibc)
    }

    @Test
    fun unusedCleanupDoesNotChangeAnotherOwnersListenStateEvenWhenTransportIsUnavailable() {
        val driver = Driver().apply { period = 77; reject = true }
        val session = RootSocketSession(driver::execute, { "" }, { _, _ -> error("Unexpected peer query") })
        assertEquals("OK", session.request("CLEAR_EXT_LISTEN"))
        session.close()
        assertEquals(77, driver.period)
    }

    @Test
    fun acknowledgedEarlyClearReleasesItsObligationBeforeAnotherOwnerStarts() {
        val driver = Driver()
        val session = RootSocketSession(driver::execute, { "" }, { _, _ -> error("Unexpected peer query") })
        session.request("COMMAND P2P_EXT_LISTEN 10 10")
        assertEquals("OK", session.request("CLEAR_EXT_LISTEN"))
        assertEquals(0, driver.period)
        driver.period = 99
        session.close()
        assertEquals(99, driver.period)
    }

    @Test
    fun rejectedEarlyClearStillRestoresOnOwnerExit() {
        val driver = Driver()
        val session = RootSocketSession(driver::execute, { "" }, { _, _ -> error("Unexpected peer query") })
        session.request("COMMAND P2P_EXT_LISTEN 10 10")
        driver.failListenCleanup = true
        assertTrue(session.request("CLEAR_EXT_LISTEN").startsWith("ERR "))
        assertEquals(10, driver.period)
        driver.failListenCleanup = false
        session.close()
        assertEquals(0, driver.period)
    }

    @Test
    fun acknowledgedNeutralCommandsReleaseEveryOwnedRestoration() {
        val driver = Driver()
        val session = RootSocketSession(driver::execute, { "" }, { _, _ -> error("Unexpected peer query") })
        session.request("COMMAND WFD_SUBELEM_SET 0 000600111c440032")
        session.request("COMMAND WFD_SUBELEM_SET 7 00020001")
        session.request("COMMAND P2P_EXT_LISTEN 10 10")
        session.request("COMMAND WFD_SUBELEM_SET 0 ")
        session.request("COMMAND WFD_SUBELEM_SET 7 ")
        session.request("COMMAND P2P_EXT_LISTEN 0 0")
        driver.wfd = true
        driver.uibc = true
        driver.period = 99
        session.close()
        assertTrue(driver.wfd)
        assertTrue(driver.uibc)
        assertEquals(99, driver.period)
    }

    @Test
    fun uncertainNeutralCommandRetainsRestorationUntilAcknowledged() {
        val driver = Driver()
        val session = RootSocketSession(driver::execute, { "" }, { _, _ -> error("Unexpected peer query") })
        session.request("COMMAND WFD_SUBELEM_SET 7 00020001")
        driver.loseReply = true
        assertEquals("UNCERTAIN", session.request("COMMAND WFD_SUBELEM_SET 7 "))
        // A lost reply cannot certify that neutral state remains applied.
        driver.uibc = true
        session.close()
        assertFalse(driver.uibc)
    }

    @Test fun lostBandAcknowledgementStillReleasesTheOwnedRestrictionOnEof() {
        val driver = Driver().apply { loseReply = true }
        val session = RootSocketSession(driver::execute, { "" }, { _, _ -> null })
        assertEquals("UNCERTAIN", session.request("COMMAND P2P_SET disallow_freq 0-2400,2500-7125"))
        assertTrue(driver.eligible(2412))
        assertFalse(driver.eligible(5180))
        assertFalse(driver.eligible(5955))
        session.close()
        assertTrue(driver.eligible(5180))
        assertTrue(driver.eligible(5955))
        driver.excluded = "0-2500"
        session.close()
        assertFalse(driver.eligible(2412)) // An old close cannot clear a new owner's policy.
    }

    @Test fun unusedSessionDoesNotClearAnExternalBandPolicy() {
        val driver = Driver().apply { excluded = "0-2500" }
        RootSocketSession(driver::execute, { "" }, { _, _ -> null }).close()
        assertFalse(driver.eligible(2412))
        assertTrue(driver.eligible(5180))
    }

    @Test fun bandCleanupFailureStillAttemptsEveryIndependentRestoration() {
        val restored = ArrayList<String>()
        var cleaning = false
        val session = RootSocketSession({ command ->
            if (cleaning) {
                restored += command
                throw java.io.IOException("Denied cleanup for $command")
            }
            true
        }, { "" }, { _, _ -> null })
        session.request("COMMAND P2P_SET disallow_freq 0-4900,5900-7125")
        session.request("COMMAND WFD_SUBELEM_SET 0 000600111c440032")
        session.request("COMMAND WFD_SUBELEM_SET 7 00020001")
        session.request("COMMAND P2P_EXT_LISTEN 10 10")
        cleaning = true
        val failure = assertThrows(IllegalStateException::class.java) { session.close() }
        assertEquals(listOf("P2P_SET disallow_freq ", "P2P_EXT_LISTEN 0 0",
            "WFD_SUBELEM_SET 7 ", "WFD_SUBELEM_SET 0 "), restored)
        assertTrue(failure.message.orEmpty().contains("P2P_SET disallow_freq"))
        assertEquals(3, failure.cause?.suppressed?.size)
    }

    @Test fun readonlyPeerLookupCannotRestoreAnotherOwnersDriverState() {
        val driver = Driver().apply { wfd = true; uibc = true; period = 77 }
        val session = RootSocketSession(driver::execute, { "" }, { _, peer ->
            RootBridgeProtocol.peerInterfaceAddress(peer, "$peer\nintended_addr=a6:08:53:3b:71:33\n")
        })
        assertEquals("PEER_INTERFACE a6:08:53:3b:71:33",
            session.request("PEER_INTERFACE p2p0 96:08:53:3b:71:33"))
        session.close()
        assertTrue(driver.wfd)
        assertTrue(driver.uibc)
        assertEquals(77, driver.period)
    }

    @Test fun unavailablePeerTransportIsRetryableWithoutHidingOtherRootFailures() {
        var ready = false
        val session = RootSocketSession({ error("No mutation expected") }, { "" }, { _, _ ->
            if (!ready) throw SupplicantTransportUnavailable("No verified supplicant transport")
            "a6:08:53:3b:71:33"
        })
        val query = "PEER_INTERFACE p2p0 96:08:53:3b:71:33"
        assertEquals("LOOKUP_UNAVAILABLE", session.request(query))
        ready = true
        assertEquals("PEER_INTERFACE a6:08:53:3b:71:33", session.request(query))
        session.close()
    }

    @Test fun permanentPeerLookupFailureDoesNotBecomeNotFound() {
        val session = RootSocketSession({ error("No mutation expected") }, { "" }, { _, _ ->
            error("Invalid peer interface reply")
        })
        assertThrows(IllegalStateException::class.java) {
            session.request("PEER_INTERFACE p2p0 96:08:53:3b:71:33")
        }
        session.close()
    }

    @Test fun missingPeerRemainsAnExpectedReadinessOutcome() {
        val session = RootSocketSession({ error("No mutation expected") }, { "" }, { _, _ -> null })
        assertEquals("NOT_FOUND", session.request("PEER_INTERFACE p2p0 96:08:53:3b:71:33"))
        session.close()
    }
}
