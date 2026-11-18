package foxlost.miracast.sink.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RtpEndpointRegistryTest {
    @Before
    fun resetRegistry() = RtpEndpointRegistry.clear()

    @After
    fun clearRegistry() = RtpEndpointRegistry.clear()

    @Test
    fun launchFailureWakesReadinessWaiterWithOriginalCause() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val failure = SecurityException("ActivityManager rejected launch")
        RtpEndpointRegistry.begin("current")
        try {
            val result = executor.submit<IllegalStateException?> {
                entered.countDown()
                try {
                    RtpEndpointRegistry.await("current", 15550, null, 5000L)
                    null
                } catch (error: IllegalStateException) {
                    error
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            RtpEndpointRegistry.failLaunch("current", failure)
            val error = result.get(1, TimeUnit.SECONDS)!!
            assertSame(failure, error.cause)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun failureBeforeWaitIsRetainedAndCannotBecomeReady() {
        val failure = IllegalStateException("launch rejected")
        RtpEndpointRegistry.begin("current")
        RtpEndpointRegistry.failLaunch("current", failure)
        RtpEndpointRegistry.publish("current", 15550, null)
        try {
            RtpEndpointRegistry.await("current", 15550, null, 0L)
            throw AssertionError("Launch failure must terminate readiness")
        } catch (error: IllegalStateException) {
            assertSame(failure, error.cause)
        }
    }

    @Test
    fun staleFailurePublishAndClearCannotChangeCurrentReadiness() {
        val owner = Any()
        RtpEndpointRegistry.begin("old")
        RtpEndpointRegistry.begin("current")
        RtpEndpointRegistry.publish("current", 15550, 15551, owner)
        RtpEndpointRegistry.failLaunch("old", SecurityException("stale launch"))
        RtpEndpointRegistry.publish("old", 15552, null)
        RtpEndpointRegistry.clear("old", owner)
        assertTrue(RtpEndpointRegistry.await("current", 15550, 15551, 0L))
        assertFalse(RtpEndpointRegistry.await("old", 15552, null, 0L))
    }

    @Test
    fun readinessRequiresBothExpectedPortsAndToken() {
        val owner = Any()
        RtpEndpointRegistry.begin("current")
        assertFalse(RtpEndpointRegistry.await("current", 15550, 15551, 0L))
        RtpEndpointRegistry.publish("current", 15550, null, owner)
        assertFalse(RtpEndpointRegistry.await("current", 15550, 15551, 0L))
        RtpEndpointRegistry.publish("current", 15550, 15552, owner)
        assertFalse(RtpEndpointRegistry.await("current", 15550, 15551, 0L))
        RtpEndpointRegistry.publish("current", 15550, 15551, owner)
        assertTrue(RtpEndpointRegistry.await("current", 15550, 15551, 0L))
        assertFalse(RtpEndpointRegistry.await("current", 15552, 15551, 0L))
        assertFalse(RtpEndpointRegistry.await("other", 15550, 15551, 0L))
        RtpEndpointRegistry.clear("current", owner)
        assertFalse(RtpEndpointRegistry.await("current", 15550, 15551, 0L))
    }

    @Test
    fun staleFailureCannotReplaceCurrentLaunchFailure() {
        val failure = SecurityException("current launch rejected")
        RtpEndpointRegistry.begin("current")
        RtpEndpointRegistry.failLaunch("current", failure)
        RtpEndpointRegistry.failLaunch("old", IllegalStateException("stale launch"))
        RtpEndpointRegistry.clear("old", Any())
        try {
            RtpEndpointRegistry.await("current", 15550, null, 0L)
            throw AssertionError("Current launch failure must remain terminal")
        } catch (error: IllegalStateException) {
            assertSame(failure, error.cause)
        }
    }

    @Test
    fun newSessionDoesNotInheritPreviousLaunchFailure() {
        RtpEndpointRegistry.begin("old")
        RtpEndpointRegistry.failLaunch("old", SecurityException("launch rejected"))
        RtpEndpointRegistry.begin("current")
        assertFalse(RtpEndpointRegistry.await("current", 15550, null, 0L))
        RtpEndpointRegistry.publish("current", 15550, null)
        assertTrue(RtpEndpointRegistry.await("current", 15550, null, 0L))
    }

    @Test
    fun endedSessionCannotReopenReadinessOrAuthorizeALatePlayer() {
        RtpEndpointRegistry.begin("old")
        assertTrue(RtpEndpointRegistry.isActive("old"))
        RtpEndpointRegistry.end("old")
        RtpEndpointRegistry.publish("old", 15550, null)
        assertFalse(RtpEndpointRegistry.isActive("old"))
        assertFalse(RtpEndpointRegistry.await("old", 15550, null, 0L))
        RtpEndpointRegistry.begin("current")
        RtpEndpointRegistry.end("old")
        RtpEndpointRegistry.publish("current", 15550, null)
        assertTrue(RtpEndpointRegistry.isActive("current"))
        assertTrue(RtpEndpointRegistry.await("current", 15550, null, 0L))
    }

    @Test
    fun retiringReceiverCannotClearReplacementWithinTheSameSession() {
        val retired = Any()
        val replacement = Any()
        RtpEndpointRegistry.begin("session")
        RtpEndpointRegistry.publish("session", 15550, 15551, retired)
        RtpEndpointRegistry.clear("session", retired)
        assertFalse(RtpEndpointRegistry.await("session", 15550, 15551, 0))
        RtpEndpointRegistry.publish("session", 15550, 15551, replacement)
        // Both old socket and media workers can run their finally blocks later.
        repeat(2) { RtpEndpointRegistry.clear("session", retired) }
        assertTrue(RtpEndpointRegistry.await("session", 15550, 15551, 0))
        assertTrue(RtpEndpointRegistry.isActive("session"))
        RtpEndpointRegistry.clear("session", replacement)
        assertFalse(RtpEndpointRegistry.await("session", 15550, 15551, 0))
        assertTrue(RtpEndpointRegistry.isActive("session"))
    }

    @Test
    fun closingPortsStayReservedAcrossSessionResetWithoutBlockingOtherPorts() {
        val owner = Any()
        RtpEndpointRegistry.retiring(owner, 15550, 15551)
        try {
            assertFalse(RtpEndpointRegistry.awaitRetirement(15550, null, 0))
            assertFalse(RtpEndpointRegistry.awaitRetirement(15551, null, 0))
            assertFalse(RtpEndpointRegistry.awaitRetirement(15552, 15550, 0))
            assertTrue(RtpEndpointRegistry.awaitRetirement(15552, 15553, 0))
            RtpEndpointRegistry.begin("replacement")
            RtpEndpointRegistry.clear()
            assertFalse(RtpEndpointRegistry.awaitRetirement(15550, null, 0))
        } finally {
            RtpEndpointRegistry.retired(owner)
        }
        assertTrue(RtpEndpointRegistry.awaitRetirement(15550, 15551, 0))
    }
}
