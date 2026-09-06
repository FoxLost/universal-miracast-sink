package foxlost.miracast.sink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSessionTest {
    @Test
    fun selectsWindowsForSourceGoMicrosoftTranscriptShape() {
        val profile = AdaptiveProfileSelector.select(
            SourceEvidence(
                role = P2pRole.SinkClientSourceGo,
                entry = RtspEntry.Outbound,
                firstMethod = "OPTIONS",
                requestedParameters = setOf(
                    "wfd_video_formats",
                    "intel_friendly_name",
                    "microsoft_video_formats",
                ),
            ),
        )

        assertEquals(SourceKind.Windows, profile.sourceKind)
        assertEquals(RtspEntry.Outbound, profile.entry)
        assertTrue(profile.restrictedPublic)
        assertEquals(80L, profile.setupDelayMs)
    }

    @Test
    fun selectsAndroidForSinkGoSourceServerShape() {
        val profile = AdaptiveProfileSelector.select(
            SourceEvidence(
                role = P2pRole.SinkGoSourceClient,
                entry = RtspEntry.Outbound,
                firstMethod = "OPTIONS",
                requestedParameters = setOf("wfd_video_formats", "wfd_audio_codecs"),
            ),
        )

        assertEquals(SourceKind.Android, profile.sourceKind)
        assertEquals(RtspEntry.Outbound, profile.entry)
    }

    @Test
    fun bothP2pRolesUseOutboundRtspEntry() {
        assertEquals(
            RtspEntry.Outbound,
            AdaptiveProfileSelector.entryForRole(P2pRole.SinkClientSourceGo),
        )
        assertEquals(
            RtspEntry.Outbound,
            AdaptiveProfileSelector.entryForRole(P2pRole.SinkGoSourceClient),
        )
    }

    @Test
    fun controllerKeepsOneGenerationAndCleansUpOnce() {
        val states = mutableListOf<SessionState>()
        val cleanup = mutableListOf<String>()
        val controller = AdaptiveSessionController(object : AdaptiveSessionController.Listener {
            override fun onStateChanged(generation: Long, state: SessionState, context: SessionContext?) {
                states += state
            }

            override fun onCleanup(generation: Long, reason: String) {
                cleanup += reason
            }
        })

        val generation = controller.start()
        val context = controller.onGroupFormed(
            generation,
            PeerIdentity("peer", "Windows", 7236),
            P2pRole.SinkClientSourceGo,
            "192.168.137.1",
            "192.168.137.2",
        )
        assertNotNull(context)
        assertNotNull(controller.beginOutbound(generation))
        assertTrue(controller.onTransportReady(generation, "session", 15550, 15551, 1902, 1903, 7L))
        controller.stop("test cancellation")
        controller.stop("duplicate cancellation")

        assertEquals(SessionState.Idle, controller.state)
        assertEquals(listOf("test cancellation"), cleanup)
        assertEquals(SessionState.Stopping, states[states.size - 2])
        assertEquals(SessionState.Idle, states.last())
        assertNull(controller.context)
    }

    @Test
    fun groupDisconnectInvalidatesGenerationAndCleansUpOnce() {
        val cleanup = mutableListOf<String>()
        val controller = AdaptiveSessionController(object : AdaptiveSessionController.Listener {
            override fun onStateChanged(generation: Long, state: SessionState, context: SessionContext?) = Unit

            override fun onCleanup(generation: Long, reason: String) {
                cleanup += "$generation:$reason"
            }
        })
        val generation = controller.start()
        assertNotNull(
            controller.onGroupFormed(
                generation,
                PeerIdentity("peer", "Android", 7236),
                P2pRole.SinkGoSourceClient,
                "192.168.49.1",
                "192.168.49.2",
            ),
        )

        assertTrue(controller.onGroupDisconnected(generation))
        assertTrue(!controller.onGroupDisconnected(generation))
        assertEquals(listOf("$generation:P2P group disconnected"), cleanup)
        assertTrue(!controller.isCurrent(generation))
    }

    @Test
    fun inboundChoiceIsRejectedAfterGenerationChanges() {
        val controller = AdaptiveSessionController()
        val first = controller.start()
        controller.stop("retry")
        val second = controller.start()
        assertTrue(first != second)
        assertNull(controller.acceptInbound(first, "192.168.49.2"))
    }
    
    @Test
    fun stoppedOrReplacedGenerationCannotStartP2p() {
        val controller = AdaptiveSessionController()
        val first = controller.start()
        controller.stop("queued start cancellation")
        assertTrue(!controller.isCurrent(first))

        val second = controller.start()
        assertTrue(first != second)
        assertTrue(!controller.isCurrent(first))
        assertTrue(controller.isCurrent(second))
    }
    @Test
    fun staleAttemptCallbackIsRejectedAfterRetry() {
        val controller = AdaptiveSessionController()
        val generation = controller.start()
        assertNotNull(
            controller.onGroupFormed(
                generation = generation,
                peer = PeerIdentity("windows", "Windows", 7236),
                role = P2pRole.SinkClientSourceGo,
                groupOwnerAddress = "192.168.137.1",
                localAddress = "192.168.137.2",
                attemptId = 1L,
            ),
        )
        assertTrue(controller.resetAttempt(generation, 1L, "readiness timeout"))
        assertNull(
            controller.onGroupFormed(
                generation = generation,
                peer = PeerIdentity("stale", "stale", 7236),
                role = P2pRole.SinkClientSourceGo,
                groupOwnerAddress = "192.168.137.1",
                localAddress = "192.168.137.2",
                attemptId = 1L,
            ),
        )
        val android = controller.onGroupFormed(
            generation = generation,
            peer = PeerIdentity("android", "Android", 7236),
            role = P2pRole.SinkGoSourceClient,
            groupOwnerAddress = "192.168.49.1",
            localAddress = "192.168.49.2",
            attemptId = 2L,
        )
        assertNotNull(android)
        assertEquals(P2pRole.SinkGoSourceClient, android?.role)
        assertEquals(2L, android?.attemptId)
    }
}
