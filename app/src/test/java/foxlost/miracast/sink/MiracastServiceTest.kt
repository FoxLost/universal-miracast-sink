package foxlost.miracast.sink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiracastServiceTest {
    @Test
    fun queuedPlayerLaunchDoesNotRunInline() {
        var queued: Runnable? = null
        var launched = false
        var failure: Exception? = null

        assertTrue(
            postPlayerLaunch(
                post = { runnable -> queued = runnable; true },
                launch = { launched = true },
                onFailure = { failure = it },
            ),
        )
        assertFalse(launched)
        assertNotNull(queued)

        queued!!.run()

        assertTrue(launched)
        assertEquals(null, failure)
    }

    @Test
    fun playerLaunchExceptionIsReportedWithoutEscapingRunnable() {
        var failure: Exception? = null
        var queued: Runnable? = null

        assertTrue(
            postPlayerLaunch(
                post = { runnable -> queued = runnable; true },
                launch = { throw IllegalStateException("launch failed") },
                onFailure = { failure = it },
            ),
        )

        queued!!.run()

        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun rejectedLaunchQueueIsReportedAndReturnsFalse() {
        var failure: Exception? = null

        assertFalse(
            postPlayerLaunch(
                post = { false },
                launch = {},
                onFailure = { failure = it },
            ),
        )

        assertTrue(failure is IllegalStateException)
    }

}
