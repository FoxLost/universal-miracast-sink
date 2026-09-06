package foxlost.miracast.sink.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pAttemptControllerTest {
    @Test
    fun startsAtMostThreeAttemptsAndRequiresCleanupBeforeRetry() {
        val controller = P2pAttemptController()
        val first = controller.start(7L)
        assertEquals(P2pAttemptController.FailureDecision.Retry(500L, 2), controller.fail(first))
        assertTrue(controller.completeCleanup(first))
        val attempt2 = controller.beginNext()
        assertNotNull(attempt2)
        val secondToken = requireNotNull(attempt2)
        val third = controller.fail(secondToken)
        assertTrue(third is P2pAttemptController.FailureDecision.Retry)
        assertTrue(controller.completeCleanup(secondToken))
        val attempt3 = controller.beginNext()
        assertNotNull(attempt3)
        val thirdToken = requireNotNull(attempt3)
        assertTrue(controller.fail(thirdToken) is P2pAttemptController.FailureDecision.Exhausted)
        assertTrue(controller.completeCleanup(thirdToken))
        assertNull(controller.beginNext())
        assertEquals(3, controller.attemptsStarted)
    }
    @Test
    fun initialStartPublishesTokenForGroupCallbacks() {
        val controller = P2pAttemptController()

        val token = controller.start(7L)

        assertEquals(token, controller.activeToken)
        assertTrue(controller.accepts(token))
    }

    @Test
    fun staleFailureCannotInvalidateNewGenerationOrAttempt() {
        val controller = P2pAttemptController()
        val first = controller.start(1L)
        assertTrue(controller.fail(first) is P2pAttemptController.FailureDecision.Retry)
        assertTrue(controller.completeCleanup(first))
        val second = controller.beginNext()
        assertNotNull(second)
        val secondToken = requireNotNull(second)
        assertEquals(P2pAttemptController.FailureDecision.Ignored, controller.fail(first))
        assertTrue(controller.accepts(secondToken))
        assertFalse(controller.completeCleanup(first))
        assertEquals(2, controller.attemptsStarted)
    }
    @Test
    fun generationReplacementInvalidatesEarlierToken() {
        val controller = P2pAttemptController()
        val old = controller.start(1L)
        val fresh = controller.start(2L)
        assertFalse(controller.accepts(old))
        assertTrue(controller.accepts(fresh))
        assertEquals(P2pAttemptController.FailureDecision.Ignored, controller.fail(old))
    }
}
