package foxlost.miracast.sink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiracastServiceTest {
    @Test
    fun playerLaunchRunsOnlyWhenMainLooperExecutesRunnable() {
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
        assertEquals("launch failed", failure?.message)
    }

    @Test
    fun rejectedMainLooperLaunchIsReportedAndReturnsFalse() {
        var failure: Exception? = null

        assertFalse(
            postPlayerLaunch(
                post = { false },
                launch = {},
                onFailure = { failure = it },
            ),
        )

        assertTrue(failure is IllegalStateException)
        assertEquals("main looper rejected PlayerActivity launch", failure?.message)
    }

    @Test
    fun mediaPreparationCallbackDelegatesToOwnerMethodOnce() {
        class Owner {
            var calls = 0

            fun prepareMedia(generation: Long): foxlost.miracast.sink.rtsp.LocalRtpEndpoint {
                calls++
                return foxlost.miracast.sink.rtsp.LocalRtpEndpoint(
                    rtpPort = 15550 + generation.toInt(),
                    rtcpPort = 15551 + generation.toInt(),
                )
            }
        }

        val owner = Owner()
        val callbacks = object : foxlost.miracast.sink.rtsp.RtspSessionCallbacks {
            override fun onMediaStarting(generation: Long) = Unit
            override fun prepareMedia(generation: Long) = owner.prepareMedia(generation)
            override fun onTransportReady(
                generation: Long,
                transport: foxlost.miracast.sink.rtsp.NegotiatedTransport,
            ) = Unit
            override fun onPlay(generation: Long) = Unit
            override fun onProfile(generation: Long, profile: CompatibilityProfile) = Unit
            override fun onSessionEnded(generation: Long, reason: String) = Unit
        }

        assertEquals(
            foxlost.miracast.sink.rtsp.LocalRtpEndpoint(15557, 15558),
            callbacks.prepareMedia(7L),
        )
        assertEquals(1, owner.calls)
    }
}
