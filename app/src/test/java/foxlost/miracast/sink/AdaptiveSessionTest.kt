package foxlost.miracast.sink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSessionTest {
    @Test
    fun callbacksFromDestroyedServiceCannotMatchAReplacementController() {
        val previous = AdaptiveSessionController()
        val oldGeneration = previous.start()
        previous.stop("service destroyed")
        val replacement = AdaptiveSessionController()
        val currentGeneration = replacement.start()
        assertFalse(replacement.isCurrent(oldGeneration))
        assertFalse(replacement.markGroupForming(oldGeneration))
        assertTrue(replacement.markGroupForming(currentGeneration))
    }

    @Test
    fun selectsWindowsForSourceGoMicrosoftTranscriptShape() {
        val profile = AdaptiveProfileSelector.select(
            SourceEvidence(
                role = P2pRole.SinkClientSourceGo,
                entry = RtspEntry.Outbound,
                firstMethod = "OPTIONS",
                userAgent = "Microsoft Windows",
                requestedParameters = setOf(
                    "wfd_video_formats",
                    "intel_friendly_name",
                    "microsoft_video_formats",
                ),
            ),
        )

        assertEquals(SourceKind.Windows, profile.sourceKind)
    }

    @Test
    fun selectsAndroidForSinkGoSourceServerShape() {
        val profile = AdaptiveProfileSelector.select(
            SourceEvidence(
                role = P2pRole.SinkGoSourceClient,
                entry = RtspEntry.Outbound,
                firstMethod = "OPTIONS",
                server = "Android",
                requestedParameters = setOf("wfd_video_formats", "wfd_audio_codecs"),
            ),
        )

        assertEquals(SourceKind.Android, profile.sourceKind)
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
    fun discoveryAndStoppedSessionsCannotStartMedia() {
        val controller = AdaptiveSessionController()
        val generation = controller.start()
        assertTrue(!controller.markMediaStarting(generation))
        assertTrue(!controller.markStreaming(generation))
        assertNull(controller.beginOutbound(generation))
        controller.stop("cancel")
        assertTrue(!controller.markMediaStarting(generation))
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
    @Test
    fun protocolShapeAndGoRoleDoNotIdentifySourcePlatform() {
        for (role in P2pRole.entries) {
            val profile = AdaptiveProfileSelector.select(
                SourceEvidence(role, RtspEntry.Outbound, firstMethod = "OPTIONS",
                    requestedParameters = setOf("wfd_video_formats", "microsoft_video_formats")),
            )
            assertEquals(SourceKind.Unknown, profile.sourceKind)
            assertTrue(!profile.secondPlay)
        }
    }

    @Test
    fun duplicateConnectAndPrematurePlayAreRejected() {
        val controller = AdaptiveSessionController()
        val generation = controller.start()
        controller.onGroupFormed(generation, PeerIdentity("peer", "Source", 7236),
            P2pRole.SinkGoSourceClient, "192.168.49.1", null, 7L)
        assertNotNull(controller.beginOutbound(generation, 7L))
        assertNull(controller.beginOutbound(generation, 7L))
        assertTrue(!controller.markStreaming(generation))
        assertTrue(controller.markMediaStarting(generation))
        assertTrue(controller.resetAttempt(generation, 7L, "failed negotiation"))
        assertTrue(!controller.isCurrent(generation, 7L))
        assertTrue(!controller.markMediaStarting(generation))
        assertTrue(!controller.onTransportReady(generation, "old", 15550, null, null, null, null))
    }
    @Test
    fun sourceStartupBudgetAllowsNinetySecondsAndNeverBecomesNegative() {
        val start = 123_000_000L
        assertEquals(90_000L, remainingControlConnectMs(start, start))
        assertEquals(43_000L, remainingControlConnectMs(start, start + 47_000_000_000L))
        assertEquals(1L, remainingControlConnectMs(start, start + 89_999_000_000L))
        assertEquals(0L, remainingControlConnectMs(start, start + 90_000_000_000L))
        assertEquals(0L, remainingControlConnectMs(start, start + 120_000_000_000L))
    }

    @Test
    fun duplicateResetCannotPublishAnotherRetryState() {
        var resets = 0
        val controller = AdaptiveSessionController(object : AdaptiveSessionController.Listener {
            override fun onStateChanged(generation: Long, state: SessionState, context: SessionContext?) {
                if (state == SessionState.Discovering) resets += 1
            }

            override fun onCleanup(generation: Long, reason: String) = Unit
        })
        val generation = controller.start()
        assertTrue(controller.resetAttempt(generation, 1L, "failed before group readiness"))
        assertFalse(controller.resetAttempt(generation, 1L, "delayed duplicate failure"))
        assertFalse(controller.isCurrent(generation, 1L))
        assertTrue(controller.isCurrent(generation, 2L))
        assertEquals(2, resets)
    }

    @Test
    fun stoppingInvalidatesCallbacksBeforeCleanupRuns() {
        lateinit var controller: AdaptiveSessionController
        controller = AdaptiveSessionController(object : AdaptiveSessionController.Listener {
            override fun onStateChanged(generation: Long, state: SessionState, context: SessionContext?) {
                if (state == SessionState.Stopping) {
                    assertFalse(controller.isCurrent(generation))
                    assertFalse(controller.isCurrent(generation, 1L))
                }
            }

            override fun onCleanup(generation: Long, reason: String) {
                assertFalse(controller.isCurrent(generation))
                assertFalse(controller.isCurrent(generation, 1L))
                assertFalse(controller.onGroupDisconnected(generation))
            }
        })
        controller.start()
        controller.stop("test")
        assertEquals(SessionState.Idle, controller.state)
    }

    @Test
    fun oldDisconnectCannotStopReplacementGeneration() {
        val controller = AdaptiveSessionController()
        val previous = controller.start()
        val current = controller.start()
        assertFalse(controller.onGroupDisconnected(previous))
        assertTrue(controller.isCurrent(current))
        assertEquals(SessionState.Discovering, controller.state)
    }
}
