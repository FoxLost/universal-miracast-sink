package foxlost.miracast.sink

import org.junit.Assert.*
import org.junit.Test

class SessionNavigationTest {
    private fun snapshot(generation: Long = 7L, attempt: Long = 3L) =
        PlayerSessionSnapshot(generation, attempt, 15550, null, 0x1234L)

    @Test fun listeningPreparedAndBoundAreNotStreaming() {
        val state = SessionNavigation()
        assertNull(state.resumable)
        state.prepare(snapshot())
        assertNull(state.resumable)
        val lease = state.claim(7L)!!
        assertNull(state.resumable)
        assertTrue(state.markBound(7L, lease))
        assertNull(state.resumable)
        assertTrue(state.markStreaming(7L, 3L))
        assertEquals(snapshot(), state.resumable)
    }

    @Test fun playBeforeBindDoesNotAdvertiseAnUnboundPlayer() {
        val state = SessionNavigation()
        state.prepare(snapshot())
        val lease = state.claim(7L)!!
        assertTrue(state.markStreaming(7L, 3L))
        assertNull(state.resumable)
        state.markBound(7L, lease)
        assertEquals(snapshot(), state.resumable)
    }

    @Test fun returningKeepsSameOwnerAndDoesNotPermitSecondReceiver() {
        val state = SessionNavigation()
        state.prepare(snapshot())
        val lease = state.claim(7L)!!
        state.markBound(7L, lease)
        state.markStreaming(7L, 3L)
        repeat(3) {
            assertEquals(snapshot(), state.resumable)
            assertNull(state.claim(7L))
            assertTrue(state.owns(7L, lease))
        }
    }

    @Test fun destroyHidesResumeUntilSameEpochReceiverRebinds() {
        val state = SessionNavigation()
        state.prepare(snapshot())
        val old = state.claim(7L)!!
        state.markBound(7L, old)
        state.markStreaming(7L, 3L)
        assertTrue(state.release(7L, old))
        assertNull(state.resumable)
        assertEquals(snapshot(), state.snapshot)
        val replacement = state.claim(7L)!!
        assertNotEquals(old, replacement)
        assertFalse(state.release(7L, old))
        assertFalse(state.markBound(7L, old))
        assertNull(state.resumable)
        state.markBound(7L, replacement)
        assertEquals(snapshot(), state.resumable)
    }

    @Test fun retryInSameGenerationRejectsOldAttemptAndLeaseCallbacks() {
        val state = SessionNavigation()
        state.prepare(snapshot())
        val old = state.claim(7L)!!
        state.markBound(7L, old)
        state.markStreaming(7L, 3L)
        state.prepare(snapshot(attempt = 4L))
        val replacement = state.claim(7L)!!
        assertFalse(state.markBound(7L, old))
        assertFalse(state.release(7L, old))
        assertFalse(state.markStreaming(7L, 3L))
        assertFalse(state.transport(snapshot()))
        state.markBound(7L, replacement)
        assertNull(state.resumable)
        state.markStreaming(7L, 4L)
        assertEquals(snapshot(attempt = 4L), state.resumable)
    }

    @Test fun stopDisconnectOrFailureInvalidatesAllCallbacksAndRecovery() {
        val state = SessionNavigation()
        state.prepare(snapshot())
        val lease = state.claim(7L)!!
        state.markBound(7L, lease)
        state.markStreaming(7L, 3L)
        state.clear()
        assertNull(state.resumable)
        assertNull(state.snapshot)
        assertNull(state.claim(7L))
        assertFalse(state.release(7L, lease))
        assertFalse(state.markBound(7L, lease))
        assertFalse(state.markStreaming(7L, 3L))
        assertFalse(state.transport(snapshot()))
    }

    @Test fun negotiatedSnapshotRequiresExactEpochAndPorts() {
        val state = SessionNavigation()
        state.prepare(snapshot().copy(ssrc = null))
        assertFalse(state.transport(snapshot(generation = 8L)))
        assertFalse(state.transport(snapshot().copy(rtpPort = 15552)))
        assertFalse(state.transport(snapshot().copy(rtcpPort = 15551)))
        assertTrue(state.transport(snapshot()))
        assertEquals(0x1234L, state.snapshot!!.ssrc)
    }

    @Test fun firstBackWarnsSecondWithinTwoSecondsReturnsAndDisarms() {
        val back = DashboardBackWindow()
        assertFalse(back.press(100L))
        assertTrue(back.press(2099L))
        assertFalse(back.press(2100L))
    }

    @Test fun expiredBackWindowWarnsAgainIncludingExactDeadline() {
        val back = DashboardBackWindow()
        assertFalse(back.press(100L))
        assertFalse(back.press(2100L))
        assertTrue(back.press(2101L))
    }

    @Test fun generationReplacementRejectsOldOwnerAndCannotResumeBeforePlay() {
        val state = SessionNavigation()
        state.prepare(snapshot())
        val old = state.claim(7L)!!
        state.markBound(7L, old)
        state.markStreaming(7L, 3L)
        state.prepare(snapshot(generation = 8L))
        assertNull(state.claim(7L))
        assertFalse(state.release(7L, old))
        assertFalse(state.markStreaming(7L, 3L))
        val replacement = state.claim(8L)!!
        state.markBound(8L, replacement)
        assertNull(state.resumable)
        assertFalse(state.release(8L, replacement))
    }
    @Test fun pauseResumeOrOtherInteractionDisarmsBackWindow() {
        val back = DashboardBackWindow()
        repeat(3) {
            assertFalse(back.press(100L))
            back.reset()
        }
        assertFalse(back.press(200L))
        assertTrue(back.press(201L))
    }
}
