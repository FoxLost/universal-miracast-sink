package foxlost.miracast.sink.p2p

import org.junit.Assert.*
import org.junit.Test

class P2pBandTest {
    @Test fun unsupportedRadioCannotOfferForcedFiveGhzButRetainsAutoAndTwoGhz() {
        val available = P2pBand.entries.filter { it.supportedBy(false) }
        assertEquals(listOf(P2pBand.AUTO, P2pBand.GHZ_2_4), available)
        assertEquals(P2pBand.entries, P2pBand.entries.filter { it.supportedBy(true) })
    }

    @Test fun forcedBandsRejectUnknownWrongBandAndSixGhzGroups() {
        assertTrue(P2pBand.GHZ_2_4.accepts(2412))
        assertFalse(P2pBand.GHZ_2_4.accepts(0))
        assertFalse(P2pBand.GHZ_2_4.accepts(2400))
        assertFalse(P2pBand.GHZ_2_4.accepts(2500))
        assertFalse(P2pBand.GHZ_2_4.accepts(5180))
        assertTrue(P2pBand.GHZ_5.accepts(5180))
        assertFalse(P2pBand.GHZ_5.accepts(0))
        assertFalse(P2pBand.GHZ_5.accepts(2412))
        assertFalse(P2pBand.GHZ_5.accepts(4900))
        assertFalse(P2pBand.GHZ_5.accepts(5900))
        assertFalse(P2pBand.GHZ_5.accepts(5955))
        // Auto does not invent a band requirement on devices lacking frequency metadata.
        assertTrue(P2pBand.AUTO.accepts(0))
        assertTrue(P2pBand.AUTO.accepts(5955))
    }
}
